package com.niangao.mihoyo.modules.workbench;

import com.niangao.mihoyo.modules.workbench.config.WorkbenchSessionProperties;
import com.niangao.mihoyo.modules.workbench.mapper.RedisWorkbenchSessionMapper;
import com.niangao.mihoyo.modules.genshin.mapper.GenshinDeviceMapper;
import com.niangao.mihoyo.modules.genshin.mapper.GenshinVerificationMapper;
import com.niangao.mihoyo.modules.mihoyo.config.MihoyoRedisProperties;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** 复用项目 Redis 连接配置；只删除本测试随机前缀下的 Key。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "mihoyo.redis.phone-key-secret=workbench-mapper-test-only")
class RedisWorkbenchSessionMapperTest {
    @Autowired StringRedisTemplate redis;
    WorkbenchSessionProperties settings;
    RedisWorkbenchSessionMapper first, second;
    final List<String> owned = new ArrayList<>();
    @BeforeEach void setup() {
        settings = new WorkbenchSessionProperties();
        settings.setKeyPrefix("niangao:test:workbench:" + UUID.randomUUID());
        first = new RedisWorkbenchSessionMapper(redis, settings); second = new RedisWorkbenchSessionMapper(redis, settings);
    }
    String digest() {
        String id = UUID.randomUUID().toString().replace("-", "").repeat(2); owned.add(settings.getKeyPrefix() + ":session:" + id); return id;
    }
    String key(String id) { return settings.getKeyPrefix() + ":session:" + id; }
    @AfterEach void cleanup() { if (!owned.isEmpty()) { redis.delete(owned.stream().map(k -> k + ":verification").toList()); redis.delete(owned); } }

    @Test void verificationIsSingleUseAndStaleFailuresCannotEraseNewProof() {
        String id = digest(); first.insert(id, "encrypted", "csrf");
        var verification = new GenshinVerificationMapper(redis, settings);
        assertEquals("ok", verification.execute(id, "failure", "", "encrypted-failure"));
        assertEquals("ok", verification.execute(id, "reserve", "task-one", "encrypted-failure"));
        assertEquals("busy", verification.execute(id, "reserve", "task-two", "encrypted-failure"));
        assertEquals("ok", verification.execute(id, "ready", "task-one", "encrypted-task"));
        assertNull(verification.execute(id, "claim", "wrong-task"));
        assertEquals("encrypted-task", verification.execute(id, "claim", "task-one"));
        assertNull(verification.execute(id, "claim", "task-one"));
        assertNull(verification.execute(id, "read", "task"));
        assertEquals("ok", verification.execute(id, "pass", "task-one", "encrypted-proof"));
        assertEquals("stale", verification.execute(id, "failure", "", "late-failure"));
        assertEquals("encrypted-proof", verification.execute(id, "read", "proof"));
        assertNull(verification.execute(id, "read", "failure"));
        long ttl = redis.getExpire(key(id) + ":verification", TimeUnit.MILLISECONDS);
        assertTrue(ttl > 0 && ttl <= 300000);
    }

    @Test void logoutOrExpiryCannotResurrectVerificationAndTaskTtlIsBoundedByParent() {
        String id = digest(); var verification = new GenshinVerificationMapper(redis, settings);
        assertNull(verification.execute(id, "failure", "", "failure"));
        assertFalse(redis.hasKey(key(id) + ":verification"));
        first.insert(id, "encrypted", "csrf"); redis.expire(key(id), Duration.ofSeconds(20));
        assertEquals("ok", verification.execute(id, "failure", "", "failure"));
        assertTrue(redis.getExpire(key(id) + ":verification", TimeUnit.MILLISECONDS) <= 20000);
        assertEquals("ok", verification.execute(id, "reserve", "task", "failure"));
        assertEquals("ok", verification.execute(id, "ready", "task", "encrypted-task"));
        first.delete(id);
        assertFalse(redis.hasKey(key(id) + ":verification"));
        assertNull(verification.execute(id, "claim", "task"));
        assertNull(verification.execute(id, "pass", "task", "late-proof"));
        assertFalse(redis.hasKey(key(id) + ":verification"));
    }

    @Test void luaUsesRedisClockAndAtomicIdleAndAbsoluteDeadlines() {
        String id = digest();
        var created = first.insert(id, "encrypted-fixture", "csrf-fixture");
        assertNotNull(created);
        assertEquals(Duration.ofDays(7).toMillis(), created.expiresAt() - created.createdAt());
        assertEquals(Duration.ofDays(30).toMillis(), created.absoluteExpiresAt() - created.createdAt());
        assertTrue(redis.getExpire(key(id), TimeUnit.MILLISECONDS) > 0);
        assertNull(second.insert(id, "replacement", "replacement"));
        long absolute = System.currentTimeMillis() + 60_000;
        redis.opsForHash().put(key(id), "absolute_expires_at", String.valueOf(absolute));
        assertEquals(absolute, second.read(id, true).expiresAt());
        assertEquals("encrypted-fixture", second.read(id, false).encrypted());
    }

    @Test void expiredIdleOrAbsoluteDeadlineIsDeletedAndNeverRecreated() {
        for (String field : List.of("expires_at", "absolute_expires_at")) {
            String id = digest(); first.insert(id, "encrypted", "csrf");
            redis.opsForHash().put(key(id), field, "1");
            assertNull(second.read(id, true)); assertFalse(redis.hasKey(key(id)));
            assertNull(first.read(id, true)); assertFalse(redis.hasKey(key(id)));
        }
    }

    @Test void deviceFingerprintCacheIsSharedAndExpiresWithoutOverwritingTheWinningValue() {
        var deviceSettings = new MihoyoRedisProperties(); deviceSettings.setKeyPrefix(settings.getKeyPrefix());
        var cache1 = new GenshinDeviceMapper(redis, deviceSettings);
        var cache2 = new GenshinDeviceMapper(redis, deviceSettings);
        String id = UUID.randomUUID().toString(), key = settings.getKeyPrefix() + ":genshin:fp:" + id;
        owned.add(key);
        assertNull(cache1.find(id));
        assertEquals("server-fp-one", cache1.saveIfAbsent(id, "server-fp-one"));
        assertEquals("server-fp-one", cache2.saveIfAbsent(id, "server-fp-two"));
        assertEquals("server-fp-one", cache2.find(id));
        long ttl = redis.getExpire(key, TimeUnit.SECONDS);
        assertTrue(ttl > 0 && ttl <= Duration.ofHours(24).toSeconds());
    }

    @Test void concurrentRenewAndDeleteAcrossInstancesCannotResurrectSession() throws Exception {
        String id = digest(); first.insert(id, "encrypted", "csrf");
        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        try {
            Future<?> renew = executor.submit(() -> { gate.await(); for (int i = 0; i < 30; i++) first.read(id, true); return null; });
            Future<?> delete = executor.submit(() -> { gate.await(); second.delete(id); return null; });
            gate.countDown(); renew.get(10, TimeUnit.SECONDS); delete.get(10, TimeUnit.SECONDS);
            assertNull(first.read(id, true)); assertFalse(redis.hasKey(key(id)));
        } finally { executor.shutdownNow(); }
    }
}
