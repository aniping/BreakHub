package com.example.instrumentdemo.config;

import com.ateagents.breakhub.probe.BreakHubProbe;
import com.ateagents.breakhub.probe.ProbeConfig;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ProbeConfiguration {

    @Bean(destroyMethod = "close")
    public BreakHubProbe breakHubProbe(
            @Value("${debugger.server-url:http://127.0.0.1:18621}") String hubUrl,
            @Value("${debugger.business-client-token:}") String businessClientToken,
            @Value("${debugger.connect-timeout-ms:300}") int connectTimeoutMs,
            @Value("${debugger.read-timeout-ms:1000}") int readTimeoutMs,
            @Value("${debugger.breakpoint-timeout-ms:300000}") int breakpointTimeoutMs) {
        ProbeConfig config = new ProbeConfig(
                hubUrl,
                businessClientToken,
                connectTimeoutMs,
                readTimeoutMs,
                breakpointTimeoutMs);
        return BreakHubProbe.open(config);
    }
}
