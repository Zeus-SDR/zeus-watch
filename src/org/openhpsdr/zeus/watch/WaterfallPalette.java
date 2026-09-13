// SPDX-License-Identifier: LicenseRef-Proprietary
// Copyright (C) 2026 Douglas J. Cerrato (KB2UKA) and Christian Suarez (N9WAR).
package org.openhpsdr.zeus.watch;

/**
 * The station's default "blue" waterfall palette, ported from
 * {@code zeus-web/src/gl/colormap.ts} so the watch and the desktop paint the
 * same signal the same colour.
 *
 * <p>The floor is held black well past the midpoint: a noisy band should read
 * dark rather than flood the screen with mid-band colour, and weak carriers
 * separate from it. Pure arithmetic with no Android dependency, so the match
 * with the web renderer can be asserted directly.
 */
public final class WaterfallPalette {
    /** Anchor positions from BLUE_ANCHORS in the web renderer. */
    static final float[] STOPS = {
        0f, 0.42f, 0.52f, 0.62f, 0.71f, 0.79f, 0.87f, 0.94f, 1f,
    };

    /** Anchor colours for {@link #STOPS}, opaque ARGB. */
    static final int[] COLORS = {
        0xFF000000, 0xFF000000, 0xFF00006E, 0xFF001EEB, 0xFF00D2FF,
        0xFF0AFF46, 0xFFFFFF00, 0xFFFF2800, 0xFFFFFFFF,
    };

    /** Mirrors INTENSITY_LUT_CONTRAST in the web renderer. */
    public static final float CONTRAST = 1.12f;

    /** The station's default receive display window, FIXED_DB_MIN..FIXED_DB_MAX. */
    public static final float DB_MIN = -140f;
    public static final float DB_MAX = -50f;

    /** Width of the display window; moving the floor slides it, keeping span. */
    public static final float WINDOW_DB = DB_MAX - DB_MIN;

    /** How far the floor may be driven either way from the station default. */
    public static final float FLOOR_MIN = -160f;
    public static final float FLOOR_MAX = -80f;

    /** Matches COLORMAP_LUT_SIZE in the web renderer, so both resample alike. */
    public static final int LUT_SIZE = 1024;

    private static final int[] LUT = build(LUT_SIZE);

    private WaterfallPalette() { }

    /** Normalised display level, 0..1, with the renderer's contrast transfer. */
    public static float level(float db) {
        return level(db, DB_MIN);
    }

    /** As {@link #level(float)}, with the display floor moved to {@code floorDb}. */
    public static float level(float db, float floorDb) {
        if (Float.isNaN(db)) return 0f;
        float level = (db - floorDb) / WINDOW_DB;
        level = (level - 0.5f) * CONTRAST + 0.5f;
        return Math.max(0f, Math.min(1f, level));
    }

    /** Keeps a floor inside the range the display can usefully show. */
    public static float clampFloor(float floorDb) {
        if (Float.isNaN(floorDb)) return DB_MIN;
        return Math.max(FLOOR_MIN, Math.min(FLOOR_MAX, floorDb));
    }

    /** Opaque ARGB for a level in dB. */
    public static int color(float db) {
        return color(db, DB_MIN);
    }

    public static int color(float db, float floorDb) {
        return LUT[Math.round(level(db, floorDb) * (LUT.length - 1))];
    }

    /** Opaque ARGB for an already-normalised 0..1 position along the ramp. */
    public static int at(float position) {
        float t = Math.max(0f, Math.min(1f, position));
        int stop = 0;
        while (stop < STOPS.length - 2 && t > STOPS[stop + 1]) stop++;
        float span = STOPS[stop + 1] - STOPS[stop];
        float mix = span <= 0f ? 0f : (t - STOPS[stop]) / span;
        return blend(COLORS[stop], COLORS[stop + 1], mix);
    }

    static int[] build(int entries) {
        int[] lut = new int[entries];
        for (int index = 0; index < entries; index++) lut[index] = at(index / (float) (entries - 1));
        return lut;
    }

    private static int blend(int from, int to, float mix) {
        int red = Math.round((1 - mix) * ((from >> 16) & 0xff) + mix * ((to >> 16) & 0xff));
        int green = Math.round((1 - mix) * ((from >> 8) & 0xff) + mix * ((to >> 8) & 0xff));
        int blue = Math.round((1 - mix) * (from & 0xff) + mix * (to & 0xff));
        return 0xFF000000 | (red << 16) | (green << 8) | blue;
    }
}
