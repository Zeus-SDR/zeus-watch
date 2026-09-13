// SPDX-License-Identifier: LicenseRef-Proprietary
// Copyright (C) 2026 Douglas J. Cerrato (KB2UKA) and Christian Suarez (N9WAR).
package org.openhpsdr.zeus.watch;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The whole watch interface: focused pages the operator swipes between, an
 * outer tuning ring, and a mini spectrum. Everything is laid out inside the
 * square that fits within the round face, so no control is ever clipped by the
 * bezel.
 *
 * <p>The view is display-only state: it never talks to the station. It reports
 * intent through {@link Listener} and redraws when the activity feeds station
 * state back in.
 */
@SuppressLint("ViewConstructor")
public final class WatchView extends FrameLayout {
    /** Palette pulled from the station's own design tokens. */
    public static final class Theme {
        public final int background;
        public final int surface;
        public final int foreground;
        public final int muted;
        public final int accent;
        public final int tx;
        public final int line;

        public Theme(int background, int surface, int foreground, int muted, int accent, int tx, int line) {
            this.background = background;
            this.surface = surface;
            this.foreground = foreground;
            this.muted = muted;
            this.accent = accent;
            this.tx = tx;
            this.line = line;
        }
    }

    public interface Listener {
        /** Ring or button tuning, in whole steps of the configured step size. */
        void onTuneSteps(int steps);

        /** Absolute retune from the keypad. */
        void onTuneTo(long hz);

        void onAction(String path, JSONObject body);

        void onPtt(boolean on);

        void onStationTune(boolean on);

        void onTwoTone(boolean on);

        void onAudio(boolean on);

        void onDisplayEnabled(boolean on);

        void onKeepAwake(boolean on);

        /**
         * Enables keying by wrist. Returns false when the watch has no usable
         * orientation sensor, so the switch does not claim to be on.
         */
        boolean onWristPtt(boolean on);
    }

    /**
     * The signal trace keeps the station's amber; it is signal-strength
     * visualisation, never chrome.
     */
    private static final int TRACE_COLOR = 0xFFFFA028;

    private static final String[] MODES = {
        "LSB", "USB", "CWL", "CWU", "AM", "FM", "SAM", "DSB", "DIGL", "DIGU",
    };

    /** Mirrors zeus-web/src/components/design/data.ts so both agree on a band. */
    private static final String[] BAND_KEYS = {
        "160m", "80m", "60m", "40m", "30m", "20m", "17m", "15m", "12m", "10m", "6m",
    };
    private static final long[] BAND_CENTERS = {
        1840000L, 3573000L, 5357000L, 7074000L, 10136000L, 14210000L,
        18100000L, 21285000L, 24915000L, 28400000L, 50110000L,
    };

    /**
     * A touch on the edge must hold still this long before it becomes tuning.
     * A page swipe is moving within a few milliseconds, so it never qualifies.
     */
    private static final long RING_DWELL_MS = 150L;

    /**
     * Which way the rim raises the frequency. Turning it clockwise reads as
     * "wind the dial down" on this watch, so the sign is inverted here rather
     * than inside the router, which keeps its own geometry honest.
     */
    private static final int RING_SENSE = -1;

    /** The steps this station actually tunes with; the picker offers no others. */
    private static final int[] STEPS_HZ = { 500, 1000, 5000 };
    /** Spectral zoom the engine accepts; 1 is the full span. */
    private static final int[] ZOOM_LEVELS = { 1, 2, 4, 8, 16, 32 };
    private static final int[] FILTER_WIDTHS_HZ = { 500, 1000, 1800, 2400, 2700, 3000, 3600, 4400, 6000 };

    private static final int PAGE_PTT = 0;
    private static final int PAGE_RADIO = 1;
    private static final int PAGE_SCOPE = 2;
    private static final int PAGE_KEYPAD = 3;
    private static final int PAGE_BAND = 4;
    private static final int PAGE_MODE = 5;
    private static final int PAGE_FILTER = 6;
    private static final int PAGE_STEP = 7;
    private static final int PAGE_ZOOM = 8;
    private static final int PAGE_SWITCH = 9;
    private static final int PAGE_LEVEL = 10;
    private static final String[] PAGE_TITLES = {
        "PTT", "Radio", "Scope", "Frequency", "Band", "Mode", "Filter", "Step",
        "Zoom", "Switches", "Levels",
    };

    private final Theme theme;
    private final Listener listener;
    private final float scale;
    private final int face;
    private final int ringInner;
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringMarkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF ringBounds = new RectF();

    private final FrameLayout pageHost;
    private ListPage stepPage;
    private ListPage zoomPage;
    private final List<View> pages = new ArrayList<>();
    private int page = PAGE_RADIO;

    // Live station state as last reported.
    private long vfoHz;
    private int modeIndex = 1;
    private int filterLowHz = 100;
    private int filterHighHz = 2900;
    private int stepHz = 500;
    private double sMeterDbm = -160;
    private boolean moxOn;
    private boolean tunOn;
    private boolean twoToneOn;
    private double fwdWatts;
    private double swr = 1.0;
    /** Amplifier forward power, or null when no amplifier is reporting. */
    private Double ampWatts;
    private boolean connected;
    private String connectionDetail = "Connecting";
    private String lastError;
    private long lastErrorAt;

    private double afGainDb;
    private double agcTopDb = 90;
    private int attenDb;
    private int micGainDb;
    private int drivePct;
    private int zoomLevel = 1;

    /** Bottom of the display window; the operator drags the waterfall to move it. */
    private float floorDb = WaterfallPalette.DB_MIN;
    private long floorShownAt;

    private boolean audioOn = true;
    private boolean scopeOn = true;
    private boolean keepAwake = true;
    private boolean preampOn;
    private boolean wristPtt;

    // Page 0 / 1
    private TextView pttButton;
    private TextView pttStatus;
    private TextView radioFrequency;
    private TextView radioMeta;
    private TextView radioMox;
    private MeterView radioMeter;

    // Page 2
    private ScopeView scope;
    private TextView scopeFrequency;
    private TextView scopeMeter;
    private TextView scopeMox;

    // Page 3
    private TextView keypadEntry;
    private final StringBuilder keypadDigits = new StringBuilder();

    // Pages 4-6
    private ListPage bandPage;
    private ListPage modePage;
    private ListPage filterPage;

    // Page 7
    private final List<TextView> switches = new ArrayList<>();

    // Page 8
    private int levelIndex;
    private TextView levelName;
    private TextView levelValue;

