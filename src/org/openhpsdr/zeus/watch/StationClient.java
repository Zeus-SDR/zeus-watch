// SPDX-License-Identifier: LicenseRef-Proprietary
// Copyright (C) 2026 Douglas J. Cerrato (KB2UKA) and Christian Suarez (N9WAR).
package org.openhpsdr.zeus.watch;

import android.util.Log;
import java.io.IOException;
import java.security.cert.X509Certificate;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.SSLContext;
import javax.net.ssl.X509TrustManager;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

/**
 * The watch's own connection to the station: pinned HTTPS for control, the
 * station realtime socket for spectrum, audio and state edges, and a separate
 * short-lease socket that owns transmit.
 *
 * <p>Transmit never uses the global REST endpoints directly. The station's
 * watch-session socket holds a private lease whose heartbeat this client must
 * keep alive; losing the socket, or losing the microphone, drops the lease and
 * the station returns to a safe idle even when the desktop UI is still running.
 *
 * <p>Every listener callback arrives on a socket or worker thread. Callers own
 * marshalling to the UI thread.
 */
public final class StationClient {
    /** Shared logcat tag; transmit steps are traced so a failure is findable. */
    public static final String TAG = "ZeusWatch";

    /** Number of spectrum columns the watch renders; frames are reduced here. */
    public static final int DISPLAY_BINS = 120;

    /** 20 ms of 48 kHz mono, matching the station TX ingest contract. */
    public static final int MIC_FRAME_SAMPLES = WireFrames.MIC_FRAME_SAMPLES;

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final long HEARTBEAT_MS = 500L;

    public interface Result {
        /** A JSONObject or JSONArray on success, otherwise a message in error. */
        void complete(Object json, String error);
    }

    public interface Listener {
        /** A whole station state, or a synthesised patch carrying changed keys. */
        void onState(JSONObject fullOrPartial);

        void onDisplay(float[] panDb, float[] waterfallDb, long centerHz, double hzPerBin);

        void onMeter(float dbm);

        void onConnection(boolean connected, String detail);

        void onError(String message);
    }

    public interface AudioSink {
        void onRxAudio(float[] interleaved, int channels, int sampleRateHz);
    }

    private final String httpBase;
    private final String wsBase;
    private final Listener listener;
    private final OkHttpClient http;
    private final ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor(
            runnable -> {
                Thread thread = new Thread(runnable, "zeus-watch-session");
                thread.setDaemon(true);
                return thread;
            });
    private final AtomicBoolean closed = new AtomicBoolean();
    /**
     * The socket this client is deliberately dropping. Unkeying revokes the
     * lease on purpose, and that must not be reported as a fault.
     */
    private final java.util.concurrent.atomic.AtomicReference<WebSocket> releasing =
            new java.util.concurrent.atomic.AtomicReference<>();

    private volatile AudioSink audioSink;
    private volatile WebSocket stream;
    private volatile WebSocket session;
    private volatile boolean sessionReady;
    private volatile boolean transmitReady;
    private volatile boolean audioEnabled;
    private volatile boolean displayEnabled;
    private ScheduledFuture<?> heartbeat;

