// SPDX-License-Identifier: LicenseRef-Proprietary
// Copyright (C) 2026 Douglas J. Cerrato (KB2UKA) and Christian Suarez (N9WAR).
package org.openhpsdr.zeus.watch;

import java.net.URL;
import java.security.cert.X509Certificate;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;

/** A pinned, bounded reachability check; browser certificate handling is separate. */
public final class LanProbe {
    private volatile boolean cancelled;

    public boolean reachable(String address, X509Certificate pinned) {
        HttpsURLConnection current = null;
        try {
            if (cancelled) return false;
            pinned.checkValidity();
            URL station = new URL(address);
            if (!"https".equals(station.getProtocol()) || station.getUserInfo() != null) return false;
            URL endpoint = new URL(station.getProtocol(), station.getHost(), station.getPort(), "/product/status");
            SSLContext tls = PinnedTls.context(pinned, PinnedTls.trustManager(pinned));
            current = (HttpsURLConnection) endpoint.openConnection();
            current.setSSLSocketFactory(tls.getSocketFactory());
            // Retain the platform hostname verifier, including IP SAN validation.
            current.setConnectTimeout(2000);
            current.setReadTimeout(2000);
            current.setInstanceFollowRedirects(false);
            current.setUseCaches(false);
            if (cancelled) return false;
            return current.getResponseCode() == 200 && !cancelled;
        } catch (Exception error) {
            return false;
        } finally {
            if (current != null) current.disconnect();
        }
    }

    public void cancel() {
        cancelled = true;
    }
}
