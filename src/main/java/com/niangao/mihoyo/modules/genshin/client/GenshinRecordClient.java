package com.niangao.mihoyo.modules.genshin.client;

import com.google.gson.*;
import com.niangao.mihoyo.common.exception.BusinessException;
import com.niangao.mihoyo.modules.genshin.config.GenshinProperties;
import com.niangao.mihoyo.modules.genshin.dto.GenshinRecordRequest;
import com.niangao.mihoyo.modules.genshin.util.GenshinChannels;
import com.niangao.mihoyo.modules.genshin.service.GenshinDeviceService;
import com.niangao.mihoyo.modules.mihoyo.client.MihoyoGameAccountClient;
import com.niangao.mihoyo.modules.mihoyo.client.SdkTransport;
import com.niangao.mihoyo.modules.mihoyo.client.ReadOnlyRetry;
import com.niangao.mihoyo.modules.mihoyo.client.model.UpstreamReply;
import com.niangao.mihoyo.modules.mihoyo.util.CookieUtil;
import com.niangao.mihoyo.modules.mihoyo.util.ProtocolJsonUtil;
import io.clientcore.core.http.models.HttpHeaderName;
import io.clientcore.core.models.binarydata.BinaryData;
import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;
import uigf.mihoyo.DsSigner;
import uigf.mihoyo.game.CharacterDetailRequest;
import uigf.mihoyo.game.CharacterListRequest;
import uigf.mihoyo.game.genshin.record.GenshinRecordApiClient;
import uigf.mihoyo.game.genshin.record.RecordClientBuilder;
import java.util.*;
import java.util.function.Consumer;

/** 使用真实 SDK 与 DS v2；先验证通行证绑定，禁止只凭任意 UID 读取装备档案。 */
@Component
@Slf4j
public class GenshinRecordClient {
    private final MihoyoGameAccountClient accounts;
    private final SdkTransport transport;
    private final GenshinProperties properties;
    private final GenshinDeviceService devices;

    public GenshinRecordClient(MihoyoGameAccountClient accounts, SdkTransport transport,
            GenshinProperties properties, GenshinDeviceService devices) {
        this.accounts = accounts;
        this.transport = transport;
        this.properties = properties;
        this.devices = devices;
    }

    public record Authorization(String cookie, GenshinDeviceService.Device device) {
        @Override public String toString() { return "RecordAuthorization[redacted]"; }
    }

    public Authorization authorize(GenshinRecordRequest request) {
        CookieUtil.validate(request.stoken(), "stoken");
        CookieUtil.validate(request.mid(), "mid");
        if (!GenshinChannels.supports(request.gameBiz(), request.region())) {
            throw new BusinessException(400, "原神渠道与服务器不匹配");
        }
        String deviceId = devices.deviceId(request.stuid(), request.mid());
        var authorized = accounts.queryAuthorizedRoles(request.stoken(), request.stuid(), request.mid(), null, deviceId);
        for (JsonElement element : authorized.roles()) {
            if (!element.isJsonObject()) continue;
            JsonObject role = element.getAsJsonObject();
            if (request.gameUid().equals(ProtocolJsonUtil.optional(role, "game_uid"))
                    && request.region().equals(ProtocolJsonUtil.optional(role, "region"))
                    && GenshinChannels.supports(ProtocolJsonUtil.optional(role, "game_biz"), ProtocolJsonUtil.optional(role, "region"))) {
                return new Authorization(authorized.cookie(), devices.resolve(request.stuid(), request.mid()));
            }
        }
        throw new BusinessException(403, "该原神角色未关联到当前米哈游账号，请重新查询绑定角色");
    }

    public JsonObject overview(GenshinRecordRequest request) {
        return overview(request, null);
    }
    public JsonObject overview(GenshinRecordRequest request, String challenge) {
        var authorization = authorize(request);
        return call("genshin_overview", authorization.device(), challenge, client -> client.getIndex(authorization.cookie(), request.region(),
                request.gameUid(), null, 0));
    }

    public JsonObject characters(GenshinRecordRequest request) {
        return characters(request, null);
    }
    public JsonObject characters(GenshinRecordRequest request, String challenge) {
        var authorization = authorize(request);
        return call("genshin_characters", authorization.device(), challenge, client -> client.listCharacters(authorization.cookie(),
                new CharacterListRequest(request.gameUid(), request.region())));
    }

