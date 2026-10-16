package com.niangao.mihoyo.modules.mihoyo.entity;

import com.google.gson.JsonObject;
import com.niangao.mihoyo.modules.mihoyo.enums.LoginOperation;
import com.niangao.mihoyo.modules.mihoyo.enums.LoginState;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 内部流程实体，不是数据库用户实体，也不能直接作为 HTTP 响应返回。 实体是 Redis 的一次快照，状态变更必须由 Lua 原子提交。 Lombok 生成访问器；身份字段 final 不生成
 * setter，toString 手动脱敏。
 */
@Getter
@Setter
public class LoginSession {
    /** 流程会话 ID，前端用它关联短信和验证码提交。 */
    private final String id;

    /** 本次流程的手机号，仅后端保存，不出现在会话摘要中。 */
    private final String mobile;

    /** 手机号区号，当前只支持 +86。 */
    private final String areaCode;

    /** 本次流程固定的随机设备标识，发送短信和登录共用。 */
    private final String deviceId;

    /** 从创建时确定的过期时间，后续请求不会续期。 */
    private final Instant expiresAt;

    /** 流程状态，决定哪些接口操作可以继续执行。 */
    private LoginState state;

    /** 上游短信成功返回的登录操作类型，提交验证码时需要带回。 */
    private String actionType;

    /** 当前人机验证属于短信阶段还是登录阶段。 */
    private LoginOperation pendingOperation;

    /** 米哈游风控会话 ID，与本地 id 不是同一个概念。 */
    private String aigisSessionId;

    /** 待处理的上游 GT3/GT4 任务，保存到验证成功或流程结束。 */
    private JsonObject challenge;

    /** 本流程已实际发起的短信上游调用次数。 */
    private int smsAttempts;

    /** 本流程已实际发起的验证码登录调用次数。 */
    private int loginAttempts;

    /** 当前风险验证任务已提交的结果次数。 */
    private int challengeAttempts;

    /** 用于摘要显示的短信冷却时间；手机号共享冷却另存于 Mapper。 */
    private Instant resendAfter;

    /** Redis 原子转换版本，用于拒绝旧快照提交。 */
    private long version;

    /** 本次上游调用的唯一 ID，用于拒绝迟到结果。 */
    private String operationId;

    /** 本次上游调用的执行截止时间，以 Redis TIME 为准。 */
    private Instant operationDeadline;

    /** 快照读取时的 Redis 时间，摘要不依赖不同实例的本地时钟。 */
    private Instant snapshotTime;

    /** 短信成功后上游要求的冷却秒数，保存时由 Lua 换算服务器时间。 */
    private int smsCooldownSeconds;

    /** 创建内部流程实体并初始化 CREATED 状态，身份字段由构造方法一次性确定。 */
    public LoginSession(
            String id, String mobile, String areaCode, String deviceId, Instant expiresAt) {
        this.id = id;
        this.mobile = mobile;
        this.areaCode = areaCode;
        this.deviceId = deviceId;
        this.expiresAt = expiresAt;
        this.state = LoginState.CREATED;
        this.resendAfter = Instant.MIN;
    }

    @Override
    public String toString() {
        return "LoginSession[state=" + state + "]";
    }
}
