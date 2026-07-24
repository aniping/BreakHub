package com.ateagents.breakhub.probe;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DebuggerSettingsConfig {

    public DebuggerSettingsConfig(
            @Value("${debugger.server-url:http://127.0.0.1:18621}") String serverUrl,
            @Value("${debugger.business-client-token:}") String businessClientToken,
            @Value("${debugger.service-name:instrument-service}") String serviceName,
            @Value("${debugger.connect-timeout-ms:300}") int connectTimeoutMs,
            @Value("${debugger.read-timeout-ms:1000}") int readTimeoutMs,
            @Value("${debugger.breakpoint-timeout-ms:300000}") int breakpointTimeoutMs) {
        DebuggerSettings.enabled = false;
        DebuggerSettings.serverUrl = serverUrl;
        DebuggerSettings.businessClientToken = businessClientToken;
        DebuggerSettings.serviceName = serviceName;
        DebuggerSettings.connectTimeoutMs = connectTimeoutMs;
        DebuggerSettings.readTimeoutMs = readTimeoutMs;
        DebuggerSettings.breakpointTimeoutMs = breakpointTimeoutMs;
    }
}
