package com.niangao.mihoyo.modules.workbench.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.niangao.mihoyo.common.exception.BusinessException;
import com.niangao.mihoyo.modules.workbench.config.WorkbenchSessionProperties;
import com.niangao.mihoyo.modules.workbench.entity.WorkbenchSecret;
import org.springframework.stereotype.Component;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/** 每条密文使用独立 nonce，AAD 绑定会话哈希，防止把其他会话的密文移入本会话。 */
@Component
public class WorkbenchCipher {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final WorkbenchSessionProperties settings;
    private final ObjectMapper json;

    public WorkbenchCipher(WorkbenchSessionProperties settings, ObjectMapper json) {
        this.settings = settings;
        this.json = json;
    }

    public void requireConfigured() { key(); }

    private SecretKeySpec key() {
        try {
            byte[] bytes = Base64.getDecoder().decode(settings.getEncryptionKey());
            if (bytes.length != 32) throw new IllegalArgumentException();
            return new SecretKeySpec(bytes, "AES");
        } catch (RuntimeException ex) {
            throw new BusinessException(503, "工作台凭证加密密钥尚未正确配置");
        }
    }

    public String encrypt(WorkbenchSecret secret, String digest) {
        return encryptValue(secret, digest);
    }

    public String encryptValue(Object secret, String digest) {
        var key = key();
        try {
            byte[] nonce = new byte[12];
            RANDOM.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            cipher.updateAAD(digest.getBytes(StandardCharsets.UTF_8));
            byte[] encrypted = cipher.doFinal(json.writeValueAsBytes(secret));
            byte[] packed = Arrays.copyOf(nonce, nonce.length + encrypted.length);
            System.arraycopy(encrypted, 0, packed, nonce.length, encrypted.length);
            return Base64.getEncoder().encodeToString(packed);
        } catch (Exception ex) { throw new BusinessException(503, "工作台凭证加密失败"); }
    }

    public WorkbenchSecret decrypt(String value, String digest) {
        return decryptValue(value, digest, WorkbenchSecret.class);
    }

    public <T> T decryptValue(String value, String digest, Class<T> type) {
        var key = key();
        try {
            byte[] packed = Base64.getDecoder().decode(value);
            if (packed.length < 29) throw new IllegalArgumentException();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, Arrays.copyOf(packed, 12)));
            cipher.updateAAD(digest.getBytes(StandardCharsets.UTF_8));
            return json.readValue(cipher.doFinal(packed, 12, packed.length - 12), type);
        } catch (Exception ex) { throw new BusinessException(401, "工作台会话无法恢复，请重新连接账号"); }
    }
}
