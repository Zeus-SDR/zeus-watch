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
 * Public-key trust for the operator's own station. The LAN listener presents a
 * self-signed certificate, so no certificate authority can vouch for it; the
 * build embeds the station's public certificate and every connection must
 * present the same public key. The station re-issues its certificate whenever
 * its LAN addresses change but keeps the key, so pinning the key rather than
 * the certificate bytes survives a new DHCP lease or VPN adapter.
 */
public final class PinnedTls {
    private PinnedTls() { }

    /** The station presented a key other than the one this build pins. */
    public static final class StationKeyChanged extends CertificateException {
        StationKeyChanged() {
            super("Station key changed");
        }
    }

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
                // Compare SubjectPublicKeyInfo DER; a certificate without an
                // encodable key fails closed like any mismatch.
                byte[] expected = pinned.getPublicKey().getEncoded();
                byte[] presented = chain[0].getPublicKey().getEncoded();
                if (expected == null || presented == null || !MessageDigest.isEqual(expected, presented))
                    throw new StationKeyChanged();
            }
        };
    }

    /**
     * The embedded certificate only carries the pinned key; its own dates are
     * not checked, because the station renews it with the same key. The
     * presented certificate's validity is checked on every handshake.
     */
    public static SSLContext context(X509Certificate pinned, X509TrustManager manager)
            throws GeneralSecurityException {
        SSLContext tls = SSLContext.getInstance("TLS");
        tls.init(null, new TrustManager[] { manager }, null);
        return tls;
    }
}
