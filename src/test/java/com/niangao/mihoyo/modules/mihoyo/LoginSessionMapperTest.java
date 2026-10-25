package com.niangao.mihoyo.modules.mihoyo;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.google.gson.JsonParser;
import com.niangao.mihoyo.common.exception.BusinessException;
import com.niangao.mihoyo.modules.mihoyo.client.MihoyoPassportClient;
import com.niangao.mihoyo.modules.mihoyo.client.model.UpstreamReply;
import com.niangao.mihoyo.modules.mihoyo.config.MihoyoProperties;
import com.niangao.mihoyo.modules.mihoyo.config.MihoyoRedisProperties;
import com.niangao.mihoyo.modules.mihoyo.dto.*;
import com.niangao.mihoyo.modules.mihoyo.entity.LoginSession;
import com.niangao.mihoyo.modules.mihoyo.enums.*;
import com.niangao.mihoyo.modules.mihoyo.mapper.impl.RedisLoginSessionMapper;
import com.niangao.mihoyo.modules.mihoyo.mapper.redis.RedisLoginScripts;
import com.niangao.mihoyo.modules.mihoyo.service.impl.MihoyoLoginServiceImpl;
import com.niangao.mihoyo.modules.mihoyo.util.RedisLoginKeyFactory;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** 连接真实 Redis，分别构造两个无共享 Java 锁的 Mapper/Service；全部 Key 使用随机隔离前缀。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class LoginSessionMapperTest {
    @Autowired
    StringRedisTemplate redis;
    @Autowired
    RedisLoginScripts scripts;
    RedisLoginSessionMapper first;
    RedisLoginSessionMapper second;
    RedisLoginKeyFactory keys;
    MihoyoProperties properties;
    MihoyoRedisProperties settings;
    final Set<String> ids = new HashSet<>();
    final Set<String> phones = new HashSet<>();

    @BeforeEach
    void setup() {
        properties = new MihoyoProperties();
        settings = new MihoyoRedisProperties();
        settings.setKeyPrefix("niangao:test:mapper:" + UUID.randomUUID());
        settings.setPhoneKeySecret("test-only-shared-hmac-secret");
        keys = new RedisLoginKeyFactory(settings);
        first = new RedisLoginSessionMapper(redis, keys, scripts, properties, settings);
        second = new RedisLoginSessionMapper(redis, keys, scripts, properties, settings);
    }

    @AfterEach
    void cleanup() {
        List<String> owned = new ArrayList<>();
        owned.add(keys.index());
        ids.forEach(id -> owned.add(keys.session(id)));
        phones.forEach(phone -> owned.add(keys.cooldown(phone)));
        redis.delete(owned);
    }

    LoginSession create(String phone) {
        String id = UUID.randomUUID().toString();
        ids.add(id);
        phones.add(phone);
        return first.insert(
                new LoginSession(id, phone, "+86", "test-device", Instant.now().plusSeconds(600)));
    }

    LoginSession claim(RedisLoginSessionMapper mapper, LoginSession value) {
        return mapper.beginOperation(
                value.getId(),
                value.getVersion(),
                LoginOperation.SMS,
                value.getChallenge() != null);
    }

    LoginSession sent(LoginSession value) {
        String id = value.getOperationId();
        value.setState(LoginState.SMS_SENT);
        value.setActionType("login");
        value.setChallenge(null);
        value.setAigisSessionId(null);
        value.setPendingOperation(null);
        value.setSmsCooldownSeconds(60);
        return first.completeOperation(value, id);
    }

    LoginSession challenge(LoginSession value, String riskId) {
        String id = value.getOperationId();
        value.setState(LoginState.SMS_CHALLENGE);
        value.setChallenge(
                JsonParser.parseString("{\"use_v4\":false,\"gt\":\"mock-id\"}").getAsJsonObject());
        value.setAigisSessionId(riskId);
        value.setPendingOperation(LoginOperation.SMS);
        return first.completeOperation(value, id);
    }

    void forceDeadlinePast(LoginSession value) {
        redis.opsForHash()
                .put(
                        keys.session(value.getId()),
                        "operation_deadline",
                        String.valueOf(value.getSnapshotTime().toEpochMilli() - 1));
    }

    @Test
    void sameSessionTwoInstancesOnlyOneObtainsExecutionRight() throws Exception {
        LoginSession original = create("13900000000");
        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        AtomicInteger winners = new AtomicInteger();
        try {
            List<Future<?>> tasks = new ArrayList<>();
            for (var mapper : List.of(first, second))
                tasks.add(
                        executor.submit(
                                () -> {
                                    gate.await();
                                    try {
                                        claim(mapper, original);
                                        winners.incrementAndGet();
                                    } catch (BusinessException ex) {
                                        assertEquals(409, ex.getStatus());
                                    }
                                    return null;
                                }));
            gate.countDown();
            for (Future<?> task : tasks) task.get(10, TimeUnit.SECONDS);
            assertEquals(1, winners.get());
            LoginSession stored = second.findById(original.getId());
            assertEquals(LoginState.SMS_PROCESSING, stored.getState());
            assertEquals(1, stored.getSmsAttempts());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void samePhoneDifferentSessionsHaveSingleSmsOwner() throws Exception {
        LoginSession a = create("13900000000"), b = create("13900000000");
        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        AtomicInteger winners = new AtomicInteger();
        try {
            var tasks =
                    List.of(
                            executor.submit(
                                    () -> {
                                        gate.await();
                                        try {
                                            claim(first, a);
                                            winners.incrementAndGet();
                                        } catch (BusinessException ex) {
                                            assertEquals(429, ex.getStatus());
                                        }
                                        return null;
                                    }),
                            executor.submit(
                                    () -> {
                                        gate.await();
                                        try {
                                            claim(second, b);
                                            winners.incrementAndGet();
                                        } catch (BusinessException ex) {
                                            assertEquals(429, ex.getStatus());
                                        }
                                        return null;
                                    }));
            gate.countDown();
            for (Future<?> task : tasks) task.get(10, TimeUnit.SECONDS);
            assertEquals(1, winners.get());
            assertTrue(redis.getExpire(keys.cooldown(a.getMobile()), TimeUnit.MILLISECONDS) > 0);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void capacityLimitIsAtomicAcrossInstances() throws Exception {
        properties.setMaxSessions(5);
        var executor = Executors.newFixedThreadPool(8);
        AtomicInteger winners = new AtomicInteger();
        List<Future<?>> tasks = new ArrayList<>();
        try {
            for (int i = 0; i < 30; i++) {
                String id = UUID.randomUUID().toString();
                ids.add(id);
                RedisLoginSessionMapper mapper = i % 2 == 0 ? first : second;
                tasks.add(
                        executor.submit(
                                () -> {
                                    try {
                                        mapper.insert(
                                                new LoginSession(
                                                        id,
                                                        "13900000000",
                                                        "+86",
                                                        "device",
                                                        Instant.now().plusSeconds(600)));
                                        winners.incrementAndGet();
                                    } catch (BusinessException ex) {
                                        assertEquals(429, ex.getStatus());
                                    }
                                }));
            }
            for (Future<?> task : tasks) task.get(10, TimeUnit.SECONDS);
            assertEquals(5, winners.get());
            assertEquals(5L, redis.opsForZSet().zCard(keys.index()));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void fixedSessionTtlIsNotRenewedByClaimOrCompletion() throws Exception {
        LoginSession original = create("13900000000");
        long before = redis.getExpire(keys.session(original.getId()), TimeUnit.MILLISECONDS);
        Thread.sleep(60);
        LoginSession completed = sent(claim(second, original));
        long after = redis.getExpire(keys.session(original.getId()), TimeUnit.MILLISECONDS);
        assertTrue(after < before);
        assertEquals(original.getExpiresAt(), completed.getExpiresAt());
        assertEquals(LoginState.SMS_SENT, completed.getState());
    }

    @Test
    void challengeResumeKeepsSameDeviceAndCooldownOwnership() {
        LoginSession original = create("13900000000");
        LoginSession pending = challenge(claim(first, original), "risk-1");
        assertEquals(LoginState.SMS_CHALLENGE, second.findById(original.getId()).getState());
        LoginSession resumed = claim(second, pending);
        assertEquals(original.getDeviceId(), resumed.getDeviceId());
        assertEquals(2, resumed.getSmsAttempts());
        assertEquals(1, resumed.getChallengeAttempts());
        sent(resumed);
    }

    @Test
    void challengeCannotBypassAnotherSessionPhoneCooldown() {
        LoginSession original = create("13900000000");
        LoginSession pending = challenge(claim(first, original), "risk-1");
        redis.delete(keys.cooldown(original.getMobile())); // 模拟该流程的旧冷却已到期。
        LoginSession other = create("13900000000");
        claim(second, other);
        assertEquals(
                429,
                assertThrows(BusinessException.class, () -> claim(first, pending)).getStatus());
        assertEquals(LoginState.SMS_CHALLENGE, first.findById(original.getId()).getState());
    }

    @Test
    void oldSnapshotAndLateCallbacksCannotOverwriteNewOperation() {
        LoginSession original = create("13900000000");
        LoginSession a = claim(first, original);
        String oldOperation = a.getOperationId();
        LoginSession pending = challenge(a, "risk-1");
        assertEquals(
                409,
                assertThrows(BusinessException.class, () -> claim(second, original)).getStatus());
        LoginSession b = claim(second, pending);
        assertEquals(
                409,
                assertThrows(
                                BusinessException.class,
                                () -> first.failOperation(original.getId(), oldOperation))
                        .getStatus());
        a.setState(LoginState.SMS_SENT);
        assertEquals(
                409,
                assertThrows(
                                BusinessException.class,
                                () -> first.completeOperation(a, oldOperation))
                        .getStatus());
        assertEquals(b.getOperationId(), first.findById(original.getId()).getOperationId());
        sent(b);
    }

    @Test
    void abandonedWorkerFailsClosedAfterDeadlineAndKeepsCooldown() {
        LoginSession claimed = claim(first, create("13900000000"));
        String oldOperation = claimed.getOperationId();
        forceDeadlinePast(claimed); // 模拟进程中断，省去等待真实 90 秒执行期限。
        LoginSession recovered = second.findById(claimed.getId());
        assertEquals(LoginState.FAILED, recovered.getState());
        assertNull(recovered.getOperationId());
        assertEquals(
                409,
                assertThrows(BusinessException.class, () -> claim(first, recovered)).getStatus());
        claimed.setState(LoginState.SMS_SENT);
        assertEquals(
                409,
                assertThrows(
                                BusinessException.class,
                                () -> first.completeOperation(claimed, oldOperation))
                        .getStatus());
        assertEquals(claimed.getId(), redis.opsForValue().get(keys.cooldown(claimed.getMobile())));
    }

    @Test
    void lateSuccessWithoutStatusReadIsRejectedAtCommitTime() {
        LoginSession claimed = claim(first, create("13900000000"));
        String operationId = claimed.getOperationId();
        forceDeadlinePast(claimed);
        claimed.setState(LoginState.SMS_SENT);
        assertEquals(
                409,
                assertThrows(
                                BusinessException.class,
                                () -> second.completeOperation(claimed, operationId))
                        .getStatus());
        assertEquals(LoginState.FAILED, first.findById(claimed.getId()).getState());
    }

    @Test
    void deletionDuringRequestCannotBeResurrectedBySuccessOrFailure() {
        LoginSession claimed = claim(first, create("13900000000"));
        String operationId = claimed.getOperationId();
        second.delete(claimed.getId());
        claimed.setState(LoginState.SMS_SENT);
        assertEquals(
                410,
                assertThrows(
                                BusinessException.class,
                                () -> first.completeOperation(claimed, operationId))
                        .getStatus());
        assertEquals(
                410,
                assertThrows(
                                BusinessException.class,
                                () -> first.failOperation(claimed.getId(), operationId))
                        .getStatus());
        assertFalse(redis.hasKey(keys.session(claimed.getId())));
        assertEquals(claimed.getId(), redis.opsForValue().get(keys.cooldown(claimed.getMobile())));
    }

    @Test
    void redisExpiresSessionAndFreesCapacityWithoutJavaCleanupJob() throws Exception {
        properties.setSessionTtl(Duration.ofMillis(180));
        properties.setMaxSessions(1);
        LoginSession original = create("13900000000");
        Thread.sleep(250);
        assertEquals(
                410,
                assertThrows(BusinessException.class, () -> first.findById(original.getId()))
                        .getStatus());
        assertFalse(redis.hasKey(keys.session(original.getId())));
        create("13900000000");
    }

    @Test
    void insufficientRemainingTtlDoesNotReservePhoneOrStartOperation() {
        properties.setSessionTtl(Duration.ofSeconds(5));
        LoginSession original = create("13900000000");
        assertEquals(
                410,
                assertThrows(BusinessException.class, () -> claim(first, original)).getStatus());
        assertFalse(redis.hasKey(keys.cooldown(original.getMobile())));
        assertEquals(0, first.findById(original.getId()).getSmsAttempts());
    }

    @Test
    void verificationAttemptsAndSmsAttemptsArePersistedAcrossInstances() {
        LoginSession current = challenge(claim(first, create("13900000000")), "risk-1");
        for (int i = 0; i < 3; i++) current = challenge(claim(second, current), "risk-1");
        LoginSession limited = current;
        assertEquals(4, limited.getSmsAttempts());
        assertEquals(3, limited.getChallengeAttempts());
        assertEquals(
                429,
                assertThrows(BusinessException.class, () -> claim(first, limited)).getStatus());
    }

    @Test
    void twoServiceInstancesCallUpstreamOnlyOnceAndReturnPersistedState() throws Exception {
        MihoyoPassportClient client = mock(MihoyoPassportClient.class);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        doAnswer(
                        invocation -> {
                            calls.incrementAndGet();
                            entered.countDown();
                            assertTrue(release.await(5, TimeUnit.SECONDS));
                            UpstreamReply reply = new UpstreamReply();
                            reply.setHttpStatus(200);
                            reply.setBody(
                                    JsonParser.parseString(
                                                    "{\"retcode\":0,\"data\":{\"action_type\":\"login\",\"countdown\":60}} ")
                                            .getAsJsonObject());
                            return reply;
                        })
                .when(client)
                .call(any(LoginSession.class), eq(LoginOperation.SMS), isNull(), isNull());
        var serviceA = new MihoyoLoginServiceImpl(first, client, properties, Clock.systemUTC(), org.mockito.Mockito.mock(com.niangao.mihoyo.modules.mihoyo.service.LoginDeviceBinding.class));
        var serviceB = new MihoyoLoginServiceImpl(second, client, properties, Clock.systemUTC(), org.mockito.Mockito.mock(com.niangao.mihoyo.modules.mihoyo.service.LoginDeviceBinding.class));
        LoginSession original = create("13900000000");
        var executor = Executors.newSingleThreadExecutor();
        try {
            var result =
                    executor.submit(
                            () -> serviceA.sendSms(new SendSmsRequest(original.getId(), null)));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals(
                    409,
                    assertThrows(
                                    BusinessException.class,
                                    () ->
                                            serviceB.sendSms(
                                                    new SendSmsRequest(original.getId(), null)))
                            .getStatus());
            release.countDown();
            assertEquals(LoginState.SMS_SENT, result.get(10, TimeUnit.SECONDS).state());
            assertEquals(1, calls.get());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void credentialsAreOnlyReturnedAfterCommitAndNeverStoredInRedis() {
        MihoyoPassportClient client = mock(MihoyoPassportClient.class);
        UpstreamReply reply = new UpstreamReply();
        reply.setHttpStatus(200);
        reply.setBody(
                JsonParser.parseString(
                                "{\"retcode\":0,\"data\":{\"stoken\":\"test-sensitive-stoken\",\"aid\":\"123\",\"mid\":\"test-sensitive-mid\"}}")
                        .getAsJsonObject());
        when(client.call(any(LoginSession.class), eq(LoginOperation.LOGIN), eq("012345"), isNull()))
                .thenReturn(reply);
        var service = new MihoyoLoginServiceImpl(first, client, properties, Clock.systemUTC(), org.mockito.Mockito.mock(com.niangao.mihoyo.modules.mihoyo.service.LoginDeviceBinding.class));
        LoginSession session = sent(claim(second, create("13900000000")));
        assertEquals(
                "test-sensitive-stoken",
                service.verify(new VerifyLoginRequest(session.getId(), "012345", null))
                        .credentials()
                        .stoken());
        String stored = redis.opsForHash().entries(keys.session(session.getId())).toString();
        assertFalse(stored.contains("test-sensitive-stoken"));
        assertFalse(stored.contains("test-sensitive-mid"));
        assertFalse(stored.contains("012345"));
        assertEquals(LoginState.LOGGED_IN, second.findById(session.getId()).getState());
    }

    @Test
    void phoneKeyIsHmacAndSameSecretGivesSameKeyOnAnotherInstance() {
        String key = keys.cooldown("13900000000");
        assertFalse(key.contains("13900000000"));
        assertEquals(key, new RedisLoginKeyFactory(settings).cooldown("13900000000"));
    }

    @Test
    void expiredInFlightSessionCannotBeRecreatedByLateCommit() throws Exception {
        LoginSession claimed = claim(first, create("13900000000"));
        String operationId = claimed.getOperationId();
        redis.expire(keys.session(claimed.getId()), Duration.ofMillis(30));
        Thread.sleep(80);
        claimed.setState(LoginState.SMS_SENT);
        assertEquals(
                410,
                assertThrows(
                                BusinessException.class,
                                () -> second.completeOperation(claimed, operationId))
                        .getStatus());
        assertFalse(redis.hasKey(keys.session(claimed.getId())));
        assertTrue(redis.hasKey(keys.cooldown(claimed.getMobile())));
    }

    @Test
    void upstreamFailureMarksFailedAndDoesNotReleasePhoneCooldown() {
        MihoyoPassportClient client = mock(MihoyoPassportClient.class);
        when(client.call(any(LoginSession.class), eq(LoginOperation.SMS), isNull(), isNull()))
                .thenThrow(new BusinessException(502, "模拟网络结果不明"));
        var service = new MihoyoLoginServiceImpl(first, client, properties, Clock.systemUTC(), org.mockito.Mockito.mock(com.niangao.mihoyo.modules.mihoyo.service.LoginDeviceBinding.class));
        LoginSession original = create("13900000000");
        assertEquals(
                502,
                assertThrows(
                                BusinessException.class,
                                () -> service.sendSms(new SendSmsRequest(original.getId(), null)))
                        .getStatus());
        assertEquals(LoginState.FAILED, second.findById(original.getId()).getState());
        assertTrue(redis.hasKey(keys.cooldown(original.getMobile())));
        LoginSession another = create("13900000000");
        assertEquals(
                429,
                assertThrows(BusinessException.class, () -> claim(second, another)).getStatus());
        verify(client, times(1))
                .call(any(LoginSession.class), eq(LoginOperation.SMS), isNull(), isNull());
    }

    @Test
    void credentialsAreNotReturnedWhenSessionWasDeletedDuringUpstreamRequest() {
        MihoyoPassportClient client = mock(MihoyoPassportClient.class);
        LoginSession original = sent(claim(first, create("13900000000")));
        when(client.call(any(LoginSession.class), eq(LoginOperation.LOGIN), eq("123456"), isNull()))
                .thenAnswer(
                        invocation -> {
                            second.delete(original.getId());
                            UpstreamReply reply = new UpstreamReply();
                            reply.setHttpStatus(200);
                            reply.setBody(
                                    JsonParser.parseString(
                                                    "{\"retcode\":0,\"data\":{\"stoken\":\"late-sensitive-token\",\"aid\":\"123\",\"mid\":\"test-mid\"}}")
                                            .getAsJsonObject());
                            return reply;
                        });
        var service = new MihoyoLoginServiceImpl(first, client, properties, Clock.systemUTC(), org.mockito.Mockito.mock(com.niangao.mihoyo.modules.mihoyo.service.LoginDeviceBinding.class));
        assertEquals(
                410,
                assertThrows(
                                BusinessException.class,
                                () ->
                                        service.verify(
                                                new VerifyLoginRequest(
                                                        original.getId(), "123456", null)))
                        .getStatus());
        assertFalse(redis.hasKey(keys.session(original.getId())));
    }
}
