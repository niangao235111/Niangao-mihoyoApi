package com.niangao.mihoyo.modules.mihoyo.util;

import com.niangao.mihoyo.common.exception.BusinessException;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

import javax.crypto.Cipher;

/** 按米哈游协议 RSA/PKCS1 加密手机号和区号；每次创建独立 Cipher。 */
@Component
public class RsaEncryptionUtil {
    static final String RSA_KEY =
            "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDDvekdPMHN3AYhm/vktJT+YJr7"
                    + "cI5DcsNKqdsx5DZX0gDuWFuIjzdwButrIYPNmRJ1G8ybDIF7oDW2eEpm5sMbL9zs"
                    + "9ExXCdvqrn51qELbqj0XxtMTIpaCHFSI50PfPpTFV9Xt/hmyVwokoOXFlAEgCn+Q"
                    + "CgGs52bFoYMtyi+xEQIDAQAB";
    private final PublicKey publicKey;

    public RsaEncryptionUtil() {
        try {
            publicKey =
                    KeyFactory.getInstance("RSA")
                            .generatePublic(
                                    new X509EncodedKeySpec(Base64.getDecoder().decode(RSA_KEY)));
        } catch (Exception ex) {
            throw new IllegalStateException("米哈游 RSA 公钥配置无效");
        }
    }

    public RsaEncryptionUtil(PublicKey publicKey) {
        this.publicKey = publicKey;
    }

    /** 按米哈游短信协议使用 RSA/ECB/PKCS1Padding 加密字符串，再做 Base64 编码；每次创建 Cipher，避免多线程共用可变对象。 */
    public String encrypt(String value) {
        try {
            Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            cipher.init(Cipher.ENCRYPT_MODE, publicKey);
            return Base64.getEncoder()
                    .encodeToString(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new BusinessException(502, "米哈游请求加密失败");
        }
    }
}
