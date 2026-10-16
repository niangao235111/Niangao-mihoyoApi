package com.niangao.mihoyo.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class TimeConfiguration {
    /** 提供统一 UTC 时钟，业务代码通过注入获取时间，便于测试过期与冷却行为。 */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
