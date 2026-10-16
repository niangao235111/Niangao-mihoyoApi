package com.niangao.mihoyo.modules.workbench;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.niangao.mihoyo.common.exception.BusinessException;
import com.niangao.mihoyo.modules.mihoyo.dto.GameAccountsRequest;
import com.niangao.mihoyo.modules.mihoyo.service.MihoyoGameAccountService;
import com.niangao.mihoyo.modules.mihoyo.vo.GameAccountsResponse;
import com.niangao.mihoyo.modules.workbench.config.WorkbenchSessionProperties;
import com.niangao.mihoyo.modules.workbench.entity.WorkbenchSecret;
import com.niangao.mihoyo.modules.workbench.mapper.WorkbenchSessionMapper;
import com.niangao.mihoyo.modules.workbench.service.WorkbenchSessionService;
import com.niangao.mihoyo.modules.workbench.util.WorkbenchCipher;
import org.junit.jupiter.api.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class WorkbenchSessionTest {
    final WorkbenchSessionProperties settings = new WorkbenchSessionProperties();
    final MemoryMapper mapper = new MemoryMapper();
    final MihoyoGameAccountService accounts = mock(MihoyoGameAccountService.class);
    WorkbenchCipher cipher;
    WorkbenchSessionService service;
    final GameAccountsRequest input = new GameAccountsRequest("fixture-stoken", "123456", "fixture-mid", null, null);

    @BeforeEach void setup() {
        settings.setEncryptionKey(Base64.getEncoder().encodeToString(new byte[32]));
        cipher = new WorkbenchCipher(settings, new ObjectMapper());
        service = new WorkbenchSessionService(mapper, cipher, accounts);
        when(accounts.queryAccounts(any())).thenReturn(new GameAccountsResponse("test", 0, List.of()));
    }

    @Test void encryptedStorageAndRefreshRestoreWithoutReturningCredentials() {
        var issued = service.create(input, null);
        var stored = mapper.rows.get(WorkbenchSessionService.digest(issued.token()));
        assertFalse(stored.encrypted().contains(input.stoken()));
        assertFalse(stored.encrypted().contains(input.mid()));
        assertFalse(mapper.rows.containsKey(issued.token()));
        var restored = service.restore(issued.token());
        assertTrue(restored.connected());
        assertEquals(issued.view().csrfToken(), restored.csrfToken());
        assertEquals("米哈游账号 · 3456", restored.accountLabel());
        assertFalse(issued.toString().contains(issued.token()));
        assertFalse(stored.toString().contains(stored.encrypted()));
    }

    @Test void renewSlidesSevenDaysButStopsAtAbsoluteThirtyDays() {
        var issued = service.create(input, null);
        long start = mapper.now;
        for (int day = 1; day <= 29; day++) {
            mapper.now = start + Duration.ofDays(day).toMillis();
            var view = service.renew(issued.token(), issued.view().csrfToken());
            assertEquals(start + Duration.ofDays(Math.min(day + 7, 30)).toMillis(), view.expiresAt().toEpochMilli());
            assertEquals(start + Duration.ofDays(30).toMillis(), view.absoluteExpiresAt().toEpochMilli());
        }
        mapper.now = start + Duration.ofDays(30).toMillis();
        assertStatus(401, () -> service.restore(issued.token()));
        assertTrue(mapper.rows.isEmpty());
    }

    @Test void idleExpiryAndLogoutCannotBeRevivedByLateRenew() {
        var issued = service.create(input, null);
        mapper.now += Duration.ofDays(7).toMillis();
        assertStatus(401, () -> service.renew(issued.token(), issued.view().csrfToken()));
        assertTrue(mapper.rows.isEmpty());
        var next = service.create(input, null);
        service.logout(next.token(), next.view().csrfToken());
        assertStatus(401, () -> service.renew(next.token(), next.view().csrfToken()));
        assertTrue(mapper.rows.isEmpty());
    }

    @Test void csrfFailureDoesNotTouchSessionAndRotationRevokesPreviousToken() {
        var first = service.create(input, null);
        long expires = first.view().expiresAt().toEpochMilli();
        mapper.now += 1000;
        assertStatus(403, () -> service.renew(first.token(), "wrong"));
        assertEquals(expires, mapper.rows.get(WorkbenchSessionService.digest(first.token())).expiresAt());
        var second = service.create(input, first.token());
        assertStatus(401, () -> service.restore(first.token()));
        assertNotEquals(first.token(), second.token());
        assertNotEquals(first.view().csrfToken(), second.view().csrfToken());
    }

    @Test void onlyConfirmedStokenInvalidationRevokesSession() {
        var issued = service.create(input, null);
        for (var failure : List.of(new BusinessException(502, "network"),
                new BusinessException(422, "risk5003", Map.of("stage", "genshin_overview", "upstream_retcode", 5003)),
                new BusinessException(422, "private", Map.of("stage", "genshin_equipment", "upstream_retcode", -100)),
                new BusinessException(422, "risk", Map.of("stage", "stoken_to_ltoken", "upstream_retcode", 1034)))) {
            assertSame(failure, assertThrows(BusinessException.class, () -> service.withSession(issued.token(), issued.view().csrfToken(), secret -> { throw failure; })));
            assertTrue(service.restore(issued.token()).connected());
        }
        assertStatus(401, () -> service.withSession(issued.token(), issued.view().csrfToken(), secret -> {
            throw new BusinessException(422, "expired", Map.of("stage", "stoken_to_ltoken", "upstream_retcode", -100));
        }));
        assertStatus(401, () -> service.restore(issued.token()));
    }

    @Test void logoutDuringUpstreamRequestDiscardsLateResult() {
        var issued = service.create(input, null);
        assertStatus(401, () -> service.withSession(issued.token(), issued.view().csrfToken(), secret -> {
            service.logout(issued.token(), issued.view().csrfToken()); return "late data";
        }));
    }

    @Test void gcmRejectsTamperingOtherSessionAndWrongKey() {
        var secret = new WorkbenchSecret(input.stoken(), input.stuid(), input.mid(), List.of());
        String encrypted = cipher.encrypt(secret, "session-a");
        assertEquals(secret, cipher.decrypt(encrypted, "session-a"));
        assertNotEquals(encrypted, cipher.encrypt(secret, "session-a"));
        assertStatus(401, () -> cipher.decrypt(encrypted, "session-b"));
        byte[] bytes = Base64.getDecoder().decode(encrypted); bytes[bytes.length - 1] ^= 1;
        assertStatus(401, () -> cipher.decrypt(Base64.getEncoder().encodeToString(bytes), "session-a"));
        byte[] wrong = new byte[32]; Arrays.fill(wrong, (byte) 1);
        settings.setEncryptionKey(Base64.getEncoder().encodeToString(wrong));
        assertStatus(401, () -> cipher.decrypt(encrypted, "session-a"));
        settings.setEncryptionKey("");
        assertStatus(503, () -> service.create(input, null));
        assertTrue(mapper.rows.isEmpty());
    }

    static void assertStatus(int expected, org.junit.jupiter.api.function.Executable action) {
        assertEquals(expected, assertThrows(BusinessException.class, action).getStatus());
    }

    /** 可控时间的服务测试替身；Lua 的原子行为由 RedisWorkbenchSessionMapperTest 单独验证。 */
    static class MemoryMapper implements WorkbenchSessionMapper {
        long now = 1_700_000_000_000L;
        final Map<String, Stored> rows = new HashMap<>();
        public Stored insert(String key, String encrypted, String csrf) {
            var row = new Stored(encrypted, csrf, now, now + Duration.ofDays(7).toMillis(), now + Duration.ofDays(30).toMillis());
            return rows.putIfAbsent(key, row) == null ? row : null;
        }
        public Stored read(String key, boolean touch) {
            var row = rows.get(key);
            if (row == null) return null;
            if (now >= row.expiresAt() || now >= row.absoluteExpiresAt()) { rows.remove(key); return null; }
            if (touch) { row = new Stored(row.encrypted(), row.csrf(), row.createdAt(), Math.min(now + Duration.ofDays(7).toMillis(), row.absoluteExpiresAt()), row.absoluteExpiresAt()); rows.put(key, row); }
            return row;
        }
        public void delete(String key) { rows.remove(key); }
    }
}
