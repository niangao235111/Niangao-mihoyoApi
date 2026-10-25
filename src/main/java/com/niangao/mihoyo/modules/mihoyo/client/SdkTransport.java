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
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.EnumSet;
import javax.net.ssl.SSLContext;

/** SDK HTTP 适配：固定头、有限响应；仅显式只读调用允许有限网络重试。 */
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
        return create(deviceId, capture, binding, proof, maxBytes, customize, null);
    }

    public HttpClient createReadOnly(String stage, String deviceId, UpstreamReply capture, boolean binding,
            int maxBytes, java.util.function.Consumer<HttpHeaders> customize) {
        return request -> {
            // SDK 的只读查询也可能使用 POST；先物化请求体，确保每次重放使用完整相同字节。
            if (request.getBody() != null) request.setBody(BinaryData.fromBytes(request.getBody().toBytes()));
            return ReadOnlyRetry.execute(stage, remaining ->
                    create(deviceId, capture, binding, null, maxBytes, customize, remaining).send(request));
        };
    }

    // 祈愿为长分页读取；稳定的超时档位复用客户端，避免每页新建 TLS 连接。
    private final java.util.Map<String, HttpClient> readClients = new java.util.LinkedHashMap<>();
    public HttpClient createReadOnlyPooled(String stage, String deviceId, UpstreamReply capture, boolean binding,
            int maxBytes, java.util.function.Consumer<HttpHeaders> customize) {
        return request -> {
            if (request.getBody() != null) request.setBody(BinaryData.fromBytes(request.getBody().toBytes()));
            return ReadOnlyRetry.execute(stage, remaining ->
                    create(deviceId, capture, binding, null, maxBytes, customize, remaining, true).send(request));
        };
    }

    private HttpClient create(String deviceId, UpstreamReply capture, boolean binding, String proof, int maxBytes,
            java.util.function.Consumer<HttpHeaders> customize, Duration remaining) {
        return create(deviceId, capture, binding, proof, maxBytes, customize, remaining, false);
    }

    private HttpClient create(String deviceId, UpstreamReply capture, boolean binding, String proof, int maxBytes,
            java.util.function.Consumer<HttpHeaders> customize, Duration remaining, boolean pooled) {
        if (maxBytes < 1 || maxBytes > 4 * 1024 * 1024) {
            throw new IllegalArgumentException("Invalid response limit");
        }
        Duration connect = properties.getConnectTimeout(), responseTimeout = properties.getResponseTimeout();
        if (remaining != null) {
            // 获取响应和读取流均受超时限制，分别分配剩余预算，避免串行等待超过预算。
            responseTimeout = minimum(responseTimeout, remaining.dividedBy(2));
            if (pooled && responseTimeout.toSeconds() >= 1) responseTimeout = Duration.ofSeconds(responseTimeout.toSeconds());
            connect = minimum(connect, minimum(responseTimeout, Duration.ofSeconds(pooled ? 10 : 5)));
        }
        HttpClient delegate;
        if (pooled) {
            String key = connect.toMillis() + ":" + responseTimeout.toMillis();
            synchronized (readClients) {
                delegate = readClients.get(key);
                if (delegate == null) {
                    delegate = buildDelegate(connect, responseTimeout);
                    if (readClients.size() >= 8) readClients.remove(readClients.keySet().iterator().next());
                    readClients.put(key, delegate);
                }
            }
        } else delegate = buildDelegate(connect, responseTimeout);
        final HttpClient sender = delegate;
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
            Response<BinaryData> response;
            try { response = sender.send(request); }
            catch (RuntimeException ex) { throw ReadOnlyRetry.failure("upstream", ex); }
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
                if (ReadOnlyRetry.isNetworkFailure(ex))
                    throw new BusinessException(502, "上游响应读取超时或网络中断",
                            java.util.Map.of("reason", "upstream_network_error"));
                throw new BusinessException(502, "无法读取上游响应");
            } finally {
                response.close();
            }
        };
    }

    protected HttpClient buildDelegate(Duration connect, Duration response) {
        return new JdkHttpClientBuilder().sslContext(buildSslContext())
                .connectionTimeout(connect).responseTimeout(response).readTimeout(response).build();
    }

    /** 米哈游部分节点的 TLS 1.3 协商会超时；仅此 SDK 客户端使用 TLS 1.2，保留默认信任校验。 */
    protected SSLContext buildSslContext() {
        try {
            SSLContext context = SSLContext.getInstance("TLSv1.2");
            context.init(null, null, null);
            return context;
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("无法初始化米哈游 TLS 客户端", ex);
        }
    }

    private static Duration minimum(Duration a, Duration b) { return a.compareTo(b) < 0 ? a : b; }

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
