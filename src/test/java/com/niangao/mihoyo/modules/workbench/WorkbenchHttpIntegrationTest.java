package com.niangao.mihoyo.modules.workbench;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.niangao.mihoyo.NiangaoApplication;
import com.niangao.mihoyo.common.exception.BusinessException;
import com.niangao.mihoyo.modules.genshin.service.GenshinArchiveService;
import com.niangao.mihoyo.modules.mihoyo.service.MihoyoGameAccountService;
import com.niangao.mihoyo.modules.mihoyo.vo.GameAccountsResponse;
import com.niangao.mihoyo.modules.workbench.mapper.WorkbenchSessionMapper;
import com.niangao.mihoyo.modules.workbench.service.WorkbenchSessionService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 真实 Spring HTTP/Cookie/CSRF/加解密/Redis 链路；仅上游服务使用替身，不访问真实账号。 */
@SpringBootTest(classes = NiangaoApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"mihoyo.redis.phone-key-secret=workbench-test-only",
                "workbench.session.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
                "workbench.session.cookie-secure=true"})
class WorkbenchHttpIntegrationTest {
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired WorkbenchSessionMapper mapper;
    @MockBean MihoyoGameAccountService accounts;
    @MockBean GenshinArchiveService archive;
    String cookie, csrf;
    final Set<String> digests = new HashSet<>();
    static final String PREFIX = "niangao:test:workbench:http:" + UUID.randomUUID();
    static final String INPUT = "{\"stoken\":\"fixture-stoken\",\"stuid\":\"123456\",\"mid\":\"fixture-mid\"}";
    static final String SELECTOR = "{\"game_uid\":\"123456789\",\"region\":\"cn_gf01\",\"game_biz\":\"hk4e_cn\"}";

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("workbench.session.key-prefix", () -> PREFIX);
    }
    @AfterEach void cleanup() { digests.forEach(mapper::delete); }
    @BeforeEach void setup() { when(accounts.queryAccounts(any())).thenReturn(new GameAccountsResponse("test", 0, List.of())); }
    HttpResponse<String> send(String method, String path, String body, String... headers) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/workbench" + path));
        request.header("Origin", "http://127.0.0.1:5173");
        if (cookie != null) request.header("Cookie", cookie);
        if (csrf != null) request.header("X-Workspace-CSRF", csrf);
        for (int i = 0; i < headers.length; i += 2) request.setHeader(headers[i], headers[i + 1]);
        if (body == null) request.GET();
        else request.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
    JsonNode create() throws Exception {
        var response = send("POST", "/session", INPUT);
        assertEquals(200, response.statusCode());
        String setCookie = response.headers().firstValue("Set-Cookie").orElseThrow();
        assertTrue(setCookie.contains("HttpOnly")); assertTrue(setCookie.contains("SameSite=Lax"));
        assertTrue(setCookie.contains("Secure")); assertTrue(setCookie.contains("Path=/api"));
        assertTrue(setCookie.contains("Max-Age=2592000")); assertFalse(setCookie.contains("Domain="));
        cookie = setCookie.split(";", 2)[0];
        digests.add(WorkbenchSessionService.digest(cookie.substring(cookie.indexOf('=') + 1)));
        var data = json.readTree(response.body()).path("data"); csrf = data.path("csrf_token").asText();
        assertFalse(response.body().contains("fixture-stoken")); assertFalse(response.body().contains("fixture-mid"));
        assertFalse(response.body().contains(cookie.substring(cookie.indexOf('=') + 1)));
        return data;
    }

    @Test void createRestoreRenewAndLogoutThroughHttp() throws Exception {
        var created = create();
        var restored = send("GET", "/session", null);
        assertEquals(200, restored.statusCode());
        assertEquals("no-store", restored.headers().firstValue("Cache-Control").orElseThrow());
        var restoredData = json.readTree(restored.body()).path("data");
        assertEquals(created.path("csrf_token"), restoredData.path("csrf_token"));
        assertEquals(created.path("absolute_expires_at"), restoredData.path("absolute_expires_at"));
        assertTrue(java.time.Instant.parse(restoredData.path("expires_at").asText()).compareTo(
                java.time.Instant.parse(created.path("expires_at").asText())) >= 0);
        assertEquals(200, send("POST", "/session/renew", "{}").statusCode());
        var logout = send("POST", "/session/logout", "{}");
        assertEquals(200, logout.statusCode()); assertTrue(logout.headers().firstValue("Set-Cookie").orElseThrow().contains("Max-Age=0"));
        assertEquals(401, send("GET", "/session", null).statusCode());
        assertEquals(401, send("POST", "/session/renew", "{}").statusCode());
    }

    @Test void csrfAndForeignOriginsCannotQueryOrRenew() throws Exception {
        create();
        csrf = null;
        assertEquals(403, send("POST", "/game/accounts", "{}").statusCode());
        assertEquals(403, send("POST", "/session/logout", "{}").statusCode());
        assertEquals(403, send("POST", "/session", INPUT, "Origin", "https://foreign.example").statusCode());
        assertEquals(403, send("GET", "/session", null, "Sec-Fetch-Site", "cross-site").statusCode());
        assertEquals(200, send("GET", "/session", null).statusCode());
    }

    @Test void browserDataRoutesUseServerCredentialsAndRejectCredentialInjection() throws Exception {
        create(); clearInvocations(accounts);
        assertEquals(200, send("POST", "/game/accounts", "{\"game_biz\":\"hk4e_cn\"}").statusCode());
        verify(accounts).queryAccounts(argThat(request -> request.stoken().equals("fixture-stoken") && request.stuid().equals("123456") && request.gameBiz().equals("hk4e_cn")));
        assertEquals(200, send("POST", "/genshin/overview", SELECTOR).statusCode());
        assertEquals(200, send("POST", "/genshin/characters", SELECTOR).statusCode());
        assertEquals(200, send("POST", "/genshin/characters/details", SELECTOR.replace("}", ",\"character_ids\":[\"10000089\"]}")).statusCode());
        verify(archive).overview(argThat(request -> request.stoken().equals("fixture-stoken") && request.gameUid().equals("123456789")), isNull());
        verify(archive).characters(argThat(request -> request.stoken().equals("fixture-stoken")), eq(false), isNull());
        verify(archive).characters(argThat(request -> List.of("10000089").equals(request.characterIds())), eq(true), isNull());
        assertEquals(400, send("POST", "/genshin/overview", SELECTOR.replace("}", ",\"stoken\":\"injected\"}")).statusCode());
        assertEquals(400, send("POST", "/game/accounts", "{\"game_biz\":\"other_game\"}").statusCode());
    }

    @Test void transientFailurePreservesSessionButExplicitStokenExpiryRevokes() throws Exception {
        create();
        when(accounts.queryAccounts(any())).thenThrow(new BusinessException(502, "上游暂不可用"));
        var unavailable = send("POST", "/game/accounts", "{}");
        assertEquals(502, unavailable.statusCode()); assertTrue(unavailable.headers().firstValue("Set-Cookie").isEmpty());
        assertEquals(200, send("GET", "/session", null).statusCode());
        doThrow(new BusinessException(422, "expired", Map.of("stage", "stoken_to_ltoken", "upstream_retcode", -101))).when(accounts).queryAccounts(any());
        var expired = send("POST", "/game/accounts", "{}");
        assertEquals(401, expired.statusCode()); assertTrue(expired.body().contains("mihoyo_credentials_expired"));
        assertTrue(expired.headers().firstValue("Set-Cookie").isEmpty(), "迟到 401 不应清除新登录的 Cookie");
        assertEquals(401, send("GET", "/session", null).statusCode());
    }

    @Test void rotatingCookieInvalidatesPreviousSession() throws Exception {
        create(); String old = cookie; create(); String current = cookie;
        cookie = old; assertEquals(401, send("GET", "/session", null).statusCode());
        cookie = current; assertEquals(200, send("GET", "/session", null).statusCode());
    }
}
