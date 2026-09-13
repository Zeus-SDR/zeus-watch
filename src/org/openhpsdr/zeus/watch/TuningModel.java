// SPDX-License-Identifier: LicenseRef-Proprietary
// Copyright (C) 2026 Douglas J. Cerrato (KB2UKA) and Christian Suarez (N9WAR).
package org.openhpsdr.zeus.watch;

/**
 * Keeps watch tuning immediate without letting it fight the station.
 *
 * <p>The dial moves locally on every detent, but only one retune is ever in
 * flight: further detents collapse into a single latest-wins request that goes
 * out when the previous one completes. While the operator is turning, and for a
 * short guard afterwards, station VFO echoes are ignored — an echo from before
 * the turn would otherwise drag the dial backwards under the operator's finger.
 *
 * <p>Pure logic with no Android or network dependency, so the coalescing and
 * echo rules can be tested directly.
 */
public final class TuningModel {
    public interface Send {
        void retune(long hz);
    }

    /** How long station VFO edges stay ignored after the operator tunes. */
    public static final long ECHO_GUARD_MS = 700L;

    private final Send send;
    private long displayedHz;
    private long desiredHz;
    private boolean inFlight;
    private boolean pending;
    private long guardUntil;

    public TuningModel(Send send) {
        this.send = send;
    }

    public long displayedHz() {
        return displayedHz;
    }

    /** Adopts a station frequency the operator has not overridden. */
    public boolean acceptStationHz(long hz, long nowMs) {
        if (hz <= 0 || !acceptsStationEdges(nowMs)) return false;
        displayedHz = hz;
        return true;
    }

    public boolean acceptsStationEdges(long nowMs) {
        return !inFlight && !pending && nowMs >= guardUntil;
    }

    /** Moves by whole detents of the configured step. */
    public void step(int steps, long stepHz, long nowMs) {
        if (steps == 0 || stepHz <= 0) return;
        long base = inFlight || pending ? desiredHz : displayedHz;
        request(base + steps * stepHz, nowMs);
    }

    /** Retunes to an absolute frequency, e.g. from the keypad or a band pick. */
    public void request(long hz, long nowMs) {
        if (hz <= 0) return;
        desiredHz = hz;
        displayedHz = hz;
        guardUntil = nowMs + ECHO_GUARD_MS;
        if (inFlight) {
            pending = true;
            return;
        }
        dispatch();
    }

    private void dispatch() {
        inFlight = true;
        pending = false;
        send.retune(desiredHz);
    }

    /**
     * Reports that the retune finished. Call with the frequency that was sent so
     * a request superseded mid-flight is re-issued rather than lost.
     */
    public void completed(long sentHz, long nowMs) {
        inFlight = false;
        if (pending || sentHz != desiredHz) {
            dispatch();
            return;
        }
        guardUntil = nowMs + ECHO_GUARD_MS;
    }
}
