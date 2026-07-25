package com.ateagents.breakhub.probe;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class BreakHubProbeTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void disabledProbeReusesItsInMemoryStateAcrossBusinessCalls() {
        ProbeConfig config = ProbeConfig.of("http://127.0.0.1:1", "test-token");
        DebugMethodInfo methodInfo = TestDebugMethodInfos.commonMethodData(
                "VNA", "start", "instrumentControl", 1, new LinkedHashMap<>());

        try (BreakHubProbe probe = BreakHubProbe.open(config)) {
            assertThat(probe.invoke(methodInfo, () -> "first")).isEqualTo("first");
            assertThat(probe.invoke(methodInfo, () -> "second")).isEqualTo("second");
        }
    }

    @Test
    void configNormalizesOnlyTheHubUrlAndPreservesTheToken() {
        ProbeConfig config = ProbeConfig.of(
                "http://127.0.0.1:18621/",
                "token-ending-with-slash/");

        assertThat(config.hubUrl()).isEqualTo("http://127.0.0.1:18621");
        assertThat(config.businessClientToken()).isEqualTo("token-ending-with-slash/");
    }

    @Test
    void leaseStateIsIsolatedBetweenProbeInstances() throws Exception {
        ProbeConfig config = ProbeConfig.of("http://127.0.0.1:1", "test-token");

        try (BreakHubProbe first = BreakHubProbe.open(config);
                BreakHubProbe second = BreakHubProbe.open(config)) {
            LeaseResult firstCreated = first.handleLease("{\"enabled\":true}");
            LeaseResult secondCreated = second.handleLease("{\"enabled\":true}");
            String firstLeaseId = body(firstCreated).get("lease_id").textValue();
            String secondLeaseId = body(secondCreated).get("lease_id").textValue();

            first.handleLease(request(false, firstLeaseId));
            LeaseResult secondRenewed = second.handleLease(request(true, secondLeaseId));

            assertThat(firstCreated.statusCode()).isEqualTo(200);
            assertThat(secondCreated.statusCode()).isEqualTo(200);
            assertThat(secondLeaseId).isNotEqualTo(firstLeaseId);
            assertThat(body(secondRenewed).get("reporting_status").textValue())
                    .isEqualTo("healthy");
        }
    }

    @Test
    void stoppingOneProbeDoesNotDisableAnotherProbeInstance() throws Exception {
        AtomicInteger reportingCalls = new AtomicInteger();
        String hubUrl = startReportingServer(reportingCalls);
        ProbeConfig config = new ProbeConfig(hubUrl, "test-token", 200, 500, 2000);
        DebugMethodInfo methodInfo = TestDebugMethodInfos.commonMethodData(
                "VNA", "start", "instrumentControl", 1, new LinkedHashMap<>());

        try (BreakHubProbe first = BreakHubProbe.open(config);
                BreakHubProbe second = BreakHubProbe.open(config)) {
            String firstLeaseId = body(first.handleLease("{\"enabled\":true}"))
                    .get("lease_id").textValue();
            second.handleLease("{\"enabled\":true}");

            first.handleLease(request(false, firstLeaseId));
            assertThat(second.invoke(methodInfo, () -> "business-result"))
                    .isEqualTo("business-result");

            assertThat(reportingCalls).hasValue(2);
        }
    }

    private JsonNode body(LeaseResult result) throws Exception {
        return OBJECT_MAPPER.readTree(result.responseBody());
    }

    private String request(boolean enabled, String leaseId) {
        return "{\"enabled\":" + enabled + ",\"lease_id\":\"" + leaseId + "\"}";
    }

    private String startReportingServer(AtomicInteger reportingCalls) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/business/interactions/before", exchange -> {
            reportingCalls.incrementAndGet();
            JsonNode request = OBJECT_MAPPER.readTree(exchange.getRequestBody());
            respond(exchange, """
                    {"interaction_id":"%s","operation":"created","tracked":true,
                     "proceed":true,"wait_required":false}
                    """.formatted(request.path("interaction_id").asText()));
        });
        server.createContext("/api/business/interactions/after", exchange -> {
            reportingCalls.incrementAndGet();
            JsonNode request = OBJECT_MAPPER.readTree(exchange.getRequestBody());
            respond(exchange, """
                    {"interaction_id":"%s","operation":"completed","tracked":true,
                     "proceed":true,"wait_required":false,"lifecycle":"completed"}
                    """.formatted(request.path("interaction_id").asText()));
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
