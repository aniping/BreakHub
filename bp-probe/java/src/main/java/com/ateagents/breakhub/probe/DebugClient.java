package com.ateagents.breakhub.probe;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

final class DebugClient {

    private static final int MAX_ORDINARY_REQUEST_TIMEOUT_MS = 5000;
    private static final int MAX_CONNECT_TIMEOUT_MS = 5000;
    private static final int DEFAULT_BREAKPOINT_TIMEOUT_MS = 300000;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final Object clientMutex = new Object();
    private final Set<ActiveRequest> activeRequests = ConcurrentHashMap.newKeySet();
    private final ProbeConfig config;
    private final ReportingChannel reportingChannel;

    private volatile ClientHolder sharedClient;

    DebugClient(ProbeConfig config, ReportingChannel reportingChannel) {
        this.config = Objects.requireNonNull(config, "config");
        this.reportingChannel = Objects.requireNonNull(reportingChannel, "reportingChannel");
    }

    WaitResponse waitContinue(String interactionId) {
        return waitContinue(interactionId, "before");
    }

    WaitResponse waitContinue(String interactionId, String pausePoint) {
        String url = config.hubUrl() + "/api/business/interactions/wait";
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("interaction_id", interactionId);
        body.put("pause_point", pausePoint);

        try {
            return executeReporting("wait_request_failed", permit -> {
                String responseBody = postJson(
                        url,
                        body,
                        positiveTimeout(
                                config.breakpointTimeoutMs(),
                                DEFAULT_BREAKPOINT_TIMEOUT_MS),
                        permit);
                if (responseBody.isEmpty()) {
                    throw new IllegalStateException("Empty reporting response");
                }
                return OBJECT_MAPPER.readValue(responseBody, WaitResponse.class);
            });
        } catch (ReportingSuppressedException suppressed) {
            return new WaitResponse("reporting_suppressed");
        } catch (Exception e) {
            System.out.println("[BreakHub] wait failed, continue business. interactionId="
                    + interactionId
                    + ", pausePoint="
                    + pausePoint
                    + ", errorType="
                    + e.getClass().getSimpleName());
            return new WaitResponse("request_failed");
        }
    }

    BeforeCallResponse beforeCall(BeforeCallRequest request) {
        String url = config.hubUrl() + "/api/business/interactions/before";
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("interaction_id", request.getCallId());
        body.put("object", request.getObjectName());
        body.put("command", request.getCmdName());
        body.put("params", request.getParams() == null
                ? Collections.emptyMap()
                : request.getParams());

        try {
            return executeReporting("before_request_failed", permit -> {
                String responseBody = postJson(url, body, ordinaryRequestTimeoutMs(), permit);
                if (responseBody.isEmpty()) {
                    throw new IllegalStateException("Empty reporting response");
                }
                BeforeCallResponse response = OBJECT_MAPPER.readValue(
                        responseBody, BeforeCallResponse.class);
                response.setSuccess(true);
                return response;
            });
        } catch (ReportingSuppressedException suppressed) {
            return failOpenBeforeResponse("reporting degraded");
        } catch (Exception e) {
            System.out.println("[BreakHub] before-call http failed, continue. method="
                    + request.getMethodName()
                    + ", errorType="
                    + e.getClass().getSimpleName());
            return failOpenBeforeResponse("http failed");
        }
    }

    AfterCallResponse afterCall(AfterCallRequest request) {
        String url = config.hubUrl() + "/api/business/interactions/after";
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("interaction_id", request.getCallId());
        body.put("result", request.getResult());

        try {
            return executeReporting("after_request_failed", permit -> {
                String responseBody = postJson(url, body, ordinaryRequestTimeoutMs(), permit);
                if (responseBody.isEmpty()) {
                    throw new IllegalStateException("Empty reporting response");
                }
                return OBJECT_MAPPER.readValue(responseBody, AfterCallResponse.class);
            });
        } catch (ReportingSuppressedException suppressed) {
            return AfterCallResponse.failOpen("reporting degraded");
        } catch (Exception e) {
            System.out.println("[BreakHub] after-call http failed, ignore. callId="
                    + request.getCallId()
                    + ", errorType="
                    + e.getClass().getSimpleName());
            return AfterCallResponse.failOpen("http failed");
        }
    }

    private BeforeCallResponse failOpenBeforeResponse(String reason) {
        return new BeforeCallResponse(false, null, "continue", reason, null, null, null, null);
    }

