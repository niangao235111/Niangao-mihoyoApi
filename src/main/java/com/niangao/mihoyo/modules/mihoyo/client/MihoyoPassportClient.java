package com.niangao.mihoyo.modules.mihoyo.client;

import com.niangao.mihoyo.common.exception.BusinessException;
import com.niangao.mihoyo.modules.mihoyo.client.model.UpstreamReply;
import com.niangao.mihoyo.modules.mihoyo.config.MihoyoProperties;
import com.niangao.mihoyo.modules.mihoyo.entity.LoginSession;
import com.niangao.mihoyo.modules.mihoyo.enums.LoginOperation;
import com.niangao.mihoyo.modules.mihoyo.util.RsaEncryptionUtil;

import org.springframework.stereotype.Component;

import uigf.mihoyo.passport.*;

import java.util.Map;

/** 只负责上游请求，不修改登录会话，不决定业务状态。 */
@lombok.extern.slf4j.Slf4j
@Component
public class MihoyoPassportClient {
    /** 连接阶段失败（请求尚未发出）的最大重试次数；短信是写操作，只在这种情况下重试。 */
    private static final int CONNECT_RETRIES = 2;

    private final MihoyoProperties properties;
    private final SdkTransport transport;
    private final RsaEncryptionUtil encryption;

    public MihoyoPassportClient(
            MihoyoProperties properties, SdkTransport transport, RsaEncryptionUtil encryption) {
        this.properties = properties;
        this.transport = transport;
        this.encryption = encryption;
    }

    /**
     * 使用当前流程的固定设备标识请求短信或验证码登录。RSA 加密及 SDK HTTP 配置由专门的工具和 Transport 完成。
     *
     * @param captcha 短信验证码，发送短信时为 null
     * @param proof 人机验证请求头内容，未触发验证时为 null
     * @return 捕获的原始协议响应，交由 Service 判断业务状态
     */
    public UpstreamReply call(
            LoginSession session, LoginOperation operation, String captcha, String proof) {
        String stage = operation == LoginOperation.SMS ? "login_sms" : "login_verify";
        String area = encryption.encrypt(session.getAreaCode());
        String mobile = encryption.encrypt(session.getMobile());
        for (int attempt = 0; ; attempt++) {
            UpstreamReply reply = new UpstreamReply();
            PassportClientBuilder builder =
                    new PassportClientBuilder()
                            .endpoint(properties.getPassportEndpoint())
                            .httpClient(transport.create(session.getDeviceId(), reply, false, proof))
                            .httpRetryOptions(SdkTransport.noRetries())
                            .httpRedirectOptions(SdkTransport.noRedirects())
                            .httpInstrumentationOptions(SdkTransport.noLogs());
            try {
                if (operation == LoginOperation.SMS)
                    builder.buildCaptchaApiClient().create(new LoginCaptchaRequest(area, mobile));
                else
                    builder.buildQrLoginApiClient()
                            .loginByMobileCaptcha(
                                    new MobileCaptchaLoginRequest(
                                            session.getActionType(), area, captcha, mobile));
            } catch (RuntimeException ex) {
                if (reply.getBody() == null) {
                    // 连接/握手失败说明请求一个字节都没发出去，重试不会造成重复短信。
                    if (attempt < CONNECT_RETRIES && connectPhaseFailure(ex)) {
                        log.warn("米哈游登录连接阶段失败，重试 stage={}, attempt={}/{}", stage, attempt + 1, CONNECT_RETRIES);
                        pause(300L * (attempt + 1));
                        continue;
                    }
                    throw ReadOnlyRetry.failure(stage, ex);
                }
                // SDK 对 token 对象的模型可能不兼容，使用已抓取 JSON 继续按业务协议校验。
            }
            SdkTransport.validateReply(reply);
            return reply;
        }
    }

    /** 仅识别「请求尚未发出」的失败类型：连接、DNS、路由、TLS 握手。 */
    private static boolean connectPhaseFailure(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 24; depth++, current = current.getCause()) {
            if (current instanceof java.net.ConnectException
                    || current instanceof java.net.UnknownHostException
                    || current instanceof java.net.NoRouteToHostException
                    || current instanceof java.net.http.HttpConnectTimeoutException
                    || current instanceof javax.net.ssl.SSLHandshakeException) {
                return true;
            }
            String message = current.getMessage();
            if (message != null && message.contains("connect timed out")) return true;
            if (current.getCause() == current) break;
        }
        return false;
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new BusinessException(502, "登录请求已中断", Map.of("reason", "request_interrupted"));
        }
    }
}
