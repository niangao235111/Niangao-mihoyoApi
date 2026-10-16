package com.niangao.mihoyo.modules.mihoyo.mapper.impl;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.niangao.mihoyo.common.exception.BusinessException;
import com.niangao.mihoyo.modules.mihoyo.config.MihoyoProperties;
import com.niangao.mihoyo.modules.mihoyo.config.MihoyoRedisProperties;
import com.niangao.mihoyo.modules.mihoyo.entity.LoginSession;
import com.niangao.mihoyo.modules.mihoyo.enums.LoginOperation;
import com.niangao.mihoyo.modules.mihoyo.enums.LoginState;
import com.niangao.mihoyo.modules.mihoyo.mapper.LoginSessionMapper;
import com.niangao.mihoyo.modules.mihoyo.mapper.redis.RedisLoginScripts;
import com.niangao.mihoyo.modules.mihoyo.util.RedisLoginKeyFactory;

import lombok.extern.slf4j.Slf4j;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Hash 存会话，ZSet 控制会话总量，字符串 Key 存冷却；所有状态变更由 Lua 完成。 */
@Slf4j
@Component
public class RedisLoginSessionMapper implements LoginSessionMapper {
    private final StringRedisTemplate redis;
    private final RedisLoginKeyFactory keys;
    private final RedisLoginScripts scripts;
    private final MihoyoProperties properties;
    private final MihoyoRedisProperties settings;

    public RedisLoginSessionMapper(
            StringRedisTemplate redis,
            RedisLoginKeyFactory keys,
            RedisLoginScripts scripts,
            MihoyoProperties properties,
            MihoyoRedisProperties settings) {
        this.redis = redis;
        this.keys = keys;
        this.scripts = scripts;
        this.properties = properties;
        this.settings = settings;
    }

    @Override
    public LoginSession insert(LoginSession session) {
        return decode(
                run(
                        scripts.create,
                        List.of(keys.session(session.getId()), keys.index()),
                        session.getId(),
                        session.getMobile(),
                        session.getAreaCode(),
                        session.getDeviceId(),
                        String.valueOf(properties.getSessionTtl().toMillis()),
                        String.valueOf(properties.getMaxSessions())));
    }

    @Override
    public LoginSession findById(String id) {
        return decode(run(scripts.read, List.of(keys.session(id))));
    }

    /** 版本号确保校验的人机验证任务与取得执行权时的任务仍是同一个。 */
    @Override
    public LoginSession beginOperation(
            String id, long expectedVersion, LoginOperation operation, boolean hasProof) {
        LoginSession snapshot = findById(id);
        return decode(
                run(
                        scripts.begin,
                        List.of(keys.session(id), keys.cooldown(snapshot.getMobile())),
                        String.valueOf(expectedVersion),
                        operation.getValue(),
                        hasProof ? "1" : "0",
                        UUID.randomUUID().toString(),
                        String.valueOf(settings.getOperationTimeout().toMillis()),
                        String.valueOf(settings.getSmsCooldown().toMillis())));
    }

    /** 显式列出允许保存的字段：验证码、SToken 和其他凭证绝不进入 Redis 会话。 */
    @Override
    public LoginSession completeOperation(LoginSession session, String operationId) {
        JsonObject update = new JsonObject();
        update.addProperty("state", session.getState().name());
        update.addProperty("action_type", empty(session.getActionType()));
        update.addProperty(
                "challenge_json",
                session.getChallenge() == null ? "" : session.getChallenge().toString());
        update.addProperty("aigis_session_id", empty(session.getAigisSessionId()));
        update.addProperty(
                "pending_operation",
                session.getPendingOperation() == null
                        ? ""
                        : session.getPendingOperation().getValue());
        update.addProperty("challenge_attempts", String.valueOf(session.getChallengeAttempts()));
        update.addProperty(
                "cooldown_ms",
                String.valueOf(
                        Math.max(
                                settings.getSmsCooldown().toMillis(),
                                session.getSmsCooldownSeconds() * 1000L)));
        return decode(
                run(
                        scripts.complete,
                        List.of(keys.session(session.getId()), keys.cooldown(session.getMobile())),
                        operationId,
                        update.toString()));
    }

    @Override
    public void failOperation(String id, String operationId) {
        run(scripts.fail, List.of(keys.session(id)), operationId);
    }

    @Override
    public void delete(String id) {
        run(scripts.delete, List.of(keys.session(id), keys.index()), id);
    }

    private JsonObject run(
            DefaultRedisScript<String> script, List<String> scriptKeys, String... arguments) {
        String response;
        try {
            response = redis.execute(script, scriptKeys, (Object[]) arguments);
        } catch (DataAccessException ex) {
            // Redis 失败时不能静默退回内存，否则会破坏多实例冷却和并发限制。
            // 异常消息可能含连接信息，日志只保留异常类型，不打印参数或 Key。
            log.error("Redis 登录会话操作失败 error_type={}", ex.getClass().getSimpleName());
            throw new BusinessException(503, "Redis 暂不可用，请检查连接配置后重试");
        }
        if (response == null) throw new BusinessException(503, "Redis 未返回操作结果");
        JsonObject body;
        try {
            body = JsonParser.parseString(response).getAsJsonObject();
        } catch (RuntimeException ex) {
            throw new BusinessException(503, "Redis 会话响应异常");
        }
        int code = body.get("code").getAsInt();
        if (code != 200) throw new BusinessException(code, body.get("message").getAsString());
        return body;
    }

    /** 重新构造快照；Redis 内部字符串转成实体类型，Service 不持有共享可变对象。 */
    private LoginSession decode(JsonObject response) {
        try {
            JsonObject value = response.getAsJsonObject("session");
            LoginSession session =
                    new LoginSession(
                            value.get("id").getAsString(),
                            value.get("mobile").getAsString(),
                            value.get("area_code").getAsString(),
                            value.get("device_id").getAsString(),
                            millis(value, "expires_at"));
            session.setState(LoginState.valueOf(value.get("state").getAsString()));
            session.setActionType(nullable(value, "action_type"));
            String operation = nullable(value, "pending_operation");
            session.setPendingOperation(
                    operation == null
                            ? null
                            : operation.equals("sms") ? LoginOperation.SMS : LoginOperation.LOGIN);
            session.setAigisSessionId(nullable(value, "aigis_session_id"));
            String challenge = nullable(value, "challenge_json");
            session.setChallenge(
                    challenge == null ? null : JsonParser.parseString(challenge).getAsJsonObject());
            session.setSmsAttempts(value.get("sms_attempts").getAsInt());
            session.setLoginAttempts(value.get("login_attempts").getAsInt());
            session.setChallengeAttempts(value.get("challenge_attempts").getAsInt());
            session.setResendAfter(millis(value, "resend_after"));
            session.setVersion(value.get("version").getAsLong());
            session.setOperationId(nullable(value, "operation_id"));
            session.setOperationDeadline(
                    nullable(value, "operation_deadline") == null
                            ? null
                            : millis(value, "operation_deadline"));
            session.setSnapshotTime(millis(value, "_server_time"));
            return session;
        } catch (RuntimeException ex) {
            throw new BusinessException(503, "Redis 会话数据格式异常");
        }
    }

    private Instant millis(JsonObject value, String name) {
        return Instant.ofEpochMilli(value.get(name).getAsLong());
    }

    private String nullable(JsonObject value, String name) {
        String text = value.get(name).getAsString();
        return text.isEmpty() ? null : text;
    }

    private String empty(String value) {
        return value == null ? "" : value;
    }
}
