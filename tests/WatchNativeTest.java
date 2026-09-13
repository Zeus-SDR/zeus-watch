// SPDX-License-Identifier: LicenseRef-Proprietary
// Copyright (C) 2026 Douglas J. Cerrato (KB2UKA) and Christian Suarez (N9WAR).
package org.openhpsdr.zeus.watch;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Standalone JVM checks for the parts of the native watch app that carry real
 * risk: the station wire format, the palette the UI is built from, and the
 * tuning coalescer that decides what the operator sees while turning the ring.
 *
 * <p>Argument 1 is the path to zeus-web/src/styles/tokens.css.
 */
public final class WatchNativeTest {
    public static void main(String[] args) throws Exception {
        display();
        audio();
        controlFrames();
        palette(args[0]);
        tuning();
        touch();
        palette2();
        wrist();
        System.out.println("All native watch checks passed");
    }

    // ---- Display ----------------------------------------------------------

    private static void display() {
        int width = 480;
        float[] pan = new float[width];
        float[] waterfall = new float[width];
        for (int index = 0; index < width; index++) {
            pan[index] = -120f;
            waterfall[index] = -130f;
        }
        // One narrow peak must survive reduction to 120 columns.
        pan[241] = -35f;
        byte[] frame = displayFrame(width, 0x03, 14_200_000L, 100f, pan, waterfall);

        WireFrames.Display decoded = WireFrames.decodeDisplay(frame, 120);
        check(decoded != null, "Display frame decodes");
        check(decoded.panDb.length == 120 && decoded.waterfallDb.length == 120, "Reduced to 120 columns");
        check(decoded.centerHz == 14_200_000L, "Center frequency survives");
        // 480 pixels of 100 Hz across 120 columns is 400 Hz per column.
        check(Math.abs(decoded.hzPerBin - 400.0) < 1e-6, "Span per column is preserved");
        check(Math.abs(decoded.panDb[60] - (-35f)) < 1e-6, "Peak survives reduction");
        check(Math.abs(decoded.panDb[0] - (-120f)) < 1e-6, "Floor is carried through");
        check(Math.abs(decoded.waterfallDb[0] - (-130f)) < 1e-6, "Waterfall is decoded separately");

        WireFrames.Display panOnly = WireFrames.decodeDisplay(
                displayFrame(width, 0x01, 0L, 100f, pan, waterfall), 120);
        check(panOnly != null && panOnly.panDb != null && panOnly.waterfallDb == null,
                "Invalid waterfall flag suppresses the waterfall");

        check(WireFrames.decodeDisplay(displayFrame(width, 0x00, 0L, 100f, pan, waterfall), 120) == null,
                "Frame with nothing valid is dropped");
        check(WireFrames.decodeDisplay(displayFrame(width, 0x03, 0L, 0f, pan, waterfall), 120) == null,
                "Zero span is rejected");

        byte[] truncated = new byte[frame.length - 4];
        System.arraycopy(frame, 0, truncated, 0, truncated.length);
        check(WireFrames.decodeDisplay(truncated, 120) == null, "Truncated display frame is dropped");
        check(WireFrames.decodeDisplay(new byte[] { 0x01 }, 120) == null, "Runt display frame is dropped");
        check(WireFrames.decodeDisplay(frame, 0) == null, "Zero columns is rejected");

        frame[0] = 0x7f;
        check(WireFrames.decodeDisplay(frame, 120) == null, "Foreign message type is not read as display");
    }

    private static byte[] displayFrame(int width, int bodyFlags, long centerHz, float hzPerPixel,
            float[] pan, float[] waterfall) {
        int payload = 16 + width * 8;
        ByteBuffer frame = ByteBuffer.allocate(16 + payload).order(ByteOrder.LITTLE_ENDIAN);
        frame.put((byte) 0x01);
        frame.put((byte) 0);
        frame.putShort((short) payload);
        frame.putInt(7);
        frame.putDouble(1.0);
        frame.put((byte) 0);
        frame.put((byte) bodyFlags);
        frame.putShort((short) width);
        frame.putLong(centerHz);
        frame.putFloat(hzPerPixel);
        for (float value : pan) frame.putFloat(value);
        for (float value : waterfall) frame.putFloat(value);
        return frame.array();
    }

