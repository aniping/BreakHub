package com.ateagents.breakhub.probe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class DebugClientTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
        DebugClient.cancelActiveRequests();
        ReportingChannel.shared().deactivate();
        DebuggerSettings.enabled = false;
        DebuggerSettings.serverUrl = "http://127.0.0.1:18621";
        DebuggerSettings.businessClientToken = "";
        DebuggerSettings.connectTimeoutMs = 300;
        DebuggerSettings.readTimeoutMs = 1000;
        DebuggerSettings.breakpointTimeoutMs = 300000;
    }

    @Test
    void beforeCallFailureDoesNotPermanentlyDisableReporting() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/business/interactions/before", exchange -> {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        server.start();

        enableReporting();
        DebuggerSettings.serverUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        DebuggerSettings.businessClientToken = "business-token";
        DebuggerSettings.connectTimeoutMs = 100;
        DebuggerSettings.readTimeoutMs = 100;

        BeforeCallRequest request = DebugInvoker.buildBeforeCallRequest("call-1",
                TestDebugMethodInfos.commonMethodData("SA", "start", "instrumentControl", 1, Map.of()));

        BeforeCallResponse response = DebugClient.beforeCall(request);

        assertThat(response.isSuccess()).isFalse();
        assertTrue(DebuggerSettings.enabled);
    }

    @Test
    void degradedCallsShareOnlyOneRenewalProbeAndSuccessfulProbeRecovers() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch probeStarted = new CountDownLatch(1);
        CountDownLatch releaseFailedProbe = new CountDownLatch(1);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/business/interactions/before", exchange -> {
            int call = calls.incrementAndGet();
            JsonNode request = OBJECT_MAPPER.readTree(exchange.getRequestBody());
            if (call == 1) {
                exchange.sendResponseHeaders(500, -1);
                exchange.close();
                return;
            }
            if (call == 2) {
                probeStarted.countDown();
                try {
                    releaseFailedProbe.await(5, TimeUnit.SECONDS);
                    exchange.sendResponseHeaders(500, -1);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } finally {
                    exchange.close();
                }
                return;
            }
            respond(exchange, 200, """
                    {"interaction_id":"%s","operation":"created","tracked":true,
                     "proceed":true,"wait_required":false}
                    """.formatted(request.path("interaction_id").asText()));
        });
        server.start();

        DebuggerSettings.serverUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        DebuggerSettings.businessClientToken = "business-token";
        DebuggerSettings.connectTimeoutMs = 100;
        DebuggerSettings.readTimeoutMs = 1000;
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1);
        scheduler.setRemoveOnCancelPolicy(true);
        ReportingLeaseManager lease = new ReportingLeaseManager(
                Duration.ofMinutes(1), scheduler, () -> "probe-lease",
                DebugClient::cancelActiveRequests, System::nanoTime);
        String leaseId = (String) lease.handle("{\"enabled\":true}").body().get("lease_id");
        BeforeCallRequest request = DebugInvoker.buildBeforeCallRequest("first-failure",
                TestDebugMethodInfos.commonMethodData("SA", "start", "instrumentControl", 1, Map.of()));

        assertThat(DebugClient.beforeCall(request).isSuccess()).isFalse();
        assertThat(DebuggerSettings.enabled).isTrue();
        assertThat(calls).hasValue(1);
        for (int index = 0; index < 5; index++) {
            assertThat(DebugClient.beforeCall(request).isSuccess()).isFalse();
        }
        assertThat(calls).hasValue(1);

        ReportingLeaseManager.HttpResult renewed = null;
        for (int index = 0; index < 3; index++) {
            renewed = lease.handle("{\"enabled\":true,\"lease_id\":\"" + leaseId + "\"}");
        }
        assertThat(renewed.body())
                .containsEntry("reporting_status", "degraded")
                .containsEntry("last_error", "before_request_failed");

        int concurrency = 12;
        CountDownLatch ready = new CountDownLatch(concurrency);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch returnedWithoutProbe = new CountDownLatch(concurrency - 1);
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        List<Future<BeforeCallResponse>> responses = new ArrayList<>();
        try {
            for (int index = 0; index < concurrency; index++) {
                int requestNumber = index;
                responses.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        return DebugClient.beforeCall(DebugInvoker.buildBeforeCallRequest(
                                "concurrent-" + requestNumber,
                                TestDebugMethodInfos.commonMethodData(
                                        "SA", "start", "instrumentControl", 1, Map.of())));
                    } finally {
                        returnedWithoutProbe.countDown();
                    }
                }));
            }
            assertThat(ready.await(2, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(probeStarted.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(returnedWithoutProbe.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(calls).hasValue(2);

            releaseFailedProbe.countDown();
            for (Future<BeforeCallResponse> response : responses) {
                assertThat(response.get(2, TimeUnit.SECONDS).isSuccess()).isFalse();
            }
            assertThat(calls).hasValue(2);
            assertThat(DebugClient.beforeCall(request).isSuccess()).isFalse();
            assertThat(calls).hasValue(2);

            lease.handle("{\"enabled\":true,\"lease_id\":\"" + leaseId + "\"}");
            assertThat(DebugClient.beforeCall(DebugInvoker.buildBeforeCallRequest(
                    "successful-probe",
                    TestDebugMethodInfos.commonMethodData(
                            "SA", "start", "instrumentControl", 1, Map.of()))).isSuccess()).isTrue();
            assertThat(DebugClient.beforeCall(DebugInvoker.buildBeforeCallRequest(
                    "healthy-request",
                    TestDebugMethodInfos.commonMethodData(
                            "SA", "start", "instrumentControl", 1, Map.of()))).isSuccess()).isTrue();
            assertThat(calls).hasValue(4);

            ReportingLeaseManager.HttpResult healthy = lease.handle(
                    "{\"enabled\":true,\"lease_id\":\"" + leaseId + "\"}");
            assertThat(healthy.body())
                    .containsEntry("reporting_status", "healthy")
                    .doesNotContainKey("last_error");
        } finally {
            start.countDown();
            releaseFailedProbe.countDown();
            executor.shutdownNow();
            lease.close();
        }
    }

    @Test
    void beforeCallUsesTheCurrentBusinessContractAndBearerCredential() throws Exception {
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<JsonNode> requestBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/business/interactions/before", exchange -> {
            method.set(exchange.getRequestMethod());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(OBJECT_MAPPER.readTree(exchange.getRequestBody()));
            respond(exchange, 200, """
                    {"interaction_id":"call-1","operation":"created","tracked":true,
                     "proceed":true,"wait_required":false}
                    """);
        });
        server.start();

        enableReporting();
        DebuggerSettings.serverUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        DebuggerSettings.businessClientToken = "business-token";
        BeforeCallRequest request = DebugInvoker.buildBeforeCallRequest("call-1",
                TestDebugMethodInfos.commonMethodData(
                        "SA", "start", "instrumentControl", 1,
                        Map.of("mode", "AUTO")));

        DebugClient.beforeCall(request);

        assertThat(method).hasValue("POST");
        assertThat(authorization).hasValue("Bearer business-token");
        assertThat(requestBody.get().properties().stream().map(Map.Entry::getKey).collect(java.util.stream.Collectors.toSet()))
                .isEqualTo(Set.of("interaction_id", "object", "command", "params"));
        assertThat(requestBody.get().path("interaction_id").asText()).isEqualTo("call-1");
        assertThat(requestBody.get().path("object").asText()).isEqualTo("SA");
        assertThat(requestBody.get().path("command").asText()).isEqualTo("start");
        assertThat(requestBody.get().at("/params/mode").asText()).isEqualTo("AUTO");
    }

    @Test
    void afterCallUsesTheCurrentBusinessContractAndBearerCredential() throws Exception {
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<JsonNode> requestBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/business/interactions/after", exchange -> {
            method.set(exchange.getRequestMethod());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(OBJECT_MAPPER.readTree(exchange.getRequestBody()));
            respond(exchange, 200, """
                    {"interaction_id":"call-1","operation":"completed","tracked":true,
                     "proceed":true,"wait_required":false,"lifecycle":"completed"}
                    """);
        });
        server.start();

        enableReporting();
        DebuggerSettings.serverUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        DebuggerSettings.businessClientToken = "business-token";
        AfterCallRequest request = new AfterCallRequest();
        request.setCallId("call-1");
        request.setResult(Map.of("code", 0, "message", "ok"));

        DebugClient.afterCall(request);

        assertThat(method).hasValue("POST");
        assertThat(authorization).hasValue("Bearer business-token");
        assertThat(requestBody.get().properties().stream()
                .map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toSet()))
                .isEqualTo(Set.of("interaction_id", "result"));
        assertThat(requestBody.get().path("interaction_id").asText()).isEqualTo("call-1");
        assertThat(requestBody.get().at("/result/message").asText()).isEqualTo("ok");
    }

    @Test
    void afterResponseRequiresExplicitBooleanPauseSignals() throws Exception {
        AfterCallResponse missingProceed = OBJECT_MAPPER.readValue("""
                {"interaction_id":"call-1","tracked":true,"wait_required":true}
                """, AfterCallResponse.class);
        AfterCallResponse nullProceed = OBJECT_MAPPER.readValue("""
                {"interaction_id":"call-1","tracked":true,"proceed":null,"wait_required":true}
                """, AfterCallResponse.class);
        AfterCallResponse explicitPause = OBJECT_MAPPER.readValue("""
                {"interaction_id":"call-1","tracked":true,"proceed":false,"wait_required":true}
                """, AfterCallResponse.class);

        assertThat(missingProceed.shouldWait("call-1")).isFalse();
        assertThat(nullProceed.shouldWait("call-1")).isFalse();
        assertThat(explicitPause.shouldWait("call-1")).isTrue();
    }

    @Test
    void ordinaryCallUsesOneDeadlineAcrossTheWholeHttpExchange() throws Exception {
        byte[] responseBytes = """
                {"interaction_id":"call-1","operation":"created","tracked":true,
                 "proceed":true,"wait_required":false}
                """.getBytes(StandardCharsets.UTF_8);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/business/interactions/before", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, responseBytes.length);
            try {
                for (byte value : responseBytes) {
                    exchange.getResponseBody().write(value);
                    exchange.getResponseBody().flush();
                    Thread.sleep(30);
                }
            } catch (IOException ignored) {
                // Expected when the client cancels the over-deadline exchange.
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();

        enableReporting();
        DebuggerSettings.serverUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        DebuggerSettings.businessClientToken = "business-token";
        DebuggerSettings.connectTimeoutMs = 0;
        DebuggerSettings.readTimeoutMs = 200;
        BeforeCallRequest request = DebugInvoker.buildBeforeCallRequest("call-1",
                TestDebugMethodInfos.commonMethodData(
                        "SA", "start", "instrumentControl", 1,
                        Map.of("mode", "AUTO")));

        long startedAt = System.nanoTime();
        BeforeCallResponse response = DebugClient.beforeCall(request);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

        assertThat(response.isSuccess()).isFalse();
        assertThat(elapsedMs).isLessThan(1000);
        assertTrue(DebuggerSettings.enabled);
    }

    @Test
    void cancelledPlaceholderCannotStartAnUnregisteredTransport() {
        DebugClient.ActiveRequest request = new DebugClient.ActiveRequest();
        java.util.concurrent.atomic.AtomicBoolean transportStarted =
                new java.util.concurrent.atomic.AtomicBoolean();
        request.cancel();

        assertThatThrownBy(() -> request.start(() -> {
            transportStarted.set(true);
            return new CompletableFuture<>();
        })).isInstanceOf(CancellationException.class);
        assertThat(transportStarted).isFalse();
    }

    @Test
    void cancellingActiveRequestsReleasesABlockedWait() throws Exception {
        CountDownLatch requestStarted = new CountDownLatch(1);
        CountDownLatch releaseServer = new CountDownLatch(1);
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<JsonNode> requestBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/business/interactions/wait", exchange -> {
            method.set(exchange.getRequestMethod());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(OBJECT_MAPPER.readTree(exchange.getRequestBody()));
            requestStarted.countDown();
            try {
                releaseServer.await(10, TimeUnit.SECONDS);
                exchange.sendResponseHeaders(200, 0);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();

        enableReporting();
        DebuggerSettings.serverUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        DebuggerSettings.businessClientToken = "business-token";
        DebuggerSettings.connectTimeoutMs = 100;
        DebuggerSettings.breakpointTimeoutMs = 10000;

        ExecutorService clientExecutor = Executors.newSingleThreadExecutor();
        try {
            Future<WaitResponse> response = clientExecutor.submit(() -> DebugClient.waitContinue("call-1"));
            assertTrue(requestStarted.await(2, TimeUnit.SECONDS));

            DebugClient.cancelActiveRequests();

            assertThat(response.get(2, TimeUnit.SECONDS)).isNotNull();
            assertThat(method).hasValue("POST");
            assertThat(authorization).hasValue("Bearer business-token");
            assertThat(requestBody.get().path("interaction_id").asText()).isEqualTo("call-1");
            assertThat(requestBody.get().path("pause_point").asText()).isEqualTo("before");
        } finally {
            releaseServer.countDown();
            clientExecutor.shutdownNow();
        }
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static void enableReporting() {
        DebuggerSettings.enabled = true;
        ReportingChannel.shared().activate();
    }
}
