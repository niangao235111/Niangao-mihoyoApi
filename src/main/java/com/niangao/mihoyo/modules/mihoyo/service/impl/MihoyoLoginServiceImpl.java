package com.niangao.mihoyo.modules.mihoyo.service.impl;

import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import com.niangao.mihoyo.common.exception.BusinessException;
import com.niangao.mihoyo.modules.mihoyo.client.MihoyoPassportClient;
import com.niangao.mihoyo.modules.mihoyo.client.model.UpstreamReply;
import com.niangao.mihoyo.modules.mihoyo.config.MihoyoProperties;
import com.niangao.mihoyo.modules.mihoyo.dto.*;
import com.niangao.mihoyo.modules.mihoyo.entity.LoginSession;
import com.niangao.mihoyo.modules.mihoyo.enums.*;
import com.niangao.mihoyo.modules.mihoyo.mapper.LoginSessionMapper;
import com.niangao.mihoyo.modules.mihoyo.service.MihoyoLoginService;
import com.niangao.mihoyo.modules.mihoyo.util.DeviceIdUtil;
import com.niangao.mihoyo.modules.mihoyo.util.ProtocolJsonUtil;
import com.niangao.mihoyo.modules.mihoyo.vo.*;

import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.*;

/** 业务层负责状态机、限流和响应组装；存储委托 Mapper，协议请求委托 Client。 */
@Slf4j
@Service
public class MihoyoLoginServiceImpl implements MihoyoLoginService {

    private final LoginSessionMapper mapper;

    private final MihoyoPassportClient client;

    private final MihoyoProperties properties;

    private final Clock clock;

    public MihoyoLoginServiceImpl(
            LoginSessionMapper mapper,
            MihoyoPassportClient client,
            MihoyoProperties properties,
            Clock clock) {
        this.mapper = mapper;
        this.client = client;
        this.properties = properties;
        this.clock = clock;
    }

    /** 创建一次短信登录流程，固定手机号、设备标识和过期时间。这里只写入会话，不向米哈游发送短信。 */
    @Override
    public LoginResponse start(StartLoginRequest request) {
        String area = request.areaCode() == null ? "+86" : request.areaCode();
        if (request.mobile() == null
                || !request.mobile().matches("1[3-9][0-9]{9}")
                || !area.equals("+86")) {
            throw new BusinessException(400, "仅支持大陆手机号和 +86 区号");
        }
        // 每次 start 都创建独立流程；设备和有效期只在这里确定一次。
        LoginSession session =
                new LoginSession(
                        UUID.randomUUID().toString(),
                        request.mobile(),
                        area,
                        DeviceIdUtil.generate(),
                        clock.instant().plus(properties.getSessionTtl()));
        LoginSession saved = mapper.insert(session);
        log.info(
                "米哈游登录会话已创建 session_tag={}, state={}, ttl_seconds={}",
                sessionTag(saved.getId()),
                saved.getState(),
                properties.getSessionTtl().toSeconds());
        return summary(saved, null, null, null);
    }

    /** 本地校验验证结果后，交由 Redis 原子校验版本、冷却、次数并取得执行资格。 */
    @Override
    public LoginResponse sendSms(SendSmsRequest request) {
        LoginSession snapshot = load(request.sessionId());
        String proof = proof(snapshot, request.geetestResult(), LoginOperation.SMS);
        LoginSession session =
                mapper.beginOperation(
                        snapshot.getId(), snapshot.getVersion(), LoginOperation.SMS, proof != null);
        return execute(session, LoginOperation.SMS, null, proof);
    }

    /** 取得 Redis 执行资格后才调用米哈游；不能依赖 Java 对象锁保护多实例请求。 */
    @Override
    public LoginResponse verify(VerifyLoginRequest request) {
        if (request.captcha() == null || !request.captcha().matches("[0-9]{4,8}")) {
            throw new BusinessException(400, "短信验证码必须是 4～8 位数字字符串");
        }
        LoginSession snapshot = load(request.sessionId());
        String proof = proof(snapshot, request.geetestResult(), LoginOperation.LOGIN);
        LoginSession session =
                mapper.beginOperation(
                        snapshot.getId(),
                        snapshot.getVersion(),
                        LoginOperation.LOGIN,
                        proof != null);
        return execute(session, LoginOperation.LOGIN, request.captcha(), proof);
    }

