package com.niangao.mihoyo.common.config;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;

import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class WebConfiguration {
    /** 禁止隐式转换，手机号、验证码和 ID 必须按接口契约使用 JSON 字符串。 */
    @Bean
    Jackson2ObjectMapperBuilderCustomizer strictJsonTypes() {
        return builder -> {
            builder.featuresToDisable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
            builder.postConfigurer(
                    mapper ->
                            mapper.coercionConfigFor(LogicalType.Textual)
                                    .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                                    .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                                    .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail));
        };
    }
}