    private boolean refreshPending;
    private Boolean pttKeyed;
    /**
     * Last text pushed into each readout. Formatting and setting text costs a
     * measure and a layout for that view, and most redraws change neither.
     */
    private String shownFrequency;
    private String shownMeta;
    private String shownScopeFrequency;
    private String shownScopeMeter;
    private String shownStatus;
    private long formattedHz = -1;
    private String formattedHzText = "";

    // A swipe always pages; the ring only takes a touch that dwells on the rim
    // first, so the two can never fight. See TouchRouter.
    private TouchRouter router;
    private float ringSweep;
    private long ringSweepAt;

    public WatchView(Context context, Listener listener, Theme theme) {
        super(context);
        this.listener = listener;
        this.theme = theme;
        int width = context.getResources().getDisplayMetrics().widthPixels;
        int height = context.getResources().getDisplayMetrics().heightPixels;
        this.face = Math.min(width, height);
        this.scale = face / 450f;
        this.ringInner = Math.round(face * 0.42f);
        setBackgroundColor(theme.background);
        setWillNotDraw(false);

        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(px(3));
        ringPaint.setColor(theme.line);
        ringMarkPaint.setStyle(Paint.Style.STROKE);
        ringMarkPaint.setStrokeWidth(px(5));
        ringMarkPaint.setStrokeCap(Paint.Cap.ROUND);
        ringMarkPaint.setColor(theme.accent);
        dotPaint.setStyle(Paint.Style.FILL);

        // Everything lives inside the largest square that fits the round face,
        // with room under it for the page dots.
        // The waterfall is the page's backdrop, not a panel on it: full bleed to
        // the bezel, with the dial and MOX floating over it. Added first so the
        // pages draw on top.
        scope = new ScopeView(context);
        addView(scope, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        scope.setVisibility(GONE);

        int side = Math.round(face * 0.68f);
        pageHost = new FrameLayout(context);
        LayoutParams bounds = new LayoutParams(side, side, Gravity.CENTER_HORIZONTAL | Gravity.TOP);
        bounds.topMargin = Math.round((face - side) / 2f - face * 0.03f);
        addView(pageHost, bounds);

        pages.add(buildPtt(context));
        pages.add(buildRadio(context));
        pages.add(buildScope(context));
        pages.add(buildKeypad(context));
        pages.add(buildBand(context));
        pages.add(buildMode(context));
        pages.add(buildFilter(context));
        pages.add(buildStep(context));
        pages.add(buildZoom(context));
        pages.add(buildSwitches(context));
        pages.add(buildLevels(context));
        for (View view : pages) {
            pageHost.addView(view, new FrameLayout.LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            view.setVisibility(GONE);
        }
        showPage(PAGE_RADIO);
        router = new TouchRouter(new TouchRouter.Sink() {
            @Override
            public void onRingArmed() {
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                invalidate();
            }

            @Override
            public void onRingStep(int direction) {
                ringSweep = (float) router.angleDegrees();
                ringSweepAt = SystemClock.elapsedRealtime();
                emitRingStep(direction);
                invalidate();
            }

            @Override
            public void onPage(int delta) {
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                showPage(Math.max(0, Math.min(pages.size() - 1, page + delta)));
            }

            @Override
            public void onVerticalDrag(float dy) {
                // Drag up to lift the floor out of the noise, down to dig into
                // it. Three pixels to the dB is fine enough to place a floor
                // exactly and coarse enough to cross the range in one swipe.
                adjustFloor(-dy / 3f);
            }
        }, face / 2f, face / 2f, ringInner,
                ViewConfiguration.get(context).getScaledTouchSlop(), face * 0.16f, RING_DWELL_MS);

        router.setVerticalEnabled(page == PAGE_SCOPE);
        setFocusable(true);
        setFocusableInTouchMode(true);
        requestFocus();
    }

    private int px(float dpAt450) {
        return Math.max(1, Math.round(dpAt450 * scale));
    }

    // ---- Construction helpers --------------------------------------------

    private TextView label(Context context, String text, float sizePx, int color) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextColor(color);
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, sizePx * scale);
        view.setGravity(Gravity.CENTER);
        view.setSingleLine(true);
        view.setEllipsize(TextUtils.TruncateAt.END);
        return view;
    }

    private TextView pill(Context context, String text, float sizePx, boolean strong) {
        TextView view = label(context, text, sizePx, strong ? theme.background : theme.foreground);
        view.setBackground(pillBackground(strong ? theme.accent : theme.surface));
        view.setPadding(px(8), px(6), px(8), px(6));
        return view;
    }