    /** 读取 Redis 快照；超时遗留操作会由读取脚本置为 FAILED，不重新返回凭证。 */
    @Override
    public LoginResponse status(SessionRequest request) {
        LoginSession session = load(request.sessionId());
        // 状态轮询频繁，仅在 DEBUG 级别输出。
        log.debug(
                "查询米哈游登录状态 session_tag={}, state={}",
                sessionTag(session.getId()),
                session.getState());
        return summary(session, null, null, null);
    }

    /** 删除 Redis 会话，保留手机号冷却；迟到的上游回调也不能把会话重新创建。 */
    @Override
    public MessageResponse delete(SessionRequest request) {
        requireId(request.sessionId());
        mapper.delete(request.sessionId());
        log.info("米哈游登录会话已删除 session_tag={}", sessionTag(request.sessionId()));
        return new MessageResponse("会话已清除");
    }

    /** 会话 ID 是流程访问凭据，只记录 UUID 前 8 位；非法标识不原样写日志。 */
    private String sessionTag(String id) {
        return id != null && id.matches("[0-9a-fA-F-]{36}") ? id.substring(0, 8) : "invalid";
    }

    /** 使用单调时钟计算耗时，避免系统时间调整影响统计。 */
    private long elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }

    private LoginSession load(String id) {
        requireId(id);
        return mapper.findById(id);
    }

    private void requireId(String id) {
        if (id == null || id.isBlank()) throw new BusinessException(400, "缺少 session_id");
    }

    /** 请求、解析、保存分开执行；凭证只有在操作结果原子提交成功后才返回客户端。 */
    private LoginResponse execute(
            LoginSession session, LoginOperation operation, String captcha, String proof) {
        String operationId = session.getOperationId();
        long started = System.nanoTime();
        // 仅输出会话短标识，不能打印请求对象、验证结果或上游响应。
        log.info(
                "米哈游登录操作开始 session_tag={}, operation={}, state={}, has_proof={}",
                sessionTag(session.getId()),
                operation.getValue(),
                session.getState(),
                proof != null);
        try {
            LoginResponse result =
                    process(session, client.call(session, operation, captcha, proof), operation);
            LoginSession saved = mapper.completeOperation(session, operationId);
            log.info(
                    "米哈游登录操作完成 session_tag={}, operation={}, state={}, elapsed_ms={}",
                    sessionTag(saved.getId()),
                    operation.getValue(),
                    saved.getState(),
                    elapsedMillis(started));
            return summary(saved, result.sentNew(), result.message(), result.credentials());
        } catch (BusinessException ex) {
            log.warn(
                    "米哈游登录操作失败 session_tag={}, operation={}, status={}, elapsed_ms={}",
                    sessionTag(session.getId()),
                    operation.getValue(),
                    ex.getStatus(),
                    elapsedMillis(started));
            failQuietly(session.getId(), operationId);
            throw ex;
        } catch (RuntimeException ex) {
            // SDK 异常消息可能携带响应内容，仅记录类型和代码位置。
            log.error(
                    "米哈游登录操作异常 session_tag={}, operation={}, error_type={}, location={}, elapsed_ms={}",
                    sessionTag(session.getId()),
                    operation.getValue(),
                    ex.getClass().getSimpleName(),
                    ex.getStackTrace().length == 0 ? "unknown" : ex.getStackTrace()[0],
                    elapsedMillis(started));
            failQuietly(session.getId(), operationId);
            throw new BusinessException(502, "米哈游响应处理异常；未自动重试");
        }
    }

    /** Redis 临时不可用时保留原始错误，遗留执行状态在期限后由读取脚本失败关闭。 */
    private void failQuietly(String id, String operationId) {
        try {
            mapper.failOperation(id, operationId);
        } catch (BusinessException ignored) {
            /* 已删除、已超时、已完成或 Redis 不可用，不覆盖新状态。 */
            log.warn("登录失败状态未写入 session_tag={}, status={}", sessionTag(id), ignored.getStatus());
        }
    }

    /** 把上游 retcode 转换为本项目的流程状态：需要人机验证时暂停、短信成功时保存 action_type、登录成功时提取凭证。 */
    private LoginResponse process(
            LoginSession session, UpstreamReply reply, LoginOperation operation) {
        JsonObject body = reply.getBody();
        int retcode = ProtocolJsonUtil.integer(body, "retcode");
        log.info(
                "米哈游登录上游结果 session_tag={}, operation={}, upstream_retcode={}",
                sessionTag(session.getId()),
                operation.getValue(),
                retcode);
        // -3101 表示需要人工验证，不是登录成功；保存任务让前端完成后继续。
        if (retcode == -3101) {
            try {
                JsonObject aigis = JsonParser.parseString(reply.getAigis()).getAsJsonObject();
                String riskId = ProtocolJsonUtil.required(aigis, "session_id");
                JsonElement task = aigis.get("data");
                JsonObject challenge =
                        task.isJsonObject()
                                ? task.getAsJsonObject()
                                : JsonParser.parseString(task.getAsString()).getAsJsonObject();
                // 若是新任务则重置尝试计数；同一任务重复返回不能绕过次数限制。
                if (!riskId.equals(session.getAigisSessionId())) session.setChallengeAttempts(0);
                session.setAigisSessionId(riskId);
                session.setChallenge(challenge.deepCopy());
                session.setPendingOperation(operation);
                session.setState(
                        operation == LoginOperation.SMS
                                ? LoginState.SMS_CHALLENGE
                                : LoginState.LOGIN_CHALLENGE);
                return summary(session, null, null, null);
            } catch (RuntimeException ex) {
                throw new BusinessException(422, "需要人机验证，但上游验证任务格式不支持");
            }
        }
        if (retcode != 0)
            throw new BusinessException(
                    422,
                    "Upstream rejected login operation",
                    Map.of(
                            "upstream_retcode",
                            retcode,
                            "operation",
                            operation.getValue(),
                            "session_id",
                            session.getId(),
                            "state",
                            session.getState().name()));
        if (!body.has("data") || !body.get("data").isJsonObject())
            throw new BusinessException(502, "上游响应缺少 data");
        JsonObject data = body.getAsJsonObject("data");
        // 当前步骤成功后清理挑战信息，下一阶段若有新挑战会重新保存。
        session.setChallenge(null);
        session.setAigisSessionId(null);
        session.setPendingOperation(null);
        session.setChallengeAttempts(0);
        if (operation == LoginOperation.SMS) {
            String action = ProtocolJsonUtil.required(data, "action_type");
            if (action.equals("regist") || action.equals("register"))
                throw new BusinessException(422, "手机号尚未注册通行证，本接口不执行注册");
            session.setActionType(action);
            session.setState(LoginState.SMS_SENT);
            int seconds =
                    data.has("countdown")
                            ? Math.max(
                                    60, Math.min(300, ProtocolJsonUtil.integer(data, "countdown")))
                            : 60;
            session.setResendAfter(clock.instant().plusSeconds(seconds));
            session.setSmsCooldownSeconds(seconds);
            boolean sentNew = !data.has("sent_new") || ProtocolJsonUtil.bool(data, "sent_new");
            return summary(
                    session, sentNew, sentNew ? "短信已发送，请输入收到的验证码" : "服务端复用已发送的短信，请输入已有验证码", null);
        }
        // 先确认凭证完整再标记成功，防止缺字段响应被当作有效登录。
        MihoyoCredentials credentials = parseCredentials(data);
        session.setState(LoginState.LOGGED_IN);
        return summary(session, null, "登录成功，凭证仅在本次响应返回，不写入文件", credentials);
    }

    /**
     * 按当前任务的 GT3/GT4 版本校验结果，生成米哈游 x-rpc-aigis 请求头内容。没有验证任务时返回 null。
     *
     * @return 上游风险会话 ID 与 Base64 编码验证结果的组合
     */
    private String proof(LoginSession session, GeetestResult result, LoginOperation operation) {
        if (session.getChallenge() == null) return null;
        if (operation != session.getPendingOperation())
            throw new BusinessException(409, "请先完成待处理的验证操作");
        if (result == null) throw new BusinessException(409, "请先完成人机验证");
        if (session.getChallengeAttempts() >= 3) throw new BusinessException(429, "人机验证尝试次数已达上限");
        boolean v4 =
                session.getChallenge().has("use_v4")
                        && ProtocolJsonUtil.bool(session.getChallenge(), "use_v4");
        JsonObject clean = new JsonObject();
        // 两个版本的结果字段不同，按任务版本逐项取值形成白名单。
        if (v4) {
            addProof(clean, "captcha_id", result.captchaId());
            addProof(clean, "lot_number", result.lotNumber());
            addProof(clean, "pass_token", result.passToken());
            addProof(clean, "gen_time", result.genTime());
            addProof(clean, "captcha_output", result.captchaOutput());
        } else {
            addProof(clean, "geetest_challenge", result.geetestChallenge());
            addProof(clean, "geetest_validate", result.geetestValidate());
            addProof(clean, "geetest_seccode", result.geetestSeccode());
        }
        return session.getAigisSessionId()
                + ";"
                + Base64.getEncoder()
                        .encodeToString(
                                ProtocolJsonUtil.JSON
                                        .toJson(clean)
                                        .getBytes(StandardCharsets.UTF_8));
    }

    /** 只接收当前协议要求的非空验证字段，不将前端的整个对象直接转发给上游。 */
    private void addProof(JsonObject object, String key, String value) {
        if (value == null || value.isBlank()) throw new BusinessException(400, "缺少人机验证字段: " + key);
        object.addProperty(key, value);
    }

    /** 兼容上游 tokens 数组、token 对象和 stoken 字段格式。只有 token_type=1 的令牌作为 SToken；关键账号字段缺失时不能判定登录成功。 */
    private MihoyoCredentials parseCredentials(JsonObject data) {
        for (String key : List.of("realname_info", "reactivate_info")) {
            if (data.has(key) && data.get(key).isJsonObject()) {
                JsonObject task = data.getAsJsonObject(key);
                if (task.has("required") && ProtocolJsonUtil.bool(task, "required"))
                    throw new BusinessException(422, "请在官方应用完成账号验证");
            }
        }
        if (data.has("need_realperson") && ProtocolJsonUtil.bool(data, "need_realperson"))
            throw new BusinessException(422, "请在官方应用完成真人验证");
        // 上游凭证返回格式存在多种变体，优先读取明确标识为 SToken 的令牌。
        String stoken = null;
        if (data.has("tokens") && data.get("tokens").isJsonArray()) {
            for (JsonElement item : data.getAsJsonArray("tokens")) {
                if (item.isJsonObject()) {
                    JsonObject token = item.getAsJsonObject();
                    if (token.has("token_type")
                            && ProtocolJsonUtil.integer(token, "token_type") == 1)
                        stoken = ProtocolJsonUtil.optional(token, "token");
                }
            }
        }
        if (stoken == null && data.has("token") && data.get("token").isJsonObject()) {
            JsonObject token = data.getAsJsonObject("token");
            if (token.has("token_type") && ProtocolJsonUtil.integer(token, "token_type") == 1)
                stoken = ProtocolJsonUtil.optional(token, "token");
        }
        if (stoken == null) stoken = ProtocolJsonUtil.optional(data, "stoken");
        JsonObject user =
                data.has("user_info") && data.get("user_info").isJsonObject()
                        ? data.getAsJsonObject("user_info")
                        : data;
        // 这些名称都是通行证账号 ID 的协议别名，不是游戏 UID。
        String stuid = ProtocolJsonUtil.optional(user, "aid");
        if (stuid == null) stuid = ProtocolJsonUtil.optional(user, "account_id");
        if (stuid == null) stuid = ProtocolJsonUtil.optional(user, "uid");
        String mid = ProtocolJsonUtil.optional(user, "mid");
        if (stoken == null
                || stoken.isBlank()
                || stuid == null
                || stuid.isBlank()
                || mid == null
                || mid.isBlank()) {
            throw new BusinessException(502, "登录响应缺少 SToken、通行证 ID 或 mid");
        }
        return new MihoyoCredentials(
                "stoken", stoken, stuid, mid, ProtocolJsonUtil.optional(data, "login_ticket"));
    }

    /** 构造公开 VO，隐藏手机号、设备和计数等内部状态。传入的凭证仅进入本次返回值，不保存回流程实体。 */
    private LoginResponse summary(
            LoginSession session, Boolean sentNew, String message, MihoyoCredentials credentials) {
        Map<String, Object> challenge = null;
        if (session.getChallenge() != null) {
            challenge =
                    ProtocolJsonUtil.JSON.fromJson(
                            session.getChallenge(),
                            new TypeToken<Map<String, Object>>() {}.getType());
        }
        String verificationUrl =
                challenge == null
                        ? null
                        : properties.getFrontendUrl().replaceAll("/+$", "")
                                + "/?session_id="
                                + session.getId();
        return new LoginResponse(
                session.getId(),
                session.getState(),
                Math.max(
                        0,
                        Duration.between(session.getSnapshotTime(), session.getExpiresAt())
                                .getSeconds()),
                Math.max(
                        0,
                        Duration.between(session.getSnapshotTime(), session.getResendAfter())
                                .getSeconds()),
                challenge,
                session.getPendingOperation() == null
                        ? null
                        : session.getPendingOperation().getValue(),
                session.getAigisSessionId(),
                verificationUrl,
                sentNew,
                message,
                credentials);
    }
}