    // ---- Audio ------------------------------------------------------------

    private static void audio() {
        int samples = 240;
        ByteBuffer frame = ByteBuffer.allocate(16 + 8 + samples * 2 * 4).order(ByteOrder.LITTLE_ENDIAN);
        frame.put((byte) 0x02);
        frame.put((byte) 0);
        frame.putShort((short) (8 + samples * 2 * 4));
        frame.putInt(3);
        frame.putDouble(2.0);
        frame.put((byte) 0);
        frame.put((byte) 2);
        frame.putInt(48000);
        frame.putShort((short) samples);
        for (int index = 0; index < samples * 2; index++) frame.putFloat(index / 1000f);

        WireFrames.Audio decoded = WireFrames.decodeAudio(frame.array());
        check(decoded != null, "Audio frame decodes");
        check(decoded.channels == 2 && decoded.sampleRateHz == 48000, "Audio format is read");
        check(decoded.interleaved.length == samples * 2, "Every interleaved sample is read");
        check(Math.abs(decoded.interleaved[479] - 0.479f) < 1e-6, "Last sample is intact");

        byte[] short_ = new byte[frame.array().length - 8];
        System.arraycopy(frame.array(), 0, short_, 0, short_.length);
        check(WireFrames.decodeAudio(short_) == null, "Truncated audio frame is dropped");
        check(WireFrames.decodeAudio(new byte[] { 0x02, 0, 0, 0 }) == null, "Runt audio frame is dropped");
    }

    // ---- Control frames ---------------------------------------------------

    private static void controlFrames() {
        check(WireFrames.micFrame(new float[959]) == null, "A short microphone block is refused");
        check(WireFrames.micFrame(new float[961]) == null, "An over-long microphone block is refused");
        float[] block = new float[WireFrames.MIC_FRAME_SAMPLES];
        block[0] = 0.5f;
        block[959] = -0.25f;
        byte[] mic = WireFrames.micFrame(block);
        check(mic != null && mic.length == 1 + 960 * 4, "Microphone frame is 3841 bytes");
        check(mic[0] == 0x20, "Microphone frame carries the TX ingest type");
        ByteBuffer view = WireFrames.wrap(mic);
        check(Math.abs(view.getFloat(1) - 0.5f) < 1e-6, "Microphone samples are little-endian floats");
        check(Math.abs(view.getFloat(1 + 959 * 4) - (-0.25f)) < 1e-6, "Last microphone sample is intact");

        byte[] displayOn = WireFrames.streamRequest(WireFrames.MSG_DISPLAY_REQUEST, 2);
        check(displayOn.length == 2 && displayOn[0] == 0x22 && displayOn[1] == 2,
                "Display request asks for the visible tier");
        byte[] audioOff = WireFrames.streamRequest(WireFrames.MSG_AUDIO_REQUEST, 0);
        check(audioOff[0] == 0x21 && audioOff[1] == 0, "Audio request can be turned off");

        ByteBuffer meter = ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN);
        meter.put((byte) 0x14).putFloat(-73.5f);
        check(Math.abs(WireFrames.decodeRxMeter(meter.array()) - (-73.5f)) < 1e-6, "RX meter decodes");
        check(Float.isNaN(WireFrames.decodeRxMeter(new byte[] { 0x14, 0, 0 })), "Short RX meter is dropped");

        ByteBuffer vfo = ByteBuffer.allocate(18).order(ByteOrder.LITTLE_ENDIAN);
        vfo.put((byte) 0x26).put((byte) 0).putLong(7_074_000L).putLong(7_074_000L);
        check(WireFrames.decodeVfoHz(vfo.array()) == 7_074_000L, "VFO edge decodes");
        ByteBuffer rx2 = ByteBuffer.allocate(18).order(ByteOrder.LITTLE_ENDIAN);
        rx2.put((byte) 0x26).put((byte) 1).putLong(7_074_000L).putLong(7_074_000L);
        check(WireFrames.decodeVfoHz(rx2.array()) == -1L, "An RX2 edge never moves the watch dial");

        check(WireFrames.decodeMox(new byte[] { 0x1c, 1, 0, 0 }).moxOn, "MOX on decodes");
        check(WireFrames.decodeMox(new byte[] { 0x1c, 0, 1, 0 }).tunOn, "TUN on decodes");
        check(WireFrames.decodeMox(new byte[] { 0x1c, 1, 0 }) == null, "Short MOX frame is dropped");

