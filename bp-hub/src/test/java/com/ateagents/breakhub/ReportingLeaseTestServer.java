package com.ateagents.breakhub;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

final class ReportingLeaseTestServer implements AutoCloseable {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Set<String> CREATE_FIELDS = Set.of("enabled");
    private static final Set<String> LEASE_FIELDS = Set.of("enabled", "lease_id");

    private final HttpServer server;
    private boolean enabled;
    private boolean rejectCreate;
    private boolean malformedCreateAcknowledgement;
    private int stopFailures;
    private int renewalFailures;
    private int renewalAttempts;
    private int attempts;
    private int successfulRequests;
    private int nextLeaseNumber;
    private String activeLeaseId;
    private String lastClosedLeaseId;
    private String lastStoppedLeaseId;
    private boolean authorizationSeen;
    private CountDownLatch stopStarted;
    private CountDownLatch allowStop;
    private CountDownLatch renewalStarted;
    private CountDownLatch allowRenewal;

    private ReportingLeaseTestServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/switch", this::handle);
        server.start();
    }

    static ReportingLeaseTestServer start() {
        try {
            return new ReportingLeaseTestServer();
        } catch (IOException error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    URI endpoint() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/switch");
    }

    InetSocketAddress getAddress() {
        return server.getAddress();
    }

    synchronized void reset() {
        enabled = false;
        rejectCreate = false;
        malformedCreateAcknowledgement = false;
        stopFailures = 0;
        renewalFailures = 0;
        renewalAttempts = 0;
        attempts = 0;
        successfulRequests = 0;
        activeLeaseId = null;
        lastClosedLeaseId = null;
        lastStoppedLeaseId = null;
        authorizationSeen = false;
        stopStarted = null;
        allowStop = null;
        renewalStarted = null;
        allowRenewal = null;
    }

    synchronized void simulateProcessRestart() {
        enabled = false;
        rejectCreate = false;
        malformedCreateAcknowledgement = false;
        stopFailures = 0;
        renewalFailures = 0;
        activeLeaseId = null;
        lastClosedLeaseId = null;
        lastStoppedLeaseId = null;
        stopStarted = null;
        allowStop = null;
        renewalStarted = null;
        allowRenewal = null;
    }

    synchronized void rejectCreate(boolean reject) {
        rejectCreate = reject;
    }

    synchronized void malformedCreateAcknowledgement(boolean malformed) {
        malformedCreateAcknowledgement = malformed;
    }

    synchronized void failStops(int failures) {
        stopFailures = failures;
    }

    synchronized void failRenewals(int failures) {
        renewalFailures = failures;
    }

    synchronized void blockStops(CountDownLatch started, CountDownLatch allow) {
        stopStarted = started;
        allowStop = allow;
    }

    synchronized void blockRenewals(CountDownLatch started, CountDownLatch allow) {
        renewalStarted = started;
        allowRenewal = allow;
    }

    synchronized boolean enabled() {
        return enabled;
    }

    synchronized int attempts() {
        return attempts;
    }

    synchronized int successfulRequests() {
        return successfulRequests;
    }

    synchronized int renewalAttempts() {
        return renewalAttempts;
    }

    synchronized String activeLeaseId() {
        return activeLeaseId;
    }

    synchronized String lastStoppedLeaseId() {
        return lastStoppedLeaseId;
    }

    synchronized boolean authorizationSeen() {
        return authorizationSeen;
    }

    @Override
    public void close() {
        server.stop(0);
    }

    void stop(int ignoredDelaySeconds) {
        close();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        synchronized (this) {
            authorizationSeen |= exchange.getRequestHeaders().getFirst("Authorization") != null;
            LeaseRequest request = parse(requestBody);
            if (!"POST".equals(exchange.getRequestMethod()) || request == null) {
                respondError(exchange, 400, "INVALID_REPORTING_LEASE_REQUEST",
                        "Invalid reporting lease request");
                return;
            }
            attempts++;
            if (request.enabled() && request.leaseId() == null) {
                create(exchange);
            } else if (request.enabled()) {
                renew(exchange, request.leaseId());
            } else {
                stop(exchange, request.leaseId());
            }
        }
    }

    private void create(HttpExchange exchange) throws IOException {
        if (rejectCreate || activeLeaseId != null) {
            respondError(exchange, 409, "REPORTING_LEASE_ALREADY_ACTIVE",
                    "A reporting lease is already active");
            return;
        }
        activeLeaseId = "lease-test-" + ++nextLeaseNumber;
        enabled = true;
        if (malformedCreateAcknowledgement) {
            respond(exchange, 200, """
                    {"success":true,"result":"created","changed":true,"enabled":true,
                     "lease_timeout_seconds":30,"reporting_status":"healthy"}
                    """);
            return;
        }
        successfulRequests++;
        respondSuccess(exchange, "created", true, true, activeLeaseId);
    }

    private void renew(HttpExchange exchange, String leaseId) throws IOException {
        renewalAttempts++;
        if (activeLeaseId == null) {
            respondError(exchange, 404, "REPORTING_LEASE_NOT_FOUND", "Reporting lease not found");
            return;
        }
        if (!activeLeaseId.equals(leaseId)) {
            respondError(exchange, 409, "REPORTING_LEASE_CONFLICT",
                    "Reporting lease does not match the active lease");
            return;
        }
        CountDownLatch started = renewalStarted;
        CountDownLatch allow = allowRenewal;
        if (started != null && allow != null) {
            started.countDown();
            try {
                allow.await(40, TimeUnit.SECONDS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        }
        if (renewalFailures > 0) {
            renewalFailures--;
            respondError(exchange, 503, "REPORTING_LEASE_UNAVAILABLE",
                    "Reporting lease service unavailable");
            return;
        }
        successfulRequests++;
        respondSuccess(exchange, "renewed", false, true, activeLeaseId);
    }

    private void stop(HttpExchange exchange, String leaseId) throws IOException {
        if (activeLeaseId == null) {
            if (leaseId.equals(lastClosedLeaseId)) {
                successfulRequests++;
                respondSuccess(exchange, "already_stopped", false, false, null);
                return;
            }
            respondError(exchange, 404, "REPORTING_LEASE_NOT_FOUND", "Reporting lease not found");
            return;
        }
        if (!activeLeaseId.equals(leaseId)) {
            respondError(exchange, 409, "REPORTING_LEASE_CONFLICT",
                    "Reporting lease does not match the active lease");
            return;
        }
        CountDownLatch started = stopStarted;
        CountDownLatch allow = allowStop;
        if (started != null && allow != null) {
            started.countDown();
            try {
                allow.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        }
        if (stopFailures > 0) {
            stopFailures--;
            respondError(exchange, 503, "REPORTING_LEASE_UNAVAILABLE",
                    "Reporting lease service unavailable");
            return;
        }
        lastStoppedLeaseId = leaseId;
        lastClosedLeaseId = leaseId;
        activeLeaseId = null;
        enabled = false;
        successfulRequests++;
        respondSuccess(exchange, "stopped", true, false, null);
    }

    private static LeaseRequest parse(String requestBody) {
        try {
            JsonNode body = OBJECT_MAPPER.readTree(requestBody);
            if (body == null || !body.isObject() || !body.path("enabled").isBoolean()) {
                return null;
            }
            Set<String> fields = new HashSet<>();
            body.fieldNames().forEachRemaining(fields::add);
            String leaseId = null;
            if (body.has("lease_id")) {
                if (!body.path("lease_id").isTextual() || body.path("lease_id").textValue().isBlank()) {
                    return null;
                }
                leaseId = body.path("lease_id").textValue();
            }
            if (!fields.equals(leaseId == null ? CREATE_FIELDS : LEASE_FIELDS)
                    || (!body.path("enabled").booleanValue() && leaseId == null)) {
                return null;
            }
            return new LeaseRequest(body.path("enabled").booleanValue(), leaseId);
        } catch (Exception error) {
            return null;
        }
    }

    private void respondSuccess(
            HttpExchange exchange,
            String result,
            boolean changed,
            boolean enabled,
            String leaseId) throws IOException {
        String leaseField = leaseId == null ? "" : ",\"lease_id\":\"" + leaseId + "\"";
        respond(exchange, 200, "{" +
                "\"success\":true," +
                "\"result\":\"" + result + "\"," +
                "\"changed\":" + changed + "," +
                "\"enabled\":" + enabled + "," +
                "\"lease_timeout_seconds\":30," +
                "\"reporting_status\":\"" + (enabled ? "healthy" : "idle") + "\"" +
                leaseField + "}");
    }

    private void respondError(HttpExchange exchange, int status, String code, String message)
            throws IOException {
        respond(exchange, status, "{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private record LeaseRequest(boolean enabled, String leaseId) {
    }
}
