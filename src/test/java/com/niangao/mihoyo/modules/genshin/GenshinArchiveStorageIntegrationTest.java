package com.niangao.mihoyo.modules.genshin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.niangao.mihoyo.common.exception.BusinessException;
import com.niangao.mihoyo.modules.genshin.dto.GenshinRecordRequest;
import com.niangao.mihoyo.modules.genshin.mapper.GenshinArchiveMapper;
import com.niangao.mihoyo.modules.genshin.service.*;
import com.niangao.mihoyo.modules.genshin.vo.GenshinArchive.*;
import com.niangao.mihoyo.modules.genshin.vo.GenshinArchive.Character;
import com.niangao.mihoyo.modules.mihoyo.service.MihoyoGameAccountService;
import com.niangao.mihoyo.modules.mihoyo.vo.*;
import com.niangao.mihoyo.modules.workbench.entity.WorkbenchSecret;
import com.niangao.mihoyo.modules.workbench.mapper.WorkbenchSessionMapper;
import com.niangao.mihoyo.modules.workbench.service.WorkbenchSessionService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import java.net.URI;
import java.net.http.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 真实MySQL/Redis，随机测试账号；只清理本测试创建的数据，不访问真实米游社。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "genshin.storage.enabled=true", "mihoyo.redis.phone-key-secret=storage-test-only",
        "workbench.session.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
