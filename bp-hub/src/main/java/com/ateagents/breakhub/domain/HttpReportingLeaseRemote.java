package com.ateagents.breakhub.domain;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.ateagents.breakhub.api.ProductException;
import com.ateagents.breakhub.config.ProductProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Component
public final class HttpReportingLeaseRemote implements ReportingLeaseRemote {

    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    private static final Set<String> ACTIVE_ACK_FIELDS = Set.of(
            "success", "result", "changed", "enabled", "lease_timeout_seconds",
            "reporting_status", "lease_id");
    private static final Set<String> DEGRADED_ACK_FIELDS = Set.of(
            "success", "result", "changed", "enabled", "lease_timeout_seconds",
            "reporting_status", "last_error", "lease_id");
    private static final Set<String> STOP_ACK_FIELDS = Set.of(
            "success", "result", "changed", "enabled", "lease_timeout_seconds",
            "reporting_status");
    private static final Set<String> ERROR_FIELDS = Set.of("code", "message");

    private final URI endpoint;
    private final Duration requestTimeout;
    private final ObjectMapper objectMapper;
    private final HttpClient client;

    @Autowired
    public HttpReportingLeaseRemote(ProductProperties properties, ObjectMapper objectMapper) {
        this(URI.create(properties.equipment().debuggerSwitch().url()), REQUEST_TIMEOUT, objectMapper);
    }

    HttpReportingLeaseRemote(URI endpoint, Duration requestTimeout, ObjectMapper objectMapper) {
        this.endpoint = endpoint;
        this.requestTimeout = requestTimeout;
        this.objectMapper = objectMapper;
        this.client = HttpClient.newBuilder()
                .connectTimeout(requestTimeout)
                .build();
    }

    @Override
    public CompletableFuture<LeaseAcknowledgement> create() {
        return send("{\"enabled\":true}", response ->
                parseActiveAcknowledgement(response, "created", true, null));
    }

    @Override
    public CompletableFuture<LeaseAcknowledgement> renew(String leaseId) {
        return send(requestBody(true, leaseId), response ->
                parseActiveAcknowledgement(response, "renewed", false, leaseId));
    }

    @Override
    public CompletableFuture<Void> stop(String leaseId) {
        return send(requestBody(false, leaseId), response -> {
            JsonNode body = parseSuccess(response, STOP_ACK_FIELDS);
            boolean stopped = matchesCommon(body, "stopped", true, false, "idle");
            boolean alreadyStopped = matchesCommon(body, "already_stopped", false, false, "idle");
            if (!stopped && !alreadyStopped) {
                throw invalidAcknowledgement();
            }
            return null;
        });
    }

    private <T> CompletableFuture<T> send(String requestBody, Function<HttpResponse<String>, T> parser) {
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                .build();

        CompletableFuture<HttpResponse<String>> transport = client.sendAsync(
                request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        CompletableFuture<T> result = new CompletableFuture<>();
        transport.whenComplete((response, error) -> {
            if (error != null) {
                result.completeExceptionally(requestFailure(error));
                return;
            }
            try {
                result.complete(parser.apply(response));
            } catch (RuntimeException parsingError) {
                result.completeExceptionally(parsingError);
            }
        });
        result.whenComplete((ignored, error) -> {
            if (result.isCancelled()) {
                transport.cancel(true);
            }
        });
        return result;
    }

    private LeaseAcknowledgement parseActiveAcknowledgement(
            HttpResponse<String> response,
            String result,
            boolean changed,
            String expectedLeaseId) {
        JsonNode body = parseSuccessfulBody(response);
        String channelStatus = body.path("reporting_status").isTextual()
                ? body.path("reporting_status").textValue()
                : "";
        Set<String> expectedFields = "healthy".equals(channelStatus)
                ? ACTIVE_ACK_FIELDS
                : DEGRADED_ACK_FIELDS;
        if (!("healthy".equals(channelStatus) || "degraded".equals(channelStatus))
                || !hasExactly(body, expectedFields)
                || !matchesCommon(body, result, changed, true, channelStatus)
                || !validLeaseId(body.path("lease_id"))) {
            throw invalidAcknowledgement();
        }
        String leaseId = body.path("lease_id").textValue();
        if (expectedLeaseId != null && !expectedLeaseId.equals(leaseId)) {
            throw invalidAcknowledgement();
        }
        String lastError = null;
        if ("degraded".equals(channelStatus)) {
            if (!validErrorCode(body.path("last_error"))) {
                throw invalidAcknowledgement();
            }
            lastError = body.path("last_error").textValue();
        }
        return new LeaseAcknowledgement(
                leaseId,
                Duration.ofSeconds(body.path("lease_timeout_seconds").longValue()),
                channelStatus,
                lastError);
    }

    private JsonNode parseSuccess(HttpResponse<String> response, Set<String> expectedFields) {
        JsonNode body = parseSuccessfulBody(response);
        if (!hasExactly(body, expectedFields)) {
            throw invalidAcknowledgement();
        }
        return body;
    }

    private JsonNode parseSuccessfulBody(HttpResponse<String> response) {
        if (response.statusCode() != 200) {
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                throw invalidAcknowledgement();
            }
            throw remoteFailure(response);
        }
        return parseBody(response.body());
    }

