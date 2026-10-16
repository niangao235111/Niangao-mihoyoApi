package com.niangao.mihoyo.modules.genshin.client;

import com.google.gson.*;
import com.niangao.mihoyo.common.exception.BusinessException;
import com.niangao.mihoyo.modules.genshin.config.GenshinProperties;
import com.niangao.mihoyo.modules.genshin.dto.VerificationSubmit;
import com.niangao.mihoyo.modules.genshin.entity.GenshinVerification.*;
import com.niangao.mihoyo.modules.mihoyo.config.MihoyoProperties;
import com.niangao.mihoyo.modules.mihoyo.util.CookieUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** 与官方国服战绩网页一致的 GT3 create/verify 流程，固定国服路径，不使用海外接口。 */
@Component
@Slf4j
public class GenshinVerificationClient {
    private final GenshinProperties properties;
    private final MihoyoProperties timeouts;
    private final HttpClient http;
    private static final SecureRandom RANDOM = new SecureRandom();
    public GenshinVerificationClient(GenshinProperties properties, MihoyoProperties timeouts) {
        this.properties = properties; this.timeouts = timeouts;
        http = HttpClient.newBuilder().connectTimeout(timeouts.getConnectTimeout()).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    public static String path(String stage) {
        return switch (stage) {
            case "genshin_overview" -> "/genshin/api/index";
            case "genshin_characters" -> "/genshin/api/character/list";
            case "genshin_equipment" -> "/genshin/api/character/detail";
            default -> throw new BusinessException(400, "不支持的档案验证场景");
        };
    }
    public Challenge create(GenshinRecordClient.Authorization authorization, Failure failure) {
        JsonObject data = request("genshin_verification_create", "createVerification", "is_high=true", null,
                authorization.cookie(), authorization.device().id(), authorization.device().fp(), failure);
        try {
            if (data.has("use_v4") && data.get("use_v4").getAsBoolean()) throw new IllegalArgumentException();
            String gt = data.get("gt").getAsString(), challenge = data.get("challenge").getAsString();
            if (gt.isBlank() || gt.length() > 512 || challenge.isBlank() || challenge.length() > 512) throw new IllegalArgumentException();
            return new Challenge(gt, challenge, data.get("new_captcha").getAsInt(), data.get("success").getAsInt(), false);
        } catch (RuntimeException ex) { throw new BusinessException(502, "米游社未返回支持的 GT3 验证任务，请稍后重试"); }
    }
    public String submit(Task task, VerificationSubmit result) {
        var body = new JsonObject();
        body.addProperty("geetest_challenge", result.geetestChallenge());
        body.addProperty("geetest_validate", result.geetestValidate());
        body.addProperty("geetest_seccode", result.geetestSeccode());
        JsonObject data = request("genshin_verification_submit", "verifyVerification", "", body.toString(),
                task.cookie(), task.deviceId(), task.fp(), task.failure());
        try { return CookieUtil.validate(data.get("challenge").getAsString(), "verified_challenge"); }
        catch (RuntimeException ex) { throw new BusinessException(502, "米游社未返回战绩验证标识，请重新获取验证任务"); }
    }
    private JsonObject request(String stage, String operation, String query, String body,
            String cookie, String deviceId, String fp, Failure failure) {
        try {
            String timestamp = String.valueOf(System.currentTimeMillis() / 1000), nonce = String.valueOf(RANDOM.nextInt(100001) + 100000);
            String signed = "salt=" + properties.getDsSalt() + "&t=" + timestamp + "&r=" + nonce + "&b=" + (body == null ? "" : body) + "&q=" + query;
            String ds = timestamp + "," + nonce + "," + HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(signed.getBytes(StandardCharsets.UTF_8)));
            String url = properties.getRecordEndpoint() + "/game_record/app/card/wapi/" + operation + (query.isEmpty() ? "" : "?" + query);
            var builder = HttpRequest.newBuilder(URI.create(url)).timeout(timeouts.getResponseTimeout())
                    .header("Cookie", cookie).header("DS", ds).header("User-Agent", properties.getUserAgent())
                    .header("Origin", "https://webstatic.mihoyo.com").header("Referer", "https://webstatic.mihoyo.com/")
                    .header("X-Requested-With", "com.mihoyo.hyperion").header("x-rpc-client_type", "5")
                    .header("x-rpc-app_version", properties.getAppVersion()).header("x-rpc-device_id", deviceId)
                    .header("x-rpc-device_fp", fp).header("x-rpc-challenge_game", "2")
                    .header("x-rpc-challenge_path", path(failure.stage()));
            if (failure.trace() != null && failure.trace().matches("[A-Za-z0-9_.:-]{1,256}")) builder.header("x-rpc-challenge_trace", failure.trace());
            if (body == null) builder.GET();
            else builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
            var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            byte[] bytes; try (var stream = response.body()) { bytes = stream.readNBytes(65537); }
            if (response.statusCode() != 200 || bytes.length > 65536) throw unavailable(stage);
            var result = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
            int code = result.get("retcode").getAsInt();
            log.info("战绩安全验证上游结果 stage={}, http_status={}, upstream_retcode={}", stage, response.statusCode(), code);
            if (code != 0) throw new BusinessException(422, "米游社安全验证未通过，请重新获取任务后由本人完成验证",
                    Map.of("stage", stage, "upstream_retcode", code));
            if (!result.has("data") || !result.get("data").isJsonObject()) throw unavailable(stage);
            return result.getAsJsonObject("data");
        } catch (BusinessException ex) { throw ex; }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw unavailable(stage); }
        catch (Exception ex) { throw unavailable(stage); }
    }
    private BusinessException unavailable(String stage) {
        return new BusinessException(502, "米游社安全验证请求异常，未自动重试；请重新获取验证任务", Map.of("stage", stage));
    }
}
