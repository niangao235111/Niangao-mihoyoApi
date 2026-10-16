package com.niangao.mihoyo.modules.mihoyo.util;

import com.niangao.mihoyo.modules.mihoyo.config.MihoyoRedisProperties;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** 统一 Key 名称，手机号使用 HMAC 摘要；多 Key Lua 使用同一个 Redis hash tag。 */
@Component
public class RedisLoginKeyFactory {
    private final String prefix;
    private final byte[] secret;

    public RedisLoginKeyFactory(MihoyoRedisProperties properties) {
        prefix = properties.getKeyPrefix() + ":{login}:";
        secret = properties.getPhoneKeySecret().getBytes(StandardCharsets.UTF_8);
    }

    public String session(String id) {
        return prefix + "session:" + id;
    }

    public String index() {
        return prefix + "sessions";
    }

    /** 同一手机号跨会话、跨实例使用同一个冷却 Key，不在 Key 中明文暴露号码。 */
    public String cooldown(String mobile) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return prefix
                    + "sms:cooldown:"
                    + HexFormat.of()
                            .formatHex(mac.doFinal(mobile.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("无法生成手机号 Redis Key");
        }
    }
}
