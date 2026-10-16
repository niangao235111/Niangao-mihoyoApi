package com.niangao.mihoyo.modules.genshin.client;

import com.google.gson.*;
import com.niangao.mihoyo.common.exception.BusinessException;
import com.niangao.mihoyo.modules.genshin.config.GenshinProperties;
import com.niangao.mihoyo.modules.mihoyo.config.MihoyoProperties;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 申请与本站稳定设备 ID 对应的真实指纹；不发送 Cookie 或米哈游账号凭证。 */
@Component
public class GenshinDeviceClient {
    private final GenshinProperties properties;
    private final MihoyoProperties timeouts;
    private final HttpClient http;

    public GenshinDeviceClient(GenshinProperties properties, MihoyoProperties timeouts) {
        this.properties = properties;
        this.timeouts = timeouts;
        this.http = HttpClient.newBuilder().connectTimeout(timeouts.getConnectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public String fetch(String deviceId) {
        JsonObject body = new JsonObject();
        body.addProperty("device_id", deviceId);
        body.addProperty("seed_id", UUID.randomUUID().toString());
        body.addProperty("seed_time", String.valueOf(System.currentTimeMillis()));
        body.addProperty("platform", "4");
        // 此字段只是申请时的初始值；绝不作为成功指纹使用，必须采用服务端返回值。
        body.addProperty("device_fp", UUID.randomUUID().toString().replace("-", "").substring(0, 13));
        body.addProperty("app_name", "bbs_cn");
        JsonObject fields = new JsonObject(); fields.addProperty("userAgent", properties.getUserAgent());
        body.addProperty("ext_fields", fields.toString());
        try {
            var request = HttpRequest.newBuilder(URI.create(properties.getDeviceFpEndpoint() + "/device-fp/api/getFp"))
                    .timeout(timeouts.getResponseTimeout()).header("Content-Type", "application/json")
                    .header("User-Agent", properties.getUserAgent())
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            byte[] bytes;
            try (var stream = response.body()) { bytes = stream.readNBytes(65537); }
            if (response.statusCode() != 200 || bytes.length > 65536) throw failure();
            var result = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
            var data = result.getAsJsonObject("data");
            if (!result.has("retcode") || result.get("retcode").getAsInt() != 0 || data == null
                    || !data.has("code") || data.get("code").getAsInt() != 200) throw failure();
            String fp = data.get("device_fp").getAsString();
            if (!fp.matches("[A-Za-z0-9_-]{6,128}")) throw failure();
            return fp;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt(); throw failure();
        } catch (Exception ex) { throw failure(); }
    }

    private BusinessException failure() {
        return new BusinessException(502, "米游社设备指纹申请失败，请稍后重试；未自动重试",
                Map.of("stage", "genshin_device_fp"));
    }
}
