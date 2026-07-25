package com.ateagents.breakhub.probe;

import java.util.Objects;

public record ProbeConfig(
        String hubUrl,
        String businessClientToken,
        int connectTimeoutMs,
        int readTimeoutMs,
        int breakpointTimeoutMs) {

    private static final int DEFAULT_CONNECT_TIMEOUT_MS = 300;
    private static final int DEFAULT_READ_TIMEOUT_MS = 1000;
    private static final int DEFAULT_BREAKPOINT_TIMEOUT_MS = 300000;

    public ProbeConfig {
        hubUrl = normalizeHubUrl(hubUrl);
        businessClientToken = requireText(businessClientToken, "businessClientToken");
    }

    public static ProbeConfig of(String hubUrl, String businessClientToken) {
        return new ProbeConfig(
                hubUrl,
                businessClientToken,
                DEFAULT_CONNECT_TIMEOUT_MS,
                DEFAULT_READ_TIMEOUT_MS,
                DEFAULT_BREAKPOINT_TIMEOUT_MS);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static String normalizeHubUrl(String value) {
        String hubUrl = requireText(value, "hubUrl");
        return hubUrl.endsWith("/") ? hubUrl.substring(0, hubUrl.length() - 1) : hubUrl;
    }
}