    private int ordinaryRequestTimeoutMs() {
        int configured = config.readTimeoutMs();
        if (configured <= 0 || configured > MAX_ORDINARY_REQUEST_TIMEOUT_MS) {
            return MAX_ORDINARY_REQUEST_TIMEOUT_MS;
        }
        return configured;
    }

    private int positiveTimeout(int configured, int fallback) {
        return configured > 0 ? configured : fallback;
    }

    private <T> T executeReporting(
            String failureSummary,
            ReportingOperation<T> operation) throws Exception {
        ReportingChannel.Permit permit = reportingChannel.tryAcquire();
        if (!permit.allowed()) {
            throw new ReportingSuppressedException();
        }
        try {
            T result = operation.execute(permit);
            if (!reportingChannel.succeeded(permit)) {
                throw new ReportingSuppressedException();
            }
            return result;
        } catch (ReportingSuppressedException suppressed) {
            throw suppressed;
        } catch (Exception error) {
            reportingChannel.failed(permit, failureSummary);
            throw error;
        }
    }

    private String postJson(
            String url,
            Object body,
            int requestTimeoutMs,
            ReportingChannel.Permit permit) throws Exception {
        String token = config.businessClientToken();
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("Business client token is not configured");
        }

        byte[] bytes = OBJECT_MAPPER.writeValueAsBytes(body);
        HttpClient client = sharedClient();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(requestTimeoutMs))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json;charset=UTF-8")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes))
                .build();

        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(requestTimeoutMs);
        ActiveRequest activeRequest = new ActiveRequest();
        register(activeRequest, permit);
        try {
            CompletableFuture<HttpResponse<String>> exchange = activeRequest.start(() -> client.sendAsync(
                    request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)));
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                throw new TimeoutException("HTTP request deadline elapsed");
            }
            HttpResponse<String> response = exchange.get(remainingNanos, TimeUnit.NANOSECONDS);
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                throw new IllegalStateException("HTTP " + status);
            }
            return response.body() == null ? "" : response.body();
        } catch (TimeoutException timeout) {
            activeRequest.cancel();
            throw timeout;
        } catch (InterruptedException interrupted) {
            activeRequest.cancel();
            Thread.currentThread().interrupt();
            throw interrupted;
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            throw new IllegalStateException("HTTP request failed");
        } finally {
            activeRequests.remove(activeRequest);
        }
    }

    private HttpClient sharedClient() {
        int configured = config.connectTimeoutMs();
        int connectTimeoutMs = configured > 0
                ? Math.min(configured, MAX_CONNECT_TIMEOUT_MS)
                : MAX_CONNECT_TIMEOUT_MS;
        ClientHolder current = sharedClient;
        if (current != null && current.connectTimeoutMs() == connectTimeoutMs) {
            return current.client();
        }

        synchronized (clientMutex) {
            current = sharedClient;
            if (current == null || current.connectTimeoutMs() != connectTimeoutMs) {
                current = new ClientHolder(
                        HttpClient.newBuilder()
                                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                                .build(),
                        connectTimeoutMs);
                sharedClient = current;
            }
            return current.client();
        }
    }

    void cancelActiveRequests() {
        for (ActiveRequest request : activeRequests) {
            if (activeRequests.remove(request)) {
                request.cancel(true);
            }
        }
    }

    private void register(ActiveRequest request, ReportingChannel.Permit permit)
            throws ReportingSuppressedException {
        activeRequests.add(request);
        if (!reportingChannel.canStart(permit)) {
            activeRequests.remove(request);
            request.cancel(true);
            throw new ReportingSuppressedException();
        }
    }

    @FunctionalInterface
    private interface ReportingOperation<T> {
        T execute(ReportingChannel.Permit permit) throws Exception;
    }

    private static final class ReportingSuppressedException extends Exception {
    }

    static final class ActiveRequest {

        private boolean cancelled;
        private CompletableFuture<?> exchange;

        synchronized <T> CompletableFuture<T> start(Supplier<CompletableFuture<T>> sender) {
            if (cancelled) {
                throw new CancellationException("Reporting request was cancelled before start");
            }
            CompletableFuture<T> started = Objects.requireNonNull(sender.get(), "request future");
            exchange = started;
            return started;
        }

        synchronized void cancel() {
            cancel(true);
        }

        synchronized void cancel(boolean mayInterruptIfRunning) {
            cancelled = true;
            if (exchange != null) {
                exchange.cancel(mayInterruptIfRunning);
            }
        }
    }

    private record ClientHolder(HttpClient client, int connectTimeoutMs) {
    }
}
