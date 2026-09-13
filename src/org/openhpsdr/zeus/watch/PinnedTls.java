// SPDX-License-Identifier: LicenseRef-Proprietary
// Copyright (C) 2026 Douglas J. Cerrato (KB2UKA) and Christian Suarez (N9WAR).
package org.openhpsdr.zeus.watch;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Exact-certificate trust for the operator's own station. The LAN listener
 * presents a self-signed certificate, so no certificate authority can vouch
 * for it; the build embeds the public certificate and every connection must
 * match those bytes exactly.
 */
public final class PinnedTls {
    private PinnedTls() { }

    public static X509TrustManager trustManager(final X509Certificate pinned) {
        return new X509TrustManager() {
            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[] { pinned };
            }

            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                throw new CertificateException("Client certificates are not accepted");
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                if (chain == null || chain.length == 0) throw new CertificateException("Missing server certificate");
                chain[0].checkValidity();
                // CertificateEncodingException is a CertificateException, so a
                // certificate we cannot re-encode fails closed like any mismatch.
                if (!MessageDigest.isEqual(pinned.getEncoded(), chain[0].getEncoded()))
                    throw new CertificateException("Station certificate changed");
            }
        };
    }

    public static SSLContext context(X509Certificate pinned, X509TrustManager manager)
            throws GeneralSecurityException {
        pinned.checkValidity();
        SSLContext tls = SSLContext.getInstance("TLS");
        tls.init(null, new TrustManager[] { manager }, null);
        return tls;
    }
}
