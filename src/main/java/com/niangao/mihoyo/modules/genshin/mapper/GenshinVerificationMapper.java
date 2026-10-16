package com.niangao.mihoyo.modules.genshin.mapper;

import com.niangao.mihoyo.common.exception.BusinessException;
import com.niangao.mihoyo.modules.workbench.config.WorkbenchSessionProperties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class GenshinVerificationMapper {
    private final StringRedisTemplate redis;
    private final WorkbenchSessionProperties settings;
    private final DefaultRedisScript<String> script = new DefaultRedisScript<>();
    public GenshinVerificationMapper(StringRedisTemplate redis, WorkbenchSessionProperties settings) {
        this.redis = redis; this.settings = settings;
        script.setLocation(new ClassPathResource("redis/workbench/verification.lua")); script.setResultType(String.class);
    }
    public String execute(String digest, String operation, String... args) {
        if (digest == null || !digest.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid session digest");
        String parent = settings.getKeyPrefix() + ":session:" + digest;
        var arguments = new ArrayList<String>(); arguments.add(operation); arguments.addAll(Arrays.asList(args));
        try { return redis.execute(script, List.of(parent, parent + ":verification"), arguments.toArray()); }
        catch (DataAccessException ex) { throw new BusinessException(503, "安全验证状态存储暂不可用，请稍后重试"); }
    }
}
