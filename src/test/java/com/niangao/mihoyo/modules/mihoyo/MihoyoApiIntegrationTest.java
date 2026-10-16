package com.niangao.mihoyo.modules.mihoyo;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.google.gson.*;
import com.niangao.mihoyo.modules.mihoyo.util.RsaEncryptionUtil;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import javax.crypto.Cipher;

/** 使用真实 Spring MVC + Service + Mapper + SDK，仅米哈游上游被本地 HTTP 服务替代。 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@AutoConfigureMockMvc
@Import(MihoyoApiIntegrationTest.TestBeans.class)
class MihoyoApiIntegrationTest {
    static final String REDIS_PREFIX = "niangao:test:api:" + UUID.randomUUID();
    static final Set<String> SESSION_IDS = java.util.concurrent.ConcurrentHashMap.newKeySet();
    static final KeyPair KEYS;
    static final HttpServer UPSTREAM;
    static final Queue<Throwable> ERRORS = new ConcurrentLinkedQueue<>();
    static final Map<String, String> DEVICES = new java.util.concurrent.ConcurrentHashMap<>();
    static final AtomicInteger SMS_CALLS = new AtomicInteger();
    static final AtomicInteger LOGIN_CALLS = new AtomicInteger();
    static final AtomicInteger ACCOUNT_CALLS = new AtomicInteger();

    static {
        try {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(1024);
            KEYS = generator.generateKeyPair();
            UPSTREAM = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            UPSTREAM.createContext(
                    "/",
                    exchange -> {
                        String output;
                        try {
                            String path = exchange.getRequestURI().getPath();
                            assertNotNull(exchange.getRequestHeaders().getFirst("x-rpc-device_id"));
                            if (path.startsWith("/account/ma-cn")) {
                                assertEquals("POST", exchange.getRequestMethod());
                                assertEquals(
                                        "ddxf5dufpuyo",
                                        exchange.getRequestHeaders().getFirst("x-rpc-app_id"));
                                JsonObject body =
                                        JsonParser.parseString(
                                                        new String(
                                                                exchange.getRequestBody()
                                                                        .readAllBytes(),
                                                                StandardCharsets.UTF_8))
                                                .getAsJsonObject();
                                Cipher rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding");
                                rsa.init(Cipher.DECRYPT_MODE, KEYS.getPrivate());
                                String mobile =
                                        new String(
                                                rsa.doFinal(
                                                        Base64.getDecoder()
                                                                .decode(
                                                                        body.get("mobile")
                                                                                .getAsString())),
                                                StandardCharsets.UTF_8);
                                assertEquals(
                                        "+86",
                                        new String(
                                                rsa.doFinal(
                                                        Base64.getDecoder()
                                                                .decode(
                                                                        body.get("area_code")
                                                                                .getAsString())),
                                                StandardCharsets.UTF_8));
                                String device =
                                        exchange.getRequestHeaders().getFirst("x-rpc-device_id");
                                assertEquals(53, device.length());
                                String previous = DEVICES.putIfAbsent(mobile, device);
                                if (previous != null) assertEquals(previous, device);
                                boolean sms =
                                        path.equals(
                                                "/account/ma-cn-verifier/verifier/createLoginCaptcha");
                                if (sms) SMS_CALLS.incrementAndGet();
                                else {
                                    LOGIN_CALLS.incrementAndGet();
                                    assertEquals(
                                            "/account/ma-cn-passport/app/loginByMobileCaptcha",
                                            path);
                                    assertEquals("login", body.get("action_type").getAsString());
                                }
                                String proof = exchange.getRequestHeaders().getFirst("x-rpc-aigis");
                                if ((mobile.endsWith("002") || mobile.endsWith("004"))
                                        && proof == null) {
                                    boolean v4 = mobile.endsWith("004");
                                    JsonObject task = new JsonObject();
                                    task.addProperty("use_v4", v4);
                                    task.addProperty("gt", "mock-gt");
                                    JsonObject risk = new JsonObject();
                                    risk.addProperty(
                                            "session_id",
                                            "risk-" + (sms ? "sms" : "login") + (v4 ? "4" : "3"));
                                    risk.add("data", task);
                                    exchange.getResponseHeaders()
                                            .set("x-rpc-aigis", risk.toString());
                                    output = "{\"retcode\":-3101,\"data\":null}";
                                } else if (sms) {
                                    if (proof != null) {
                                        assertTrue(proof.startsWith("risk-sms"));
                                        JsonObject result =
                                                JsonParser.parseString(
                                                                new String(
                                                                        Base64.getDecoder()
                                                                                .decode(
                                                                                        proof.split(
                                                                                                        ";",
                                                                                                        2)[
                                                                                                1]),
                                                                        StandardCharsets.UTF_8))
                                                        .getAsJsonObject();
                                        assertTrue(
                                                result.has(
                                                        mobile.endsWith("004")
                                                                ? "captcha_output"
                                                                : "geetest_validate"));
                                    }
                                    output =
                                            "{\"retcode\":0,\"data\":{\"action_type\":\"login\",\"countdown\":60,\"sent_new\":true}}";
                                } else if (body.get("captcha").getAsString().equals("999999")) {
                                    output =
                                            "{\"retcode\":-100,\"message\":\"SECRET_UPSTREAM_MESSAGE\",\"data\":null}";
                                } else if (mobile.endsWith("005")) {
                                    output =
                                            "{\"retcode\":0,\"data\":{\"token\":{\"token_type\":2,\"token\":\"wrong-token-type\"},\"user_info\":{\"aid\":\"123\",\"mid\":\"mock-mid\"}}}";
                                } else {
                                    if (proof != null) assertTrue(proof.startsWith("risk-login"));
                                    output =
                                            "{\"retcode\":0,\"data\":{\"token\":{\"token_type\":1,\"token\":\"mock-stoken\"},\"user_info\":{\"aid\":\"123\",\"mid\":\"mock-mid\"}}}";
                                }
                            } else {
                                ACCOUNT_CALLS.incrementAndGet();
                                assertEquals("GET", exchange.getRequestMethod());
                                String cookie = exchange.getRequestHeaders().getFirst("Cookie");
                                if (path.equals("/account/auth/api/getLTokenBySToken")) {
                                    assertTrue(cookie.contains("stoken_v2="));
                                    output =
                                            cookie.contains("stoken=expired;")
                                                    ? "{\"retcode\":-100,\"message\":\"SECRET_UPSTREAM_MESSAGE\"}"
                                                    : "{\"retcode\":0,\"data\":{\"ltoken\":\"mock-ltoken\"}}";
                                } else {
                                    assertEquals("/binding/api/getUserGameRolesByCookie", path);
                                    assertFalse(cookie.contains("stoken="));
                                    assertTrue(cookie.contains("ltoken_v2=mock-ltoken"));
                                    assertTrue(
                                            exchange.getRequestURI().getQuery() == null
                                                    || !exchange.getRequestURI()
                                                            .getQuery()
                                                            .contains("game_biz"));
                                    output =
                                            cookie.contains("ltuid=888;")
                                                    ? "{\"retcode\":0,\"data\":{\"list\":[]}}"
                                                    : """
                                {"retcode":0,"data":{"list":[
                                  {"game_biz":"hk4e_cn","game_uid":100000001,"region":"cn_gf01","nickname":"测试旅行者","level":"60","is_official":"1","unwanted_secret":"HIDDEN_FIELD"},
                                  {"game_biz":"hk4e_cn","game_uid":"500000001","region":"cn_qd01","level":50},
                                  {"game_biz":"hk4e_global","game_uid":"800000001","region":"os_asia"},
                                  {"game_biz":"hk4e_cn","game_uid":"800000002","region":"os_usa"},
                                  {"game_biz":"hkrpg_cn","game_uid":"100000002","region":"prod_gf_cn"}
                                ]}}
                                """;
                                }
                            }
                        } catch (Throwable ex) {
                            ERRORS.add(ex);
                            output = "{\"retcode\":-999,\"data\":null}";
                        }
                        byte[] bytes = output.getBytes(StandardCharsets.UTF_8);
                        exchange.getResponseHeaders().set("Content-Type", "application/json");
                        exchange.sendResponseHeaders(200, bytes.length);
                        exchange.getResponseBody().write(bytes);
                        exchange.close();
                    });
            UPSTREAM.start();
        } catch (Exception ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    @DynamicPropertySource
    static void endpoints(DynamicPropertyRegistry registry) {
        registry.add("mihoyo.redis.key-prefix", () -> REDIS_PREFIX);
        String endpoint = "http://127.0.0.1:" + UPSTREAM.getAddress().getPort();
        registry.add("mihoyo.passport-endpoint", () -> endpoint);
        registry.add("mihoyo.binding-endpoint", () -> endpoint);
    }

    @TestConfiguration
    static class TestBeans {
        @Bean
        @Primary
        RsaEncryptionUtil testRsa() {
            return new RsaEncryptionUtil(KEYS.getPublic());
        }
    }

    @Autowired

    MockMvc mvc;

    @Autowired

    org.springframework.data.redis.core.StringRedisTemplate redis;

    @Autowired

    com.niangao.mihoyo.modules.mihoyo.util.RedisLoginKeyFactory redisKeys;

    @AfterEach
    void upstreamAssertions() {
        assertTrue(ERRORS.isEmpty(), () -> "模拟上游断言失败: " + ERRORS);
    }

    @AfterAll
    void stopServer() {
        UPSTREAM.stop(0);
        // 只清理本类随机命名空间内的已知 Key，不执行 FLUSHDB 或全库扫描。
        List<String> cleanup = new ArrayList<>();
        cleanup.add(redisKeys.index());
        SESSION_IDS.forEach(id -> cleanup.add(redisKeys.session(id)));
        for (int i = 1; i <= 6; i++) cleanup.add(redisKeys.cooldown("1390000000" + i));
        redis.delete(cleanup);
    }

    ResultActions api(String path, String body) throws Exception {
        return mvc.perform(
                        post("/api/mihoyo/" + path).contentType("application/json").content(body))
                .andExpect(resultEnvelope());
    }

    org.springframework.test.web.servlet.ResultMatcher resultEnvelope() {
        return result -> {
            JsonObject body =
                    JsonParser.parseString(result.getResponse().getContentAsString())
                            .getAsJsonObject();
            assertEquals(3, body.size());
            if (body.has("data") && body.get("data").isJsonObject()) {
                JsonObject data = body.getAsJsonObject("data");
                if (data.has("session_id")) SESSION_IDS.add(data.get("session_id").getAsString());
            }
            assertEquals(result.getResponse().getStatus(), body.get("code").getAsInt());
            assertTrue(body.get("message").getAsJsonPrimitive().isString());
            assertTrue(body.has("data"));
        };
    }

    String start(String phone) throws Exception {
        String response =
                api("login/start", "{\"mobile\":\"" + phone + "\"}")
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.state").value("CREATED"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return JsonParser.parseString(response)
                .getAsJsonObject()
                .getAsJsonObject("data")
                .get("session_id")
                .getAsString();
    }

    String session(String id) {
        return "{\"session_id\":\"" + id + "\"}";
    }

    String code(String id, String captcha) {
        return "{\"session_id\":\"" + id + "\",\"captcha\":\"" + captcha + "\"}";
    }

    @Test
    void completeCredentialFlowAndAccountLookup() throws Exception {
        String id = start("13900000001");
        api("login/sms", session(id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("SMS_SENT"));
        int before = SMS_CALLS.get();
        api("login/sms", session(id)).andExpect(status().isConflict());
        assertEquals(before, SMS_CALLS.get());
        api("login/verify", code(id, "123456"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("LOGGED_IN"))
                .andExpect(jsonPath("$.data.credentials.stoken").value("mock-stoken"));
        api("login/status", session(id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.credentials").doesNotExist());
        api("game/accounts", "{\"stoken\":\"mock-stoken\",\"stuid\":\"123\",\"mid\":\"mock-mid\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(2))
                .andExpect(jsonPath("$.data.list[0].game_uid").value("100000001"))
                .andExpect(jsonPath("$.data.list[0].level").value(60))
                .andExpect(jsonPath("$.data.list[0].is_official").value(true))
                .andExpect(
                        content()
                                .string(
                                        org.hamcrest.Matchers.not(
                                                org.hamcrest.Matchers.containsString(
                                                        "HIDDEN_FIELD"))))
                .andExpect(
                        content()
                                .string(
                                        org.hamcrest.Matchers.not(
                                                org.hamcrest.Matchers.containsString(
                                                        "mock-ltoken"))));
        api("login/delete", session(id)).andExpect(status().isOk());
        api("login/status", session(id)).andExpect(status().isGone());
        String second = start("13900000001");
        api("login/sms", session(second)).andExpect(status().isTooManyRequests());
    }

    @Test
    void gt3PausesBothSmsAndLoginAndResumesWithCorrectRiskSession() throws Exception {
        String id = start("13900000002");
        api("login/sms", session(id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("SMS_CHALLENGE"));
        int before = SMS_CALLS.get();
        api("login/sms", session(id)).andExpect(status().isConflict());
        assertEquals(before, SMS_CALLS.get());
        String proof =
                "\"geetest_result\":{\"geetest_challenge\":\"c\",\"geetest_validate\":\"v\",\"geetest_seccode\":\"s\"}";
        api("login/sms", "{\"session_id\":\"" + id + "\"," + proof + "}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("SMS_SENT"));
        api("login/verify", code(id, "123456"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("LOGIN_CHALLENGE"))
                .andExpect(jsonPath("$.data.credentials").doesNotExist())
                .andExpect(jsonPath("$.data.pending_operation").value("login"));
        api("login/verify", "{\"session_id\":\"" + id + "\",\"captcha\":\"123456\"," + proof + "}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("LOGGED_IN"));
    }

    @Test
    void gt4ProofIsEncodedAndResumesSms() throws Exception {
        String id = start("13900000004");
        api("login/sms", session(id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.challenge.use_v4").value(true));
        api(
                        "login/sms",
                        "{\"session_id\":\""
                                + id
                                + "\",\"geetest_result\":{\"captcha_id\":\"id\",\"lot_number\":\"lot\",\"pass_token\":\"p\",\"gen_time\":\"1\",\"captcha_output\":\"o\"}}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("SMS_SENT"));
    }

    @Test
    void wrongCodeFailsSessionAndRedactsUpstreamMessage() throws Exception {
        String id = start("13900000003");
        api("login/sms", session(id)).andExpect(status().isOk());
        api("login/verify", code(id, "999999"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.data.upstream_retcode").value(-100))
                .andExpect(
                        content()
                                .string(
                                        org.hamcrest.Matchers.not(
                                                org.hamcrest.Matchers.containsString(
                                                        "SECRET_UPSTREAM_MESSAGE"))));
        api("login/status", session(id)).andExpect(jsonPath("$.data.state").value("FAILED"));
        api("login/verify", code(id, "123456")).andExpect(status().isConflict());
    }

    @Test
    void missingStokenDoesNotBecomeSuccessfulLogin() throws Exception {
        String id = start("13900000005");
        api("login/sms", session(id)).andExpect(status().isOk());
        api("login/verify", code(id, "123456"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.data.credentials").doesNotExist());
    }

    @Test
    void accountFilterEmptyAndExpiredCredentials() throws Exception {
        api("game/accounts", "{\"stoken\":\"s\",\"stuid\":\"123\",\"mid\":\"m\",\"game_biz\":\"hk4e_cn\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(1))
                .andExpect(jsonPath("$.data.list[0].region").value("cn_gf01"));
        api(
                        "game/accounts",
                        "{\"stoken\":\"s\",\"stuid\":\"123\",\"mid\":\"m\",\"game_biz\":\"hk4e_bilibili\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(1))
                .andExpect(jsonPath("$.data.list[0].region").value("cn_qd01"));
        api("game/accounts", "{\"stoken\":\"s\",\"stuid\":\"888\",\"mid\":\"m\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(0));
        api("game/accounts", "{\"stoken\":\"expired\",\"stuid\":\"123\",\"mid\":\"m\"}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.data.stage").value("stoken_to_ltoken"));
    }

    @Test
    void validationRejectsBadFieldsBeforeNetwork() throws Exception {
        int before = ACCOUNT_CALLS.get();
        api("game/accounts", "{\"stoken\":\"bad;injection\",\"stuid\":\"123\",\"mid\":\"m\"}")
                .andExpect(status().isBadRequest());
        api(
                        "game/accounts",
                        "{\"stoken\":\"s\",\"stuid\":\"123\",\"mid\":\"m\",\"game_biz\":\"hkrpg_cn\"}")
                .andExpect(status().isBadRequest());
        assertEquals(before, ACCOUNT_CALLS.get());
        api("login/start", "{\"mobile\":13800000000}").andExpect(status().isBadRequest());
        api("login/start", "{\"mobile\":\"13800000000\",\"area_code\":\"+1\"}")
                .andExpect(status().isBadRequest());
        api("login/start", "{\"mobile\":\"13800000000\",\"unexpected\":true}")
                .andExpect(status().isBadRequest());
        api("login/sms", "{}").andExpect(status().isBadRequest());
        api("login/status", session("unknown")).andExpect(status().isGone());
    }

    @Test
    void pathsMethodsContentTypesAndSizeLimits() throws Exception {
        mvc.perform(post("/api/login/start").contentType("application/json").content("{}"))
                .andExpect(resultEnvelope())
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/mihoyo/login/start"))
                .andExpect(resultEnvelope())
                .andExpect(status().isMethodNotAllowed());
        mvc.perform(post("/api/mihoyo/login/start").contentType("text/plain").content("{}"))
                .andExpect(resultEnvelope())
                .andExpect(status().isUnsupportedMediaType());
        api("login/start", "[]").andExpect(status().isBadRequest());
        api("login/start", "{\"mobile\":\"" + "1".repeat(17000) + "\"}")
                .andExpect(status().isPayloadTooLarge());
        api("login/start", "{\"mobile\":\"13900000006\"}")
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void errorResultKeepsNullDataAndContainerFallbackUsesSameContract() throws Exception {
        api("login/status", session("missing-result-test"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.message").value("Session expired or deleted"))
                .andExpect(
                        result ->
                                assertTrue(
                                        JsonParser.parseString(
                                                        result.getResponse().getContentAsString())
                                                .getAsJsonObject()
                                                .get("data")
                                                .isJsonNull()));
        mvc.perform(
                        get("/error")
                                .requestAttr(
                                        jakarta.servlet.RequestDispatcher.ERROR_STATUS_CODE, 404))
                .andExpect(resultEnvelope())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("接口不存在"));
    }
}