        ByteBuffer tx = ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN);
        tx.put((byte) 0x16).putFloat(42.5f).putFloat(1.5f).putFloat(1.2f);
        float[] meters = WireFrames.decodeTxMeters(tx.array());
        check(meters != null && Math.abs(meters[0] - 42.5f) < 1e-6, "Forward power decodes");
        check(Math.abs(meters[1] - 1.2f) < 1e-6, "SWR is read from the third field");
    }

    // ---- Palette ----------------------------------------------------------

    private static void palette(String tokensPath) throws Exception {
        String css = new String(Files.readAllBytes(Paths.get(tokensPath)), StandardCharsets.UTF_8);
        Map<String, Integer> colors = PaletteParser.parse(css);
        String[] required = {
            "--bg-app", "--bg-1", "--fg-0", "--fg-2", "--accent-bright", "--tx", "--line-strong",
        };
        for (String key : required) {
            int color = PaletteParser.require(colors, key);
            check((color >>> 24) == 0xff, key + " is opaque");
        }
        // The default theme must win: the light-theme block later in the file
        // redefines the same names, and the watch follows the default one.
        int background = PaletteParser.require(colors, "--bg-app");
        int foreground = PaletteParser.require(colors, "--fg-0");
        check(luminance(background) < luminance(foreground),
                "Default theme is read, not a later theme block");

        check(!colors.containsKey("--accent-rank-2"),
                "Computed token values are skipped, only literal colours are read");

        try {
            PaletteParser.require(colors, "--not-a-zeus-token");
            check(false, "A missing token must fail the build");
        } catch (java.io.IOException expected) {
            check(true, "A missing token fails loudly");
        }
        try {
            PaletteParser.parse("body { color: #ffffff; }");
            check(false, "A stylesheet with no default theme must fail");
        } catch (java.io.IOException expected) {
            check(true, "A stylesheet with no default theme fails loudly");
        }
    }

    private static int luminance(int color) {
        return ((color >> 16) & 0xff) + ((color >> 8) & 0xff) + (color & 0xff);
    }

    // ---- Tuning -----------------------------------------------------------

    private static void tuning() {
        List<Long> sent = new ArrayList<>();
        TuningModel model = new TuningModel(sent::add);
        long now = 0;

        check(model.acceptStationHz(14_200_000L, now), "An idle dial follows the station");
        check(model.displayedHz() == 14_200_000L, "Station frequency is displayed");

        model.step(1, 500L, now);
        check(model.displayedHz() == 14_200_500L, "One detent moves by one step immediately");
        check(sent.size() == 1 && sent.get(0) == 14_200_500L, "The first detent is sent at once");

        // Three more detents while the first request is still in the air.
        model.step(1, 500L, now);
        model.step(1, 500L, now);
        model.step(1, 500L, now);
        check(model.displayedHz() == 14_202_000L, "The dial keeps moving while a request is in flight");
        check(sent.size() == 1, "Detents coalesce into the one request in flight");

        check(!model.acceptStationHz(14_200_000L, now), "A stale echo cannot drag the dial back");
        check(model.displayedHz() == 14_202_000L, "The operator's frequency is retained");

        model.completed(14_200_500L, now);
        check(sent.size() == 2 && sent.get(1) == 14_202_000L, "The latest frequency is sent next");

        model.completed(14_202_000L, now);
        check(sent.size() == 2, "A settled dial sends nothing further");

        check(!model.acceptStationHz(14_200_000L, now + 100), "Echoes stay ignored inside the guard");
        check(model.acceptStationHz(14_300_000L, now + TuningModel.ECHO_GUARD_MS),
                "The station is followed again once the guard expires");
        check(model.displayedHz() == 14_300_000L, "A front-panel tune reaches the watch");

        model.request(0L, now);
        check(model.displayedHz() == 14_300_000L, "A nonsense frequency is ignored");
        model.step(0, 500L, now);
        model.step(1, 0L, now);
        check(sent.size() == 2, "Zero steps and a zero step size send nothing");

        List<Long> down = new ArrayList<>();
        TuningModel reverse = new TuningModel(down::add);
        reverse.acceptStationHz(7_100_000L, 0);
        reverse.step(-3, 1000L, 0);
        check(reverse.displayedHz() == 7_097_000L, "Reverse tuning subtracts whole steps");
        check(down.size() == 1 && down.get(0) == 7_097_000L, "Reverse tuning is sent once");
    }

    // ---- Touch arbitration -------------------------------------------------

    /** Records what the router decided so each gesture can be asserted. */
    private static final class Recorder implements TouchRouter.Sink {
        int armed;
        int steps;
        int page;
        int pages;

        @Override
        public void onRingArmed() {
            armed++;
        }

        @Override
        public void onRingStep(int direction) {
            steps += direction;
        }

        @Override
        public void onPage(int delta) {
            page += delta;
            pages++;
        }

        @Override
        public void onVerticalDrag(float dy) {
            drag += dy;
            drags++;
        }

        float drag;
        int drags;
    }

    /** A 450 px round face, the Watch5 Pro geometry. */
    private static TouchRouter router(Recorder sink) {
        return new TouchRouter(sink, 225f, 225f, 189f, 16f, 72f, 150L);
    }

    private static void touch() {
        // A swipe that starts on the rim, where a page swipe naturally begins.
        Recorder rim = new Recorder();
        TouchRouter router = router(rim);
        router.down(400f, 225f, 0);
        for (int step = 1; step <= 8; step++) router.move(400f - step * 25f, 225f, step * 12L);
        router.up(200f, 225f, 96L);
        check(rim.steps == 0, "A swipe from the rim does not tune");
        check(rim.pages == 1 && rim.page == 1, "A swipe from the rim pages forward");

        // The same swipe the other way.
        Recorder back = new Recorder();
        router = router(back);
        router.down(60f, 225f, 0);
        for (int step = 1; step <= 8; step++) router.move(60f + step * 25f, 225f, step * 12L);
        router.up(260f, 225f, 96L);
        check(back.steps == 0 && back.page == -1, "A swipe back pages back");

        // A swipe across the top, the case where a straight drag sweeps a wide
        // angle and geometry alone cannot tell it from a turn of the ring.
        Recorder top = new Recorder();
        router = router(top);
        router.down(150f, 40f, 0);
        for (int step = 1; step <= 8; step++) router.move(150f + step * 25f, 40f, step * 12L);
        router.up(350f, 40f, 96L);
        check(top.steps == 0, "A straight swipe near the top edge does not tune");
        check(top.pages == 1, "A straight swipe near the top edge pages");

        // Rest on the rim, then turn: this is what tuning takes.
        Recorder ring = new Recorder();
        router = router(ring);
        router.down(414f, 225f, 0);
        router.move(414f, 225f, 200L);
        check(ring.armed == 1, "Dwelling on the rim arms the ring");
        // 46 degrees clockwise around the rim is five detents. Sweeps stay off
        // exact multiples of the detent so float rounding cannot decide a test.
        for (int degree = 1; degree <= 46; degree++) {
            double radians = Math.toRadians(degree);
            router.move(225f + (float) (189 * Math.cos(radians)),
                    225f + (float) (189 * Math.sin(radians)), 200L + degree);
        }
        check(ring.steps == 5, "Turning the armed ring tunes one step per 9 degrees");
        check(ring.pages == 0, "Turning the ring never pages");
        router.up(225f, 414f, 260L);

        // Counter-clockwise tunes the other way.
        Recorder reverse = new Recorder();
        router = router(reverse);
        router.down(414f, 225f, 0);
        router.move(414f, 225f, 200L);
        for (int degree = 1; degree <= 46; degree++) {
            double radians = Math.toRadians(-degree);
            router.move(225f + (float) (189 * Math.cos(radians)),
                    225f + (float) (189 * Math.sin(radians)), 200L + degree);
        }
        check(reverse.steps == -5, "Turning the ring back tunes down");

        // A touch that starts inside the rim can never arm the ring.
        Recorder inside = new Recorder();
        router = router(inside);
        router.down(225f, 225f, 0);
        router.move(225f, 225f, 500L);
        check(inside.armed == 0, "Dwelling in the middle does not arm the ring");
        for (int step = 1; step <= 8; step++) router.move(225f + step * 25f, 225f, 500L + step * 12L);
        router.up(425f, 225f, 600L);
        check(inside.page == -1, "A swipe from the middle still pages");

        // A tap must do nothing at all.
        Recorder tap = new Recorder();
        router = router(tap);
        router.down(225f, 300f, 0);
        router.up(225f, 300f, 40L);
        check(tap.armed == 0 && tap.steps == 0 && tap.pages == 0, "A tap neither tunes nor pages");

        // A short drag is not a page swipe.
        Recorder nudge = new Recorder();
        router = router(nudge);
        router.down(225f, 300f, 0);
        for (int step = 1; step <= 2; step++) router.move(225f + step * 20f, 300f, step * 12L);
        router.up(265f, 300f, 24L);
        check(nudge.pages == 0, "A short drag does not page");

        // A vertical drag is not a page swipe either.
        Recorder vertical = new Recorder();
        router = router(vertical);
        router.down(225f, 120f, 0);
        for (int step = 1; step <= 8; step++) router.move(225f, 120f + step * 25f, step * 12L);
        router.up(225f, 320f, 96L);
        check(vertical.pages == 0, "A vertical drag does not page");

        // A vertical drag moves the floor, but only where it is enabled.
        Recorder off = new Recorder();
        router = router(off);
        router.down(225f, 140f, 0);
        for (int step = 1; step <= 8; step++) router.move(225f, 140f + step * 20f, step * 12L);
        router.up(225f, 300f, 96L);
        check(off.drags == 0, "A vertical drag does nothing where it is not enabled");

        Recorder floor = new Recorder();
        router = router(floor);
        router.setVerticalEnabled(true);
        router.down(225f, 140f, 0);
        for (int step = 1; step <= 8; step++) router.move(225f, 140f + step * 20f, step * 12L);
        router.up(225f, 300f, 96L);
        check(floor.drags > 0, "A vertical drag reports where it is enabled");
        // Reporting starts where the drag engages, not at touch-down: the slop
        // travelled to prove intent is not part of the adjustment.
        check(Math.abs(floor.drag - 120f) < 1f, "The drag reports movement from where it engaged");
        check(floor.pages == 0 && floor.steps == 0, "A vertical drag neither pages nor tunes");

        // Dragging up reports the other sign, so the floor can go both ways.
        Recorder upward = new Recorder();
        router = router(upward);
        router.setVerticalEnabled(true);
        router.down(225f, 300f, 0);
        for (int step = 1; step <= 8; step++) router.move(225f, 300f - step * 20f, step * 12L);
        router.up(225f, 140f, 96L);
        check(upward.drag < 0, "Dragging up reports a negative distance");

        // Horizontal still wins where it is clearly horizontal.
        Recorder both = new Recorder();
        router = router(both);
        router.setVerticalEnabled(true);
        router.down(400f, 225f, 0);
        for (int step = 1; step <= 8; step++) router.move(400f - step * 25f, 225f, step * 12L);
        router.up(200f, 225f, 96L);
        check(both.pages == 1 && both.drags == 0, "A horizontal swipe still pages with the drag enabled");

        // Turning the gesture off mid-drag stops it.
        Recorder stopped = new Recorder();
        router = router(stopped);
        router.setVerticalEnabled(true);
        router.down(225f, 140f, 0);
        router.move(225f, 200f, 12L);
        int before = stopped.drags;
        router.setVerticalEnabled(false);
        router.move(225f, 260f, 24L);
        check(stopped.drags == before, "Disabling the gesture stops it mid-drag");

        // A cancelled gesture commits nothing.
        Recorder cancelled = new Recorder();
        router = router(cancelled);
        router.down(400f, 225f, 0);
        for (int step = 1; step <= 8; step++) router.move(400f - step * 25f, 225f, step * 12L);
        router.cancel();
        check(cancelled.pages == 0, "A cancelled swipe does not page");
    }

    // ---- Waterfall palette -------------------------------------------------

    /** The watch must paint a level the same colour the desktop does. */
    private static void palette2() {
        // Anchors land on their published colours.
        check(WaterfallPalette.at(0f) == 0xFF000000, "The palette floor is black");
        check(WaterfallPalette.at(1f) == 0xFFFFFFFF, "The palette peak is white");
        for (int index = 0; index < WaterfallPalette.STOPS.length; index++) {
            check(WaterfallPalette.at(WaterfallPalette.STOPS[index]) == WaterfallPalette.COLORS[index],
                    "Anchor " + WaterfallPalette.STOPS[index] + " keeps its colour");
        }

        // The floor is held black past the midpoint, which is the whole point
        // of the station's blue ramp: a noisy band must not flood the screen.
        check(WaterfallPalette.at(0.30f) == 0xFF000000, "Below the first stop stays black");
        check(WaterfallPalette.at(0.41f) == 0xFF000000, "The floor is still black at 0.41");
        check(WaterfallPalette.at(0.47f) != 0xFF000000, "Colour appears past the black shelf");

        // The ramp is a hue journey, not a brightness ramp -- it dips through
        // green on the way to yellow -- so what matters is that it is smooth.
        // A band edge would show as a visible seam in the waterfall.
        int[] lut = WaterfallPalette.build(WaterfallPalette.LUT_SIZE);
        int widest = 0;
        for (int index = 1; index < lut.length; index++) {
            for (int shift = 0; shift <= 16; shift += 8) {
                int step = Math.abs(((lut[index] >> shift) & 0xff) - ((lut[index - 1] >> shift) & 0xff));
                if (step > widest) widest = step;
            }
        }
        check(WaterfallPalette.LUT_SIZE == 1024, "The ramp resamples at the renderer's LUT size");
        check(widest <= 6, "The ramp has no visible band edge, widest step " + widest);
        check(lut[0] == 0xFF000000 && lut[lut.length - 1] == 0xFFFFFFFF, "The ramp spans black to white");

        // dB maps through the station's fixed receive window with its contrast.
        check(WaterfallPalette.level(-140f) == 0f, "The bottom of the window is level zero");
        check(WaterfallPalette.level(-50f) == 1f, "The top of the window is level one");
        check(WaterfallPalette.level(-200f) == 0f, "Below the window clamps");
        check(WaterfallPalette.level(0f) == 1f, "Above the window clamps");
        float middle = WaterfallPalette.level((WaterfallPalette.DB_MIN + WaterfallPalette.DB_MAX) / 2f);
        check(Math.abs(middle - 0.5f) < 1e-6, "The window midpoint is the ramp midpoint");
        // Contrast steepens either side of the midpoint.
        check(WaterfallPalette.level(-70f) > (-70f - -140f) / 90f, "Contrast lifts the upper half");
        check(WaterfallPalette.level(-120f) < (-120f - -140f) / 90f, "Contrast deepens the lower half");
        check(Float.isNaN(Float.NaN) && WaterfallPalette.level(Float.NaN) == 0f, "A NaN level reads as the floor");

        // Moving the floor slides the window without changing its width.
        check(WaterfallPalette.level(-120f, -120f) == 0f, "The moved floor is level zero");
        check(WaterfallPalette.level(-120f + WaterfallPalette.WINDOW_DB, -120f) == 1f,
                "The window keeps its width when the floor moves");
        check(WaterfallPalette.level(-130f, -120f) == 0f, "Below the moved floor clamps");
        // Lifting the floor slides the window up, so a given signal sits lower in
        // it and dims. That is the point: noise is pushed down toward black.
        check(WaterfallPalette.level(-100f, -120f) < WaterfallPalette.level(-100f, -140f),
                "Lifting the floor pushes a signal toward black");
        check(WaterfallPalette.level(-100f, -150f) > WaterfallPalette.level(-100f, -140f),
                "Dropping the floor brings the same signal up");
        check(WaterfallPalette.clampFloor(-500f) == WaterfallPalette.FLOOR_MIN, "The floor cannot go below its range");
        check(WaterfallPalette.clampFloor(0f) == WaterfallPalette.FLOOR_MAX, "The floor cannot go above its range");
        check(WaterfallPalette.clampFloor(Float.NaN) == WaterfallPalette.DB_MIN, "A NaN floor falls back to the default");

        check(WaterfallPalette.color(-140f) == 0xFF000000, "A dead-quiet bin is black");
        check(WaterfallPalette.color(-50f) == 0xFFFFFFFF, "A full-scale bin is white");
    }

    // ---- Keying by wrist ---------------------------------------------------

    /** Feeds a face tilt: 0 is looking straight up at the operator, 180 face down. */
    private static boolean feed(WristFlip flip, float tilt, long fromMs, long toMs, long stepMs) {
        double radians = Math.toRadians(tilt);
        float y = (float) (9.8 * Math.sin(radians));
        float z = (float) (9.8 * Math.cos(radians));
        boolean keyed = flip.isKeyed();
        for (long now = fromMs; now <= toMs; now += stepMs) keyed = flip.update(0f, y, z, now);
        return keyed;
    }

    private static void wrist() {
        check(Math.abs(WristFlip.tiltDegrees(1f)) < 0.01f, "Face up reads as no tilt");
        check(Math.abs(WristFlip.tiltDegrees(0f) - 90f) < 0.01f, "Face edge-on reads as a right angle");
        check(Math.abs(WristFlip.tiltDegrees(-1f) - 180f) < 0.01f, "Face down reads as fully turned");

        // Switching it on while looking at the watch must not key: the operator
        // is looking at it to switch it on. It arms only once turned away.
        WristFlip flip = new WristFlip();
        check(!feed(flip, 10f, 0, 2000, 20), "Already in view does not key");
        check(!flip.isArmed(), "It is not armed while still in view");

        // Turn away, then flick to view and hold: that is the gesture.
        check(!feed(flip, 120f, 2100, 2400, 20), "Turning away does not key");
        check(flip.isArmed(), "Turning away arms it");
        check(!feed(flip, 10f, 2500, 2600, 20), "Coming into view does not key before the dwell");
        check(feed(flip, 10f, 2620, 3000, 20), "Held in view, it keys");

        // Turning away releases, and re-arms for the next over.
        check(!feed(flip, 120f, 3100, 3300, 20), "Turning away unkeys");
        check(flip.isArmed(), "It re-arms on the way out");
        check(feed(flip, 15f, 3400, 3900, 20), "The next flick keys again");
        feed(flip, 120f, 4000, 4200, 20);

        // A glance passes through without dwelling.
        WristFlip glance = new WristFlip();
        feed(glance, 120f, 0, 200, 20);
        glance.update(0f, 1.7f, 9.6f, 300);
        glance.update(0f, 1.7f, 9.6f, 360);
        feed(glance, 120f, 380, 600, 20);
        check(!glance.isKeyed(), "A glance does not key");

        // Hysteresis: between the angles it neither keys nor releases.
        WristFlip edge = new WristFlip();
        feed(edge, 120f, 0, 300, 20);
        check(!feed(edge, 60f, 400, 2000, 20), "Between the angles does not key");
        check(feed(edge, 20f, 2100, 2600, 20), "Coming fully into view keys");
        check(feed(edge, 60f, 2700, 3200, 20), "Drifting back between the angles stays keyed");
        check(!feed(edge, 90f, 3300, 3600, 20), "Past the release angle unkeys");

        // An arm left face-up must not transmit forever, nor retake the key
        // without the face being turned away first.
        WristFlip stuck = new WristFlip();
        feed(stuck, 120f, 0, 300, 20);
        check(feed(stuck, 10f, 400, 900, 20), "Keys before the long-hold check");
        long past = 900 + WristFlip.MAX_HOLD_MS + 100;
        check(!feed(stuck, 10f, past, past + 100, 50), "A key held past the maximum is dropped");
        check(!feed(stuck, 10f, past + 200, past + 4000, 50),
                "It cannot retake the key while still face up");
        feed(stuck, 120f, past + 4200, past + 4400, 20);
        check(feed(stuck, 10f, past + 4500, past + 5000, 20),
                "It keys again once the face has been turned away");

        // Nonsense samples say nothing about which way is up.
        WristFlip freefall = new WristFlip();
        feed(freefall, 120f, 0, 300, 20);
        check(!freefall.update(0f, 0f, 0f, 400), "A zero sample is ignored");

        // reset() must drop the key and the arming, not just one of them.
        WristFlip cleared = new WristFlip();
        feed(cleared, 120f, 0, 300, 20);
        check(feed(cleared, 10f, 400, 900, 20), "Keyed before reset");
        cleared.reset();
        check(!cleared.isKeyed() && !cleared.isArmed(), "reset drops the key and the arming");
    }

    private static void check(boolean success, String name) {
        if (!success) throw new AssertionError(name);
        System.out.println("PASS: " + name);
    }
}
