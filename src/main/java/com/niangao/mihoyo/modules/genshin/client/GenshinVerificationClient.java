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
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.io.IOException;
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
    // 官方 community-game-records 网页（非 App bridge）使用的 DS1 配对。
    // 不复用战绩 /app API 的 DS2 盐、版本或请求路径。
    private static final String WEB_DS_SALT = "h8w582wxwgqvahcdkpvdhbh2w9casgfl";
    private static final String NONCE_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789";
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
    public Created create(GenshinRecordClient.Authorization authorization, Failure failure) {
        var reply = request("genshin_verification_create", "createVerification", "is_high=true", null,
                authorization.cookie(), authorization.device().id(), authorization.device().fp(), failure);
        JsonObject data = reply.data();
        try {
            if (data.has("use_v4") && data.get("use_v4").getAsBoolean()) throw new IllegalArgumentException();
            String gt = data.get("gt").getAsString(), challenge = data.get("challenge").getAsString();
            if (gt.isBlank() || gt.length() > 512 || challenge.isBlank() || challenge.length() > 512) throw new IllegalArgumentException();
            var captcha = new Challenge(gt, challenge, data.get("new_captcha").getAsInt(), data.get("success").getAsInt(), false);
            // success=0 表示上游以离线验证码回复，本地能显示但服务端无法校验；记录该状态便于区分风控与协议问题。
            log.info("战绩安全验证任务 stage={}, gt_len={}, challenge_len={}, new_captcha={}, success={}, data_keys={}",
                    "genshin_verification_create", gt.length(), challenge.length(), captcha.newCaptcha(), captcha.success(), dataKeys(reply.data()));
            String cookie = taskCookies(authorization.cookie(), reply.uri(), reply.headers().map(),
                    URI.create(properties.getRecordEndpoint() + "/game_record/card/wapi/verifyVerification"));
            log.info("战绩安全验证 Cookie 合并 stage=genshin_verification_create, request_cookie_names={}, response_set_cookie_names={}, merged_cookie_names={}",
                    cookieNames(authorization.cookie()), setCookieNames(reply.headers().map()), cookieNames(cookie));
            return new Created(captcha, cookie);
        } catch (RuntimeException ex) { throw new BusinessException(502, "米游社未返回支持的 GT3 验证任务，请稍后重试"); }
    }
    public String submit(Task task, VerificationSubmit result) {
        var body = new JsonObject();
        body.addProperty("geetest_challenge", result.geetestChallenge());
        body.addProperty("geetest_validate", result.geetestValidate());
        body.addProperty("geetest_seccode", result.geetestSeccode());
        JsonObject data = request("genshin_verification_submit", "verifyVerification", "", body.toString(),
                task.cookie(), task.deviceId(), task.fp(), task.failure()).data();
        try { return CookieUtil.validate(data.get("challenge").getAsString(), "verified_challenge"); }
        catch (RuntimeException ex) { throw new BusinessException(502, "米游社未返回战绩验证标识，请重新获取验证任务"); }
    }
    private record Reply(JsonObject data, URI uri, HttpHeaders headers) {
        @Override public String toString() { return "VerificationReply[redacted]"; }
    }
    private Reply request(String stage, String operation, String query, String body,
            String cookie, String deviceId, String fp, Failure failure) {
        try {
            String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
            var nonceBuilder = new StringBuilder(6);
            for (int i = 0; i < 6; i++) nonceBuilder.append(NONCE_CHARS.charAt(RANDOM.nextInt(NONCE_CHARS.length())));
            String nonce = nonceBuilder.toString();
            String signed = "salt=" + WEB_DS_SALT + "&t=" + timestamp + "&r=" + nonce;
            String ds = timestamp + "," + nonce + "," + HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(signed.getBytes(StandardCharsets.UTF_8)));
            String url = properties.getRecordEndpoint() + "/game_record/card/wapi/" + operation + (query.isEmpty() ? "" : "?" + query);
            String challengePath = path(failure.stage());
            boolean hasTrace = failure.trace() != null && failure.trace().matches("[A-Za-z0-9_.:-]{1,256}");
            var builder = HttpRequest.newBuilder(URI.create(url)).timeout(timeouts.getResponseTimeout())
                    .header("Cookie", cookie).header("DS", ds).header("User-Agent", properties.getUserAgent())
                    .header("Origin", "https://webstatic.mihoyo.com").header("Referer", "https://webstatic.mihoyo.com/")
                    .header("X-Requested-With", "com.mihoyo.hyperion").header("x-rpc-client_type", "5")
                    .header("x-rpc-app_version", "2.3.0").header("x-rpc-device_id", deviceId)
                    .header("x-rpc-device_fp", fp).header("x-rpc-challenge_game", "2")
                    .header("x-rpc-challenge_path", challengePath);
            if (hasTrace) builder.header("x-rpc-challenge_trace", failure.trace());
            if (body == null) builder.GET();
            else builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
            // 只记录协议形状：方法、路径、签名长度、挑战路径与 trace 是否存在；不记录 Cookie 值、DS 或验证码内容。
            log.info("战绩安全验证请求 stage={}, method={}, path={}, client_type=5, app_version=2.3.0, ds_len={}, challenge_path={}, challenge_trace_present={}, device_id_len={}, cookie_names={}",
                    stage, body == null ? "GET" : "POST", URI.create(url).getPath(), ds.length(), challengePath, hasTrace, deviceId.length(), cookieNames(cookie));
            var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            byte[] bytes; try (var stream = response.body()) { bytes = stream.readNBytes(65537); }
            if (response.statusCode() != 200 || bytes.length > 65536) throw unavailable(stage);
            var result = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
            int code = result.get("retcode").getAsInt();
            var traceHeader = response.headers().firstValue("x-trace-id");
            log.info("战绩安全验证上游结果 stage={}, http_status={}, upstream_retcode={}, set_cookie_count={}, response_trace_present={}, response_trace_len={}, response_aigis_present={}",
                    stage, response.statusCode(), code, response.headers().allValues("Set-Cookie").size(),
                    traceHeader.isPresent(), traceHeader.map(String::length).orElse(0),
                    response.headers().firstValue("x-rpc-aigis").isPresent());
            if (code != 0) {
                // 只提取错误说明与字段名；绝不记录原始响应、Cookie、验证码结果或放行标识。
                log.warn("战绩安全验证拒绝 stage={}, upstream_retcode={}, upstream_message={}, response_has_challenge={}, set_cookie_count={}, data_keys={}",
                        stage, code, diagnosticMessage(result),
                        result.has("data") && result.get("data").isJsonObject()
                                && result.getAsJsonObject("data").has("challenge"),
                        response.headers().allValues("Set-Cookie").size(), dataKeys(result));
            }
            if (code != 0) throw new BusinessException(422, "米游社未接受本次安全验证（上游代码 " + code + "），请获取新任务后重新完成验证",
                    Map.of("stage", stage, "upstream_retcode", code));
            if (!result.has("data") || !result.get("data").isJsonObject()) throw unavailable(stage);
            return new Reply(result.getAsJsonObject("data"), URI.create(url), response.headers());
        } catch (BusinessException ex) { throw ex; }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw unavailable(stage); }
        catch (Exception ex) { throw unavailable(stage); }
    }
    /** 每个任务独立的 Cookie 容器；响应 Cookie 只进入该任务的服务端密文，不共享给其他用户。 */
    static String taskCookies(String original, URI source, Map<String, List<String>> headers, URI target) {
        var jar = new CookieManager(null, SAME_OR_PARENT_DOMAIN);
        for (String part : original.split(";")) {
            int separator = part.indexOf('=');
            if (separator < 1) continue;
            var cookie = new HttpCookie(part.substring(0, separator).strip(), part.substring(separator + 1).strip());
            cookie.setVersion(0);
            cookie.setDomain(source.getHost());
            cookie.setPath("/");
            cookie.setSecure("https".equalsIgnoreCase(source.getScheme()));
            jar.getCookieStore().add(source, cookie);
        }
        try {
            jar.put(source, headers);
            return String.join("; ", jar.get(target, Map.of()).getOrDefault("Cookie", List.of()));
        } catch (IOException ex) {
            throw new BusinessException(502, "无法保存米游社验证会话，请稍后重新获取任务");
        }
    }
    /**
     * 上游 Set-Cookie 常带 Domain=.mihoyo.com，ACCEPT_ORIGINAL_SERVER 会把这些响应 Cookie 全部丢弃。
     * 这里按浏览器规则接受与请求主机相同或其父域的 Cookie，范围仍限于本次任务的上游响应。
     */
    private static final CookiePolicy SAME_OR_PARENT_DOMAIN = (uri, cookie) -> {
        String domain = cookie.getDomain();
        if (domain == null || domain.isBlank()) return true;
        String host = uri.getHost();
        if (host == null) return false;
        String bare = (domain.startsWith(".") ? domain.substring(1) : domain).toLowerCase(Locale.ROOT);
        String target = host.toLowerCase(Locale.ROOT);
        return target.equals(bare) || target.endsWith("." + bare);
    };
    static String cookieNames(String cookie) {
        if (cookie == null || cookie.isBlank()) return "none";
        var names = new TreeSet<String>();
        for (String part : cookie.split(";")) {
            int separator = part.indexOf('=');
            if (separator < 1) continue;
            String name = part.substring(0, separator).strip();
            if (name.matches("[A-Za-z0-9_-]{1,32}")) names.add(name);
        }
        return names.isEmpty() ? "none" : String.join("|", names);
    }
    static String setCookieNames(Map<String, List<String>> headers) {
        var names = new TreeSet<String>();
        for (var entry : headers.entrySet()) {
            if (!"set-cookie".equalsIgnoreCase(entry.getKey())) continue;
            for (String value : entry.getValue()) {
                int separator = value.indexOf('=');
                if (separator < 1) continue;
                String name = value.substring(0, separator).strip();
                if (name.matches("[A-Za-z0-9_-]{1,32}")) names.add(name);
            }
        }
        return names.isEmpty() ? "none" : String.join("|", names);
    }
    static String dataKeys(JsonObject result) {
        if (result == null || !result.has("data") || !result.get("data").isJsonObject()) return "none";
        var keys = result.getAsJsonObject("data").keySet().stream()
                .filter(key -> key.matches("[A-Za-z0-9_]{1,32}")).sorted().limit(20).toList();
        return keys.isEmpty() ? "none" : String.join("|", keys);
    }
    static String diagnosticMessage(JsonObject result) {
        var value = result.get("message");
        if (value == null || value.isJsonNull() || (value.isJsonPrimitive()
                && value.getAsJsonPrimitive().isString() && value.getAsString().isBlank())) value = result.get("msg");
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) return "unavailable";
        String message = value.getAsString();
        if (message.length() > 256) return "redacted";
        // 上游有时在 message 中回显字段值。仅保留汉字和常用标点，ASCII 标识、数字、URL 一律移除。
        message = message.replaceAll("[^\\p{IsHan}，。！？：；、（）《》“”‘’\\s]+", "[redacted]")
                .replaceAll("[\\p{Cc}\\p{Cf}\\s]+", " ").strip();
        return message.isEmpty() ? "unavailable" : message;
    }
    private BusinessException unavailable(String stage) {
        return new BusinessException(502, "米游社安全验证请求异常，未自动重试；请重新获取验证任务", Map.of("stage", stage));
    }
}