    private ProductException remoteFailure(HttpResponse<String> response) {
        JsonNode body;
        try {
            body = objectMapper.readTree(response.body());
        } catch (Exception error) {
            return requestFailed();
        }
        if (body == null || !body.isObject() || !hasExactly(body, ERROR_FIELDS)
                || !body.path("code").isTextual() || !body.path("message").isTextual()) {
            return requestFailed();
        }
        String code = body.path("code").textValue();
        if (response.statusCode() == 409
                && ("REPORTING_LEASE_ALREADY_ACTIVE".equals(code)
                        || "REPORTING_LEASE_CONFLICT".equals(code))) {
            return failure(HttpStatus.CONFLICT, code, "业务上报租约冲突");
        }
        if (response.statusCode() == 404 && "REPORTING_LEASE_NOT_FOUND".equals(code)) {
            return failure(HttpStatus.BAD_GATEWAY, code, "业务上报租约不存在");
        }
        if (response.statusCode() == 400 && "INVALID_REPORTING_LEASE_REQUEST".equals(code)) {
            return failure(HttpStatus.BAD_GATEWAY, "REPORTING_LEASE_PROTOCOL_ERROR",
                    "业务上报租约请求不符合协议");
        }
        if (response.statusCode() == 503 && "REPORTING_LEASE_UNAVAILABLE".equals(code)) {
            return failure(HttpStatus.BAD_GATEWAY, code,
                    "Business reporting lease service is unavailable");
        }
        return requestFailed();
    }

    private static boolean matchesCommon(
            JsonNode body,
            String result,
            boolean changed,
            boolean enabled,
            String reportingStatus) {
        return body.path("success").isBoolean() && body.path("success").booleanValue()
                && body.path("result").isTextual() && result.equals(body.path("result").textValue())
                && body.path("changed").isBoolean() && body.path("changed").booleanValue() == changed
                && body.path("enabled").isBoolean() && body.path("enabled").booleanValue() == enabled
                && body.path("lease_timeout_seconds").isIntegralNumber()
                && body.path("lease_timeout_seconds").intValue() == 30
                && body.path("reporting_status").isTextual()
                && reportingStatus.equals(body.path("reporting_status").textValue());
    }

    private String requestBody(boolean enabled, String leaseId) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "enabled", enabled,
                    "lease_id", leaseId));
        } catch (Exception error) {
            throw new IllegalArgumentException("Invalid reporting lease identity", error);
        }
    }

    private JsonNode parseBody(String responseBody) {
        try {
            JsonNode body = objectMapper.readTree(responseBody);
            if (body == null || !body.isObject()) {
                throw invalidAcknowledgement();
            }
            return body;
        } catch (ProductException error) {
            throw error;
        } catch (Exception error) {
            throw invalidAcknowledgement();
        }
    }

    private static boolean hasExactly(JsonNode body, Set<String> expectedFields) {
        Set<String> actualFields = new java.util.HashSet<>();
        body.fieldNames().forEachRemaining(actualFields::add);
        return actualFields.equals(expectedFields);
    }

    private static boolean validLeaseId(JsonNode leaseId) {
        return leaseId.isTextual()
                && !leaseId.textValue().isBlank()
                && leaseId.textValue().length() <= 200;
    }

    private static boolean validErrorCode(JsonNode errorCode) {
        if (!errorCode.isTextual()) {
            return false;
        }
        String value = errorCode.textValue();
        if (value.isBlank() || value.length() > 80 || !Character.isLowerCase(value.charAt(0))) {
            return false;
        }
        return value.chars().allMatch(character ->
                Character.isLowerCase(character)
                        || Character.isDigit(character)
                        || character == '_');
    }

    private static ProductException requestFailure(Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null
                ? error.getCause()
                : error;
        if (cause instanceof java.net.http.HttpTimeoutException) {
            return failure(HttpStatus.GATEWAY_TIMEOUT, "REPORTING_LEASE_TIMEOUT",
                    "业务上报租约请求超时");
        }
        return failure(HttpStatus.BAD_GATEWAY, "REPORTING_LEASE_UNAVAILABLE",
                "无法访问业务上报租约服务");
    }

    private static ProductException invalidAcknowledgement() {
        return failure(HttpStatus.BAD_GATEWAY, "INVALID_REPORTING_LEASE_ACK",
                "业务上报租约返回了无效确认");
    }

    private static ProductException requestFailed() {
        return failure(HttpStatus.BAD_GATEWAY, "REPORTING_LEASE_REQUEST_FAILED",
                "业务上报租约请求失败");
    }

    private static ProductException failure(HttpStatus status, String code, String message) {
        return new ProductException(status, code, message);
    }
}
