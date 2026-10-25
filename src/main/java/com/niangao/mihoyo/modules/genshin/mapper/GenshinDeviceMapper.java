package com.niangao.mihoyo.modules.genshin.mapper;

import com.niangao.mihoyo.common.exception.BusinessException;
import com.niangao.mihoyo.modules.mihoyo.config.MihoyoRedisProperties;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.util.Map;

/** 仅缓存服务端返回的设备指纹 24 小时，不保存账号 ID 或凭证明文。 */
@Component
public class GenshinDeviceMapper {
    private final StringRedisTemplate redis;
    private final MihoyoRedisProperties settings;
    public GenshinDeviceMapper(StringRedisTemplate redis, MihoyoRedisProperties settings) {
        this.redis = redis; this.settings = settings;
    }
    private String key(String deviceId) {
        com.niangao.mihoyo.modules.mihoyo.util.DeviceIdUtil.requireValid(deviceId);
        return settings.getKeyPrefix() + ":genshin:fp:" + deviceId;
    }
    public String find(String deviceId) {
        try { return redis.opsForValue().get(key(deviceId)); }
        catch (DataAccessException ex) { throw unavailable(); }
    }
    public String saveIfAbsent(String deviceId, String fp) {
        try {
            if (Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key(deviceId), fp, Duration.ofHours(24)))) return fp;
            String existing = find(deviceId);
            if (existing == null) throw unavailable();
            return existing;
        } catch (DataAccessException ex) { throw unavailable(); }
    }
    private BusinessException unavailable() {
        return new BusinessException(503, "米游社设备信息存储暂不可用，请稍后重试", Map.of("stage", "genshin_device_fp"));
    }
}
