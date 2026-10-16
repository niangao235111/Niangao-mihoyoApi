package com.niangao.mihoyo.modules.mihoyo.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.niangao.mihoyo.common.exception.BusinessException;
import com.niangao.mihoyo.modules.mihoyo.client.model.UpstreamReply;
import com.niangao.mihoyo.modules.mihoyo.config.MihoyoProperties;
import com.niangao.mihoyo.modules.mihoyo.util.CookieUtil;
import com.niangao.mihoyo.modules.mihoyo.util.DeviceIdUtil;
import com.niangao.mihoyo.modules.mihoyo.util.ProtocolJsonUtil;

import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Component;

import uigf.mihoyo.game.account.GameAccountClientBuilder;
import uigf.mihoyo.passport.PassportClientBuilder;

import java.util.Map;

/** 第三方账号查询协议：SToken 转 LToken，再查询通行证关联角色。 */
@Slf4j
@Component
public class MihoyoGameAccountClient {
    private final MihoyoProperties properties;
    private final SdkTransport transport;

    public MihoyoGameAccountClient(MihoyoProperties properties, SdkTransport transport) {
        this.properties = properties;
        this.transport = transport;
    }

    /** 先用 SToken 换取 LToken，再携带 LToken Cookie 查询通行证关联角色。两次请求共用设备标识，Client 不保存凭证或写入网站绑定关系。 */
    public JsonArray queryRoles(String stoken, String stuid, String mid, String ds) {
        return queryAuthorizedRoles(stoken, stuid, mid, ds).roles();
    }

    /** 一次转换得到的 Cookie 仅供服务端当前请求继续读取游戏档案，禁止返回给网页。 */
    public record AuthorizedRoles(JsonArray roles, String cookie) {
        @Override
        public String toString() {
            return "AuthorizedRoles[redacted]";
        }
    }

    public AuthorizedRoles queryAuthorizedRoles(String stoken, String stuid, String mid, String ds) {
        return queryAuthorizedRoles(stoken, stuid, mid, ds, DeviceIdUtil.generate());
    }

    /** 档案链路复用同一账号的稳定设备 ID，避免换取 LToken 和读取战绩时切换设备。 */
    public AuthorizedRoles queryAuthorizedRoles(String stoken, String stuid, String mid, String ds, String device) {
        log.debug("关联游戏账号上游请求 stage=stoken_to_ltoken");
        // 第一步：关联角色接口使用 LToken，因此先转换 SToken。
        UpstreamReply converted = new UpstreamReply();
        var tokenClient =
                new PassportClientBuilder()
                        .endpoint(properties.getPassportEndpoint())
                        .httpClient(transport.createReadOnly("stoken_to_ltoken", device, converted, false, 262144, headers -> {}))
                        .httpRetryOptions(SdkTransport.noRetries())
                        .httpRedirectOptions(SdkTransport.noRedirects())
                        .httpInstrumentationOptions(SdkTransport.noLogs())
                        .buildAccountTokenApiClient();
        try {
            tokenClient.getLTokenBySToken(CookieUtil.stoken(stoken, stuid, mid));
        } catch (RuntimeException ex) {
            if (converted.getBody() == null)
                throw ReadOnlyRetry.failure("stoken_to_ltoken", ex);
        }
        JsonObject token = data(converted, "stoken_to_ltoken");
        String ltoken = ProtocolJsonUtil.optional(token, "ltoken");
        if (ltoken == null) ltoken = ProtocolJsonUtil.optional(token, "ltoken_v2");
        if (ltoken == null) throw new BusinessException(502, "上游未返回 LToken");
        try {
            ltoken = CookieUtil.validate(ltoken, "upstream_ltoken");
        } catch (BusinessException ex) {
            throw new BusinessException(502, "上游 LToken 格式异常");
        }
        // 第二步：只把 LToken 放入查询 Cookie，不把转换结果返回前端。
        log.debug("关联游戏账号上游请求 stage=binding_roles");
        UpstreamReply bound = new UpstreamReply();
        var roleClient =
                new GameAccountClientBuilder()
                        .endpoint(properties.getBindingEndpoint())
                        .httpClient(transport.createReadOnly("binding_roles", device, bound, true, 262144, headers -> {}))
                        .httpRetryOptions(SdkTransport.noRetries())
                        .httpRedirectOptions(SdkTransport.noRedirects())
                        .httpInstrumentationOptions(SdkTransport.noLogs())
                        .buildGameBindingApiClient();
        // 渠道筛选在 Service 内做，上游请求不传 game_biz，保持已验证的请求方式。
        try {
            roleClient.getRoles(CookieUtil.ltoken(ltoken, stuid, mid), ds, null);
        } catch (RuntimeException ex) {
            if (bound.getBody() == null) throw ReadOnlyRetry.failure("binding_roles", ex);
        }
        JsonObject result = data(bound, "binding_roles");
        if (!result.has("list") || !result.get("list").isJsonArray())
            throw new BusinessException(502, "上游缺少游戏账号列表");
        return new AuthorizedRoles(result.getAsJsonArray("list"), CookieUtil.ltoken(ltoken, stuid, mid));
    }

    /** 统一检查上游 HTTP、retcode 和 data。失败只公开错误阶段及错误码，不转发可能包含敏感信息的上游 message。 */
    private JsonObject data(UpstreamReply reply, String stage) {
        SdkTransport.validateReply(reply);
        JsonObject body = reply.getBody();
        int code = ProtocolJsonUtil.integer(body, "retcode");
        log.info("关联游戏账号上游结果 stage={}, upstream_retcode={}", stage, code);
        if (code != 0)
            throw new BusinessException(
                    422,
                    code == -100 || code == -101 ? "凭证无效或已过期，请重新登录" : "查询被上游拒绝",
                    Map.of("upstream_retcode", code, "stage", stage));
        if (!body.has("data") || !body.get("data").isJsonObject())
            throw new BusinessException(502, "上游缺少 data: " + stage);
        return body.getAsJsonObject("data");
    }
}
