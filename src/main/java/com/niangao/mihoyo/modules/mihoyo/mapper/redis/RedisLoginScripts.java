package com.niangao.mihoyo.modules.mihoyo.mapper.redis;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/** 单例 Lua 脚本，Spring Data Redis 使用 EVALSHA，缓存失效时自动回退 EVAL。 */
@Component
public class RedisLoginScripts {
    public final DefaultRedisScript<String> create;
    public final DefaultRedisScript<String> read;
    public final DefaultRedisScript<String> begin;
    public final DefaultRedisScript<String> complete;
    public final DefaultRedisScript<String> fail;
    public final DefaultRedisScript<String> delete;

    public RedisLoginScripts() {
        String common = resource("common.lua");
        create = script(common, "create.lua");
        read = script(common, "read.lua");
        begin = script(common, "begin.lua");
        complete = script(common, "complete.lua");
        fail = script(common, "fail.lua");
        delete = script(common, "delete.lua");
    }

    private DefaultRedisScript<String> script(String common, String file) {
        return new DefaultRedisScript<>(
                common + System.lineSeparator() + resource(file), String.class);
    }

    private String resource(String name) {
        try (var stream = new ClassPathResource("redis/login/" + name).getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new IllegalStateException("无法加载登录 Lua 脚本: " + name);
        }
    }
}
