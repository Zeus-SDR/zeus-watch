// SPDX-License-Identifier: LicenseRef-Proprietary
// Copyright (C) 2026 Douglas J. Cerrato (KB2UKA) and Christian Suarez (N9WAR).
package org.openhpsdr.zeus.watch;

import com.sun.net.httpserver.HttpsServer;
import com.sun.net.httpserver.HttpsConfigurator;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

/** Standalone JVM integration checks with temporary test certificates. */
public final class LanProbeTest {
    public static void main(String[] args) throws Exception {
        if (args.length == 2 && args[0].startsWith("https:")) {
            X509Certificate cert;
            try (java.io.InputStream input = Files.newInputStream(Paths.get(args[1]))) {
                cert = (X509Certificate) java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(input);
            }
            check(new LanProbe().reachable(args[0], cert), "Live station pinned probe");
            return;
        }
        KeyStore keys = load(args[0]);
        KeyManagerFactory manager = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        manager.init(keys, "changeit".toCharArray());
        SSLContext tls = SSLContext.getInstance("TLS");
        tls.init(manager.getKeyManagers(), null, null);
        X509Certificate cert = (X509Certificate) keys.getCertificate("test");
        X509Certificate mismatch = (X509Certificate) load(args[1]).getCertificate("test");
        // The same key re-issued under a new certificate, as the station does
        // when its LAN addresses change.
        X509Certificate reissued = (X509Certificate) load(args[2]).getCertificate("test");
        check(!java.util.Arrays.equals(cert.getEncoded(), reissued.getEncoded()), "Re-issued certificate differs");
        AtomicInteger status = new AtomicInteger(200);
        AtomicInteger delay = new AtomicInteger(0);
        java.util.concurrent.ExecutorService executor = Executors.newCachedThreadPool();
        HttpsServer server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(tls));
        server.setExecutor(executor);
        server.createContext("/product/status", exchange -> {
            try { Thread.sleep(delay.get()); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            if (status.get() == 302) exchange.getResponseHeaders().set("Location", "/product/status");
            try { exchange.sendResponseHeaders(status.get(), -1); } finally { exchange.close(); }
        });
        server.start();
        String url = "https://127.0.0.1:" + server.getAddress().getPort() + "/?watch=1";
        try {
            check(new LanProbe().reachable(url, cert), "Pinned 200 succeeds");
            check(new LanProbe().reachable(url, reissued), "Same key under a re-issued certificate succeeds");
            check(!new LanProbe().reachable(url, mismatch), "Different certificate fails");
            check(new LanProbe().probe(url, mismatch) == LanProbe.Result.KEY_CHANGED, "Different key reports key change");
            check(!new LanProbe().reachable(url.replace("127.0.0.1", "localhost"), cert), "Hostname mismatch fails");
            check(new LanProbe().probe(url.replace("127.0.0.1", "localhost"), cert) == LanProbe.Result.IDENTITY_REJECTED,
                    "Hostname mismatch reports identity rejection");
            status.set(302); check(!new LanProbe().reachable(url, cert), "Redirect fails");
            status.set(503); check(!new LanProbe().reachable(url, cert), "Non-200 fails");
            check(new LanProbe().probe(url, cert) == LanProbe.Result.NOT_READY, "Non-200 reports not ready");
            status.set(200); delay.set(3000);
            long start = System.nanoTime();
            check(!new LanProbe().reachable(url, cert), "Read timeout fails");
            check((System.nanoTime() - start) / 1000000 < 2800, "Timeout bounded");
            LanProbe cancelled = new LanProbe(); cancelled.cancel();
            check(!cancelled.reachable(url, cert), "Cancelled probe fails");
        } finally { server.stop(0); executor.shutdownNow(); }
        check(new LanProbe().probe(url, cert) == LanProbe.Result.NO_ANSWER, "Closed port reports no answer");
        check(LanProbe.classify(new java.io.IOException("Hostname 10.0.0.5 not verified")) == LanProbe.Result.IDENTITY_REJECTED,
                "Android hostname failure reports identity rejection");
    }
    private static KeyStore load(String path) throws Exception {
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (java.io.InputStream input = Files.newInputStream(Paths.get(path))) { keys.load(input, "changeit".toCharArray()); }
        return keys;
    }
    private static void check(boolean success, String name) {
        if (!success) throw new AssertionError(name);
        System.out.println("PASS: " + name);
    }
}
