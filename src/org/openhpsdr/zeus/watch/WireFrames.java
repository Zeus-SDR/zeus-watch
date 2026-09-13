// SPDX-License-Identifier: LicenseRef-Proprietary
// Copyright (C) 2026 Douglas J. Cerrato (KB2UKA) and Christian Suarez (N9WAR).
package org.openhpsdr.zeus.watch;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * The station realtime wire format, with no Android or network dependency so it
 * can be exercised on a plain JVM.
 *
 * <p>Layouts mirror Zeus.Contracts and zeus-web/src/realtime/frame.ts. Every
 * decoder returns null (or a sentinel) for a frame it cannot fully account for:
 * a short or malformed frame is dropped, never partially believed.
 */
public final class WireFrames {
    public static final byte MSG_DISPLAY = 0x01;
    public static final byte MSG_AUDIO = 0x02;
    public static final byte MSG_RX_METER = 0x14;
    public static final byte MSG_TX_METERS = 0x16;
    public static final byte MSG_MOX_STATE = 0x1c;
    public static final byte MSG_MIC_PCM = 0x20;
    public static final byte MSG_AUDIO_REQUEST = 0x21;
    public static final byte MSG_DISPLAY_REQUEST = 0x22;
    public static final byte MSG_VFO_STATE = 0x26;

    /** 20 ms of 48 kHz mono, matching the station TX ingest contract. */
    public static final int MIC_FRAME_SAMPLES = 960;

    private static final int HEADER_BYTES = 16;
    private static final int DISPLAY_BODY_BYTES = 16;
    private static final int AUDIO_BODY_BYTES = 8;
    private static final int DISPLAY_PAN_VALID = 0x01;
    private static final int DISPLAY_WATERFALL_VALID = 0x02;

    private WireFrames() { }

    public static final class Display {
        public final float[] panDb;
        public final float[] waterfallDb;
        public final long centerHz;
        public final double hzPerBin;

        Display(float[] panDb, float[] waterfallDb, long centerHz, double hzPerBin) {
            this.panDb = panDb;
            this.waterfallDb = waterfallDb;
            this.centerHz = centerHz;
            this.hzPerBin = hzPerBin;
        }
    }

    public static final class Audio {
        public final float[] interleaved;
        public final int channels;
        public final int sampleRateHz;

        Audio(float[] interleaved, int channels, int sampleRateHz) {
            this.interleaved = interleaved;
            this.channels = channels;
            this.sampleRateHz = sampleRateHz;
        }
    }

    public static final class Mox {
        public final boolean moxOn;
        public final boolean tunOn;

        Mox(boolean moxOn, boolean tunOn) {
            this.moxOn = moxOn;
            this.tunOn = tunOn;
        }
    }

    public static ByteBuffer wrap(byte[] raw) {
        return ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
    }

    public static byte[] micFrame(float[] samples) {
        if (samples == null || samples.length != MIC_FRAME_SAMPLES) return null;
        ByteBuffer frame = ByteBuffer.allocate(1 + MIC_FRAME_SAMPLES * 4).order(ByteOrder.LITTLE_ENDIAN);
        frame.put(MSG_MIC_PCM);
        for (float sample : samples) frame.putFloat(sample);
        return frame.array();
    }

    public static byte[] streamRequest(byte type, int level) {
        return new byte[] { type, (byte) level };
    }

