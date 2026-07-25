package com.ateagents.breakhub.probe;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Objects;

public final class BreakHubProbe implements AutoCloseable {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final DebugInvoker invoker;
    private final ReportingLeaseManager leaseManager;

    private BreakHubProbe(ProbeConfig config) {
        ReportingChannel reportingChannel = new ReportingChannel();
        DebugClient client = new DebugClient(config, reportingChannel);
        invoker = new DebugInvoker(client, reportingChannel);
        leaseManager = new ReportingLeaseManager(
                reportingChannel,
                client::cancelActiveRequests);
    }

    public static BreakHubProbe open(ProbeConfig config) {
        return new BreakHubProbe(Objects.requireNonNull(config, "config"));
    }

    public <T> T invoke(DebugMethodInfo methodInfo, DebugCallable<T> callable) {
        return invoker.invoke(methodInfo, callable);
    }

    public LeaseResult handleLease(String requestBody) {
        ReportingLeaseManager.HttpResult result = leaseManager.handle(requestBody);
        try {
            return new LeaseResult(
                    result.statusCode(),
                    OBJECT_MAPPER.writeValueAsString(result.body()));
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Could not serialize reporting lease response", error);
        }
    }

    @Override
    public void close() {
        leaseManager.close();
    }
}
