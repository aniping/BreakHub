package com.ateagents.breakhub.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

@Validated
@ConfigurationProperties(prefix = "server")
public record ServerBindingProperties(
        @NotBlank String address,
        @Min(0) @Max(65535) int port) {
}
