// SPDX-License-Identifier: LicenseRef-Proprietary
// Copyright (C) 2026 Douglas J. Cerrato (KB2UKA) and Christian Suarez (N9WAR).
package org.openhpsdr.zeus.watch;

import android.media.AudioAttributes;
import android.util.Log;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Native receive playout and microphone uplink. Both directions run on their
 * own thread behind a bounded queue: a watch that cannot keep up drops the
 * oldest audio instead of growing latency without limit.
 *
 * <p>Capture failure is a transmit-safety event, not a cosmetic one. The
 * failure callback fires from the capture thread so the caller can drop the
 * station transmit lease immediately.
 */
public final class StationAudio implements StationClient.AudioSink {
    /** The station TX ingest contract: 20 ms blocks of 48 kHz mono. */
    private static final int MIC_SAMPLE_RATE = 48000;

    /** Roughly 300 ms of receive audio; beyond that, newer audio wins. */
    private static final int PLAYOUT_QUEUE_BLOCKS = 24;

    public interface Failure {
        void onCaptureFailed(String reason);
    }

    private final Failure failure;
    private final ArrayBlockingQueue<float[]> playout = new ArrayBlockingQueue<>(PLAYOUT_QUEUE_BLOCKS);

    private volatile boolean playoutWanted;
    private volatile boolean playing;
    private volatile boolean capturing;
    private volatile int playoutRate;
    private volatile float micPeak;
    private AudioTrack track;
    private Thread playoutThread;
    private Thread captureThread;

    public StationAudio(Failure failure) {
        this.failure = failure;
    }

    // ---- Receive ----------------------------------------------------------

    /**
     * Arms speaker playout. The station picks the sample rate, so the track is
     * opened when the first frame arrives rather than guessed at up front.
     */
    public void setPlayoutEnabled(boolean enabled) {
        playoutWanted = enabled;
        if (!enabled) stopPlayout();
    }

    public boolean isPlayoutEnabled() {
        return playoutWanted;
    }

    /** Starts speaker playout. Returns false when the watch refuses the route. */
    public synchronized boolean startPlayout(int sampleRateHz) {
        if (playing && playoutRate == sampleRateHz) return true;
        stopPlayout();
        int minimum = AudioTrack.getMinBufferSize(sampleRateHz,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT);
        if (minimum <= 0) return false;
        // Two device buffers: enough to ride a scheduling hiccup without adding
        // audible delay to a live QSO.
        int bytes = Math.max(minimum * 2, sampleRateHz / 5 * 4);
        try {
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                            .setSampleRate(sampleRateHz)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setBufferSizeInBytes(bytes)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
            track.play();
        } catch (IllegalArgumentException | IllegalStateException | UnsupportedOperationException error) {
            track = null;
            return false;
        }
        playoutRate = sampleRateHz;
        playing = true;
        playout.clear();
        playoutThread = new Thread(this::drainPlayout, "zeus-watch-playout");
        playoutThread.setDaemon(true);
        playoutThread.start();
        return true;
    }

    public synchronized void stopPlayout() {
        playing = false;
        Thread worker = playoutThread;
        playoutThread = null;
        if (worker != null) worker.interrupt();
        AudioTrack current = track;
        track = null;
        if (current != null) {
            try {
                current.pause();
                current.flush();
                current.stop();
            } catch (IllegalStateException stopping) {
                // The track is already down; releasing is all that is left.
            }
            current.release();
        }
        playout.clear();
    }

