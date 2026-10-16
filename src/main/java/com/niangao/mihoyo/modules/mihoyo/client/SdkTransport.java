package com.niangao.mihoyo.modules.mihoyo.client;

import com.google.gson.JsonParser;
import com.niangao.mihoyo.common.exception.BusinessException;
import com.niangao.mihoyo.modules.mihoyo.client.model.UpstreamReply;
import com.niangao.mihoyo.modules.mihoyo.config.MihoyoProperties;

import io.clientcore.core.http.client.*;
import io.clientcore.core.http.models.*;
import io.clientcore.core.http.pipeline.*;
import io.clientcore.core.models.binarydata.BinaryData;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.EnumSet;

/** SDK HTTP 适配：固定头、有限响应、禁用自动重试/跳转/敏感请求日志。 */
@Component
public class SdkTransport {
    private final MihoyoProperties properties;

    public SdkTransport(MihoyoProperties properties) {
        this.properties = properties;
    }

    /**
     * 为一次 SDK 调用创建 HTTP 适配器，补充协议请求头并抓取有限大小的原始 JSON。
     *
     * @param deviceId 本次调用使用的设备标识
     * @param capture 保存状态、风险验证头及原始 JSON 的容器
     * @param binding true 为游戏关联查询，false 为通行证请求
     * @param proof 已完成的人机验证结果头，可为空
     */
    public HttpClient create(
            String deviceId, UpstreamReply capture, boolean binding, String proof) {
        return create(deviceId, capture, binding, proof, 262144);
    }

    /** 角色装备详情包含多张图片地址和属性，允许调用方选择有限的响应上限。 */
    public HttpClient create(
            String deviceId, UpstreamReply capture, boolean binding, String proof, int maxBytes) {
        return create(deviceId, capture, binding, proof, maxBytes, headers -> {});
    }

    /** 在公共头之后设置业务所需的最终请求头，避免战绩头被通用关联查询头覆盖。 */
    public HttpClient create(String deviceId, UpstreamReply capture, boolean binding, String proof, int maxBytes,
            java.util.function.Consumer<HttpHeaders> customize) {
        if (maxBytes < 1 || maxBytes > 4 * 1024 * 1024) {
            throw new IllegalArgumentException("Invalid response limit");
        }
        HttpClient delegate =
                new JdkHttpClientBuilder()
                        .connectionTimeout(properties.getConnectTimeout())
                        .responseTimeout(properties.getResponseTimeout())
                        .readTimeout(properties.getResponseTimeout())
                        .build();
        return request -> {
            request.getHeaders()
                    .set(
                            HttpHeaderName.USER_AGENT,
                            binding
                                    ? "Mozilla/5.0 NiangaoGameAccounts/1.0"
                                    : "HYPContainer/1.1.4.133");
            request.getHeaders().set(HttpHeaderName.fromString("x-rpc-device_id"), deviceId);
            request.getHeaders()
                    .set(HttpHeaderName.fromString("x-rpc-client_type"), binding ? "5" : "3");
            if (binding) {
                request.getHeaders().set(HttpHeaderName.ORIGIN, "https://bbs.mihoyo.com");
                request.getHeaders().set(HttpHeaderName.REFERER, "https://bbs.mihoyo.com/");
            } else
                request.getHeaders().set(HttpHeaderName.fromString("x-rpc-app_id"), "ddxf5dufpuyo");
            if (proof != null)
                request.getHeaders().set(HttpHeaderName.fromString("x-rpc-aigis"), proof);
            customize.accept(request.getHeaders());
            // JDK 根据实际 BodyPublisher 生成长度；它本来也会忽略 SDK 手工设置的此头。
            request.getHeaders().remove(HttpHeaderName.fromString("Content-Length"));
            Response<BinaryData> response = delegate.send(request);
            capture.setHttpStatus(response.getStatusCode());
            capture.setTraceId(response.getHeaders().getValue(HttpHeaderName.fromString("x-trace-id")));
            capture.setAigis(
                    response.getHeaders().getValue(HttpHeaderName.fromString("x-rpc-aigis")));
            try {
                byte[] body;
                try (var stream = response.getValue().toStream()) {
                    // 多读一个字节以检测超过 256 KB 的响应，避免无界读取。
                    body = stream.readNBytes(maxBytes + 1);
                }
                if (body.length > maxBytes) throw new BusinessException(502, "上游响应过大");
                try {
                    capture.setBody(
                            JsonParser.parseString(new String(body, StandardCharsets.UTF_8))
                                    .getAsJsonObject());
                } catch (RuntimeException ignored) {
                    /* Client 在返回前校验状态及 JSON。 */
                }
                // 原始流已读完，用有限字节重建响应供 SDK 继续解析。
                return new Response<>(
                        request,
                        response.getStatusCode(),
                        new HttpHeaders(response.getHeaders()),
                        BinaryData.fromBytes(body));
            } catch (BusinessException ex) {
                throw ex;
            } catch (Exception ex) {
                throw new BusinessException(502, "无法读取上游响应");
            } finally {
                response.close();
            }
        };
    }

    /** 关闭 SDK 自动重试，避免网络超时时重复发短信或重复提交验证码。 */
    public static HttpRetryOptions noRetries() {
        return new HttpRetryOptions(0, Duration.ofMillis(1));
    }

    /** 禁用上游重定向，避免带凭证的请求被自动转发到其他地址。 */
    public static HttpRedirectOptions noRedirects() {
        return new HttpRedirectOptions(
                0, HttpHeaderName.LOCATION, EnumSet.noneOf(HttpMethod.class));
    }

    /** 禁用 SDK 的请求响应日志、追踪和指标功能，减少敏感请求信息进入日志的机会。 */
    public static HttpInstrumentationOptions noLogs() {
        return new HttpInstrumentationOptions()
                .setHttpLogLevel(HttpInstrumentationOptions.HttpLogLevel.NONE)
                .setTracingEnabled(false)
                .setMetricsEnabled(false);
    }

    /** 仅校验传输层成功和 JSON 对象存在；HTTP 200 不等于米哈游业务成功，retcode 由调用方继续检查。 */
    public static void validateReply(UpstreamReply reply) {
        if (reply.getHttpStatus() != 200 || reply.getBody() == null) {
            throw new BusinessException(502, "上游 HTTP 或 JSON 响应异常；未自动重试");
        }
    }
}
