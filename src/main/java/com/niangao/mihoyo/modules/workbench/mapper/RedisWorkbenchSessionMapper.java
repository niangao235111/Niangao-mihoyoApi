package com.niangao.mihoyo.modules.workbench.mapper;

import com.niangao.mihoyo.common.exception.BusinessException;
import com.niangao.mihoyo.modules.workbench.config.WorkbenchSessionProperties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
public class RedisWorkbenchSessionMapper implements WorkbenchSessionMapper {
    private final StringRedisTemplate redis;
    private final WorkbenchSessionProperties settings;
    private final DefaultRedisScript<List> create = script("create");
    private final DefaultRedisScript<List> read = script("read");

    public RedisWorkbenchSessionMapper(StringRedisTemplate redis, WorkbenchSessionProperties settings) {
        this.redis = redis;
        this.settings = settings;
    }

    private static DefaultRedisScript<List> script(String name) {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("redis/workbench/" + name + ".lua"));
        script.setResultType(List.class);
        return script;
    }

    private String key(String digest) {
        if (digest == null || !digest.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid session digest");
        return settings.getKeyPrefix() + ":session:" + digest;
    }

    @Override
    public Stored insert(String digest, String encrypted, String csrf) {
        try {
            return decode(redis.execute(create, List.of(key(digest)), encrypted, csrf,
                    String.valueOf(settings.getIdleTtl().toMillis()), String.valueOf(settings.getAbsoluteTtl().toMillis())));
        } catch (DataAccessException ex) { throw unavailable(); }
    }

    @Override
    public Stored read(String digest, boolean touch) {
        try {
            return decode(redis.execute(read, List.of(key(digest)), touch ? "1" : "0",
                    String.valueOf(settings.getIdleTtl().toMillis())));
        } catch (DataAccessException ex) { throw unavailable(); }
    }

    @Override
    public void delete(String digest) {
        try { redis.delete(List.of(key(digest), key(digest) + ":verification")); }
        catch (DataAccessException ex) { throw unavailable(); }
    }

    private Stored decode(List<?> values) {
        if (values == null || values.size() != 5) return null;
        try {
            return new Stored((String) values.get(0), (String) values.get(1), Long.parseLong((String) values.get(2)),
                    Long.parseLong((String) values.get(3)), Long.parseLong((String) values.get(4)));
        } catch (RuntimeException ex) { throw unavailable(); }
    }

    private BusinessException unavailable() { return new BusinessException(503, "工作台会话存储暂不可用，请稍后重试"); }
}
