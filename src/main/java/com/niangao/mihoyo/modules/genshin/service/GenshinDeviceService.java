package com.niangao.mihoyo.modules.genshin.service;

import com.niangao.mihoyo.common.exception.BusinessException;
import com.niangao.mihoyo.modules.genshin.client.GenshinDeviceClient;
import com.niangao.mihoyo.modules.genshin.mapper.GenshinDeviceMapper;
import com.niangao.mihoyo.modules.mihoyo.config.MihoyoRedisProperties;
import com.niangao.mihoyo.modules.mihoyo.util.DeviceIdUtil;
import org.springframework.stereotype.Service;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Service
public class GenshinDeviceService {
    private final GenshinDeviceMapper mapper;
    private final GenshinDeviceClient client;
    private final MihoyoRedisProperties settings;
    private final Object[] locks = new Object[64];
    public GenshinDeviceService(GenshinDeviceMapper mapper, GenshinDeviceClient client, MihoyoRedisProperties settings) {
        this.mapper = mapper; this.client = client; this.settings = settings;
        for (int i = 0; i < locks.length; i++) locks[i] = new Object();
    }
    public record Device(String id, String fp) {
        @Override public String toString() { return "GenshinDevice[redacted]"; }
    }
    /** 按账号分离并保持跨请求、重启与多实例一致；不把 stuid/mid 直接放入 Redis Key。 */
    public String deviceId(String stuid, String mid) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(settings.getPhoneKeySecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] bytes = mac.doFinal(("genshin-record-device:v1\0" + stuid + "\0" + mid).getBytes(StandardCharsets.UTF_8));
            bytes[6] = (byte) ((bytes[6] & 0x0f) | 0x40); bytes[8] = (byte) ((bytes[8] & 0x3f) | 0x80);
            var buffer = ByteBuffer.wrap(bytes);
            return new UUID(buffer.getLong(), buffer.getLong()).toString();
        } catch (Exception ex) { throw new BusinessException(503, "米游社设备标识配置不可用"); }
    }
    public Device resolve(String stuid, String mid) {
        return resolve(stuid, mid, null);
    }
    /** 旧会话和导入凭证保持原有派生设备；新登录优先使用服务端会话保存的原设备。 */
    public String deviceId(String stuid, String mid, String savedDeviceId) {
        return savedDeviceId == null ? deviceId(stuid, mid) : DeviceIdUtil.requireValid(savedDeviceId);
    }
    public Device resolve(String stuid, String mid, String savedDeviceId) {
        String id = deviceId(stuid, mid, savedDeviceId);
        synchronized (locks[Math.floorMod(id.hashCode(), locks.length)]) {
            String fp = mapper.find(id);
            if (fp == null) fp = mapper.saveIfAbsent(id, client.fetch(id));
            if (!fp.matches("[A-Za-z0-9_-]{6,128}")) throw new BusinessException(503, "米游社设备指纹缓存异常");
            return new Device(id, fp);
        }
    }
}