    private GradientDrawable pillBackground(int fill) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(px(18));
        shape.setStroke(px(1), theme.line);
        return shape;
    }

    /**
     * Re-skins a pill only when its colour actually changed. Building a
     * drawable and setting a background forces a re-layout of that view, and
     * this runs across every pill on every state update: doing it
     * unconditionally was most of the cost of a redraw.
     */
    private void tint(TextView view, boolean active, int activeColor) {
        int fill = active ? activeColor : theme.surface;
        Object applied = view.getTag();
        if (applied instanceof Integer && (Integer) applied == fill) return;
        view.setTag(fill);
        view.setBackground(pillBackground(fill));
        view.setTextColor(active ? theme.background : theme.foreground);
    }

    private LinearLayout column(Context context) {
        LinearLayout view = new LinearLayout(context);
        view.setOrientation(LinearLayout.VERTICAL);
        view.setGravity(Gravity.CENTER);
        return view;
    }

    private LinearLayout.LayoutParams rowParams(int heightPx, float topGapPx) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, heightPx);
        params.topMargin = Math.round(topGapPx);
        return params;
    }

    private LinearLayout.LayoutParams weighted(float weight, float topGapPx) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, weight);
        params.topMargin = Math.round(topGapPx);
        return params;
    }

    // ---- Pages ------------------------------------------------------------

    private View buildPtt(Context context) {
        LinearLayout root = column(context);
        pttStatus = label(context, "Connecting", 17, theme.muted);
        root.addView(pttStatus, rowParams(px(24), 0));

        pttButton = label(context, "PTT", 34, theme.foreground);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(theme.surface);
        circle.setStroke(px(3), theme.line);
        pttButton.setBackground(circle);
        int diameter = Math.round(face * 0.68f * 0.62f);
        LinearLayout.LayoutParams pttBounds = new LinearLayout.LayoutParams(diameter, diameter);
        pttBounds.topMargin = px(10);
        pttBounds.gravity = Gravity.CENTER_HORIZONTAL;
        root.addView(pttButton, pttBounds);
        pttButton.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                    listener.onPtt(true);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    listener.onPtt(false);
                    return true;
                default:
                    return true;
            }
        });

        TextView hint = label(context, "Hold to transmit", 15, theme.muted);
        root.addView(hint, rowParams(px(22), px(10)));
        return root;
    }

    private View buildRadio(Context context) {
        LinearLayout root = column(context);
        radioFrequency = label(context, "—", 36, theme.foreground);
        root.addView(radioFrequency, rowParams(px(46), 0));

        // Forward power and SWR are read at arm's length, mid-over.
        radioMeta = label(context, "", 20, theme.muted);
        root.addView(radioMeta, rowParams(px(28), px(2)));

        radioMeter = new MeterView(context);
        root.addView(radioMeter, rowParams(px(38), px(10)));

        // Keep MOX clear of the meter above it: it is the one control on this
        // page that transmits, and it should never be a near-miss for the
        // signal reading.
        radioMox = pill(context, "MOX", 22, false);
        LinearLayout.LayoutParams moxBounds = new LinearLayout.LayoutParams(
                Math.round(face * 0.46f), px(58));
        moxBounds.topMargin = px(34);
        moxBounds.gravity = Gravity.CENTER_HORIZONTAL;
        root.addView(radioMox, moxBounds);
        radioMox.setOnClickListener(view -> {
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            listener.onPtt(!moxOn);
        });
        return root;
    }

    private View buildScope(Context context) {
        LinearLayout root = column(context);
        // The dial and MOX are all this page owns; the waterfall behind them
        // fills the face. Tuning is the bezel's alone.
        // Dial and signal share one row under the well, so the bandscope answers
        // both questions without leaving the page.
        LinearLayout readout = new LinearLayout(context);
        readout.setOrientation(LinearLayout.HORIZONTAL);
        readout.setGravity(Gravity.CENTER);
        readout.setBackground(pillBackground(theme.background));
        scopeFrequency = label(context, "\u2014", 22, theme.accent);
        scopeMeter = label(context, "\u2014", 22, theme.foreground);
        readout.addView(scopeFrequency, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams meterBounds = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT);
        meterBounds.leftMargin = px(12);
        readout.addView(scopeMeter, meterBounds);
        // Sit low in the trace well rather than crowding the bezel: the top of
        // a round face is the narrowest part of it.
        root.addView(readout, rowParams(px(38), px(34)));

        View spacer = new View(context);
        root.addView(spacer, weighted(1f, 0));

        scopeMox = pill(context, "MOX", 18, false);
        LinearLayout.LayoutParams moxBounds = new LinearLayout.LayoutParams(
                Math.round(face * 0.38f), px(48));
        moxBounds.gravity = Gravity.CENTER_HORIZONTAL;
        root.addView(scopeMox, moxBounds);
        scopeMox.setOnClickListener(view -> {
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            listener.onPtt(!moxOn);
        });
        return root;
    }

    private View buildKeypad(Context context) {
        LinearLayout root = column(context);
        keypadEntry = label(context, "MHz", 22, theme.accent);
        keypadEntry.setBackground(pillBackground(theme.surface));
        root.addView(keypadEntry, rowParams(px(40), 0));

        String[][] keys = {
            { "1", "2", "3" },
            { "4", "5", "6" },
            { "7", "8", "9" },
            { "⌫", "0", "✓" },
        };
        for (String[] rowKeys : keys) {
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER);
            for (String key : rowKeys) {
                boolean enter = "✓".equals(key);
                TextView button = pill(context, key, 20, enter);
                LinearLayout.LayoutParams bounds = new LinearLayout.LayoutParams(px(78), px(46));
                bounds.leftMargin = px(4);
                bounds.rightMargin = px(4);
                row.addView(button, bounds);
                button.setOnClickListener(view -> onKeypad(key));
            }
            root.addView(row, rowParams(px(46), px(6)));
        }
        return root;
    }

    private void onKeypad(String key) {
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        if ("⌫".equals(key)) {
            if (keypadDigits.length() > 0) keypadDigits.setLength(keypadDigits.length() - 1);
        } else if ("✓".equals(key)) {
            long hz = keypadHz();
            keypadDigits.setLength(0);
            if (hz > 0) listener.onTuneTo(hz);
        } else if (keypadDigits.length() < 9) {
            keypadDigits.append(key);
        }
        updateKeypad();
    }

    /** Digits are entered most-significant first and read as kHz. */
    private long keypadHz() {
        if (keypadDigits.length() == 0) return 0;
        try {
            return Long.parseLong(keypadDigits.toString()) * 1000L;
        } catch (NumberFormatException tooLong) {
            return 0;
        }
    }

    private void updateKeypad() {
        if (keypadEntry == null) return;
        keypadEntry.setText(keypadDigits.length() == 0 ? "Enter kHz" : formatHz(keypadHz()));
    }

    private View buildBand(Context context) {
        bandPage = new ListPage(context, "Band", BAND_KEYS.length, index -> {
            String key = BAND_KEYS[index];
            return key.substring(0, key.length() - 1) + " M";
        }, this::selectBand);
        return bandPage.root;
    }

    private View buildMode(Context context) {
        modePage = new ListPage(context, "Mode", MODES.length, index -> MODES[index], index -> {
            JSONObject body = json("mode", index);
            put(body, "receiver", 0);
            listener.onAction("/api/mode", body);
        });
        return modePage.root;
    }

    private View buildFilter(Context context) {
        filterPage = new ListPage(context, "Filter", FILTER_WIDTHS_HZ.length,
                index -> formatWidth(FILTER_WIDTHS_HZ[index]), this::selectFilterWidth);
        return filterPage.root;
    }

    private View buildStep(Context context) {
        stepPage = new ListPage(context, "Step", STEPS_HZ.length,
                index -> formatStep(STEPS_HZ[index]), this::selectStep);
        return stepPage.root;
    }

    private View buildZoom(Context context) {
        zoomPage = new ListPage(context, "Zoom", ZOOM_LEVELS.length,
                index -> ZOOM_LEVELS[index] + "\u00d7", this::selectZoom);
        return zoomPage.root;
    }

    private void selectZoom(int index) {
        zoomLevel = ZOOM_LEVELS[index];
        listener.onAction("/api/rx/zoom", json("level", zoomLevel));
        updateZoomSelection();
    }

    private int zoomIndex() {
        for (int index = 0; index < ZOOM_LEVELS.length; index++) {
            if (ZOOM_LEVELS[index] == zoomLevel) return index;
        }
        return -1;
    }

    private void updateZoomSelection() {
        if (zoomPage != null) zoomPage.setSelected(zoomIndex());
    }

    private View buildSwitches(Context context) {
        LinearLayout root = column(context);
        root.addView(label(context, "Switches", 16, theme.muted), rowParams(px(22), 0));
        String[] names = { "Speaker", "Scope", "TUNE", "2-Tone", "Preamp", "Screen", "Wrist" };
        for (int row = 0; row * 2 < names.length; row++) {
            LinearLayout line = new LinearLayout(context);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setGravity(Gravity.CENTER);
            for (int column = 0; column < 2; column++) {
                int index = row * 2 + column;
                if (index >= names.length) break;
                TextView button = pill(context, names[index], 15, false);
                LinearLayout.LayoutParams bounds = new LinearLayout.LayoutParams(px(140), px(46));
                bounds.leftMargin = px(4);
                bounds.rightMargin = px(4);
                line.addView(button, bounds);
                switches.add(button);
                button.setOnClickListener(view -> toggleSwitch(index));
            }
            root.addView(line, rowParams(px(46), px(6)));
        }
        return root;
    }

    private View buildLevels(Context context) {
        LinearLayout root = column(context);
        levelName = label(context, "AF", 17, theme.muted);
        root.addView(levelName, rowParams(px(24), 0));

        levelValue = label(context, "—", 40, theme.foreground);
        root.addView(levelValue, rowParams(px(52), px(4)));

        LinearLayout adjust = new LinearLayout(context);
        adjust.setOrientation(LinearLayout.HORIZONTAL);
        adjust.setGravity(Gravity.CENTER);
        TextView minus = pill(context, "−", 26, false);
        TextView plus = pill(context, "+", 26, false);
        LinearLayout.LayoutParams bounds = new LinearLayout.LayoutParams(px(84), px(56));
        bounds.leftMargin = px(10);
        bounds.rightMargin = px(10);
        adjust.addView(minus, bounds);
        adjust.addView(plus, new LinearLayout.LayoutParams(bounds));
        root.addView(adjust, rowParams(px(56), px(10)));
        minus.setOnClickListener(view -> nudgeLevel(-1));
        plus.setOnClickListener(view -> nudgeLevel(1));

        TextView next = pill(context, "Next control", 15, false);
        LinearLayout.LayoutParams nextBounds = new LinearLayout.LayoutParams(px(190), px(44));
        nextBounds.topMargin = px(12);
        nextBounds.gravity = Gravity.CENTER_HORIZONTAL;
        root.addView(next, nextBounds);
        next.setOnClickListener(view -> {
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            levelIndex = (levelIndex + 1) % LEVELS.length;
            updateLevels();
        });
        return root;
    }

    // ---- Actions ----------------------------------------------------------

    private int stepIndex() {
        for (int index = 0; index < STEPS_HZ.length; index++) {
            if (STEPS_HZ[index] == stepHz) return index;
        }
        return -1;
    }

    private void selectStep(int index) {
        stepHz = STEPS_HZ[index];
        listener.onAction("/api/toolbar-settings", json("stepHz", stepHz));
        updateStepSelection();
    }

    private void selectBand(int index) {
        listener.onTuneTo(BAND_CENTERS[index]);
        showPage(PAGE_RADIO);
    }

    private void selectFilterWidth(int index) {
        int width = FILTER_WIDTHS_HZ[index];
        int low = filterLowHz;
        int high = filterHighHz;
        int newLow;
        int newHigh;
        if (low >= 0 && high > 0) {
            // Upper sideband: the low edge is the operator's chosen skirt.
            newLow = low;
            newHigh = low + width;
        } else if (high <= 0 && low < 0) {
            // Lower sideband: mirror the same rule about the high edge.
            newHigh = high;
            newLow = high - width;
        } else {
            newLow = -width / 2;
            newHigh = width / 2;
        }
        JSONObject body = json("lowHz", newLow);
        put(body, "highHz", newHigh);
        put(body, "presetName", JSONObject.NULL);
        put(body, "receiver", 0);
        listener.onAction("/api/filter", body);
    }

    private void toggleSwitch(int index) {
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        switch (index) {
            case 0:
                audioOn = !audioOn;
                listener.onAudio(audioOn);
                break;
            case 1:
                scopeOn = !scopeOn;
                listener.onDisplayEnabled(scopeOn);
                break;
            case 2:
                tunOn = !tunOn;
                listener.onStationTune(tunOn);
                break;
            case 3:
                twoToneOn = !twoToneOn;
                listener.onTwoTone(twoToneOn);
                break;
            case 4:
                preampOn = !preampOn;
                listener.onAction("/api/preamp", json("on", preampOn));
                break;
            case 5:
                keepAwake = !keepAwake;
                listener.onKeepAwake(keepAwake);
                break;
            default:
                // The listener refuses when the watch cannot sense its own
                // orientation, and the switch must not claim otherwise.
                wristPtt = listener.onWristPtt(!wristPtt);
                if (!wristPtt) setError("This watch cannot sense its orientation");
                break;
        }
        updateSwitches();
    }

    /** One adjustable control per page, matching the reference watch layout. */
    private static final String[][] LEVELS = {
        { "AF", "/api/rx/afGain", "db", "-50", "20", "2", "dB" },
        { "AGC-T", "/api/agcGain", "topDb", "-20", "120", "5", "dB" },
        { "ATT", "/api/attenuator", "db", "0", "31", "1", "dB" },
        { "Mic", "/api/mic-gain", "db", "-40", "10", "1", "dB" },
        { "Drive", "/api/tx/drive", "percent", "0", "100", "5", "%" },
    };

    private double levelCurrent() {
        switch (levelIndex) {
            case 0: return afGainDb;
            case 1: return agcTopDb;
            case 2: return attenDb;
            case 3: return micGainDb;
            default: return drivePct;
        }
    }

    private void levelStore(double value) {
        switch (levelIndex) {
            case 0: afGainDb = value; break;
            case 1: agcTopDb = value; break;
            case 2: attenDb = (int) Math.round(value); break;
            case 3: micGainDb = (int) Math.round(value); break;
            default: drivePct = (int) Math.round(value); break;
        }
    }

    private void nudgeLevel(int direction) {
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        String[] spec = LEVELS[levelIndex];
        double minimum = Double.parseDouble(spec[3]);
        double maximum = Double.parseDouble(spec[4]);
        double step = Double.parseDouble(spec[5]);
        double value = Math.max(minimum, Math.min(maximum, levelCurrent() + direction * step));
        levelStore(value);
        updateLevels();
        boolean whole = levelIndex >= 2;
        listener.onAction(spec[1], json(spec[2], whole ? (Object) (int) Math.round(value) : (Object) value));
    }

    private void updateLevels() {
        if (levelName == null) return;
        String[] spec = LEVELS[levelIndex];
        levelName.setText(spec[0]);
        double value = levelCurrent();
        boolean whole = levelIndex >= 2;
        levelValue.setText(whole
                ? String.format(Locale.US, "%d %s", (int) Math.round(value), spec[6])
                : String.format(Locale.US, "%.0f %s", value, spec[6]));
    }

    // ---- Station state ----------------------------------------------------

    /** Applies a whole station state or a patch carrying only changed keys. */
    public void setState(JSONObject state) {
        if (state.has("vfoHz")) vfoHz = state.optLong("vfoHz", vfoHz);
        if (state.has("mode")) modeIndex = modeIndexOf(state.opt("mode"));
        if (state.has("filterLowHz")) filterLowHz = state.optInt("filterLowHz", filterLowHz);
        if (state.has("filterHighHz")) filterHighHz = state.optInt("filterHighHz", filterHighHz);
        if (state.has("moxOn")) moxOn = state.optBoolean("moxOn", moxOn);
        if (state.has("tunOn")) tunOn = state.optBoolean("tunOn", tunOn);
        if (state.has("fwdWatts")) fwdWatts = state.optDouble("fwdWatts", fwdWatts);
        if (state.has("swr")) swr = state.optDouble("swr", swr);
        if (state.has("rxAfGainDb")) afGainDb = state.optDouble("rxAfGainDb", afGainDb);
        if (state.has("agcTopDb")) agcTopDb = state.optDouble("agcTopDb", agcTopDb);
        if (state.has("attenDb")) attenDb = state.optInt("attenDb", attenDb);
        if (state.has("micGainDb")) micGainDb = state.optInt("micGainDb", micGainDb);
        if (state.has("drivePct")) drivePct = state.optInt("drivePct", drivePct);
        if (state.has("zoomLevel")) zoomLevel = Math.max(1, state.optInt("zoomLevel", zoomLevel));
        if (state.has("stepHz")) {
            int candidate = state.optInt("stepHz", stepHz);
            if (candidate > 0) stepHz = candidate;
        }
        if (state.has("status")) {
            Object status = state.opt("status");
            connectionDetail = status == null ? connectionDetail : String.valueOf(status);
        }
        refresh();
    }

    /** Routes a REST reply the activity fetched on the view's behalf. */
    public void setResponse(String path, Object json) {
        if (!(json instanceof JSONObject)) return;
        JSONObject body = (JSONObject) json;
        if ("/api/toolbar-settings".equals(path)) {
            int candidate = body.optInt("stepHz", 0);
            if (candidate > 0) stepHz = candidate;
            updateStepSelection();
            return;
        }
        if ("/api/tx/drive".equals(path)) {
            drivePct = body.optInt("drivePercent", drivePct);
            updateLevels();
            return;
        }
        setState(body);
    }

    private static int modeIndexOf(Object mode) {
        if (mode instanceof Number) {
            int index = ((Number) mode).intValue();
            return index >= 0 && index < MODES.length ? index : 1;
        }
        String name = String.valueOf(mode);
        for (int index = 0; index < MODES.length; index++) {
            if (MODES[index].equalsIgnoreCase(name)) return index;
        }
        return 1;
    }

    public void setMeter(float dbm) {
        sMeterDbm = dbm;
        // Touch only the page being looked at; the meter arrives several times
        // a second and neither page needs the other's work.
        if (radioMeter != null && page == PAGE_RADIO) radioMeter.invalidate();
        else if (scopeMeter != null && page == PAGE_SCOPE) {
            String reading = signalReading();
            if (!reading.equals(shownScopeMeter)) {
                shownScopeMeter = reading;
                scopeMeter.setText(reading);
            }
        }
    }

    public void setDisplay(float[] panDb, float[] waterfallDb, long centerHz, double hzPerBin) {
        if (scope == null) return;
        scope.accept(panDb, waterfallDb, centerHz, hzPerBin);
    }

    public void setConnection(boolean online, String detail) {
        connected = online;
        connectionDetail = detail;
        refresh();
    }

    public void setError(String message) {
        lastError = message;
        lastErrorAt = SystemClock.elapsedRealtime();
        refresh();
    }

    /** Clears any local transmit affordance after the station drops the key. */
    public void releasePtt() {
        moxOn = false;
        tunOn = false;
        twoToneOn = false;
        refresh();
    }

    /** Re-states what this page needs, after the station or the app paused. */
    public void reapplyDisplayDemand() {
        listener.onDisplayEnabled(scopeOn && page == PAGE_SCOPE);
    }

    public int stepHz() {
        return stepHz;
    }

    public long vfoHz() {
        return vfoHz;
    }

    /** Optimistic local retune so the dial never waits on the network. */
    public void setVfoOptimistic(long hz) {
        vfoHz = hz;
        refresh();
    }

    /**
     * Coalesces to one redraw per frame. State arrives in bursts -- a VFO edge,
     * a MOX edge and a meter can all land between two frames, and each bezel
     * detent adds another -- and redrawing for each one is work nobody sees.
     */
    private void refresh() {
        if (refreshPending) return;
        refreshPending = true;
        postOnAnimation(redraw);
    }

    private final Runnable redraw = () -> {
        refreshPending = false;
        refreshNow();
    };

    private void refreshNow() {
        String status = statusLine();
        if (pttStatus != null) {
            if (!status.equals(shownStatus)) {
                shownStatus = status;
                pttStatus.setText(status);
            }
            pttStatus.setTextColor(moxOn ? theme.tx : theme.muted);
        }
        // Compare the box explicitly: "pttKeyed != moxOn" unboxes, and the
        // first redraw happens before anything has been applied.
        if (pttButton != null && !Boolean.valueOf(moxOn).equals(pttKeyed)) {
            pttKeyed = moxOn;
            GradientDrawable circle = new GradientDrawable();
            circle.setShape(GradientDrawable.OVAL);
            circle.setColor(moxOn ? theme.tx : theme.surface);
            circle.setStroke(px(3), moxOn ? theme.tx : theme.line);
            pttButton.setBackground(circle);
            pttButton.setTextColor(moxOn ? theme.background : theme.foreground);
        }
        if (radioFrequency != null) {
            String dial = dialText(vfoHz);
            if (!dial.equals(shownFrequency)) {
                shownFrequency = dial;
                radioFrequency.setText(dial);
            }
            radioFrequency.setTextColor(moxOn ? theme.tx : theme.foreground);
        }
        if (radioMeta != null) {
            // A transmit failure has to be readable on the page the operator
            // pressed, not only on the PTT page.
            boolean fresh = lastError != null && SystemClock.elapsedRealtime() - lastErrorAt < 4000;
            String meta = fresh ? lastError : moxOn
                    ? transmitLine()
                    : String.format(Locale.US, "%s   %s", MODES[modeIndex], formatWidth(passbandWidth()));
            if (!meta.equals(shownMeta)) {
                shownMeta = meta;
                radioMeta.setText(meta);
            }
            radioMeta.setTextColor(fresh ? theme.tx : theme.muted);
        }
        if (scopeFrequency != null) {
            boolean floor = floorVisible();
            String reading = floor
                    ? String.format(Locale.US, "Floor %.0f dB", floorDb)
                    : dialText(vfoHz);
            if (!reading.equals(shownScopeFrequency)) {
                shownScopeFrequency = reading;
                scopeFrequency.setText(reading);
            }
            scopeFrequency.setTextColor(floor ? theme.foreground
                    : moxOn ? theme.tx : theme.accent);
        }
        if (scopeMeter != null) {
            // While transmitting the same spot carries the power that matters.
            String power = moxOn
                    ? String.format(Locale.US, "%.0f W", ampWatts == null ? fwdWatts : ampWatts)
                    : signalReading();
            if (!power.equals(shownScopeMeter)) {
                shownScopeMeter = power;
                scopeMeter.setText(power);
            }
            scopeMeter.setTextColor(moxOn ? theme.tx : theme.foreground);
        }
        if (radioMox != null) tint(radioMox, moxOn, theme.tx);
        if (scopeMox != null) tint(scopeMox, moxOn, theme.tx);
        if (radioMeter != null) radioMeter.invalidate();
        updateStepSelection();
        updateZoomSelection();
        updateSwitches();
        updateLevels();
        updateKeypad();
        if (bandPage != null) bandPage.setSelected(bandIndexFor(vfoHz));
        if (modePage != null) modePage.setSelected(modeIndex);
        if (filterPage != null) filterPage.setSelected(filterIndexFor(passbandWidth()));
        invalidate();
    }

    /** Rig power, then the amplifier's, so the operator sees what left the shack. */
    private String transmitLine() {
        return ampWatts == null
                ? String.format(Locale.US, "%.0f W   SWR %.1f", fwdWatts, swr)
                : String.format(Locale.US, "%.0f \u2192 %.0f W   SWR %.1f", fwdWatts, ampWatts, swr);
    }

    /** Amplifier forward power in watts, or null to show the rig's alone. */
    public void setAmpWatts(Double watts) {
        if (watts == null ? ampWatts == null : watts.equals(ampWatts)) return;
        ampWatts = watts;
        refresh();
    }

    private void adjustFloor(float deltaDb) {
        float next = WaterfallPalette.clampFloor(floorDb + deltaDb);
        if (next == floorDb) return;
        // A tick each whole dB, so the floor can be felt as well as seen.
        if ((int) next != (int) floorDb) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        floorDb = next;
        floorShownAt = SystemClock.elapsedRealtime();
        if (scope != null) scope.invalidate();
        refresh();
    }

    /** The floor readout only shows while it is being moved. */
    private boolean floorVisible() {
        return SystemClock.elapsedRealtime() - floorShownAt < 1500;
    }

    /** S-units from the station's dBm, S9 at -73 dBm and 6 dB per unit below it. */
    private String signalReading() {
        if (sMeterDbm <= -160) return "\u2014";
        if (sMeterDbm >= -73) return String.format(Locale.US, "S9+%.0f", sMeterDbm + 73);
        int unit = (int) Math.max(0, Math.min(9, Math.round((sMeterDbm + 121) / 6.0) + 1));
        return "S" + unit;
    }

    private String statusLine() {
        if (lastError != null && SystemClock.elapsedRealtime() - lastErrorAt < 4000) return lastError;
        if (!connected) return connectionDetail == null ? "Offline" : connectionDetail;
        if (moxOn) return "TRANSMIT";
        return "Radio ready";
    }

    private int passbandWidth() {
        return Math.abs(filterHighHz - filterLowHz);
    }

    private static int bandIndexFor(long hz) {
        int best = -1;
        long closest = Long.MAX_VALUE;
        for (int index = 0; index < BAND_CENTERS.length; index++) {
            long distance = Math.abs(BAND_CENTERS[index] - hz);
            if (distance < closest) {
                closest = distance;
                best = index;
            }
        }
        return closest <= 2_000_000L ? best : -1;
    }

    private static int filterIndexFor(int width) {
        for (int index = 0; index < FILTER_WIDTHS_HZ.length; index++) {
            if (Math.abs(FILTER_WIDTHS_HZ[index] - width) <= 100) return index;
        }
        return -1;
    }

    private void updateStepSelection() {
        if (stepPage != null) stepPage.setSelected(stepIndex());
    }

    private static String formatStep(int hz) {
        return hz >= 1000
                ? String.format(Locale.US, "%d kHz", hz / 1000)
                : String.format(Locale.US, "%d Hz", hz);
    }

    private void updateSwitches() {
        if (switches.size() < 7) return;
        tint(switches.get(0), audioOn, theme.accent);
        tint(switches.get(1), scopeOn, theme.accent);
        tint(switches.get(2), tunOn, theme.tx);
        tint(switches.get(3), twoToneOn, theme.tx);
        tint(switches.get(4), preampOn, theme.accent);
        tint(switches.get(5), keepAwake, theme.accent);
        // Transmit-coloured: this one can key the radio without a press.
        tint(switches.get(6), wristPtt, theme.tx);
    }

    /** Caches the last dial string; the same frequency renders many times. */
    private String dialText(long hz) {
        if (hz != formattedHz) {
            formattedHz = hz;
            formattedHzText = formatHz(hz);
        }
        return formattedHzText;
    }

    private static String formatHz(long hz) {
        long mhz = hz / 1_000_000L;
        long khz = (hz / 1000L) % 1000L;
        long rest = hz % 1000L;
        return String.format(Locale.US, "%d.%03d.%03d", mhz, khz, rest);
    }

    private static String formatWidth(int hz) {
        return hz >= 1000
                ? String.format(Locale.US, "%.1fk", hz / 1000f)
                : String.format(Locale.US, "%d Hz", hz);
    }

    private static JSONObject json(String key, Object value) {
        JSONObject body = new JSONObject();
        put(body, key, value);
        return body;
    }

    private static void put(JSONObject body, String key, Object value) {
        try {
            body.put(key, value);
        } catch (JSONException impossible) {
            // Every caller passes a primitive or JSONObject.NULL.
        }
    }

    // ---- Paging -----------------------------------------------------------

    private void showPage(int index) {
        if (index < 0 || index >= pages.size()) return;
        pages.get(page).setVisibility(GONE);
        page = index;
        pages.get(page).setVisibility(VISIBLE);
        if (scope != null) scope.setVisibility(page == PAGE_SCOPE ? VISIBLE : GONE);
        // Spectrum is expensive on both ends: ask for it only while it shows.
        listener.onDisplayEnabled(scopeOn && page == PAGE_SCOPE);
        if (router != null) router.setVerticalEnabled(page == PAGE_SCOPE);
        announceForAccessibility(PAGE_TITLES[page]);
        invalidate();
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        // While the picker is open it owns the screen: no paging, no ring.
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            router.down(event.getX(), event.getY(), SystemClock.elapsedRealtime());
            // Never claim the touch on the way down: buttons keep their press,
            // and a swipe that starts near the rim still reaches the router.
            return false;
        }
        return action == MotionEvent.ACTION_MOVE
                && router.move(event.getX(), event.getY(), SystemClock.elapsedRealtime());
    }

    @Override
    @SuppressLint("ClickableViewAccessibility")
    public boolean onTouchEvent(MotionEvent event) {
        long now = SystemClock.elapsedRealtime();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                // Own the gesture. Without this a touch that lands between
                // controls is dropped after the first event, and neither paging
                // nor the ring ever sees the drag that follows.
                router.down(event.getX(), event.getY(), now);
                return true;
            case MotionEvent.ACTION_MOVE:
                router.move(event.getX(), event.getY(), now);
                return true;
            case MotionEvent.ACTION_UP:
                router.up(event.getX(), event.getY(), now);
                invalidate();
                return true;
            case MotionEvent.ACTION_CANCEL:
                router.cancel();
                invalidate();
                return true;
            default:
                return true;
        }
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        // The rotating bezel and crown arrive as a scroll axis on Wear OS.
        if (event.getAction() == MotionEvent.ACTION_SCROLL
                && event.isFromSource(android.view.InputDevice.SOURCE_ROTARY_ENCODER)) {
            float delta = event.getAxisValue(MotionEvent.AXIS_SCROLL);
            if (delta != 0f) {
                emitRingStep(delta > 0 ? 1 : -1);
                return true;
            }
        }
        return super.onGenericMotionEvent(event);
    }

    private void emitRingStep(int direction) {
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        listener.onTuneSteps(direction * RING_SENSE);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        // The trace well stops where the dial starts. Measuring it beats a
        // fixed share of the face, which drifts the moment the row moves.
        if (scope == null || scopeFrequency == null || scope.getVisibility() != VISIBLE) return;
        int y = 0;
        for (View view = scopeFrequency; view != null && view != this;) {
            y += view.getTop();
            android.view.ViewParent parent = view.getParent();
            view = parent instanceof View ? (View) parent : null;
        }
        scope.setTraceBottom(y);
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        float inset = px(6);
        ringBounds.set(inset, inset, getWidth() - inset, getHeight() - inset);
        ringPaint.setColor(router.isRingArmed() ? theme.accent : theme.line);
        canvas.drawArc(ringBounds, 0, 360, false, ringPaint);
        if (SystemClock.elapsedRealtime() - ringSweepAt < 600) {
            canvas.drawArc(ringBounds, ringSweep - 6, 12, false, ringMarkPaint);
        }
        drawPageDots(canvas);
    }

    private void drawPageDots(Canvas canvas) {
        float spacing = px(11);
        float y = getHeight() - px(30);
        float total = spacing * (pages.size() - 1);
        float x = getWidth() / 2f - total / 2f;
        for (int index = 0; index < pages.size(); index++) {
            dotPaint.setColor(index == page ? theme.accent : theme.line);
            canvas.drawCircle(x + spacing * index, y, index == page ? px(3) : px(2), dotPaint);
        }
    }

    // ---- Sub-views --------------------------------------------------------

    /** Signal strength as a bar with the station's dBm scale. */
    private final class MeterView extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint frame = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF bar = new RectF();

        MeterView(Context context) {
            super(context);
            frame.setStyle(Paint.Style.STROKE);
            frame.setStrokeWidth(px(1));
            frame.setColor(theme.line);
            text.setColor(theme.foreground);
            text.setTextAlign(Paint.Align.CENTER);
            text.setTextSize(24 * scale);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float height = px(14);
            float top = getHeight() - height;
            bar.set(0, top, getWidth(), getHeight());
            frame.setColor(theme.line);
            canvas.drawRoundRect(bar, height / 2f, height / 2f, frame);
            // S1 is -121 dBm and S9 is -73 dBm; +60 over S9 caps the scale.
            double fraction = Math.max(0, Math.min(1, (sMeterDbm + 121) / 134.0));
            if (fraction > 0) {
                bar.set(0, top, (float) (getWidth() * fraction), getHeight());
                fill.setColor(sMeterDbm > -73 ? TRACE_COLOR : theme.accent);
                canvas.drawRoundRect(bar, height / 2f, height / 2f, fill);
            }
            text.setColor(theme.foreground);
            canvas.drawText(signalReading(), getWidth() / 2f, top - px(6), text);
        }
    }

    /** Mini panadapter over a scrolling waterfall. */
    private final class ScopeView extends View {
        // A full-face waterfall: enough rows that a whole screen of history
        // reads smoothly rather than in visible bands.
        private static final int WATERFALL_ROWS = 110;

        /** Fallback share of the face for the trace well before the dial is laid out. */
        private static final float TRACE_BAND = 0.21f;

        /** Where the well ends, set from the dial's own position. */
        private int traceBottom = -1;

        void setTraceBottom(int y) {
            if (y == traceBottom || y <= 0) return;
            traceBottom = y;
            invalidate();
        }

        private final Paint trace = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint well = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint marker = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        /**
         * One row of colour. The bitmap scrolls by moving where the newest row
         * is written rather than by shifting every pixel: rewriting the whole
         * image each frame was the single most expensive thing this view did.
         */
        private final int[] row = new int[StationClient.DISPLAY_BINS];
        /** Row holding the newest line; rows below it are older, wrapping round. */
        private int newest;
        private final android.graphics.Rect source = new android.graphics.Rect();
        private final RectF slice = new RectF();
        private final Bitmap waterfall = Bitmap.createBitmap(
                StationClient.DISPLAY_BINS, WATERFALL_ROWS, Bitmap.Config.ARGB_8888);
        private float[] pan;
        private float[] segments;
        /** Where the span is centred, and how wide each column is. */
        private long centerHz;
        private double hzPerBin;

        ScopeView(Context context) {
            super(context);
            trace.setStyle(Paint.Style.STROKE);
            trace.setStrokeWidth(px(2));
            trace.setColor(TRACE_COLOR);
            well.setStyle(Paint.Style.FILL);
            well.setColor(theme.background);
            marker.setColor(theme.accent);
            marker.setStrokeWidth(px(2));
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(px(1));
            edge.setColor(theme.line);
        }

        void accept(float[] panDb, float[] waterfallDb, long centerHz, double hzPerBin) {
            this.centerHz = centerHz;
            if (hzPerBin > 0) this.hzPerBin = hzPerBin;
            if (panDb != null) pan = panDb;
            if (waterfallDb != null) {
                int columns = StationClient.DISPLAY_BINS;
                for (int column = 0; column < columns; column++) row[column] = heat(waterfallDb[column]);
                newest = (newest + WATERFALL_ROWS - 1) % WATERFALL_ROWS;
                waterfall.setPixels(row, 0, columns, 0, newest, columns, 1);
            }
            postInvalidateOnAnimation();
        }

        private int heat(float db) {
            return WaterfallPalette.color(db, floorDb);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int width = getWidth();
            int height = getHeight();
            if (width <= 0 || height <= 0) return;
            int panHeight = traceBottom > 0
                    ? Math.min(traceBottom, height)
                    : Math.round(height * TRACE_BAND);
            // The trace reads against a dark well, not against moving colour.
            canvas.drawRect(0, 0, width, panHeight, well);
            drawWaterfall(canvas, width, panHeight, height);
            float[] bins = pan;
            if (bins != null && bins.length > 1) {
                // One drawLines beats a drawLine per column: the trace is a
                // hundred-odd segments and this runs at frame rate.
                int needed = (bins.length - 1) * 4;
                if (segments == null || segments.length != needed) segments = new float[needed];
                float columnWidth = width / (float) (bins.length - 1);
                float previousX = 0;
                float previousY = level(bins[0], panHeight);
                int at = 0;
                for (int index = 1; index < bins.length; index++) {
                    float x = columnWidth * index;
                    float y = level(bins[index], panHeight);
                    segments[at++] = previousX;
                    segments[at++] = previousY;
                    segments[at++] = x;
                    segments[at++] = y;
                    previousX = x;
                    previousY = y;
                }
                canvas.drawLines(segments, 0, at, trace);
            }
            // The dial is only at the centre when the span is centred on it. With
            // click-tuning the span stays put and the dial moves inside it, so
            // place the marker where the frequency actually is.
            canvas.drawLine(markerX(width), 0, markerX(width), height, marker);
            canvas.drawLine(0, panHeight, width, panHeight, edge);
        }

        /** Draws the ring buffer as one continuous fall, newest line on top. */
        private void drawWaterfall(Canvas canvas, int width, int top, int bottom) {
            int span = bottom - top;
            if (span <= 0) return;
            int below = WATERFALL_ROWS - newest;
            float split = top + span * (below / (float) WATERFALL_ROWS);
            source.set(0, newest, StationClient.DISPLAY_BINS, WATERFALL_ROWS);
            slice.set(0, top, width, split);
            canvas.drawBitmap(waterfall, source, slice, null);
            if (newest == 0) return;
            source.set(0, 0, StationClient.DISPLAY_BINS, newest);
            slice.set(0, split, width, bottom);
            canvas.drawBitmap(waterfall, source, slice, null);
        }

        private float markerX(int width) {
            if (hzPerBin <= 0 || centerHz <= 0 || vfoHz <= 0) return width / 2f;
            double span = hzPerBin * StationClient.DISPLAY_BINS;
            double offset = (vfoHz - centerHz) / span * width;
            return (float) Math.max(0, Math.min(width, width / 2.0 + offset));
        }

        private float level(float db, int panHeight) {
            return panHeight - WaterfallPalette.level(db, floorDb) * panHeight;
        }
    }

    private interface Label {
        String of(int index);
    }

    private interface Choose {
        void at(int index);
    }

    /** A three-row selector: the reference layout for band, mode and filter. */
    private final class ListPage {
        final LinearLayout root;
        private final List<TextView> rows = new ArrayList<>();
        private final Label label;
        private final Choose choose;
        private final int count;
        private int top;
        private int selected = -1;

        ListPage(Context context, String title, int count, Label label, Choose choose) {
            this.count = count;
            this.label = label;
            this.choose = choose;
            root = column(context);
            root.addView(WatchView.this.label(context, title, 16, theme.muted), rowParams(px(22), 0));
            boolean scrolls = count > 3;
            if (scrolls) {
                TextView up = pill(context, "▲", 15, false);
                LinearLayout.LayoutParams arrow = new LinearLayout.LayoutParams(px(74), px(32));
                arrow.gravity = Gravity.CENTER_HORIZONTAL;
                arrow.topMargin = px(2);
                root.addView(up, arrow);
                up.setOnClickListener(view -> scroll(-1));
            }
            for (int row = 0; row < 3; row++) {
                TextView button = pill(context, "", 19, false);
                LinearLayout.LayoutParams bounds = new LinearLayout.LayoutParams(px(230), px(48));
                bounds.topMargin = px(6);
                bounds.gravity = Gravity.CENTER_HORIZONTAL;
                root.addView(button, bounds);
                rows.add(button);
                int offset = row;
                button.setOnClickListener(view -> {
                    int index = top + offset;
                    if (index < 0 || index >= this.count) return;
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    this.choose.at(index);
                });
            }
            if (scrolls) {
                TextView down = pill(context, "▼", 15, false);
                LinearLayout.LayoutParams downBounds = new LinearLayout.LayoutParams(px(74), px(32));
                downBounds.gravity = Gravity.CENTER_HORIZONTAL;
                downBounds.topMargin = px(6);
                root.addView(down, downBounds);
                down.setOnClickListener(view -> scroll(1));
            }
            render();
        }

        private void scroll(int direction) {
            root.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            top = Math.max(0, Math.min(Math.max(0, count - 3), top + direction));
            render();
        }

        void setSelected(int index) {
            if (selected == index) return;
            selected = index;
            if (index >= 0) top = Math.max(0, Math.min(Math.max(0, count - 3), index - 1));
            render();
        }

        private void render() {
            for (int row = 0; row < rows.size(); row++) {
                int index = top + row;
                TextView button = rows.get(row);
                boolean present = index >= 0 && index < count;
                button.setText(present ? label.of(index) : "");
                button.setEnabled(present);
                tint(button, present && index == selected, theme.accent);
            }
        }
    }
}
