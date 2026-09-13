// SPDX-License-Identifier: LicenseRef-Proprietary
// Copyright (C) 2026 Douglas J. Cerrato (KB2UKA) and Christian Suarez (N9WAR).
package org.openhpsdr.zeus.watch;

/**
 * Turns the watch's own orientation into a transmit intent: flick the wrist so
 * the face comes into view to key, turn it away to unkey.
 *
 * <p>The reading is absolute -- how far the face is from looking straight up at
 * the operator -- rather than relative to any learned posture. A posture
 * baseline cannot work here: the operator is looking at the watch whenever they
 * are using it, so "where it usually sits" is the viewing position itself.
 *
 * <p>This keys a transmitter from an arm movement, so it is built to refuse
 * more than it accepts:
 *
 * <ul>
 *   <li>It arms only after the face has been turned away, so a gesture that
 *       begins with the watch already in view -- switching it on, for one --
 *       cannot key until the operator has deliberately turned away and back.
 *   <li>The face must stay in view for a dwell before anything happens, so a
 *       glance or a swing passes straight through.
 *   <li>It releases at a wider angle than it keys, so a wrist hovering at the
 *       threshold cannot chatter the transmitter.
 *   <li>A key that outlasts the maximum hold is dropped and cannot be retaken
 *       until the face has been turned away again, so an arm that comes to rest
 *       face-up does not transmit indefinitely.
 * </ul>
 *
 * <p>Pure geometry and timing with no Android dependency, so the thresholds can
 * be tested rather than trusted.
 */
public final class WristFlip {
    /** Degrees from face-up at or under which the face counts as in view. */
    public static final float KEY_TILT = 50f;

    /** Degrees from face-up at or over which the face counts as turned away. */
    public static final float RELEASE_TILT = 75f;

    /** How long the face must stay in view before it keys. */
    public static final long DWELL_MS = 300L;

    /** Longest a single gesture may hold the key. */
    public static final long MAX_HOLD_MS = 180_000L;

    private boolean keyed;
    /** Set once the face has been turned away; required before any key. */
    private boolean armed;
    private long inViewAt;
    private long keyedAt;

    /** Forgets everything, including whether the face has been turned away. */
    public void reset() {
        keyed = false;
        armed = false;
        inViewAt = 0;
        keyedAt = 0;
    }

    public boolean isKeyed() {
        return keyed;
    }

    /**
     * Feeds one gravity sample and returns the transmit intent afterwards.
     * Axes are Android's: z is out of the face. Units do not matter.
     */
    public boolean update(float x, float y, float z, long nowMs) {
        double magnitude = Math.sqrt((double) x * x + (double) y * y + (double) z * z);
        // Free fall, or a bogus sample: it says nothing about which way is up.
        if (!(magnitude > 0.1)) return keyed;
        float tilt = tiltDegrees((float) (z / magnitude));

        if (keyed) {
            if (tilt >= RELEASE_TILT) {
                keyed = false;
                inViewAt = 0;
                // Turned away, so it is ready for the next flick straight away.
                armed = true;
                return false;
            }
            if (nowMs - keyedAt >= MAX_HOLD_MS) {
                // Too long to be a deliberate over. Drop it, and refuse to key
                // again until the face has actually been turned away.
                keyed = false;
                inViewAt = 0;
                armed = false;
            }
            return keyed;
        }

        if (tilt >= RELEASE_TILT) {
            armed = true;
            inViewAt = 0;
            return false;
        }

        if (tilt > KEY_TILT || !armed) {
            if (tilt > KEY_TILT) inViewAt = 0;
            return false;
        }

        if (inViewAt == 0) {
            inViewAt = nowMs;
            return false;
        }
        if (nowMs - inViewAt >= DWELL_MS) {
            keyed = true;
            keyedAt = nowMs;
            armed = false;
        }
        return keyed;
    }

    /** Degrees between the face and straight up: 0 in view, 180 face down. */
    public static float tiltDegrees(float normalisedZ) {
        float clamped = Math.max(-1f, Math.min(1f, normalisedZ));
        return (float) Math.toDegrees(Math.acos(clamped));
    }

    /** True once the face has been turned away, which any key requires first. */
    public boolean isArmed() {
        return armed;
    }
}
