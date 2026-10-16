package com.niangao.mihoyo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** IDEA 运行入口。未来业务模块放在 modules 下，自动扫描注册。 */
@ConfigurationPropertiesScan
@SpringBootApplication
public class NiangaoApplication {
    public static void main(String[] args) {
        SpringApplication.run(NiangaoApplication.class, args);
    }
}