    /**
     * Decodes a display frame and reduces it to {@code bins} columns by taking
     * the peak of each bucket, so a narrow watch screen never hides a signal
     * that a wider one would show.
     */
    public static Display decodeDisplay(byte[] raw, int bins) {
        if (raw == null || bins <= 0 || raw.length < HEADER_BYTES + DISPLAY_BODY_BYTES) return null;
        if (raw[0] != MSG_DISPLAY) return null;
        ByteBuffer view = wrap(raw);
        int bodyFlags = view.get(HEADER_BYTES + 1) & 0xff;
        int width = view.getShort(HEADER_BYTES + 2) & 0xffff;
        long centerHz = view.getLong(HEADER_BYTES + 4);
        float hzPerPixel = view.getFloat(HEADER_BYTES + 12);
        if (width <= 0 || !(hzPerPixel > 0f)) return null;
        int panOffset = HEADER_BYTES + DISPLAY_BODY_BYTES;
        int waterfallOffset = panOffset + width * 4;
        if (raw.length < waterfallOffset + width * 4) return null;
        boolean hasPan = (bodyFlags & DISPLAY_PAN_VALID) != 0;
        boolean hasWaterfall = (bodyFlags & DISPLAY_WATERFALL_VALID) != 0;
        if (!hasPan && !hasWaterfall) return null;
        float[] pan = hasPan ? reduce(view, panOffset, width, bins) : null;
        float[] waterfall = hasWaterfall ? reduce(view, waterfallOffset, width, bins) : null;
        return new Display(pan, waterfall, centerHz, (double) hzPerPixel * width / bins);
    }

    static float[] reduce(ByteBuffer view, int offset, int width, int bins) {
        float[] out = new float[bins];
        for (int bin = 0; bin < bins; bin++) {
            int from = (int) ((long) bin * width / bins);
            int to = (int) ((long) (bin + 1) * width / bins);
            if (to <= from) to = from + 1;
            if (to > width) to = width;
            float peak = Float.NEGATIVE_INFINITY;
            for (int index = from; index < to; index++) {
                float value = view.getFloat(offset + index * 4);
                if (value > peak) peak = value;
            }
            out[bin] = peak == Float.NEGATIVE_INFINITY ? -160f : peak;
        }
        return out;
    }

    public static Audio decodeAudio(byte[] raw) {
        if (raw == null || raw.length < HEADER_BYTES + AUDIO_BODY_BYTES) return null;
        if (raw[0] != MSG_AUDIO) return null;
        ByteBuffer view = wrap(raw);
        int channels = view.get(HEADER_BYTES + 1) & 0xff;
        int sampleRate = view.getInt(HEADER_BYTES + 2);
        int sampleCount = view.getShort(HEADER_BYTES + 6) & 0xffff;
        if (channels < 1 || sampleRate <= 0 || sampleCount <= 0) return null;
        int floats = sampleCount * channels;
        int offset = HEADER_BYTES + AUDIO_BODY_BYTES;
        if (raw.length < offset + floats * 4) return null;
        float[] samples = new float[floats];
        for (int index = 0; index < floats; index++) samples[index] = view.getFloat(offset + index * 4);
        return new Audio(samples, channels, sampleRate);
    }

    /** Returns NaN for a frame that is not a usable RX meter reading. */
    public static float decodeRxMeter(byte[] raw) {
        if (raw == null || raw.length < 5 || raw[0] != MSG_RX_METER) return Float.NaN;
        return wrap(raw).getFloat(1);
    }

    /** Returns -1 for anything that is not an RX1 VFO edge. */
    public static long decodeVfoHz(byte[] raw) {
        if (raw == null || raw.length < 18 || raw[0] != MSG_VFO_STATE || raw[1] != 0) return -1L;
        long hz = wrap(raw).getLong(2);
        return hz > 0 ? hz : -1L;
    }

    public static Mox decodeMox(byte[] raw) {
        if (raw == null || raw.length < 4 || raw[0] != MSG_MOX_STATE) return null;
        return new Mox(raw[1] != 0, raw[2] != 0);
    }

    /** {forward watts, SWR}, or null when the frame is not TX meters. */
    public static float[] decodeTxMeters(byte[] raw) {
        if (raw == null || raw.length < 13 || raw[0] != MSG_TX_METERS) return null;
        ByteBuffer view = wrap(raw);
        return new float[] { view.getFloat(1), view.getFloat(9) };
    }
}
