// SPDX-License-Identifier: LicenseRef-Proprietary
// Copyright (C) 2026 Douglas J. Cerrato (KB2UKA) and Christian Suarez (N9WAR).
package org.openhpsdr.zeus.watch;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Browser;
import android.util.Log;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/**
 * Runs the native watch interface against the operator's own station over the
 * LAN. The hosted web remote stays the fallback for the case this app cannot
 * help with: the station is not on this network.
 */
public final class MainActivity extends Activity implements StationClient.Listener,
        WatchView.Listener, StationAudio.Failure, SensorEventListener {
    private static final int MICROPHONE_REQUEST = 1;

    /**
     * The SPE Expert / Taurus reading the web client uses. Polled only while
     * transmitting: the watch has no reason to wake the link otherwise.
     */
    private static final String AMPLIFIER_STATUS = "/api/amp/spe-taurus/status";
    private static final long AMPLIFIER_POLL_MS = 500L;

    private final LanProbe probe = new LanProbe();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private WatchView view;
    private StationClient client;
    private StationAudio audio;
    private Uri fallbackUrl;
    private Uri stationUrl;
    private X509Certificate stationCertificate;
    private boolean destroyed;
    private boolean browserLaunched;

    private final TuningModel tuning = new TuningModel(this::sendTune);
    private boolean pttWanted;
    private boolean transmitting;
    // A sleeping watch is frozen by Android, which stops the spectrum and the
    // transmit heartbeat alike. Staying lit is the useful default here.
    private boolean keepAwakeRequested = true;
    private final Runnable amplifierPoll = this::pollAmplifier;
    private final WristFlip wrist = new WristFlip();
    private SensorManager sensors;
    private Sensor orientationSensor;
    private boolean wristEnabled;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        try {
            fallbackUrl = readTarget("station-url.txt");
        } catch (IOException | IllegalArgumentException error) {
            showMessage("Rebuild the Zeus watch app with the station's HTTPS address.");
            return;
        }
        try {
            stationUrl = readTarget("lan-url.txt");
            stationCertificate = readCertificate();
        } catch (IOException | IllegalArgumentException | java.security.cert.CertificateException missing) {
            // A build without a LAN station can only offer the hosted remote.
            openBrowser(fallbackUrl);
            return;
        }
        WatchView.Theme theme;
        try {
            theme = ThemeLoader.load(this);
        } catch (IOException error) {
            showMessage("The Zeus palette asset is missing; rebuild the watch app.");
            return;
        }
        audio = new StationAudio(this);
        view = new WatchView(this, this, theme);
        setContentView(view);
        StationAudio.preferSpeaker((AudioManager) getSystemService(Context.AUDIO_SERVICE));
        applyScreenPolicy();
        connect();
    }

    // ---- Setup ------------------------------------------------------------

    private Uri readTarget(String asset) throws IOException {
        try (BufferedReader input = new BufferedReader(new InputStreamReader(
                getAssets().open(asset), StandardCharsets.UTF_8))) {
            String address = input.readLine();
            Uri target = address == null ? Uri.EMPTY : Uri.parse(address);
            if (!"https".equals(target.getScheme()) || target.getHost() == null
                    || target.getUserInfo() != null || !"1".equals(target.getQueryParameter("watch"))
                    || target.getQueryParameter("desktop") != null
                    || ("lan-url.txt".equals(asset) && target.getQueryParameter("remote") != null)) {
                throw new IllegalArgumentException("Invalid station address");
            }
            return target;
        }
    }

    private X509Certificate readCertificate() throws IOException, java.security.cert.CertificateException {
        try (InputStream input = getAssets().open("lan-certificate.der")) {
            return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(input);
        }
    }

    private void connect() {
        if (destroyed || client != null) return;
        view.setConnection(false, "Finding the station");
        worker.execute(() -> {
            boolean reachable = probe.reachable(stationUrl.toString(), stationCertificate);
            handler.post(() -> {
                if (destroyed) return;
                if (!reachable) {
                    // Off the station's network: the hosted remote is the only
                    // thing that can still reach the radio.
                    view.setConnection(false, "Station not on this network");
                    openBrowser(fallbackUrl);
                    return;
                }
                startClient();
            });
        });
    }

    private void startClient() {
        if (destroyed || client == null && stationUrl == null) return;
        try {
            client = new StationClient(stationUrl.toString(), stationCertificate, this);
        } catch (IOException error) {
            view.setConnection(false, "The station address is unusable");
            return;
        }
        client.setAudioSink(audio);
        audio.setPlayoutEnabled(true);
        client.setAudioEnabled(true);
        client.setDisplayEnabled(false);
        client.start();
        client.get("/api/toolbar-settings", (json, error) -> handler.post(() -> {
            if (!destroyed && json != null) view.setResponse("/api/toolbar-settings", json);
        }));
    }

    // ---- StationClient.Listener (socket threads) --------------------------

    @Override
    public void onState(JSONObject fullOrPartial) {
        handler.post(() -> {
            if (destroyed) return;
            JSONObject state = fullOrPartial;
            if (state.has("vfoHz")) {
                // A station echo must not drag the dial back over a tune the
                // operator has already made and can see.
                long hz = state.optLong("vfoHz", 0L);
                if (!tuning.acceptStationHz(hz, android.os.SystemClock.elapsedRealtime())) {
                    state = withoutVfo(state);
                }
            }
            view.setState(state);
        });
    }

    private static JSONObject withoutVfo(JSONObject state) {
        JSONObject copy = new JSONObject();
        java.util.Iterator<String> keys = state.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if ("vfoHz".equals(key)) continue;
            try {
                copy.put(key, state.get(key));
            } catch (org.json.JSONException skip) {
                // A key we cannot copy is a key we can safely drop.
            }
        }
        return copy;
    }

    @Override
    public void onDisplay(float[] panDb, float[] waterfallDb, long centerHz, double hzPerBin) {
        handler.post(() -> {
            if (!destroyed) view.setDisplay(panDb, waterfallDb, centerHz, hzPerBin);
        });
    }

    @Override
    public void onMeter(float dbm) {
        handler.post(() -> {
            if (!destroyed) view.setMeter(dbm);
        });
    }

    @Override
    public void onConnection(boolean connected, String detail) {
        handler.post(() -> {
            if (!destroyed) view.setConnection(connected, detail);
        });
    }

    @Override
    public void onError(String message) {
        Log.w(StationClient.TAG, "error: " + message);
        handler.post(() -> {
            if (!destroyed) view.setError(message);
        });
    }

    // ---- WatchView.Listener (UI thread) -----------------------------------

    @Override
    public void onTuneSteps(int steps) {
        if (client == null) return;
        tuning.step(steps, Math.max(1, view.stepHz()), android.os.SystemClock.elapsedRealtime());
        view.setVfoOptimistic(tuning.displayedHz());
    }

    @Override
    public void onTuneTo(long hz) {
        if (client == null || hz <= 0) return;
        tuning.request(hz, android.os.SystemClock.elapsedRealtime());
        view.setVfoOptimistic(tuning.displayedHz());
    }

    private void sendTune(long hz) {
        JSONObject body = new JSONObject();
        try {
            body.put("hz", hz);
        } catch (org.json.JSONException impossible) {
            return;
        }
        client.post("/api/vfo", body, (json, error) -> handler.post(() -> {
            if (destroyed) return;
            if (error != null) view.setError(error);
            tuning.completed(hz, android.os.SystemClock.elapsedRealtime());
        }));
    }

    @Override
    public void onAction(String path, JSONObject body) {
        if (client == null) return;
        client.post(path, body, (json, error) -> handler.post(() -> {
            if (destroyed) return;
            if (error != null) {
                view.setError(error);
                return;
            }
            view.setResponse(path, json);
        }));
    }

    @Override
    public void onPtt(boolean on) {
        Log.i(StationClient.TAG, "onPtt " + on + " client=" + (client != null));
        if (client == null) return;
        if (!on) {
            pttWanted = false;
            client.setPtt(false);
            client.setTransmitReady(false);
            audio.stopCapture();
            transmitting = false;
            applyScreenPolicy();
        startAmplifierPolling(transmitting);
            return;
        }
        // Hold the screen before keying. A sleeping watch freezes this process,
        // which stops the lease heartbeat and stops the activity -- either one
        // drops transmit, and mid-over is the worst moment to discover that.
        transmitting = true;
        applyScreenPolicy();
        startAmplifierPolling(transmitting);
        pttWanted = true;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            // Ask, but never key on the grant itself — the operator has let go
            // of the button by the time the dialog is answered.
            pttWanted = false;
            transmitting = false;
            applyScreenPolicy();
        startAmplifierPolling(transmitting);
            requestPermissions(new String[] { Manifest.permission.RECORD_AUDIO }, MICROPHONE_REQUEST);
            view.setError("Allow the microphone, then press again");
            return;
        }
        if (!audio.startCapture(client)) {
            pttWanted = false;
            transmitting = false;
            applyScreenPolicy();
        startAmplifierPolling(transmitting);
            Log.w(StationClient.TAG, "onPtt aborted: microphone unavailable");
            view.setError("The watch microphone is unavailable");
            return;
        }
        client.setTransmitReady(true);
        client.setPtt(true);
    }

    @Override
    public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        if (request != MICROPHONE_REQUEST) return;
        boolean granted = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
        view.setError(granted ? "Microphone ready" : "Zeus cannot transmit without the microphone");
    }

    @Override
    public void onStationTune(boolean on) {
        if (client == null) return;
        transmitting = on;
        applyScreenPolicy();
        startAmplifierPolling(transmitting);
        client.setTune(on);
    }

    @Override
    public void onTwoTone(boolean on) {
        if (client == null) return;
        transmitting = on;
        applyScreenPolicy();
        startAmplifierPolling(transmitting);
        client.setTwoTone(on);
    }

    @Override
    public void onAudio(boolean on) {
        audio.setPlayoutEnabled(on);
        if (client != null) client.setAudioEnabled(on);
    }

    @Override
    public void onDisplayEnabled(boolean on) {
        if (client != null) client.setDisplayEnabled(on);
    }

    /**
     * Arms or disarms keying by wrist. Returns what the switch should now read:
     * false when this watch has no orientation sensor to key from.
     */
    @Override
    public boolean onWristPtt(boolean on) {
        if (!on) {
            stopWrist();
            return false;
        }
        if (sensors == null) sensors = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        if (orientationSensor == null && sensors != null) {
            orientationSensor = sensors.getDefaultSensor(Sensor.TYPE_GRAVITY);
            // Not every watch fuses a gravity vector; raw acceleration carries
            // the same direction at rest, which is all this needs.
            if (orientationSensor == null) {
                orientationSensor = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            }
        }
        if (sensors == null || orientationSensor == null) return false;
        wrist.reset();
        wristEnabled = sensors.registerListener(this, orientationSensor,
                SensorManager.SENSOR_DELAY_GAME);
        if (!wristEnabled) return false;
        Log.i(StationClient.TAG, "wrist ptt armed on " + orientationSensor.getName());
        return true;
    }

    private void stopWrist() {
        if (!wristEnabled) return;
        wristEnabled = false;
        if (sensors != null) sensors.unregisterListener(this);
        // Never leave the radio keyed by a gesture that is no longer watched.
        if (wrist.isKeyed()) onPtt(false);
        wrist.reset();
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (!wristEnabled || destroyed || event.values.length < 3) return;
        boolean before = wrist.isKeyed();
        boolean after = wrist.update(event.values[0], event.values[1], event.values[2],
                android.os.SystemClock.elapsedRealtime());
        if (after != before) {
            Log.i(StationClient.TAG, "wrist ptt " + after);
            onPtt(after);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // Direction is all this uses; calibration accuracy does not affect it.
    }

    @Override
    public void onKeepAwake(boolean on) {
        keepAwakeRequested = on;
        applyScreenPolicy();
        startAmplifierPolling(transmitting);
    }

    /**
     * Reads the amplifier's own forward power. The rig's reading is what the
     * radio produced; this is what actually reached the antenna, so the two are
     * shown together while transmitting.
     */
    private void pollAmplifier() {
        if (destroyed || !transmitting || client == null || view == null) return;
        client.get(AMPLIFIER_STATUS, (json, error) -> handler.post(() -> {
            if (destroyed) return;
            view.setAmpWatts(error == null ? amplifierWatts(json) : null);
            if (transmitting) handler.postDelayed(amplifierPoll, AMPLIFIER_POLL_MS);
        }));
    }

    private static Double amplifierWatts(Object json) {
        if (!(json instanceof JSONObject)) return null;
        JSONObject status = (JSONObject) json;
        JSONObject amplifier = status.optJSONObject("amplifier");
        // The link has to be up and the amplifier out of standby, or the number
        // is a stale reading of something that is no longer amplifying.
        if (amplifier == null || !status.optBoolean("connected", false)
                || !amplifier.optBoolean("operate", false)) {
            return null;
        }
        double watts = amplifier.optDouble("outputPowerWatts", Double.NaN);
        return Double.isNaN(watts) || watts < 0 ? null : watts;
    }

    private void startAmplifierPolling(boolean on) {
        handler.removeCallbacks(amplifierPoll);
        // A build that fell back to the browser has no view to update.
        if (view == null) return;
        if (on) handler.post(amplifierPoll);
        else view.setAmpWatts(null);
    }

    /** The screen stays lit while transmitting, or while the operator asked. */
    private void applyScreenPolicy() {
        if (transmitting || keepAwakeRequested) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    // ---- StationAudio.Failure (capture thread) ----------------------------

    @Override
    public void onCaptureFailed(String reason) {
        // Losing the microphone must drop the station lease, not just the audio.
        StationClient current = client;
        if (current != null) current.failTransmit(reason);
        handler.post(() -> {
            if (destroyed) return;
            pttWanted = false;
            transmitting = false;
            applyScreenPolicy();
        startAmplifierPolling(transmitting);
            view.releasePtt();
            view.setError(reason);
        });
    }

    // ---- Lifecycle --------------------------------------------------------

    @Override
    protected void onStop() {
        super.onStop();
        // A gesture cannot be watched from the background, so it must not be
        // left armed there either.
        stopWrist();
        releaseTransmit();
        if (client != null) client.setDisplayEnabled(false);
        audio.setPlayoutEnabled(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (destroyed || client == null) return;
        // Reconnect the receive path only; transmit stays where the operator
        // left it, which is off.
        audio.setPlayoutEnabled(true);
        client.setAudioEnabled(true);
        // onStop stopped the spectrum; without this it never came back and the
        // bandscope stayed frozen until the operator changed pages.
        view.reapplyDisplayDemand();
        client.refreshState();
    }

    private void releaseTransmit() {
        pttWanted = false;
        wrist.reset();
        transmitting = false;
        applyScreenPolicy();
        startAmplifierPolling(transmitting);
        if (client != null) {
            client.releasePtt();
            client.setTransmitReady(false);
        }
        if (audio != null) audio.stopCapture();
        if (view != null) view.releasePtt();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        releaseTransmit();
        handler.removeCallbacksAndMessages(null);
        probe.cancel();
        if (client != null) client.close();
        if (audio != null) audio.close();
        worker.shutdownNow();
        super.onDestroy();
    }

    // ---- Browser fallback -------------------------------------------------

    private void openBrowser(Uri target) {
        if (destroyed || browserLaunched || isFinishing() || target == null) return;
        browserLaunched = true;
        try {
            Intent browser = new Intent(Intent.ACTION_VIEW, target);
            browser.addCategory(Intent.CATEGORY_BROWSABLE);
            browser.putExtra(Browser.EXTRA_APPLICATION_ID, getPackageName());
            startActivity(browser);
            finish();
        } catch (ActivityNotFoundException error) {
            showMessage("Install a web browser on this watch, then open Zeus again.");
        } catch (SecurityException error) {
            showMessage("Zeus could not open the browser. Check the watch browser and rebuild if needed.");
        }
    }

    private void showMessage(String text) {
        TextView message = new TextView(this);
        message.setText(text);
        message.setTextSize(14);
        message.setGravity(Gravity.CENTER);
        int inset = Math.round(Math.min(getResources().getDisplayMetrics().widthPixels,
                getResources().getDisplayMetrics().heightPixels) * 0.18f);
        FrameLayout root = new FrameLayout(this);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(message);
        FrameLayout.LayoutParams bounds = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        bounds.setMargins(inset, inset, inset, inset);
        root.addView(scroll, bounds);
        setContentView(root);
    }
}
