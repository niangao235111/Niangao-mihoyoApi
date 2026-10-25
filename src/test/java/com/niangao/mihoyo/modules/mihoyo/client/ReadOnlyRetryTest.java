package com.niangao.mihoyo.modules.mihoyo.client;

import com.niangao.mihoyo.common.exception.BusinessException;
import com.niangao.mihoyo.modules.mihoyo.client.model.UpstreamReply;
import com.niangao.mihoyo.modules.mihoyo.config.MihoyoProperties;
import io.clientcore.core.http.client.HttpClient;
import io.clientcore.core.http.models.*;
import io.clientcore.core.models.binarydata.BinaryData;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.*;
import java.io.*;
import java.net.ConnectException;
import java.security.cert.CertificateException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ReadOnlyRetryTest {
    @AfterEach void reset() { RequestContextHolder.resetRequestAttributes(); Thread.interrupted(); }
    RuntimeException network() { return new UncheckedIOException(new ConnectException("fixture-secret-not-for-public")); }

    @Test void retriesTwoTransientFailuresThenReturnsSuccess() {
        AtomicInteger calls = new AtomicInteger();
        assertEquals("ok", ReadOnlyRetry.execute("binding_roles", budget -> {
            assertTrue(budget.toSeconds() <= 45);
            if (calls.incrementAndGet() < 3) throw network();
            return "ok";
        }));
        assertEquals(3, calls.get());
    }

    @Test void exhaustsAfterThreeAttemptsWithoutPublishingExceptionDetails() {
        AtomicInteger calls = new AtomicInteger();
        var error = assertThrows(BusinessException.class, () -> ReadOnlyRetry.execute("binding_roles", budget -> { calls.incrementAndGet(); throw network(); }));
        assertEquals(3, calls.get()); assertEquals(502, error.getStatus());
        assertEquals("binding_roles", error.getDetails().get("stage"));
        assertEquals(2, error.getDetails().get("retry_count"));
        assertFalse(error.getMessage().contains("fixture-secret"));
    }

    @Test void businessRiskAndHttpFailuresAreNeverRetried() {
        for (int status : List.of(422, 429, 502, 503)) {
            AtomicInteger calls = new AtomicInteger();
            var original = new BusinessException(status, "upstream refusal");
            assertSame(original, assertThrows(BusinessException.class, () -> ReadOnlyRetry.execute("genshin_overview", budget -> { calls.incrementAndGet(); throw original; })));
            assertEquals(1, calls.get());
        }
    }

    @Test void certificateAndInterruptFailuresAreNeverRetried() {
        var certificate = new RuntimeException(new IOException(new CertificateException("fixture")));
        AtomicInteger calls = new AtomicInteger();
        assertSame(certificate, assertThrows(RuntimeException.class, () -> ReadOnlyRetry.execute("binding_roles", budget -> { calls.incrementAndGet(); throw certificate; })));
        assertEquals(1, calls.get());
        Thread.currentThread().interrupt();
        calls.set(0);
        assertThrows(RuntimeException.class, () -> ReadOnlyRetry.execute("binding_roles", budget -> { calls.incrementAndGet(); throw network(); }));
        assertEquals(1, calls.get()); assertTrue(Thread.currentThread().isInterrupted());
    }

    @Test void multipleStagesShareTheSameRequestDeadline() {
        var attributes = new ServletRequestAttributes(new MockHttpServletRequest());
        RequestContextHolder.setRequestAttributes(attributes);
        ReadOnlyRetry.execute("stoken_to_ltoken", budget -> "ok");
        String key = ReadOnlyRetry.class.getName() + ".deadline";
        Object initial = attributes.getAttribute(key, RequestAttributes.SCOPE_REQUEST);
        ReadOnlyRetry.execute("binding_roles", budget -> "ok");
        assertEquals(initial, attributes.getAttribute(key, RequestAttributes.SCOPE_REQUEST));
        attributes.setAttribute(key, System.nanoTime()-1, RequestAttributes.SCOPE_REQUEST);
        AtomicInteger calls = new AtomicInteger();
        assertThrows(BusinessException.class, () -> ReadOnlyRetry.execute("genshin_overview", budget -> calls.incrementAndGet()));
        assertEquals(0, calls.get());
    }

    SdkTransport transport(HttpClient client) {
        return new SdkTransport(new MihoyoProperties()) {
            @Override protected HttpClient buildDelegate(Duration connect, Duration response) { return client; }
        };
    }
    HttpRequest request() {
        return new HttpRequest().setMethod(HttpMethod.POST).setUri("https://example.invalid/read")
                .setBody(BinaryData.fromBytes("{\"ids\":[1,2]}".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    @Test void readOnlyPostReplaysTheFullBodyAndDeviceId() {
        AtomicInteger calls = new AtomicInteger();
        var adapter = transport(request -> {
            assertEquals("{\"ids\":[1,2]}", request.getBody().toString());
            assertEquals("fixture-device", request.getHeaders().getValue(HttpHeaderName.fromString("x-rpc-device_id")));
            if (calls.incrementAndGet() < 3) throw network();
            return new Response<>(request,200,new HttpHeaders(),BinaryData.fromBytes("{\"retcode\":0,\"data\":{}}".getBytes()));
        });
        var capture = new UpstreamReply();
        try (var result = adapter.createReadOnly("genshin_equipment","fixture-device",capture,true,4096,headers -> {}).send(request())) {
            assertEquals(200,result.getStatusCode()); assertEquals(3,calls.get()); assertNotNull(capture.getBody());
        }
    }

    @Test void mutationTransportDoesNotRetryAndReadOnlyHttpResponsesDoNotRetry() {
        AtomicInteger calls = new AtomicInteger();
        var adapter = transport(request -> { calls.incrementAndGet(); throw network(); });
        assertThrows(BusinessException.class, () -> adapter.create("fixture-device",new UpstreamReply(),false,null).send(request()));
        assertEquals(1,calls.get());
        calls.set(0);
        var httpAdapter = transport(request -> { calls.incrementAndGet(); return new Response<>(request,503,new HttpHeaders(),BinaryData.fromBytes("{}".getBytes())); });
        try(var response = httpAdapter.createReadOnly("binding_roles","fixture-device",new UpstreamReply(),true,4096,headers -> {}).send(request())) {
            assertEquals(503,response.getStatusCode()); assertEquals(1,calls.get());
        }
    }
    @Test void pooledPaginationReusesClientAndActualHttpConnectionAcrossPages() throws Exception {
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        java.util.Set<Integer> ports=java.util.concurrent.ConcurrentHashMap.newKeySet();
        server.createContext("/read",exchange->{
            ports.add(exchange.getRemoteAddress().getPort());exchange.getRequestBody().readAllBytes();
            byte[] body="{\"retcode\":0,\"data\":{}}".getBytes();
            exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
        });server.start();
        AtomicInteger builds=new AtomicInteger();
        var pooled=new SdkTransport(new MihoyoProperties()) {
            @Override protected HttpClient buildDelegate(Duration connect,Duration response) {
                builds.incrementAndGet();assertEquals(Duration.ofSeconds(10),connect);
                return super.buildDelegate(connect,response);
            }
        };
        try {
            for(int page=0;page<15;page++) {
                var captured=new UpstreamReply();
                var request=new HttpRequest().setMethod(HttpMethod.GET).setUri("http://127.0.0.1:"+server.getAddress().getPort()+"/read?page="+page);
                try(var response=pooled.createReadOnlyPooled("genshin_wishes","fixture",captured,true,4096,h->{}).send(request)){
                    assertEquals(200,response.getStatusCode());assertNotNull(captured.getBody());
                }
            }
            assertEquals(1,builds.get());assertEquals(1,ports.size());
        }finally{server.stop(0);}
    }
}
