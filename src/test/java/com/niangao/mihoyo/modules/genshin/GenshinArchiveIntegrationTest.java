package com.niangao.mihoyo.modules.genshin;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.*;
import com.niangao.mihoyo.NiangaoApplication;
import com.niangao.mihoyo.modules.genshin.service.GenshinDeviceService;
import com.niangao.mihoyo.modules.workbench.mapper.WorkbenchSessionMapper;
import com.niangao.mihoyo.modules.workbench.service.WorkbenchSessionService;
import org.springframework.data.redis.core.StringRedisTemplate;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** 真实 Spring HTTP + SDK + Redis + 本地模拟米游社，不访问真实账号，不解真实验证码。 */
@SpringBootTest(classes = NiangaoApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"mihoyo.redis.phone-key-secret=archive-integration-test-secret",
                "workbench.session.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
@ExtendWith(OutputCaptureExtension.class)
class GenshinArchiveIntegrationTest {
    static final HttpServer UPSTREAM;
    static final Queue<Throwable> ERRORS = new ConcurrentLinkedQueue<>();
    static final AtomicInteger RECORD_CALLS = new AtomicInteger();
    static final AtomicInteger FP_CALLS = new AtomicInteger();
    static final AtomicInteger CREATE_CALLS = new AtomicInteger(), VERIFY_CALLS = new AtomicInteger();
    static final String PREFIX = "niangao:test:archive:" + UUID.randomUUID();
    static final Set<String> RECORD_DEVICES = ConcurrentHashMap.newKeySet();
    static final Set<String> ALL_DEVICES = ConcurrentHashMap.newKeySet();
    static volatile String mode = "normal";
    static volatile String recordRegion = "cn_gf01", recordUid = "123456789";
    static final String BODY = """
            {"stoken":"fixture-stoken","stuid":"123","mid":"fixture-mid",
            "game_uid":"123456789","game_biz":"hk4e_cn","region":"cn_gf01"}
            """;
    static {
        try {
            UPSTREAM = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            UPSTREAM.createContext("/", exchange -> {
                String data = "null";
                int retcode = 0;
                try {
                    String path = exchange.getRequestURI().getPath();
                    String cookie = exchange.getRequestHeaders().getFirst("Cookie");
                    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    String deviceHeader = exchange.getRequestHeaders().getFirst("x-rpc-device_id");
                    if (deviceHeader != null) ALL_DEVICES.add(deviceHeader);
                    if (path.equals("/device-fp/api/getFp")) {
                        FP_CALLS.incrementAndGet();
                        assertNull(cookie, "设备指纹申请不得发送用户 Cookie");
                        var payload = JsonParser.parseString(body).getAsJsonObject();
                        ALL_DEVICES.add(payload.get("device_id").getAsString());
                        assertEquals("4", payload.get("platform").getAsString());
                        assertTrue(JsonParser.parseString(payload.get("ext_fields").getAsString()).getAsJsonObject().has("userAgent"));
                        assertFalse(body.contains("fixture-stoken")); assertFalse(body.contains("fixture-mid"));
                        data = mode.equals("fp_failure") ? "{\"code\":403,\"device_fp\":\"\"}" : "{\"code\":200,\"device_fp\":\"fixture-device-fp\"}";
                    } else if (path.equals("/account/ma-cn-verifier/verifier/createLoginCaptcha")) {
                        data = "{\"action_type\":\"login\",\"sent_new\":true,\"countdown\":60}";
                    } else if (path.equals("/account/ma-cn-passport/app/loginByMobileCaptcha")) {
                        data = "{\"stoken\":\"fixture-stoken\",\"user_info\":{\"aid\":\"123\",\"mid\":\"fixture-mid\"}}";
                    } else if (path.equals("/account/auth/api/getLTokenBySToken")) {
                        assertTrue(cookie.contains("stoken=fixture-stoken"));
                        data = "{\"ltoken\":\"fixture-ltoken\"}";
                    } else if (path.equals("/binding/api/getUserGameRolesByCookie")) {
                        assertTrue(cookie.contains("ltoken_v2=fixture-ltoken"));
                        data = "{\"list\":[{\"game_biz\":\"hk4e_cn\",\"game_uid\":\"123456789\",\"region\":\"cn_gf01\"},{\"game_biz\":\"hk4e_cn\",\"game_uid\":\"506005588\",\"region\":\"cn_qd01\"}]}";
                    } else {
                        boolean verification = path.contains("/card/wapi/");
                        if (!verification) RECORD_CALLS.incrementAndGet();
                        if (exchange.getRequestMethod().equals("POST")) {
                            assertEquals(String.valueOf(body.getBytes(StandardCharsets.UTF_8).length),
                                    exchange.getRequestHeaders().getFirst("Content-Length"), "JDK must send the actual body length");
                        }
                        assertTrue(cookie.contains("ltoken_v2=fixture-ltoken"));
                        assertFalse(cookie.contains("stoken="));
                        String[] ds = exchange.getRequestHeaders().getFirst("DS").split(",");
                        assertEquals(3, ds.length);
                        String query = exchange.getRequestURI().getRawQuery();
                        String canonical = query == null ? "" : String.join("&", Arrays.stream(query.split("&")).sorted().toList());
                        String signed = verification
                                ? "salt=h8w582wxwgqvahcdkpvdhbh2w9casgfl&t=" + ds[0] + "&r=" + ds[1]
                                : "salt=xV8v4Qu54lUKrEYFZkJhB8cuOh9Asafs&t=" + ds[0] + "&r=" + ds[1] + "&b=" + body + "&q=" + canonical;
                        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(signed.getBytes(StandardCharsets.UTF_8))), ds[2]);
                        assertEquals(verification ? "2.3.0" : "2.11.1", exchange.getRequestHeaders().getFirst("x-rpc-app_version"));
                        assertEquals("fixture-device-fp", exchange.getRequestHeaders().getFirst("x-rpc-device_fp"));
                        assertEquals("5", exchange.getRequestHeaders().getFirst("x-rpc-client_type"));
                        assertEquals("https://webstatic.mihoyo.com", exchange.getRequestHeaders().getFirst("Origin"));
                        assertEquals("https://webstatic.mihoyo.com/", exchange.getRequestHeaders().getFirst("Referer"));
                        assertEquals("com.mihoyo.hyperion", exchange.getRequestHeaders().getFirst("X-Requested-With"));
                        assertTrue(exchange.getRequestHeaders().getFirst("User-Agent").contains("Chrome/"));
                        RECORD_DEVICES.add(exchange.getRequestHeaders().getFirst("x-rpc-device_id"));
                        exchange.getResponseHeaders().set("x-trace-id", "fixture-record-trace");
                        if (verification) {
                            assertTrue(path.startsWith("/game_record/card/wapi/"), "网页验证不能复用 App 战绩路径");
                            assertTrue(ds[1].matches("[a-z0-9]{6}"));
                            assertEquals("2", exchange.getRequestHeaders().getFirst("x-rpc-challenge_game"));
                            assertEquals("/genshin/api/index", exchange.getRequestHeaders().getFirst("x-rpc-challenge_path"));
                            assertEquals("fixture-record-trace", exchange.getRequestHeaders().getFirst("x-rpc-challenge_trace"));
                        }
                        if (mode.equals("private")) retcode = 10102;
                        else if (mode.equals("risk")) retcode = 1034;
                        else if (mode.equals("risk5003")) retcode = 5003;
                        else if (mode.startsWith("verification") && !verification
                                && !"fixture-approved".equals(exchange.getRequestHeaders().getFirst("x-rpc-challenge"))) retcode = 1034;
                        else if (mode.equals("http")) {
                            exchange.sendResponseHeaders(503, -1); exchange.close(); return;
                        }
                        if (path.endsWith("/createVerification")) {
                            CREATE_CALLS.incrementAndGet(); assertEquals("GET", exchange.getRequestMethod());
                            assertFalse(cookie.contains("verification_session="), "其他任务的会话 Cookie 不得进入新任务");
                            exchange.getResponseHeaders().add("Set-Cookie", "verification_session=fixture-task-session; Path=/game_record/card/wapi; HttpOnly");
                            assertEquals("is_high=true", query);
                            data = "{\"gt\":\"fixture-gt\",\"challenge\":\"fixture-challenge\",\"new_captcha\":1,\"success\":1}";
                        } else if (path.endsWith("/verifyVerification")) {
                            VERIFY_CALLS.incrementAndGet(); assertEquals("POST", exchange.getRequestMethod());
                            assertTrue(cookie.contains("verification_session=fixture-task-session"), "必须携带创建任务时上游设置的 Cookie");
                            var proof = JsonParser.parseString(body).getAsJsonObject();
                            assertEquals(Set.of("geetest_challenge", "geetest_validate", "geetest_seccode"), proof.keySet());
                            assertEquals("fixture-validate", proof.get("geetest_validate").getAsString());
                            data = "{\"challenge\":\"fixture-approved\"}";
                            if (mode.equals("verification_reject")) retcode = 10306;
                        } else if (path.endsWith("/index")) {
                            assertEquals("GET", exchange.getRequestMethod());
                            assertTrue(exchange.getRequestURI().getQuery().contains("role_id=" + recordUid));
                            assertTrue(exchange.getRequestURI().getQuery().contains("server=" + recordRegion));
                            data = mode.equals("missing") ? "{}" : """
                                    {"role":{"nickname":"示例旅行者","level":60},
                                     "stats":{"achievement_number":1572,"active_day_number":983,"spiral_abyss":"12-3",
                                     "role_combat":{"max_round_id":10,"is_unlock":true,"has_data":true},
                                     "avatar_number":89,"luxurious_chest_number":222},
                                     "world_explorations":[{"name":"蒙德","exploration_percentage":1000,"level":8}],
                                     "stoken":"upstream-secret-must-not-return"}
                                    """;
                        } else if (path.endsWith("/character/list") || path.endsWith("/character/detail")) {
                            assertEquals("POST", exchange.getRequestMethod());
                            JsonObject payload = JsonParser.parseString(body).getAsJsonObject();
                            assertEquals(recordUid, payload.get("role_id").getAsString());
                            assertEquals(recordRegion, payload.get("server").getAsString());
                            boolean detail = path.endsWith("/detail");
                            if (detail) assertTrue(payload.getAsJsonArray("character_ids").get(0).getAsJsonPrimitive().isNumber());
                            data = mode.equals("empty") ? "{\"list\":[]}" : detail ? GenshinArchiveIntegrationTest.DETAIL : GenshinArchiveIntegrationTest.LIST;
                        } else throw new AssertionError("Unexpected path: " + path);
                    }
                } catch (Throwable failure) { ERRORS.add(failure); retcode = -999; }
                byte[] bytes = ("{\"retcode\":" + retcode + ",\"message\":\"fixture-ltoken\",\"data\":" + data + "}").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
            });
            UPSTREAM.start();
        } catch (Exception ex) { throw new ExceptionInInitializerError(ex); }
    }
    static final String LIST = """
            {"list":[{"id":10000089,"name":"芙宁娜","icon":"https://upload-bbs.mihoyo.com/icon.png",
            "element":"Hydro","rarity":5,"level":90,"actived_constellation_num":2,"fetter":10,
            "weapon":{"id":11513,"name":"静水流涌之辉","level":90,"rarity":5,"affix_level":1}}]}
            """;
    static final String DETAIL = """
            {"property_map":{"2000":{"name":"生命值"},"20":{"name":"暴击率"}},"list":[{
             "base":{"id":10000089,"name":"芙宁娜","level":90,"rarity":5,"element":"Hydro","actived_constellation_num":2,"fetter":10},
             "weapon":{"id":11513,"name":"静水流涌之辉","level":90,"rarity":5,"affix_level":1},
             "skills":[{"skill_id":10891,"name":"独舞之邀","skill_type":1,"level":10}],
             "selected_properties":[{"property_type":2000,"final":"37466"},{"property_type":20,"final":"69.0%"}],
             "relics":[{"id":1,"name":"黄金乐曲的变奏","pos":1,"pos_name":"生之花","rarity":5,"level":20,
               "icon":"javascript:alert(1)","set":{"name":"黄金剧团"},
               "main_property":{"property_type":2000,"value":"4780"},
               "sub_property_list":[{"property_type":20,"value":"10.5%"}]}],
             "stoken":"upstream-secret-must-not-return"}]}
            """;
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String url = "http://127.0.0.1:" + UPSTREAM.getAddress().getPort();
        registry.add("mihoyo.passport-endpoint", () -> url);
        registry.add("mihoyo.binding-endpoint", () -> url);
        registry.add("genshin.record-endpoint", () -> url);
        registry.add("genshin.device-fp-endpoint", () -> url);
        registry.add("mihoyo.redis.key-prefix", () -> PREFIX);
        registry.add("workbench.session.key-prefix", () -> PREFIX + ":workbench");
    }
    @LocalServerPort int port;
    @Autowired ObjectMapper mapper;
    @Autowired StringRedisTemplate redis;
    @Autowired GenshinDeviceService devices;
    @Autowired WorkbenchSessionMapper sessions;
    @Autowired com.niangao.mihoyo.modules.mihoyo.service.MihoyoLoginService logins;
    final Set<String> sessionDigests = new HashSet<>();
    String cookie, csrf;
    static final String SELECTOR = "{\"game_uid\":\"123456789\",\"game_biz\":\"hk4e_cn\",\"region\":\"cn_gf01\"}";
    final HttpClient http = HttpClient.newHttpClient();
    @BeforeEach void reset() { mode = "normal"; recordRegion = "cn_gf01"; recordUid = "123456789"; ERRORS.clear(); RECORD_CALLS.set(0); FP_CALLS.set(0); CREATE_CALLS.set(0); VERIFY_CALLS.set(0); RECORD_DEVICES.clear(); ALL_DEVICES.clear(); }
    @AfterEach void checkUpstream() {
        try { assertTrue(ERRORS.isEmpty(), () -> "Upstream fixture assertions: " + ERRORS); }
        finally { redis.delete(PREFIX + ":genshin:fp:" + devices.deviceId("123", "fixture-mid")); sessionDigests.forEach(sessions::delete); }
    }
    @AfterAll static void stop() { UPSTREAM.stop(0); }

    HttpResponse<String> post(String path, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/genshin/" + path))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    JsonNode success(String path, String body) throws Exception {
        var response = post(path, body); assertEquals(200, response.statusCode(), response.body());
        assertFalse(response.body().contains("fixture-ltoken"));
        assertFalse(response.body().contains("fixture-stoken"));
        assertFalse(response.body().contains("upstream-secret"));
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElse(""));
        return mapper.readTree(response.body()).path("data");
    }
    HttpResponse<String> workbench(String path, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/workbench" + path))
                .header("Origin", "http://127.0.0.1:5173");
        if (cookie != null) request.header("Cookie", cookie);
        if (csrf != null) request.header("X-Workspace-CSRF", csrf);
        if (body == null) request.GET();
        else request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
    void login() throws Exception {
        var response = workbench("/session", "{\"stoken\":\"fixture-stoken\",\"stuid\":\"123\",\"mid\":\"fixture-mid\"}");
        assertEquals(200, response.statusCode(), response.body());
        cookie = response.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
        csrf = mapper.readTree(response.body()).path("data").path("csrf_token").asText();
        sessionDigests.add(WorkbenchSessionService.digest(cookie.substring(cookie.indexOf('=') + 1)));
    }
    String startTask() throws Exception {
        return startTask(SELECTOR);
    }
    String startTask(String selector) throws Exception {
        mode = "verification";
        assertEquals(422, workbench("/genshin/overview", selector).statusCode());
        var response = workbench("/genshin/verification/start", selector);
        assertEquals(200, response.statusCode(), response.body());
        assertFalse(response.body().contains("fixture-ltoken")); assertFalse(response.body().contains("fixture-device-fp"));
        var data = mapper.readTree(response.body()).path("data");
        assertEquals("fixture-challenge", data.path("challenge").path("challenge").asText());
        return data.path("verification_id").asText();
    }
    String proof(String id, String challenge) {
        return "{\"verification_id\":\"" + id + "\",\"geetest_challenge\":\"" + challenge
                + "\",\"geetest_validate\":\"fixture-validate\",\"geetest_seccode\":\"fixture-seccode\"}";
    }
    @Test void smsLoginRestoreArchiveAndManualVerificationKeepExactlyTheSameDevice() throws Exception {
        var flow = logins.start(new com.niangao.mihoyo.modules.mihoyo.dto.StartLoginRequest("13800000000", "+86"));
        try {
            logins.sendSms(new com.niangao.mihoyo.modules.mihoyo.dto.SendSmsRequest(flow.sessionId(), null));
            var result = logins.verify(new com.niangao.mihoyo.modules.mihoyo.dto.VerifyLoginRequest(flow.sessionId(), "123456", null));
            assertEquals("fixture-stoken", result.credentials().stoken());
            assertEquals(1, ALL_DEVICES.size());
            String originalDevice = ALL_DEVICES.iterator().next();
            assertEquals(53, originalDevice.length());
            login();
            // 在进入战绩前模拟浏览器刷新与重新查询账号，仍只能沿用同一设备。
            var restored = workbench("/session", null);
            assertEquals(200, restored.statusCode());
            assertFalse(restored.body().contains(originalDevice));
            assertEquals(200, workbench("/game/accounts", "{}").statusCode());
            String task = startTask();
            assertEquals(200, workbench("/genshin/verification/submit", proof(task, "fixture-challenge")).statusCode());
            assertEquals(200, workbench("/genshin/overview", SELECTOR).statusCode());
            assertEquals(Set.of(originalDevice), ALL_DEVICES,
                    "短信、登录、LToken、角色、指纹、任务创建/提交和放行查询必须使用原设备");
            assertEquals(Set.of(originalDevice), RECORD_DEVICES);
        } finally {
            logins.delete(new com.niangao.mihoyo.modules.mihoyo.dto.SessionRequest(flow.sessionId()));
            // 仅清理本测试随机命名空间内的短期设备交接记录与指纹。
            var handoffs = redis.keys(PREFIX + ":login-device:*");
            if (handoffs != null && !handoffs.isEmpty()) redis.delete(handoffs);
            for (String device : ALL_DEVICES) redis.delete(PREFIX + ":genshin:fp:" + device);
        }
    }
    @Test void manualVerificationHttpRoundTripRetriesArchiveAndCannotReplay() throws Exception {
        login(); String id = startTask();
        String digest = WorkbenchSessionService.digest(cookie.substring(cookie.indexOf('=') + 1));
        String key = PREFIX + ":workbench:session:" + digest + ":verification";
        String encryptedTask = (String) redis.opsForHash().get(key, "task");
        assertNotNull(encryptedTask); assertFalse(encryptedTask.contains("fixture-ltoken"));
        assertEquals(200, workbench("/genshin/verification/submit", proof(id, "fixture-challenge")).statusCode());
        assertNull(redis.opsForHash().get(key, "task"));
        assertFalse(redis.opsForHash().get(key, "proof").toString().contains("fixture-approved"));
        var response = workbench("/genshin/overview", SELECTOR);
        assertEquals(200, response.statusCode(), response.body());
        assertEquals(1572, mapper.readTree(response.body()).path("data").path("achievements").asInt());
        assertFalse(response.body().contains("fixture-approved"));
        assertEquals(200, workbench("/genshin/characters", SELECTOR).statusCode());
        assertEquals(200, workbench("/genshin/characters/details", SELECTOR.replace("}", ",\"character_ids\":[\"10000089\"]}")).statusCode());
        assertEquals(410, workbench("/genshin/verification/submit", proof(id, "fixture-challenge")).statusCode());
        assertEquals(1, CREATE_CALLS.get()); assertEquals(1, VERIFY_CALLS.get()); assertEquals(1, RECORD_DEVICES.size());
        assertEquals(200, workbench("/session", null).statusCode());
    }
    @Test void verificationRequiresRiskMatchingRoleAndCsrf() throws Exception {
        login(); assertEquals(409, workbench("/genshin/verification/start", SELECTOR).statusCode());
        mode = "verification"; assertEquals(422, workbench("/genshin/overview", SELECTOR).statusCode());
        assertEquals(409, workbench("/genshin/verification/start", SELECTOR.replace("123456789", "987654321")).statusCode());
        csrf = null; assertEquals(403, workbench("/genshin/verification/start", SELECTOR).statusCode());
        assertEquals(0, CREATE_CALLS.get());
    }
    @Test void verificationCannotBeSubmittedByAnotherSessionOrAfterLogout() throws Exception {
        login(); String id = startTask(), oldCookie = cookie, oldCsrf = csrf;
        cookie = null; csrf = null; login();
        assertEquals(410, workbench("/genshin/verification/submit", proof(id, "fixture-challenge")).statusCode());
        cookie = oldCookie; csrf = oldCsrf;
        assertEquals(200, workbench("/session/logout", "{}").statusCode());
        assertEquals(401, workbench("/genshin/verification/submit", proof(id, "fixture-challenge")).statusCode());
        assertEquals(0, VERIFY_CALLS.get());
    }
    @Test void mismatchedCaptchaConsumesTaskWithoutCallingUpstream() throws Exception {
        login(); String id = startTask();
        assertEquals(403, workbench("/genshin/verification/submit", proof(id, "other-challenge")).statusCode());
        assertEquals(410, workbench("/genshin/verification/submit", proof(id, "fixture-challenge")).statusCode());
        assertEquals(0, VERIFY_CALLS.get()); assertEquals(200, workbench("/session", null).statusCode());
    }
    @Test void rejectedCaptchaDoesNotRetryOrRevokeLogin() throws Exception {
        login(); String id = startTask(); mode = "verification_reject";
        var rejected = workbench("/genshin/verification/submit", proof(id, "fixture-challenge"));
        assertEquals(422, rejected.statusCode());
        assertTrue(rejected.body().contains("10306"));
        assertEquals(422, workbench("/genshin/overview", SELECTOR).statusCode(), "拒绝后不得写入放行凭证");
        assertEquals(410, workbench("/genshin/verification/submit", proof(id, "fixture-challenge")).statusCode());
        assertEquals(429, workbench("/genshin/verification/start", SELECTOR).statusCode());
        assertEquals(1, VERIFY_CALLS.get()); assertEquals(200, workbench("/session", null).statusCode());
    }
    @Test void overviewHasFourPrimaryMetricsAndOptionalExploration() throws Exception {
        var data = success("overview", BODY);
        assertEquals("123456789", data.path("uid").asText());
        assertEquals(1572, data.path("achievements").asInt()); assertEquals(983, data.path("active_days").asInt());
        assertEquals("12-3", data.path("spiral_abyss").asText()); assertEquals(10, data.path("theater").path("max_act").asInt());
        assertEquals(100.0, data.path("explorations").get(0).path("percentage").asDouble());
    }
    @Test void characterEquipmentAndPropertyNamesAreMappedWithoutInventedScores() throws Exception {
        var list = success("characters", BODY); assertEquals(1, list.path("count").asInt());
        assertEquals("10000089", list.path("list").get(0).path("id").asText());
        var data = success("characters/details", BODY.strip().replace("}", ",\"character_ids\":[\"10000089\"]}"));
        var character = data.path("list").get(0);
        assertEquals("生命值", character.path("properties").get(0).path("name").asText());
        assertEquals("37466", character.path("properties").get(0).path("value").asText());
        assertEquals(10, character.path("skills").get(0).path("level").asInt());
        assertFalse(character.path("artifacts").get(0).has("icon")); assertFalse(character.has("score"));
    }
    @Test void ownershipAndChannelAreCheckedBeforeRecordRequests() throws Exception {
        assertEquals(403, post("overview", BODY.replace("123456789", "987654321")).statusCode());
        assertEquals(403, post("overview", BODY.replace("cn_gf01", "cn_qd01")).statusCode());
        assertEquals(400, post("overview", BODY.replace("hk4e_cn", "hk4e_bilibili")).statusCode());
        assertEquals(0, RECORD_CALLS.get());
    }
    @Test void privateAndRiskResponsesExposeOnlyRedactedStageAndCode() throws Exception {
        for (String value : List.of("private", "risk")) {
            mode = value; var response = post("overview", BODY); assertEquals(422, response.statusCode());
            assertTrue(response.body().contains("genshin_overview")); assertFalse(response.body().contains("fixture-ltoken"));
        }
    }
    @Test void emptyCharactersAreValidButMissingOverviewIsNotZero() throws Exception {
        mode = "empty"; assertEquals(0, success("characters", BODY).path("count").asInt());
        mode = "missing"; assertEquals(502, post("overview", BODY).statusCode());
    }
    @Test void malformedInputsAndOversizedDetailBatchesAreRejected() throws Exception {
        assertEquals(400, post("characters/details", BODY).statusCode());
        assertEquals(400, post("overview", BODY.replace("\"123456789\"", "123456789")).statusCode());
        String ids = String.join(",", Collections.nCopies(41, "\"10000089\""));
        assertEquals(400, post("characters/details", BODY.strip().replace("}", ",\"character_ids\":[" + ids + "]}")).statusCode());
        assertEquals(0, RECORD_CALLS.get());
    }
    @Test void httpFailuresDoNotAutomaticallyRetry() throws Exception {
        mode = "http"; assertEquals(502, post("overview", BODY).statusCode()); assertEquals(1, RECORD_CALLS.get());
    }
    @Test void sameAccountReusesDeviceAndFingerprintAcrossArchiveRequests() throws Exception {
        success("overview", BODY); success("characters", BODY); success("overview", BODY);
        assertEquals(1, FP_CALLS.get()); assertEquals(1, RECORD_DEVICES.size());
        assertTrue(RECORD_DEVICES.contains(devices.deviceId("123", "fixture-mid")));
    }
    @Test void risk5003PreservesItsMeaningWithoutLeakingUpstreamResponse() throws Exception {
        mode = "risk5003";
        var response = post("overview", BODY); assertEquals(422, response.statusCode());
        var result = mapper.readTree(response.body());
        assertEquals(5003, result.path("data").path("upstream_retcode").asInt());
        // 官方网页把 5003 视为账号风险并直接提示，只有 1034 才会弹极验，因此这里不能再标记为需要验证。
        assertEquals("upstream_account_risk", result.path("data").path("reason").asText());
        assertTrue(result.path("message").asText().contains("账号存在风险"));
        assertFalse(response.body().contains("fixture-ltoken")); assertEquals(1, RECORD_CALLS.get());
    }
    @Test void risk1034StillAsksForVerification() throws Exception {
        mode = "risk";
        var response = post("overview", BODY); assertEquals(422, response.statusCode());
        var result = mapper.readTree(response.body());
        assertEquals(1034, result.path("data").path("upstream_retcode").asInt());
        assertEquals("upstream_verification_required", result.path("data").path("reason").asText());
    }
    @Test void failedFingerprintNeverFallsBackToInventedFingerprintOrCallsRecord() throws Exception {
        mode = "fp_failure"; var response = post("overview", BODY);
        assertEquals(502, response.statusCode()); assertTrue(response.body().contains("genshin_device_fp"));
        assertEquals(1, FP_CALLS.get()); assertEquals(0, RECORD_CALLS.get());
    }
    @Test void recordDiagnosticsShowActualStageAndCodeWithoutCredentials(CapturedOutput output) throws Exception {
        mode = "risk5003"; assertEquals(422, post("overview", BODY).statusCode());
        mode = "normal"; success("overview", BODY);
        assertTrue(output.getAll().contains("stage=genshin_overview, http_status=200, upstream_retcode=5003"));
        assertTrue(output.getAll().contains("stage=genshin_overview, http_status=200, upstream_retcode=0"));
        assertFalse(output.getAll().contains("fixture-stoken"));
        assertFalse(output.getAll().contains("fixture-ltoken"));
        assertFalse(output.getAll().contains("fixture-mid"));
        assertFalse(output.getAll().contains("fixture-device-fp"));
    }
    @Test void originalAccountLookupStillReturnsTheSameResultContract() throws Exception {
        var response = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/mihoyo/game/accounts"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(
                        "{\"stoken\":\"fixture-stoken\",\"stuid\":\"123\",\"mid\":\"fixture-mid\"}")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode()); assertEquals(2, mapper.readTree(response.body()).path("data").path("count").asInt());
        assertFalse(response.body().contains("fixture-ltoken")); assertEquals(0, RECORD_CALLS.get());
    }

    @Test void bilibiliUsesDomesticGameBizAndChannelRegionForEveryRecordEndpoint() throws Exception {
        recordUid = "506005588"; recordRegion = "cn_qd01";
        String body = BODY.replace("123456789", recordUid).replace("cn_gf01", recordRegion);
        assertEquals(recordUid, success("overview", body).path("uid").asText());
        success("characters", body);
        success("characters/details", body.strip().replace("}", ",\"character_ids\":[\"10000089\"]}"));
        success("overview", body.replace("hk4e_cn", "hk4e_bilibili"));
        assertEquals(4, RECORD_CALLS.get());
    }

    @Test void bilibiliVerificationRoundTripRetainsCorrectUidAndRegion() throws Exception {
        recordUid = "506005588"; recordRegion = "cn_qd01";
        login(); String selector = SELECTOR.replace("123456789", recordUid).replace("cn_gf01", recordRegion);
        String id = startTask(selector);
        assertEquals(200, workbench("/genshin/verification/submit", proof(id, "fixture-challenge")).statusCode());
        var response = workbench("/genshin/overview", selector);
        assertEquals(200, response.statusCode(), response.body());
        assertEquals(recordUid, mapper.readTree(response.body()).path("data").path("uid").asText());
        assertEquals(200, workbench("/genshin/characters", selector).statusCode());
        assertEquals(200, workbench("/genshin/characters/details", selector.replace("}", ",\"character_ids\":[\"10000089\"]}")).statusCode());
    }

    @Test void internationalSelectorsAreRejectedBeforeAnyUpstreamTraffic() throws Exception {
        for (String region : List.of("os_asia", "os_euro", "os_usa", "os_cht")) {
            assertEquals(400, post("overview", BODY.replace("cn_gf01", region)).statusCode());
        }
        assertEquals(400, post("overview", BODY.replace("hk4e_cn", "hk4e_global")).statusCode());
        assertEquals(0, RECORD_CALLS.get()); assertEquals(0, FP_CALLS.get());
    }
}
