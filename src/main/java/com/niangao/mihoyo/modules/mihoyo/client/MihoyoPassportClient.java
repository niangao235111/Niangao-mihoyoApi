package com.niangao.mihoyo.modules.mihoyo.client;

import com.niangao.mihoyo.common.exception.BusinessException;
import com.niangao.mihoyo.modules.mihoyo.client.model.UpstreamReply;
import com.niangao.mihoyo.modules.mihoyo.config.MihoyoProperties;
import com.niangao.mihoyo.modules.mihoyo.entity.LoginSession;
import com.niangao.mihoyo.modules.mihoyo.enums.LoginOperation;
import com.niangao.mihoyo.modules.mihoyo.util.RsaEncryptionUtil;

import org.springframework.stereotype.Component;

import uigf.mihoyo.passport.*;

/** 只负责上游请求，不修改登录会话，不决定业务状态。 */
@Component
public class MihoyoPassportClient {
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
        UpstreamReply reply = new UpstreamReply();
        PassportClientBuilder builder =
                new PassportClientBuilder()
                        .endpoint(properties.getPassportEndpoint())
                        .httpClient(transport.create(session.getDeviceId(), reply, false, proof))
                        .httpRetryOptions(SdkTransport.noRetries())
                        .httpRedirectOptions(SdkTransport.noRedirects())
                        .httpInstrumentationOptions(SdkTransport.noLogs());
        String area = encryption.encrypt(session.getAreaCode());
        String mobile = encryption.encrypt(session.getMobile());
        try {
            if (operation == LoginOperation.SMS)
                builder.buildCaptchaApiClient().create(new LoginCaptchaRequest(area, mobile));
            else
                builder.buildQrLoginApiClient()
                        .loginByMobileCaptcha(
                                new MobileCaptchaLoginRequest(
                                        session.getActionType(), area, captcha, mobile));
        } catch (RuntimeException ex) {
            // SDK 对 token 对象的模型可能不兼容，使用已抓取 JSON 继续按业务协议校验。
            if (reply.getBody() == null) throw new BusinessException(502, "上游网络或解码异常；未自动重试");
        }
        SdkTransport.validateReply(reply);
        return reply;
    }
}