class GenshinArchiveStorageIntegrationTest {
    @Autowired GenshinArchiveMapper mapper;
    @Autowired GenshinArchiveStorage storage;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @Autowired WorkbenchSessionMapper sessions;
    @MockBean MihoyoGameAccountService accounts;
    @MockBean GenshinArchiveService archive;
    @LocalServerPort int port;
    String owner, cookie, csrf;
    GenshinRecordRequest request;
    WorkbenchSecret secret;
    final Set<String> sessionDigests = new HashSet<>();
    static final Instant NOW = Instant.parse("2026-10-16T01:02:03Z");
    @BeforeEach void setup() {
        owner = Long.toString(ThreadLocalRandom.current().nextLong(800000000000000000L, 899999999999999999L));
        request = new GenshinRecordRequest("fixture-stoken", owner, "fixture-mid", "506005588", "cn_qd01", "hk4e_cn", List.of("10000089"));
        secret = new WorkbenchSecret(request.stoken(), owner, request.mid(), List.of(new GameAccountVO("hk4e_cn", request.gameUid(), request.region(), "世界树", "测试", 60, "原神", false, false)));
        when(accounts.queryAccounts(any())).thenReturn(new GameAccountsResponse("fixture", 1, secret.roles()));
    }
    @AfterEach void cleanup() {
        sessionDigests.forEach(sessions::delete);
        var ids = db.queryForList("SELECT id FROM ng_genshin_account WHERE owner_stuid=?", Long.class, owner);
        for (long id : ids) {
            for (String table : List.of("ng_genshin_overview", "ng_genshin_character", "ng_genshin_sync_run")) db.update("DELETE FROM " + table + " WHERE account_id=?", id);
            db.update("DELETE FROM ng_genshin_account WHERE id=? AND owner_stuid=?", id, owner);
        }
    }
    Overview overview(int achievements) {
        return new Overview(request.gameUid(), "数据库测试旅行者", 60, null, achievements, 983, "12-3", new Theater(10, true, true), List.of(), List.of(), NOW);
    }
    Character character(int level, boolean detailed) {
        return new Character("10000089", "芙宁娜", null, "Hydro", 5, level, 2, 10,
                new Weapon("11513", "静水流涌之辉", null, 5, 90, 1, null, null),
                detailed ? List.of(new Skill("1", "普通攻击", 10, 1, null)) : List.of(), List.of(), List.of(), detailed);
    }
    Characters characters(boolean detailed) { return new Characters(request.gameUid(), 1, List.of(character(90, detailed)), NOW); }
    @Test void successfulSectionsPersistWithoutCredentialsAndFailingRefreshRetainsData() {
        storage.track(secret, request, "genshin_overview", () -> overview(1572));
        storage.track(secret, request, "genshin_characters", () -> characters(false));
        storage.track(secret, request, "genshin_equipment", () -> characters(true));
        var saved = storage.read(secret, request);
        assertEquals(1572, saved.overview().achievements()); assertEquals(NOW, saved.overview().updatedAt());
        assertTrue(saved.characters().list().get(0).detailed()); assertEquals(NOW, saved.detailUpdatedAt().get("10000089"));
        assertEquals("静水流涌之辉", saved.characters().list().get(0).weapon().name()); assertEquals(3, saved.queries().size());
        assertThrows(BusinessException.class, () -> storage.track(secret, request, "genshin_equipment", () -> {
            throw new BusinessException(422, "secret raw message", Map.of("reason", "upstream_verification_required", "upstream_retcode", 1034));
        }));
        var after = storage.read(secret, request);
        assertEquals(saved.overview(), after.overview()); assertEquals(saved.characters(), after.characters());
        assertEquals("FAILED", after.queries().get(0).status()); assertEquals(1034, after.queries().get(0).upstreamRetcode());
        String stored = db.queryForObject("SELECT detail_json FROM ng_genshin_character c JOIN ng_genshin_account a ON a.id=c.account_id WHERE owner_stuid=?", String.class, owner);
        assertFalse(stored.contains("fixture-stoken")); assertFalse(stored.contains("fixture-mid"));
        assertFalse(after.queries().get(0).errorReason().contains("secret"));
    }
    @Test void outOfOrderResponsesDoNotOverwriteNewerOverviewRosterOrEquipment() {
        var old = mapper.begin(request, false); var newer = mapper.begin(request, false);
        mapper.overview(newer, overview(200)); mapper.overview(old, overview(100));
        assertEquals(200, storage.read(secret, request).overview().achievements());
        var roster = mapper.begin(request, false); mapper.characters(roster, characters(false), false, 0);
        var oldDetail = mapper.begin(request, true); var newDetail = mapper.begin(request, true);
        mapper.characters(newDetail, characters(true), true, 1);
        mapper.characters(oldDetail, new Characters(request.gameUid(), 1, List.of(character(20, true)), NOW.minusSeconds(20)), true, 1);
        assertEquals(90, storage.read(secret, request).characters().list().get(0).level());
        var oldList = mapper.begin(request, false); var emptyList = mapper.begin(request, false);
        mapper.characters(emptyList, new Characters(request.gameUid(), 0, List.of(), NOW), false, 0);
        mapper.characters(oldList, characters(false), false, 0);
        mapper.characters(oldDetail, characters(true), true, 1);
        assertEquals(0, storage.read(secret, request).characters().count());
    }
    @Test void basicRefreshPreservesDetailsAndPartialBatchIsRecorded() {
        storage.track(secret, request, "genshin_characters", () -> characters(false));
        storage.track(secret, request, "genshin_equipment", () -> characters(true));
        storage.track(secret, request, "genshin_characters", () -> new Characters(request.gameUid(), 1, List.of(character(95, false)), NOW.plusSeconds(10)));
        var saved = storage.read(secret, request);
        assertEquals(95, saved.characters().list().get(0).level()); assertTrue(saved.characters().list().get(0).detailed());
        assertEquals(NOW, saved.detailUpdatedAt().get("10000089"));
        var partial = mapper.begin(new GenshinRecordRequest(request.stoken(), owner, request.mid(), request.gameUid(), request.region(), request.gameBiz(), List.of("10000089", "10000001")), true);
        mapper.characters(partial, characters(true), true, 2);
        var query = storage.read(secret, request).queries().get(0);
        assertEquals("PARTIAL", query.status()); assertEquals(1, query.succeededDetailCount()); assertEquals(1, query.failedDetailCount());
    }
    @Test void savedHttpIsSessionScopedAndDoesNotCallUpstream() throws Exception {
        login(); clearInvocations(accounts, archive);
        var empty = post("/genshin/saved", selector()); assertEquals(200, empty.statusCode());
        assertFalse(json.readTree(empty.body()).path("data").has("overview"));
        when(archive.overview(any(), isNull())).thenReturn(overview(1572));
        assertEquals(200, post("/genshin/overview", selector()).statusCode());
        clearInvocations(accounts, archive);
        var saved = post("/genshin/saved", selector()); assertEquals(200, saved.statusCode());
        assertEquals(1572, json.readTree(saved.body()).path("data").path("overview").path("achievements").asInt());
        verifyNoInteractions(accounts, archive);
        assertEquals(403, post("/genshin/saved", selector().replace("506005588", "123456789")).statusCode());
        String savedCsrf = csrf; csrf = "wrong"; assertEquals(403, post("/genshin/saved", selector()).statusCode()); csrf = savedCsrf;
        assertEquals(200, post("/session/logout", "{}").statusCode());
        assertEquals(401, post("/genshin/saved", selector()).statusCode());
    }
    @Test void unrelatedOwnerAndServerCannotReadStoredAccount() {
        storage.track(secret, request, "genshin_overview", () -> overview(1572));
        var other = new GenshinRecordRequest(request.stoken(), owner + "1", request.mid(), request.gameUid(), request.region(), request.gameBiz(), null);
        var otherSecret = new WorkbenchSecret(other.stoken(), other.stuid(), other.mid(), secret.roles());
        assertNull(storage.read(otherSecret, other).overview());
        var wrongRegion = new GenshinRecordRequest(request.stoken(), owner, request.mid(), request.gameUid(), "cn_gf01", request.gameBiz(), null);
        assertEquals(403, assertThrows(BusinessException.class, () -> storage.read(secret, wrongRegion)).getStatus());
    }
    @Test void databaseWriteFailureRollsBackEntireRosterAndKeepsExistingData() {
        storage.track(secret, request, "genshin_characters", () -> characters(false));
        var valid = character(95, false);
        var invalid = new Character("10000001", "fixture-invalid", null, "Hydro", 5, -1, 0, 0, null, List.of(), List.of(), List.of(), false);
        var failure = assertThrows(BusinessException.class, () -> storage.track(secret, request, "genshin_characters", () ->
                new Characters(request.gameUid(), 2, List.of(valid, invalid), NOW.plusSeconds(10))));
        assertEquals(503, failure.getStatus());
        var saved = storage.read(secret, request);
        assertEquals(1, saved.characters().count()); assertEquals(90, saved.characters().list().get(0).level());
        assertEquals(NOW, saved.characters().updatedAt());
        assertEquals("storage_unavailable", saved.queries().get(0).errorReason());
        assertEquals("FAILED", saved.queries().get(0).status());
    }
    String selector() { return "{\"game_uid\":\"506005588\",\"game_biz\":\"hk4e_cn\",\"region\":\"cn_qd01\"}"; }
    HttpResponse<String> post(String path, String body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/workbench" + path))
                .header("Origin", "http://127.0.0.1:5173").header("Content-Type", "application/json");
        if (cookie != null) builder.header("Cookie", cookie);
        if (csrf != null) builder.header("X-Workspace-CSRF", csrf);
        return HttpClient.newHttpClient().send(builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    void login() throws Exception {
        var response = post("/session", "{\"stoken\":\"fixture-stoken\",\"stuid\":\"" + owner + "\",\"mid\":\"fixture-mid\"}");
        assertEquals(200, response.statusCode()); cookie = response.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
        sessionDigests.add(WorkbenchSessionService.digest(cookie.substring(cookie.indexOf('=') + 1)));
        csrf = json.readTree(response.body()).path("data").path("csrf_token").asText();
    }
}