    public StationClient(String stationUrl, X509Certificate pin, Listener listener) throws IOException {
        this.listener = listener;
        HttpUrl parsed = HttpUrl.parse(stationUrl);
        if (parsed == null || !"https".equals(parsed.scheme())) {
            throw new IOException("The station address must be an HTTPS URL");
        }
        String authority = parsed.host() + ":" + parsed.port();
        this.httpBase = "https://" + authority;
        this.wsBase = "wss://" + authority;
        X509TrustManager trust = PinnedTls.trustManager(pin);
        SSLContext tls;
        try {
            tls = PinnedTls.context(pin, trust);
        } catch (java.security.GeneralSecurityException error) {
            throw new IOException("The embedded station certificate is unusable", error);
        }
        this.http = new OkHttpClient.Builder()
                .sslSocketFactory(tls.getSocketFactory(), trust)
                .connectTimeout(4, TimeUnit.SECONDS)
                .readTimeout(6, TimeUnit.SECONDS)
                .pingInterval(15, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();
    }

    public void setAudioSink(AudioSink sink) {
        this.audioSink = sink;
    }

    /** Opens the realtime socket and loads the current station state. */
    public void start() {
        if (closed.get()) return;
        openStream();
        refreshState();
    }

    public void refreshState() {
        get("/api/state", (json, error) -> {
            if (json instanceof JSONObject) listener.onState((JSONObject) json);
            else if (error != null) listener.onError(error);
        });
    }

    // ---- REST -------------------------------------------------------------

    public void get(String path, Result result) {
        send(new Request.Builder().url(httpBase + path).get().build(), result);
    }

    public void post(String path, JSONObject body, Result result) {
        RequestBody payload = RequestBody.create(body == null ? "{}" : body.toString(), JSON);
        send(new Request.Builder().url(httpBase + path).post(payload).build(), result);
    }

    private void send(Request request, Result result) {
        if (closed.get()) {
            if (result != null) result.complete(null, "The watch is disconnected");
            return;
        }
        http.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException error) {
                if (result != null) result.complete(null, describe(error));
            }

            @Override
            public void onResponse(Call call, Response response) {
                try (Response scoped = response) {
                    ResponseBody body = scoped.body();
                    String text = body == null ? "" : body.string();
                    if (!scoped.isSuccessful()) {
                        if (result != null) result.complete(null, "Station returned " + scoped.code());
                        return;
                    }
                    if (result == null) return;
                    Object parsed = text.isEmpty() ? new JSONObject() : new JSONTokener(text).nextValue();
                    if (parsed instanceof JSONObject || parsed instanceof JSONArray) {
                        result.complete(parsed, null);
                    } else {
                        result.complete(null, "Station sent an unexpected reply");
                    }
                } catch (IOException | JSONException error) {
                    if (result != null) result.complete(null, describe(error));
                }
            }
        });
    }

    private static String describe(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isEmpty() ? error.getClass().getSimpleName() : message;
    }

    // ---- Realtime stream --------------------------------------------------

    private void openStream() {
        Request request = new Request.Builder().url(wsBase + "/ws").build();
        stream = http.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket socket, Response response) {
                listener.onConnection(true, "Connected");
                pushStreamDemand(socket);
            }

            @Override
            public void onMessage(WebSocket socket, ByteString bytes) {
                try {
                    decode(bytes);
                } catch (RuntimeException error) {
                    // One malformed frame must not take the socket down.
                    listener.onError("Dropped a malformed station frame");
                }
            }

            @Override
            public void onFailure(WebSocket socket, Throwable error, Response response) {
                if (closed.get()) return;
                listener.onConnection(false, describe(error));
                scheduleReconnect();
            }

            @Override
            public void onClosed(WebSocket socket, int code, String reason) {
                if (closed.get()) return;
                listener.onConnection(false, "Station closed the connection");
                scheduleReconnect();
            }
        });
    }

    private void scheduleReconnect() {
        if (closed.get()) return;
        try {
            timers.schedule(() -> {
                if (closed.get()) return;
                openStream();
                refreshState();
            }, 1500, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.RejectedExecutionException shuttingDown) {
            // close() won the race.
        }
    }

    private void decode(ByteString bytes) {
        int length = bytes.size();
        if (length < 1) return;
        byte type = bytes.getByte(0);
        if (type == WireFrames.MSG_DISPLAY) {
            WireFrames.Display frame = WireFrames.decodeDisplay(bytes.toByteArray(), DISPLAY_BINS);
            // Reduced on the socket thread: the watch draws 120 columns, and
            // handing a full-width frame to the UI thread 30 times a second is
            // what made the browser client feel heavy on this hardware.
            if (frame != null) {
                listener.onDisplay(frame.panDb, frame.waterfallDb, frame.centerHz, frame.hzPerBin);
            }
            return;
        }
        if (type == WireFrames.MSG_AUDIO) {
            AudioSink sink = audioSink;
            if (sink == null) return;
            WireFrames.Audio audio = WireFrames.decodeAudio(bytes.toByteArray());
            if (audio != null) sink.onRxAudio(audio.interleaved, audio.channels, audio.sampleRateHz);
            return;
        }
        if (type == WireFrames.MSG_RX_METER) {
            float dbm = WireFrames.decodeRxMeter(bytes.toByteArray());
            if (!Float.isNaN(dbm)) listener.onMeter(dbm);
            return;
        }
        if (type == WireFrames.MSG_VFO_STATE) {
            long hz = WireFrames.decodeVfoHz(bytes.toByteArray());
            if (hz > 0) listener.onState(patch("vfoHz", hz));
            return;
        }
        if (type == WireFrames.MSG_MOX_STATE) {
            WireFrames.Mox mox = WireFrames.decodeMox(bytes.toByteArray());
            if (mox == null) return;
            JSONObject state = new JSONObject();
            try {
                state.put("moxOn", mox.moxOn);
                state.put("tunOn", mox.tunOn);
            } catch (JSONException impossible) {
                return;
            }
            listener.onState(state);
            return;
        }
        if (type == WireFrames.MSG_TX_METERS) {
            float[] meters = WireFrames.decodeTxMeters(bytes.toByteArray());
            if (meters == null) return;
            JSONObject state = new JSONObject();
            try {
                state.put("fwdWatts", meters[0]);
                state.put("swr", meters[1]);
            } catch (JSONException impossible) {
                return;
            }
            listener.onState(state);
        }
    }

    private static JSONObject patch(String key, Object value) {
        JSONObject json = new JSONObject();
        try {
            json.put(key, value);
        } catch (JSONException impossible) {
            return new JSONObject();
        }
        return json;
    }

    private void pushStreamDemand(WebSocket socket) {
        socket.send(ByteString.of(WireFrames.streamRequest(WireFrames.MSG_AUDIO_REQUEST, audioEnabled ? 1 : 0)));
        // Level 2 is the station's visible-surface tier; level 0 stops spectrum
        // generation entirely while the watch screen is off.
        socket.send(ByteString.of(WireFrames.streamRequest(WireFrames.MSG_DISPLAY_REQUEST, displayEnabled ? 2 : 0)));
    }

    public void setAudioEnabled(boolean enabled) {
        audioEnabled = enabled;
        WebSocket socket = stream;
        if (socket != null) socket.send(ByteString.of(WireFrames.streamRequest(WireFrames.MSG_AUDIO_REQUEST, enabled ? 1 : 0)));
    }

    public void setDisplayEnabled(boolean enabled) {
        displayEnabled = enabled;
        WebSocket socket = stream;
        if (socket != null) socket.send(ByteString.of(WireFrames.streamRequest(WireFrames.MSG_DISPLAY_REQUEST, enabled ? 2 : 0)));
    }

    /** Sends one 20 ms microphone block to the station TX ingest. */
    public boolean sendMic(float[] samples) {
        WebSocket socket = stream;
        byte[] frame = WireFrames.micFrame(samples);
        if (socket == null || frame == null) return false;
        return socket.send(ByteString.of(frame));
    }

    // ---- Transmit lease ---------------------------------------------------

    /**
     * Declares whether microphone capture is live. Transmit requests are refused
     * while this is false, and clearing it drops the station lease.
     */
    public void setTransmitReady(boolean ready) {
        transmitReady = ready;
        if (!ready) closeSession();
    }

    /** Ends transmit authority immediately; the station returns to safe idle. */
    public void failTransmit(String reason) {
        transmitReady = false;
        closeSession();
        listener.onError(reason);
    }

    public void setPtt(boolean on) {
        if (on && !transmitReady) {
            Log.w(TAG, "ptt refused: microphone not ready");
            listener.onError("The microphone is not ready");
            return;
        }
        command("ptt", on);
    }

    public void setTune(boolean on) {
        command("tune", on);
    }

    public void setTwoTone(boolean on) {
        command("twoTone", on);
    }

    /** Drops any live key without waiting for a reply. */
    public void releasePtt() {
        WebSocket socket = session;
        if (socket == null) return;
        sendCommand(socket, "ptt", false);
        sendCommand(socket, "tune", false);
        sendCommand(socket, "twoTone", false);
    }

    private void command(String type, boolean on) {
        if (closed.get()) {
            Log.w(TAG, "command " + type + " ignored: client closed");
            return;
        }
        WebSocket socket = session;
        Log.i(TAG, "command " + type + " on=" + on + " session=" + (socket != null)
                + " ready=" + sessionReady + " txReady=" + transmitReady);
        if (!on) {
            if (socket != null) sendCommand(socket, type, false);
            return;
        }
        if (socket != null && sessionReady) {
            sendCommand(socket, type, true);
            return;
        }
        openSession(type);
    }

    private void sendCommand(WebSocket socket, String type, boolean on) {
        try {
            boolean queued = socket.send(new JSONObject().put("type", type).put("on", on).toString());
            Log.i(TAG, "sent " + type + " on=" + on + " queued=" + queued);
        } catch (JSONException impossible) {
            // Both values are literals; this cannot fail.
        }
    }

    private synchronized void openSession(final String pendingCommand) {
        if (closed.get() || session != null) return;
        Log.i(TAG, "opening transmit lease socket, pending=" + pendingCommand);
        Request request = new Request.Builder().url(wsBase + "/product/watch/session").build();
        session = http.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket socket, Response response) {
                Log.i(TAG, "lease socket open http=" + response.code());
            }

            @Override
            public void onMessage(WebSocket socket, String text) {
                Log.i(TAG, "lease socket says " + text);
                String type;
                try {
                    type = new JSONObject(text).optString("type", "");
                } catch (JSONException malformed) {
                    Log.w(TAG, "lease socket sent malformed json");
                    return;
                }
                if ("ready".equals(type)) {
                    sessionReady = true;
                    startHeartbeat(socket);
                    if (pendingCommand == null) return;
                    if (transmitReady || !"ptt".equals(pendingCommand)) {
                        sendCommand(socket, pendingCommand, true);
                    } else {
                        listener.onError("The microphone is not ready");
                    }
                    return;
                }
                if ("error".equals(type)) {
                    listener.onError("The station refused transmit control");
                    closeSession();
                }
            }

            @Override
            public void onFailure(WebSocket socket, Throwable error, Response response) {
                boolean deliberate = releasing.compareAndSet(socket, null);
                Log.i(TAG, "lease socket ended deliberate=" + deliberate
                        + (response == null ? "" : " http=" + response.code()));
                endSession(socket);
                if (!deliberate && !closed.get()) listener.onError("Transmit control disconnected");
            }

            @Override
            public void onClosed(WebSocket socket, int code, String reason) {
                Log.i(TAG, "lease socket closed code=" + code + " reason=" + reason);
                releasing.compareAndSet(socket, null);
                endSession(socket);
            }
        });
    }

    private synchronized void startHeartbeat(final WebSocket socket) {
        stopHeartbeat();
        try {
            heartbeat = timers.scheduleAtFixedRate(() -> {
                // A heartbeat must never outlive live microphone capture; the
                // station would otherwise hold the key open on a dead audio path.
                if (closed.get() || !transmitReady) {
                    closeSession();
                    return;
                }
                socket.send("{\"type\":\"heartbeat\"}");
            }, 0, HEARTBEAT_MS, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.RejectedExecutionException shuttingDown) {
            socket.cancel();
        }
    }

    private synchronized void stopHeartbeat() {
        if (heartbeat != null) {
            heartbeat.cancel(false);
            heartbeat = null;
        }
    }

    private synchronized void endSession(WebSocket socket) {
        if (session != socket) return;
        session = null;
        sessionReady = false;
        stopHeartbeat();
    }

    /**
     * Drops the lease socket. The station revokes the lease and drives itself to
     * a safe idle, so this is also the correct response to a local failure.
     */
    public void closeSession() {
        WebSocket socket;
        synchronized (this) {
            socket = session;
            session = null;
            sessionReady = false;
            stopHeartbeat();
        }
        if (socket == null) return;
        // Dropping the socket is how the station is told to stand down, so the
        // teardown it triggers is expected, not a failure to report.
        releasing.set(socket);
        socket.cancel();
    }

    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        transmitReady = false;
        releasePtt();
        closeSession();
        WebSocket socket = stream;
        stream = null;
        if (socket != null) socket.cancel();
        timers.shutdownNow();
        http.dispatcher().executorService().shutdown();
        http.connectionPool().evictAll();
    }
}
