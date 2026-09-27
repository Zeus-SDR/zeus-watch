// SPDX-License-Identifier: LicenseRef-Proprietary
// Copyright (C) 2026 Douglas J. Cerrato (KB2UKA) and Christian Suarez (N9WAR).
package org.openhpsdr.zeus.watch;

import java.net.URL;
import java.security.cert.X509Certificate;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;

/** A pinned, bounded reachability check; browser certificate handling is separate. */
public final class LanProbe {
    /** Why the station is or is not usable over the LAN. */
    public enum Result {
        /** The station answered with the pinned key and a 200. */
        REACHABLE,
        /** Nothing answered: refused, timed out, or the name did not resolve. */
        NO_ANSWER,
        /** Something answered TLS but with a key other than the pinned one. */
        KEY_CHANGED,
        /** Something answered TLS but failed another check: address not on the certificate, or expired. */
        IDENTITY_REJECTED,
        /** The station answered with the pinned key but not with a 200. */
        NOT_READY,
        /** The address is unusable, or the probe was cancelled. */
        UNUSABLE
    }

    private volatile boolean cancelled;

    public boolean reachable(String address, X509Certificate pinned) {
        return probe(address, pinned) == Result.REACHABLE;
    }

    public Result probe(String address, X509Certificate pinned) {
        HttpsURLConnection current = null;
        try {
            if (cancelled) return Result.UNUSABLE;
            URL station = new URL(address);
            if (!"https".equals(station.getProtocol()) || station.getUserInfo() != null) return Result.UNUSABLE;
            URL endpoint = new URL(station.getProtocol(), station.getHost(), station.getPort(), "/product/status");
            SSLContext tls = PinnedTls.context(pinned, PinnedTls.trustManager(pinned));
            current = (HttpsURLConnection) endpoint.openConnection();
            current.setSSLSocketFactory(tls.getSocketFactory());
            // Retain the platform hostname verifier, including IP SAN validation.
            current.setConnectTimeout(2000);
            current.setReadTimeout(2000);
            current.setInstanceFollowRedirects(false);
            current.setUseCaches(false);
            if (cancelled) return Result.UNUSABLE;
            int status = current.getResponseCode();
            if (cancelled) return Result.UNUSABLE;
            return status == 200 ? Result.REACHABLE : Result.NOT_READY;
        } catch (Exception error) {
            if (cancelled) return Result.UNUSABLE;
            return classify(error);
        } finally {
            if (current != null) current.disconnect();
        }
    }

    public void cancel() {
        cancelled = true;
    }

    static Result classify(Throwable error) {
        boolean tls = false;
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof PinnedTls.StationKeyChanged) return Result.KEY_CHANGED;
            if (cause instanceof SSLException) tls = true;
        }
        // Android reports a hostname mismatch as an IOException after the handshake.
        String message = error.getMessage();
        if (message != null && message.startsWith("Hostname ") && message.endsWith(" not verified")) tls = true;
        return tls ? Result.IDENTITY_REJECTED : Result.NO_ANSWER;
    }
}