    public JsonObject details(GenshinRecordRequest request) {
        return details(request, null);
    }
    public JsonObject details(GenshinRecordRequest request, String challenge) {
        if (request.characterIds() == null || request.characterIds().isEmpty()) {
            throw new BusinessException(400, "请选择 1..40 个角色");
        }
        var authorization = authorize(request);
        var ids = request.characterIds().stream().distinct()
                .map(id -> BinaryData.fromObject(Long.parseLong(id))).toList();
        return call("genshin_equipment", authorization.device(), challenge, client -> client.getCharacterDetails(authorization.cookie(),
                new CharacterDetailRequest(request.gameUid(), request.region(), ids)));
    }

    private JsonObject call(String stage, GenshinDeviceService.Device device, String challenge, Consumer<GenshinRecordApiClient> operation) {
        long started = System.nanoTime();
        var reply = new UpstreamReply();
        var http = transport.createReadOnly(stage, device.id(), reply, true, 4 * 1024 * 1024, headers -> {
            headers.set(HttpHeaderName.USER_AGENT, properties.getUserAgent());
            headers.set(HttpHeaderName.ORIGIN, "https://webstatic.mihoyo.com");
            headers.set(HttpHeaderName.REFERER, "https://webstatic.mihoyo.com/");
            headers.set(HttpHeaderName.fromString("X-Requested-With"), "com.mihoyo.hyperion");
            headers.set(HttpHeaderName.fromString("x-rpc-app_version"), properties.getAppVersion());
            headers.set(HttpHeaderName.fromString("x-rpc-device_fp"), device.fp());
            if (challenge != null) headers.set(HttpHeaderName.fromString("x-rpc-challenge"), CookieUtil.validate(challenge, "verification_challenge"));
        });
        var builder = new RecordClientBuilder().endpoint(properties.getRecordEndpoint())
                .httpClient(http).httpRetryOptions(SdkTransport.noRetries())
                .httpRedirectOptions(SdkTransport.noRedirects())
                .httpInstrumentationOptions(SdkTransport.noLogs());
        DsSigner.configure(builder, properties.getDsSalt());
        try {
            operation.accept(builder.buildGenshinRecordApiClient());
        } catch (RuntimeException ex) {
            // SDK 的固定模型可能不认识新属性，优先校验已捕获的原始响应。
            if (reply.getBody() == null) {
                log.warn("原神战绩请求失败 stage={}, http_status={}, error_type={}, elapsed_ms={}",
                        stage, reply.getHttpStatus(), ex.getClass().getSimpleName(), elapsed(started));
                throw ReadOnlyRetry.failure(stage, ex);
            }
        }
        try { SdkTransport.validateReply(reply); }
        catch (BusinessException ex) {
            log.warn("原神战绩响应异常 stage={}, http_status={}, has_json={}, elapsed_ms={}",
                    stage, reply.getHttpStatus(), reply.getBody() != null, elapsed(started));
            throw new BusinessException(ex.getStatus(), ex.getMessage(), Map.of("stage", stage, "http_status", reply.getHttpStatus()));
        }
        int code = ProtocolJsonUtil.integer(reply.getBody(), "retcode");
        log.info("原神战绩上游结果 stage={}, http_status={}, upstream_retcode={}, elapsed_ms={}",
                stage, reply.getHttpStatus(), code, elapsed(started));
        if (code != 0) {
            String message = switch (code) {
                case 10102 -> "战绩或角色详情未公开，请在米游社检查对应展示设置";
                case 5003 -> "米游社拒绝了当前设备的战绩请求（账号或设备风控），不代表工作台会话已过期；请在官方米游社检查账号安全状态后手动重试";
                case 1034, 10035, 10041 -> "米游社要求安全验证，请在官方米游社检查账号状态；官方应用正常也不代表本站设备已通过验证";
                case -100, -101 -> "米哈游凭证已失效，请重新连接账号";
                default -> "原神战绩查询被上游拒绝";
            };
            String reason = switch (code) {
                case 1034, 10035, 10041, 5003 -> "upstream_verification_required";
                default -> "upstream_rejected";
            };
            var details = new HashMap<String, Object>();
            details.put("stage", stage); details.put("upstream_retcode", code); details.put("reason", reason);
            if (reply.getTraceId() != null && reply.getTraceId().matches("[A-Za-z0-9_.:-]{1,256}")) details.put("verification_trace", reply.getTraceId());
            throw new BusinessException(422, message, details);
        }
        JsonElement data = reply.getBody().get("data");
        if (data == null || !data.isJsonObject()) throw new BusinessException(502, "原神战绩响应缺少数据", Map.of("stage", stage));
        return data.getAsJsonObject();
    }

    private long elapsed(long started) { return (System.nanoTime() - started) / 1_000_000; }
}