    private void drainPlayout() {
        while (playing) {
            float[] block;
            try {
                block = playout.poll(200, TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
            AudioTrack current = track;
            if (block == null || current == null) continue;
            try {
                current.write(block, 0, block.length, AudioTrack.WRITE_BLOCKING);
            } catch (IllegalStateException stopping) {
                return;
            }
        }
    }

    @Override
    public void onRxAudio(float[] interleaved, int channels, int sampleRateHz) {
        if (!playoutWanted) return;
        if (!playing || sampleRateHz != playoutRate) {
            // First frame, or the station changed rate mid-session.
            if (!startPlayout(sampleRateHz)) return;
        }
        float[] mono = channels == 1 ? interleaved : downmix(interleaved, channels);
        // Newest audio wins: a watch that stalls must not accumulate delay.
        while (!playout.offer(mono)) {
            if (playout.poll() == null) return;
        }
    }

    private static float[] downmix(float[] interleaved, int channels) {
        int frames = interleaved.length / channels;
        float[] mono = new float[frames];
        for (int frame = 0; frame < frames; frame++) {
            float sum = 0f;
            for (int channel = 0; channel < channels; channel++) sum += interleaved[frame * channels + channel];
            mono[frame] = sum / channels;
        }
        return mono;
    }

    // ---- Transmit ---------------------------------------------------------

    /**
     * Opens the microphone and streams 20 ms blocks through {@code sink}.
     * Returns only once the recorder is confirmed running, so the caller may
     * treat a true result as permission to key the station.
     */
    public synchronized boolean startCapture(StationClient sink) {
        if (capturing) return true;
        int minimum = AudioRecord.getMinBufferSize(MIC_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT);
        if (minimum <= 0) {
            Log.w(StationClient.TAG, "capture: no float buffer size, code=" + minimum);
            return false;
        }
        AudioRecord recorder;
        try {
            recorder = new AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                            .setSampleRate(MIC_SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build())
                    .setBufferSizeInBytes(Math.max(minimum * 2, StationClient.MIC_FRAME_SAMPLES * 4 * 4))
                    .build();
        } catch (IllegalArgumentException | UnsupportedOperationException error) {
            Log.w(StationClient.TAG, "capture: recorder rejected the format", error);
            return false;
        } catch (SecurityException denied) {
            Log.w(StationClient.TAG, "capture: denied", denied);
            return false;
        }
        if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
            Log.w(StationClient.TAG, "capture: recorder did not initialise");
            recorder.release();
            return false;
        }
        try {
            recorder.startRecording();
        } catch (IllegalStateException | SecurityException error) {
            Log.w(StationClient.TAG, "capture: startRecording threw", error);
            recorder.release();
            return false;
        }
        if (recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
            Log.w(StationClient.TAG, "capture: recorder never reached RECORDING");
            recorder.release();
            return false;
        }
        Log.i(StationClient.TAG, "capture: microphone live");
        capturing = true;
        captureThread = new Thread(() -> pump(recorder, sink), "zeus-watch-mic");
        captureThread.setDaemon(true);
        captureThread.start();
        return true;
    }

    private void pump(AudioRecord recorder, StationClient sink) {
        float[] block = new float[StationClient.MIC_FRAME_SAMPLES];
        try {
            while (capturing) {
                int filled = 0;
                while (filled < block.length && capturing) {
                    int read = recorder.read(block, filled, block.length - filled, AudioRecord.READ_BLOCKING);
                    if (read <= 0) {
                        fail("The watch microphone stopped");
                        return;
                    }
                    filled += read;
                }
                if (!capturing) return;
                float peak = 0f;
                for (float sample : block) {
                    float magnitude = Math.abs(sample);
                    if (magnitude > peak) peak = magnitude;
                }
                micPeak = peak;
                if (!sink.sendMic(block)) {
                    fail("The station link dropped while transmitting");
                    return;
                }
            }
        } catch (IllegalStateException | SecurityException error) {
            fail("The watch microphone failed");
        } finally {
            try {
                recorder.stop();
            } catch (IllegalStateException stopping) {
                // Already stopped; release below is what matters.
            }
            recorder.release();
            micPeak = 0f;
        }
    }

    private void fail(String reason) {
        capturing = false;
        failure.onCaptureFailed(reason);
    }

    public synchronized void stopCapture() {
        capturing = false;
        Thread worker = captureThread;
        captureThread = null;
        if (worker == null) return;
        worker.interrupt();
        try {
            worker.join(500);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    public boolean isCapturing() {
        return capturing;
    }

    /** Most recent microphone peak, 0..1, for the transmit level indicator. */
    public float micPeak() {
        return micPeak;
    }

    public void close() {
        stopCapture();
        stopPlayout();
    }

    /** Routes playout to the watch speaker rather than a paired headset. */
    public static void preferSpeaker(AudioManager manager) {
        if (manager == null) return;
        try {
            manager.setMode(AudioManager.MODE_NORMAL);
        } catch (SecurityException denied) {
            // Some watch builds reserve the audio mode; playout still works.
        }
    }
}
