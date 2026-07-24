package com.ateagents.breakhub.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Validated
@ConfigurationProperties(prefix = "breakhub")
public record ProductProperties(
        @NotBlank String dataDirectory,
        @Valid @NotNull Equipment equipment,
        @Valid @NotNull Security security,
        @Valid @NotNull ControlLease controlLease,
        @Valid @NotNull Interaction interaction) {

    public record Equipment(
            @NotBlank String id,
            @NotBlank String displayName,
            @Valid @NotNull DebuggerSwitch debuggerSwitch) {
    }

    public record DebuggerSwitch(@NotBlank String url) {
    }

    public record Security(
            @NotBlank String webUsername,
            @NotBlank String webPassword,
            @NotBlank String gatewayToken,
            @NotBlank String businessClientToken) {
    }

    public record ControlLease(@NotNull Duration timeout) {
    }

    public record Interaction(
            @NotNull Duration pauseTimeout,
            @NotNull DataSize maxPayloadSize) {
    }
}
