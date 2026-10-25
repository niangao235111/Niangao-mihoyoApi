package com.niangao.mihoyo.modules.mihoyo.client;

import com.niangao.mihoyo.modules.mihoyo.config.MihoyoProperties;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import io.clientcore.core.http.models.HttpMethod;
import io.clientcore.core.http.models.HttpRequest;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.*;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class SdkTransportTlsTest {
    @TempDir static Path temporary;
    static KeyStore certificate;
    static HttpsServer server;
    static String address;
    static final char[] PASSWORD = "local-test-only".toCharArray();
    static final AtomicReference<String> negotiated = new AtomicReference<>();

    @BeforeAll static void startServer() throws Exception {
        Path store = temporary.resolve("server.p12");
        String executable = System.getProperty("os.name").startsWith("Windows") ? "keytool.exe" : "keytool";
        Path output = temporary.resolve("keytool.log");
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-genkeypair", "-alias", "fixture", "-keyalg", "RSA", "-keysize", "2048",
                "-storetype", "PKCS12", "-keystore", store.toString(), "-storepass", String.valueOf(PASSWORD),
                "-dname", "CN=localhost", "-ext", "SAN=dns:localhost", "-validity", "2", "-noprompt")
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        if (!process.waitFor(20, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            fail("Fixture certificate generation timed out");
        }
        assertEquals(0, process.exitValue(), "Fixture certificate generation failed");
        certificate = KeyStore.getInstance("PKCS12");
        try (var input = java.nio.file.Files.newInputStream(store)) { certificate.load(input, PASSWORD); }
        var keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(certificate, PASSWORD);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keys.getKeyManagers(), null, null);
        server = HttpsServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(context));
        server.createContext("/", exchange -> {
            negotiated.set(((com.sun.net.httpserver.HttpsExchange) exchange).getSSLSession().getProtocol());
            byte[] body = "ok".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var stream = exchange.getResponseBody()) { stream.write(body); }
        });
        server.start();
        address = "https://localhost:" + server.getAddress().getPort() + "/";
    }

    @AfterAll static void stopServer() { if (server != null) server.stop(0); }

    @Test void negotiatesTls12WithoutChangingGlobalContext() throws Exception {
        SSLContext original = SSLContext.getDefault();
        var transport = new SdkTransport(new MihoyoProperties()) {
            @Override protected SSLContext buildSslContext() {
                SSLContext context = super.buildSslContext();
                try {
                    var trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                    trust.init(certificate);
                    context.init(null, trust.getTrustManagers(), null);
                    return context;
                } catch (Exception ex) { throw new IllegalStateException(ex); }
            }
        };
        try (var response = transport.buildDelegate(Duration.ofSeconds(3), Duration.ofSeconds(3))
                .send(new HttpRequest().setMethod(HttpMethod.GET).setUri(address))) {
            assertEquals(200, response.getStatusCode());
            assertEquals("ok", response.getValue().toString());
            assertEquals("TLSv1.2", negotiated.get());
        }
        assertSame(original, SSLContext.getDefault());
    }

    @Test void rejectsAnUntrustedCertificate() {
        var transport = new SdkTransport(new MihoyoProperties());
        RuntimeException error = assertThrows(RuntimeException.class, () ->
                transport.buildDelegate(Duration.ofSeconds(3), Duration.ofSeconds(3))
                        .send(new HttpRequest().setMethod(HttpMethod.GET).setUri(address)));
        boolean handshakeFailure = false;
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SSLHandshakeException) handshakeFailure = true;
        }
        assertTrue(handshakeFailure, "Untrusted certificates must fail the TLS handshake");
    }
}
