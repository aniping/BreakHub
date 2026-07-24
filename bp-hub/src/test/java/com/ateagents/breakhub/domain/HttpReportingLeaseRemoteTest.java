package com.ateagents.breakhub.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;

import com.ateagents.breakhub.api.ProductException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

class HttpReportingLeaseRemoteTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void createRequiresTheCompleteDemoAcknowledgementWithoutAuthorization() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        server = startServer(exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, """
                    {"success":true,"result":"created","changed":true,"enabled":true,
                     "lease_timeout_seconds":30,"reporting_status":"healthy","lease_id":"lease-created"}
                    """);
        });

        HttpReportingLeaseRemote client = new HttpReportingLeaseRemote(
                endpoint(server), Duration.ofSeconds(1), new ObjectMapper());

        ReportingLeaseRemote.LeaseAcknowledgement acknowledgement =
                client.create().get(2, TimeUnit.SECONDS);

        assertThat(acknowledgement.leaseId()).isEqualTo("lease-created");
        assertThat(acknowledgement.leaseTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(acknowledgement.channelStatus()).isEqualTo("healthy");
        assertThat(acknowledgement.channelLastError()).isNull();
        assertThat(requestBody.get()).isEqualTo("{\"enabled\":true}");
        assertThat(authorization.get()).isNull();
    }

    @Test
    void renewAndStopUseTheCreatedLeaseIdentity() throws Exception {
        List<String> requests = new ArrayList<>();
        AtomicInteger requestNumber = new AtomicInteger();
        server = startServer(exchange -> {
            requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            switch (requestNumber.getAndIncrement()) {
                case 0 -> respond(exchange, 200, """
                        {"success":true,"result":"created","changed":true,"enabled":true,
                         "lease_timeout_seconds":30,"reporting_status":"healthy","lease_id":"lease-cycle"}
                        """);
                case 1 -> respond(exchange, 200, """
                        {"success":true,"result":"renewed","changed":false,"enabled":true,
                         "lease_timeout_seconds":30,"reporting_status":"healthy","lease_id":"lease-cycle"}
                        """);
                case 2 -> respond(exchange, 200, """
                        {"success":true,"result":"stopped","changed":true,"enabled":false,
                         "lease_timeout_seconds":30,"reporting_status":"idle"}
                        """);
                default -> respond(exchange, 500, "{}");
            }
        });
        HttpReportingLeaseRemote client = new HttpReportingLeaseRemote(
                endpoint(server), Duration.ofSeconds(1), new ObjectMapper());

        String leaseId = client.create().get(2, TimeUnit.SECONDS).leaseId();
        ReportingLeaseRemote.LeaseAcknowledgement renewed =
                client.renew(leaseId).get(2, TimeUnit.SECONDS);
        client.stop(leaseId).get(2, TimeUnit.SECONDS);

        assertThat(renewed.channelStatus()).isEqualTo("healthy");
        assertThat(renewed.channelLastError()).isNull();

        ObjectMapper objectMapper = new ObjectMapper();
        assertThat(requests).hasSize(3);
        assertThat(objectMapper.readTree(requests.get(0)))
                .isEqualTo(objectMapper.readTree("{\"enabled\":true}"));
        assertThat(objectMapper.readTree(requests.get(1)))
                .isEqualTo(objectMapper.readTree(
                        "{\"enabled\":true,\"lease_id\":\"lease-cycle\"}"));
        assertThat(objectMapper.readTree(requests.get(2)))
                .isEqualTo(objectMapper.readTree(
                        "{\"enabled\":false,\"lease_id\":\"lease-cycle\"}"));
    }

    @ParameterizedTest
    @MethodSource("invalidCreateAcknowledgements")
    void invalidCreateAcknowledgementIsRejected(int status, String responseBody) throws Exception {
        server = startServer(exchange -> respond(exchange, status, responseBody));
        HttpReportingLeaseRemote client = new HttpReportingLeaseRemote(
                endpoint(server), Duration.ofSeconds(1), new ObjectMapper());

        assertProductFailure(client.create(), HttpStatus.BAD_GATEWAY, "INVALID_REPORTING_LEASE_ACK");
    }

    @Test
    void activeLeaseConflictRemainsDistinguishable() throws Exception {
        server = startServer(exchange -> respond(exchange, 409, """
                {"code":"REPORTING_LEASE_ALREADY_ACTIVE","message":"A reporting lease is already active"}
                """));
        HttpReportingLeaseRemote client = new HttpReportingLeaseRemote(
                endpoint(server), Duration.ofSeconds(1), new ObjectMapper());

        assertProductFailure(client.create(), HttpStatus.CONFLICT,
                "REPORTING_LEASE_ALREADY_ACTIVE");
    }

    @Test
    void missingRenewalLeaseRemainsDistinguishableForRecovery() throws Exception {
        server = startServer(exchange -> respond(exchange, 404, """
                {"code":"REPORTING_LEASE_NOT_FOUND","message":"Reporting lease not found"}
                """));
        HttpReportingLeaseRemote client = new HttpReportingLeaseRemote(
                endpoint(server), Duration.ofSeconds(1), new ObjectMapper());

        assertProductFailure(
                client.renew("lease-from-old-process"),
                HttpStatus.BAD_GATEWAY,
                "REPORTING_LEASE_NOT_FOUND");
    }

    @Test
    void requestHasAHardTimeout() throws Exception {
        server = startServer(exchange -> {
            try {
                Thread.sleep(2_000);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        });
        HttpReportingLeaseRemote client = new HttpReportingLeaseRemote(
                endpoint(server), Duration.ofMillis(100), new ObjectMapper());

        assertProductFailure(client.create(), HttpStatus.GATEWAY_TIMEOUT,
                "REPORTING_LEASE_TIMEOUT");
    }

    @Test
    void degradedRenewalAcknowledgementCarriesOnlyTheSanitizedChannelError() throws Exception {
        server = startServer(exchange -> respond(exchange, 200, """
                {"success":true,"result":"renewed","changed":false,"enabled":true,
                 "lease_timeout_seconds":30,"reporting_status":"degraded",
                 "last_error":"before_request_failed","lease_id":"lease-degraded"}
                """));
        HttpReportingLeaseRemote client = new HttpReportingLeaseRemote(
                endpoint(server), Duration.ofSeconds(1), new ObjectMapper());

        ReportingLeaseRemote.LeaseAcknowledgement acknowledgement =
                client.renew("lease-degraded").get(2, TimeUnit.SECONDS);

        assertThat(acknowledgement.channelStatus()).isEqualTo("degraded");
        assertThat(acknowledgement.channelLastError()).isEqualTo("before_request_failed");
    }

    @ParameterizedTest
    @MethodSource("invalidRenewalAcknowledgements")
    void invalidRenewalAcknowledgementIsRejected(String responseBody) throws Exception {
        server = startServer(exchange -> respond(exchange, 200, responseBody));
        HttpReportingLeaseRemote client = new HttpReportingLeaseRemote(
                endpoint(server), Duration.ofSeconds(1), new ObjectMapper());

        assertProductFailure(
                client.renew("lease-renewed"),
                HttpStatus.BAD_GATEWAY,
                "INVALID_REPORTING_LEASE_ACK");
    }

    @Test
    void renewalHasAHardTimeout() throws Exception {
        server = startServer(exchange -> {
            try {
                Thread.sleep(2_000);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        });
        HttpReportingLeaseRemote client = new HttpReportingLeaseRemote(
                endpoint(server), Duration.ofMillis(100), new ObjectMapper());

        assertProductFailure(
                client.renew("lease-timeout"),
                HttpStatus.GATEWAY_TIMEOUT,
                "REPORTING_LEASE_TIMEOUT");
    }

    @Test
    void blackholedRenewalUsesTheProductionFiveSecondTimeout() throws Exception {
        CountDownLatch requestStarted = new CountDownLatch(1);
        CountDownLatch releaseServer = new CountDownLatch(1);
        server = startServer(exchange -> {
            exchange.getRequestBody().readAllBytes();
            requestStarted.countDown();
            try {
                releaseServer.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        HttpReportingLeaseRemote client = new HttpReportingLeaseRemote(
                endpoint(server), HttpReportingLeaseRemote.REQUEST_TIMEOUT, new ObjectMapper());

        long startedAt = System.nanoTime();
        try {
            var renewal = client.renew("lease-blackhole");
            assertThat(requestStarted.await(2, TimeUnit.SECONDS)).isTrue();
            assertProductFailure(renewal, HttpStatus.GATEWAY_TIMEOUT,
                    "REPORTING_LEASE_TIMEOUT");
            assertThat(Duration.ofNanos(System.nanoTime() - startedAt))
                    .isBetween(Duration.ofSeconds(4), Duration.ofSeconds(7));
        } finally {
            releaseServer.countDown();
        }
    }

    @Test
    void blackholedRenewalCanBeCancelledBeforeItsTimeout() throws Exception {
        CountDownLatch requestStarted = new CountDownLatch(1);
        CountDownLatch releaseServer = new CountDownLatch(1);
        server = startServer(exchange -> {
            exchange.getRequestBody().readAllBytes();
            requestStarted.countDown();
            try {
                releaseServer.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        HttpReportingLeaseRemote client = new HttpReportingLeaseRemote(
                endpoint(server), HttpReportingLeaseRemote.REQUEST_TIMEOUT, new ObjectMapper());

        try {
            var renewal = client.renew("lease-cancelled");
            assertThat(requestStarted.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(renewal.cancel(true)).isTrue();
            assertThat(renewal).isCancelled();
        } finally {
            releaseServer.countDown();
        }
    }

    private static Stream<Arguments> invalidCreateAcknowledgements() {
        return Stream.of(
                Arguments.of(204, ""),
                Arguments.of(200, """
                        {"success":true,"result":"created","changed":true,"enabled":true,
                         "lease_timeout_seconds":30,"reporting_status":"healthy"}
                        """),
                Arguments.of(200, """
                        {"success":true,"result":"renewed","changed":true,"enabled":true,
                         "lease_timeout_seconds":30,"reporting_status":"healthy","lease_id":"lease"}
                        """),
                Arguments.of(200, """
                        {"success":true,"result":"created","changed":true,"enabled":true,
                         "lease_timeout_seconds":29,"reporting_status":"healthy","lease_id":"lease"}
                        """),
                Arguments.of(200, """
                        {"success":true,"result":"created","changed":true,"enabled":true,
                         "lease_timeout_seconds":30,"reporting_status":"healthy","lease_id":"lease",
                         "unexpected":true}
                        """));
    }

    private static Stream<Arguments> invalidRenewalAcknowledgements() {
        return Stream.of(
                Arguments.of("""
                        {"success":true,"result":"renewed","changed":false,"enabled":true,
                         "lease_timeout_seconds":30,"reporting_status":"unknown",
                         "last_error":"before_request_failed","lease_id":"lease-renewed"}
                        """),
                Arguments.of("""
                        {"success":true,"result":"renewed","changed":false,"enabled":true,
                         "lease_timeout_seconds":30,"reporting_status":"degraded",
                         "last_error":"","lease_id":"lease-renewed"}
                        """),
                Arguments.of("""
                        {"success":true,"result":"renewed","changed":false,"enabled":true,
                         "lease_timeout_seconds":30,"reporting_status":"degraded",
                         "last_error":"http://internal/secret","lease_id":"lease-renewed"}
                        """),
                Arguments.of("""
                        {"success":true,"result":"renewed","changed":false,"enabled":true,
                         "lease_timeout_seconds":30,"reporting_status":"degraded",
                         "last_error":"before_request_failed","lease_id":"wrong-lease"}
                        """),
                Arguments.of("""
                        {"success":true,"result":"renewed","changed":false,"enabled":true,
                         "lease_timeout_seconds":30,"reporting_status":"healthy",
                         "last_error":"before_request_failed","lease_id":"lease-renewed"}
                        """));
    }

    private static void assertProductFailure(
            java.util.concurrent.CompletableFuture<?> request,
            HttpStatus expectedStatus,
            String expectedCode) {
        assertThatThrownBy(() -> request.get(7, TimeUnit.SECONDS))
                .hasRootCauseInstanceOf(ProductException.class)
                .satisfies(error -> {
                    Throwable cause = error;
                    while (cause.getCause() != null) {
                        cause = cause.getCause();
                    }
                    ProductException productError = (ProductException) cause;
                    assertThat(productError.status()).isEqualTo(expectedStatus);
                    assertThat(productError.code()).isEqualTo(expectedCode);
                });
    }

    private static HttpServer startServer(ExchangeHandler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/demo/debugger/enabled", exchange -> handler.handle(exchange));
        server.start();
        return server;
    }

    private static URI endpoint(HttpServer server) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort()
                + "/api/demo/debugger/enabled");
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] response = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
