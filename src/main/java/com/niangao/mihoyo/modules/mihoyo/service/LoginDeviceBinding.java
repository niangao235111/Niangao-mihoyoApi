package com.niangao.mihoyo.modules.mihoyo.service;

import com.niangao.mihoyo.common.exception.BusinessException;
import com.niangao.mihoyo.modules.mihoyo.config.MihoyoRedisProperties;
import com.niangao.mihoyo.modules.mihoyo.util.DeviceIdUtil;
import com.niangao.mihoyo.modules.mihoyo.vo.MihoyoCredentials;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;

/** 成功登录到工作台创建的短期交接：仅存设备 ID，键为完整凭证的 HMAC，不保存凭证。 */
@Service
public class LoginDeviceBinding {
    private final StringRedisTemplate redis;
    private final MihoyoRedisProperties settings;

    public LoginDeviceBinding(StringRedisTemplate redis, MihoyoRedisProperties settings) {
        this.redis = redis;
        this.settings = settings;
    }

    public void remember(MihoyoCredentials credentials, String deviceId) {
        DeviceIdUtil.requireValid(deviceId);
        try {
            redis.opsForValue().set(key(credentials.stoken(), credentials.stuid(), credentials.mid()),
                    deviceId, Duration.ofMinutes(10));
        } catch (DataAccessException ex) { throw unavailable(); }
    }

    public String find(String stoken, String stuid, String mid) {
        try {
            String device = redis.opsForValue().get(key(stoken, stuid, mid));
            if (device != null) DeviceIdUtil.requireValid(device);
            return device;
        } catch (DataAccessException ex) { throw unavailable(); }
    }

    private String key(String stoken, String stuid, String mid) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(settings.getPhoneKeySecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update("login-device-binding:v1".getBytes(StandardCharsets.UTF_8));
            for (String part : new String[]{stoken, stuid, mid}) {
                byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
                mac.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
                mac.update(bytes);
            }
            return settings.getKeyPrefix() + ":login-device:" + HexFormat.of().formatHex(mac.doFinal());
        } catch (Exception ex) { throw unavailable(); }
    }

    private BusinessException unavailable() {
        return new BusinessException(503, "登录设备信息暂不可用，请稍后重试");
    }
}
