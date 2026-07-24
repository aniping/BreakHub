package com.ateagents.breakhub.config;

import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.DeserializationFeature;

@Configuration
public class JsonConfiguration {

    @Bean
    Jackson2ObjectMapperBuilderCustomizer preserveJsonNumberPrecision() {
        return builder -> builder.featuresToEnable(
                DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS,
                DeserializationFeature.USE_BIG_INTEGER_FOR_INTS);
    }
}
