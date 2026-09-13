// SPDX-License-Identifier: LicenseRef-Proprietary
// Copyright (C) 2026 Douglas J. Cerrato (KB2UKA) and Christian Suarez (N9WAR).
package org.openhpsdr.zeus.watch;

/**
 * Decides whether a touch on the round face is a page swipe or a turn of the
 * tuning ring.
 *
 * <p>Paging wins by default: the operator swipes to move between pages far more
 * often than they tune by dragging the rim, and a swipe naturally starts near
 * the rim on a round face. The ring only takes a gesture that holds still on
 * the rim first, which a swipe never does because it is already moving within a
 * few milliseconds.
 *
 * <p>Pure geometry and timing with no Android dependency, so the arbitration can
 * be tested directly rather than only on a watch.
 */
public final class TouchRouter {
    public interface Sink {
        /** The rim dwell elapsed; this gesture now belongs to the ring. */
        void onRingArmed();

        /** One detent, positive clockwise. */
        void onRingStep(int direction);

        /** A completed page swipe: +1 forward, -1 back. */
        void onPage(int delta);

        /** Vertical drag, in pixels since the last report. Only where enabled. */
        void onVerticalDrag(float dy);
    }

    /** Degrees of rim travel per tuning detent; a full turn is 40 steps. */
    public static final double DETENT_DEGREES = 9.0;

    private final Sink sink;
    private final float centerX;
    private final float centerY;
    private final float ringInner;
    private final float slop;
    private final float pageThreshold;
    private final long dwellMs;

    private float downX;
    private float downY;
    private long downAt;
    private boolean ringWaiting;
    private boolean ringArmed;
    private boolean paging;
    private boolean verticalEnabled;
    private boolean dragging;
    private float lastY;
    private double ringAngle;
    private double ringCarry;

    public TouchRouter(Sink sink, float centerX, float centerY, float ringInner,
            float slop, float pageThreshold, long dwellMs) {
        this.sink = sink;
        this.centerX = centerX;
        this.centerY = centerY;
        this.ringInner = ringInner;
        this.slop = slop;
        this.pageThreshold = pageThreshold;
        this.dwellMs = dwellMs;
    }

    /** Enables the vertical-drag gesture; off everywhere it means nothing. */
    public void setVerticalEnabled(boolean enabled) {
        verticalEnabled = enabled;
        if (!enabled) dragging = false;
    }

    public boolean isRingArmed() {
        return ringArmed;
    }

    /** Where the finger last was on the rim, in degrees, for the ring marker. */
    public double angleDegrees() {
        return ringAngle;
    }

    public void down(float x, float y, long nowMs) {
        downX = x;
        downY = y;
        downAt = nowMs;
        paging = false;
        dragging = false;
        ringArmed = false;
        lastY = y;
        ringWaiting = radius(x, y) >= ringInner;
    }

    /** Returns true once this gesture belongs to the ring or to paging. */
    public boolean move(float x, float y, long nowMs) {
        if (ringArmed) {
            turn(x, y);
            return true;
        }
        if (paging) return true;
        float dx = x - downX;
        float dy = y - downY;
        if (dragging) {
            sink.onVerticalDrag(y - lastY);
            lastY = y;
            return true;
        }
        if (ringWaiting) {
            if (nowMs - downAt >= dwellMs) {
                ringWaiting = false;
                ringArmed = true;
                ringAngle = angle(x, y);
                ringCarry = 0;
                sink.onRingArmed();
                return true;
            }
            // Already moving: a swipe, not a turn of the ring.
            if (Math.hypot(dx, dy) > slop) ringWaiting = false;
        }
        if (Math.abs(dx) > slop * 2 && Math.abs(dx) > Math.abs(dy) * 1.5f) {
            paging = true;
            return true;
        }
        if (verticalEnabled && Math.abs(dy) > slop * 2 && Math.abs(dy) > Math.abs(dx) * 1.5f) {
            dragging = true;
            lastY = y;
            return true;
        }
        return false;
    }

    public void up(float x, float y, long nowMs) {
        if (paging && !ringArmed && Math.abs(x - downX) > pageThreshold) {
            sink.onPage(x - downX < 0 ? 1 : -1);
        }
        paging = false;
        dragging = false;
        ringWaiting = false;
        ringArmed = false;
    }

    public void cancel() {
        paging = false;
        dragging = false;
        ringWaiting = false;
        ringArmed = false;
    }

    private void turn(float x, float y) {
        double now = angle(x, y);
        double delta = now - ringAngle;
        if (delta > 180) delta -= 360;
        if (delta < -180) delta += 360;
        ringAngle = now;
        ringCarry += delta;
        while (Math.abs(ringCarry) >= DETENT_DEGREES) {
            int direction = ringCarry > 0 ? 1 : -1;
            ringCarry -= direction * DETENT_DEGREES;
            sink.onRingStep(direction);
        }
    }

    private float radius(float x, float y) {
        return (float) Math.hypot(x - centerX, y - centerY);
    }

    private double angle(float x, float y) {
        double degrees = Math.toDegrees(Math.atan2(y - centerY, x - centerX));
        return degrees < 0 ? degrees + 360 : degrees;
    }
}
