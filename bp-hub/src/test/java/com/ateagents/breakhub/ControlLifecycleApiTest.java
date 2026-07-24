package com.ateagents.breakhub;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.Cookie;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class ControlLifecycleApiTest {

    private static final String GATEWAY_INSTANCE_HEADER = "X-MBP-Control-Instance";
    private static final Path DATA_DIRECTORY = createDataDirectory();
    private static final ReportingLeaseTestServer SWITCH_SERVER = ReportingLeaseTestServer.start();

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @DynamicPropertySource
    static void productProperties(DynamicPropertyRegistry registry) {
        registry.add("breakhub.data-directory", DATA_DIRECTORY::toString);
        registry.add("breakhub.equipment.id", () -> "equipment-01");
        registry.add("breakhub.equipment.display-name", () -> "一号装备");
        registry.add("breakhub.equipment.debugger-switch.url",
                () -> SWITCH_SERVER.endpoint().toString());
        registry.add("breakhub.security.web-username", () -> "admin");
        registry.add("breakhub.security.web-password", () -> "admin-secret");
        registry.add("breakhub.security.gateway-token", () -> "gateway-secret");
        registry.add("breakhub.security.business-client-token", () -> "business-secret");
        registry.add("breakhub.control-lease.timeout", () -> "800ms");
        registry.add("breakhub.interaction.pause-timeout", () -> "25m");
        registry.add("breakhub.interaction.max-payload-size", () -> "16MB");
        registry.add("server.address", () -> "127.0.0.1");
        registry.add("server.port", () -> "18601");
    }

    @BeforeEach
    void resetSwitchEvidence() {
        SWITCH_SERVER.reset();
    }

    @AfterAll
    static void stopSwitchServer() {
        SWITCH_SERVER.close();
    }

    @Test
    void firstGatewayWriteOwnsControlWhileOtherInstancesStayReadOnly() throws Exception {
        WebClient web = loginWeb();

        MvcResult started = mvc.perform(post("/api/v1/debugging/start")
                        .header("Authorization", "Bearer gateway-secret")
                        .header(GATEWAY_INSTANCE_HEADER, "gateway-a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("started"))
                .andExpect(jsonPath("$.debugging").value(true))
                .andExpect(jsonPath("$.control.controller").value("mcp"))
                .andExpect(jsonPath("$.control.owned_by_requester").value(true))
                .andReturn();
        assertThat(started.getResponse().getContentAsString())
                .doesNotContain("lease_id")
                .doesNotContain("lease-test-");
        assertThat(SWITCH_SERVER.enabled()).isTrue();
        assertThat(SWITCH_SERVER.successfulRequests()).isEqualTo(1);

        mvc.perform(get("/api/v1/overview").session(web.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.debugging.status").value("debugging"))
                .andExpect(jsonPath("$.debugging.reporting.status").value("healthy"))
                .andExpect(jsonPath("$.control.controller").value("mcp"))
                .andExpect(jsonPath("$.control.owned_by_requester").value(false));

        mvc.perform(webWrite(post("/api/v1/debugging/stop"), web))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONTROLLED_BY_MCP"));
        mvc.perform(post("/api/v1/debugging/stop")
                        .header("Authorization", "Bearer gateway-secret")
                        .header(GATEWAY_INSTANCE_HEADER, "gateway-b"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONTROLLED_BY_MCP"));
        assertThat(SWITCH_SERVER.enabled()).isTrue();

        mvc.perform(post("/api/v1/debugging/start")
                        .header("Authorization", "Bearer gateway-secret")
                        .header(GATEWAY_INSTANCE_HEADER, "gateway-a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("already_started"))
                .andExpect(jsonPath("$.debugging").value(true));
        assertThat(SWITCH_SERVER.successfulRequests()).isEqualTo(1);

        mvc.perform(post("/api/v1/debugging/stop")
                        .header("Authorization", "Bearer gateway-secret")
                        .header(GATEWAY_INSTANCE_HEADER, "gateway-a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("stopped"))
                .andExpect(jsonPath("$.debugging").value(false))
                .andExpect(jsonPath("$.control.held").value(true));
        assertThat(SWITCH_SERVER.enabled()).isFalse();
        assertThat(SWITCH_SERVER.successfulRequests()).isEqualTo(2);
        assertThat(SWITCH_SERVER.authorizationSeen()).isFalse();

        mvc.perform(post("/api/v1/control/release")
                        .header("Authorization", "Bearer gateway-secret")
                        .header(GATEWAY_INSTANCE_HEADER, "gateway-a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.released").value(true))
                .andExpect(jsonPath("$.control.held").value(false));
    }

    @Test
    void onlyTheOwningInstanceRenewsAndExpirySafelyReleasesDebugging() throws Exception {
        WebClient web = loginWeb();
        mvc.perform(post("/api/v1/debugging/start")
                        .header("Authorization", "Bearer gateway-secret")
                        .header(GATEWAY_INSTANCE_HEADER, "gateway-a"))
                .andExpect(status().isOk());
        MvcResult baselineRead = mvc.perform(get("/api/v1/overview").session(web.session()))
                .andExpect(status().isOk())
                .andReturn();
        Instant firstExpiry = Instant.parse(control(baselineRead).get("expires_at").toString());

        Thread.sleep(150);
        MvcResult foreignRead = mvc.perform(get("/api/v1/overview").session(web.session()))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(Instant.parse(control(foreignRead).get("expires_at").toString())).isEqualTo(firstExpiry);

        Thread.sleep(150);
        mvc.perform(get("/api/v1/overview")
                        .header("Authorization", "Bearer gateway-secret")
                        .header(GATEWAY_INSTANCE_HEADER, "gateway-a"))
                .andExpect(status().isOk());
        MvcResult afterOwnerRead = mvc.perform(get("/api/v1/overview").session(web.session()))
                .andExpect(status().isOk())
                .andReturn();
        Instant renewedExpiry = Instant.parse(control(afterOwnerRead).get("expires_at").toString());
        assertThat(renewedExpiry).isAfter(firstExpiry);

        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(3).toNanos();
        boolean released = false;
        while (System.nanoTime() < deadline) {
            MvcResult statusResult = mvc.perform(get("/api/v1/overview").session(web.session()))
                    .andExpect(status().isOk())
                    .andReturn();
            if (Boolean.FALSE.equals(control(statusResult).get("held"))) {
                released = true;
                break;
            }
            Thread.sleep(40);
        }
        assertThat(released).isTrue();
        long remoteStopDeadline = System.nanoTime() + java.time.Duration.ofSeconds(2).toNanos();
        while (SWITCH_SERVER.enabled() && System.nanoTime() < remoteStopDeadline) {
            Thread.sleep(20);
        }
        assertThat(SWITCH_SERVER.enabled()).isFalse();
        assertThat(SWITCH_SERVER.successfulRequests()).isEqualTo(2);
    }

    @Test
    void webHeartbeatIsInternalAndLogoutSafelyReleasesItsControl() throws Exception {
        WebClient web = loginWeb();
        mvc.perform(webWrite(post("/api/v1/control/heartbeat"), web))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONTROL_NOT_HELD"));
        mvc.perform(webWrite(post("/api/v1/debugging/start"), web))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.control.controller").value("web"))
                .andExpect(jsonPath("$.control.owned_by_requester").value(true));

        MvcResult heartbeat = mvc.perform(webWrite(post("/api/v1/control/heartbeat"), web))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.renewed").value(true))
                .andReturn();
        assertThat(heartbeat.getResponse().getContentAsString())
                .doesNotContain("token")
                .doesNotContain("instance_id");

        mvc.perform(webWrite(post("/api/auth/logout"), web))
                .andExpect(status().isNoContent());
        assertThat(SWITCH_SERVER.enabled()).isFalse();
        assertThat(SWITCH_SERVER.successfulRequests()).isEqualTo(2);

        mvc.perform(get("/api/v1/overview")
                        .header("Authorization", "Bearer gateway-secret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.debugging.status").value("idle"))
                .andExpect(jsonPath("$.control.held").value(false));
    }

    @Test
    void failedDebuggerSwitchDoesNotStartDebuggingOrKeepNewControl() throws Exception {
        WebClient web = loginWeb();
        SWITCH_SERVER.rejectCreate(true);

        mvc.perform(post("/api/v1/debugging/start")
                        .header("Authorization", "Bearer gateway-secret")
                        .header(GATEWAY_INSTANCE_HEADER, "gateway-a"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REPORTING_LEASE_ALREADY_ACTIVE"));

        mvc.perform(get("/api/v1/overview").session(web.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.debugging.status").value("idle"))
                .andExpect(jsonPath("$.control.held").value(false));
        assertThat(SWITCH_SERVER.enabled()).isFalse();
        assertThat(SWITCH_SERVER.attempts()).isEqualTo(1);
        assertThat(SWITCH_SERVER.successfulRequests()).isZero();
    }

    @Test
    void malformedLeaseAcknowledgementDoesNotCommitLocalDebugging() throws Exception {
        WebClient web = loginWeb();
        SWITCH_SERVER.malformedCreateAcknowledgement(true);

        mvc.perform(post("/api/v1/debugging/start")
                        .header("Authorization", "Bearer gateway-secret")
                        .header(GATEWAY_INSTANCE_HEADER, "gateway-a"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("INVALID_REPORTING_LEASE_ACK"));

        MvcResult overview = mvc.perform(get("/api/v1/overview").session(web.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.debugging.status").value("idle"))
                .andExpect(jsonPath("$.debugging.reporting.status").value("idle"))
                .andExpect(jsonPath("$.control.held").value(false))
                .andReturn();
        assertThat(overview.getResponse().getContentAsString())
                .doesNotContain("lease_id")
                .doesNotContain("lease-test-");
        assertThat(SWITCH_SERVER.enabled()).isTrue();
    }

    @Test
    void failedRemoteDisableCannotPreventLocalStopOrControlRelease() throws Exception {
        mvc.perform(post("/api/v1/debugging/start")
                        .header("Authorization", "Bearer gateway-secret")
                        .header(GATEWAY_INSTANCE_HEADER, "gateway-a"))
                .andExpect(status().isOk());
        SWITCH_SERVER.failStops(1);

        mvc.perform(post("/api/v1/debugging/stop")
                        .header("Authorization", "Bearer gateway-secret")
                        .header(GATEWAY_INSTANCE_HEADER, "gateway-a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("stopped"))
                .andExpect(jsonPath("$.debugging").value(false))
                .andExpect(jsonPath("$.control.held").value(true));

        mvc.perform(get("/api/v1/overview")
                        .header("Authorization", "Bearer gateway-secret")
                        .header(GATEWAY_INSTANCE_HEADER, "gateway-a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.debugging.status").value("idle"))
                .andExpect(jsonPath("$.control.held").value(true))
                .andExpect(jsonPath("$.control.owned_by_requester").value(true));
        assertThat(SWITCH_SERVER.enabled()).isTrue();
        assertThat(SWITCH_SERVER.attempts()).isEqualTo(2);
        assertThat(SWITCH_SERVER.successfulRequests()).isEqualTo(1);

        SWITCH_SERVER.reset();
        mvc.perform(post("/api/v1/debugging/start")
                        .header("Authorization", "Bearer gateway-secret")
                        .header(GATEWAY_INSTANCE_HEADER, "gateway-a"))
                .andExpect(status().isOk());
        SWITCH_SERVER.failStops(1);
        mvc.perform(post("/api/v1/control/release")
                        .header("Authorization", "Bearer gateway-secret")
                        .header(GATEWAY_INSTANCE_HEADER, "gateway-a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.released").value(true))
                .andExpect(jsonPath("$.control.held").value(false));

        mvc.perform(get("/api/v1/overview")
                        .header("Authorization", "Bearer gateway-secret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.debugging.status").value("idle"))
                .andExpect(jsonPath("$.control.held").value(false));
        assertThat(SWITCH_SERVER.enabled()).isTrue();
        assertThat(SWITCH_SERVER.attempts()).isEqualTo(2);
        assertThat(SWITCH_SERVER.successfulRequests()).isEqualTo(1);
    }

    @Test
    void webLogoutClearsInternalStateWithoutRetryingAFailedRemoteDisable() throws Exception {
        WebClient web = loginWeb();
        mvc.perform(webWrite(post("/api/v1/debugging/start"), web))
                .andExpect(status().isOk());
        SWITCH_SERVER.failStops(1);

        mvc.perform(webWrite(post("/api/auth/logout"), web))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/overview")
                        .header("Authorization", "Bearer gateway-secret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.debugging.status").value("idle"))
                .andExpect(jsonPath("$.control.held").value(false));
        assertThat(SWITCH_SERVER.enabled()).isTrue();
        assertThat(SWITCH_SERVER.attempts()).isEqualTo(2);
        assertThat(SWITCH_SERVER.successfulRequests()).isEqualTo(1);
    }

    @Test
    void simultaneousFirstWritesHaveExactlyOneWinner() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<StartAttempt>> futures = List.of("gateway-a", "gateway-b").stream()
                    .map(instance -> executor.submit(() -> {
                        ready.countDown();
                        start.await();
                        MvcResult result = mvc.perform(post("/api/v1/debugging/start")
                                        .header("Authorization", "Bearer gateway-secret")
                                        .header(GATEWAY_INSTANCE_HEADER, instance))
                                .andReturn();
                        return new StartAttempt(instance, result);
                    }))
                    .toList();
            ready.await();
            start.countDown();
            List<StartAttempt> attempts = futures.stream().map(future -> {
                try {
                    return future.get();
                } catch (Exception error) {
                    throw new RuntimeException(error);
                }
            }).toList();

            assertThat(attempts)
                    .extracting(attempt -> attempt.result().getResponse().getStatus())
                    .containsExactlyInAnyOrder(200, 409);
            StartAttempt winner = attempts.stream()
                    .filter(attempt -> attempt.result().getResponse().getStatus() == 200)
                    .findFirst()
                    .orElseThrow();
            StartAttempt loser = attempts.stream()
                    .filter(attempt -> attempt.result().getResponse().getStatus() == 409)
                    .findFirst()
                    .orElseThrow();
            assertThat(loser.result().getResponse().getContentAsString()).contains("CONTROLLED_BY_MCP");
            assertThat(SWITCH_SERVER.successfulRequests()).isEqualTo(1);

            mvc.perform(post("/api/v1/control/release")
                            .header("Authorization", "Bearer gateway-secret")
                            .header(GATEWAY_INSTANCE_HEADER, winner.instance()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.released").value(true));
            assertThat(SWITCH_SERVER.enabled()).isFalse();
            assertThat(SWITCH_SERVER.successfulRequests()).isEqualTo(2);
        } finally {
            executor.shutdownNow();
        }
    }

    private WebClient loginWeb() throws Exception {
        MvcResult login = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"admin","password":"admin-secret"}
                                """))
                .andExpect(status().isOk())
                .andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        MvcResult sessionResult = mvc.perform(get("/api/auth/session").session(session))
                .andExpect(status().isOk())
                .andReturn();
        Map<String, Object> body = objectMapper.readValue(
                sessionResult.getResponse().getContentAsByteArray(), new TypeReference<>() {
                });
        return new WebClient(
                session,
                body.get("csrf_token").toString(),
                sessionResult.getResponse().getCookie("MBP-XSRF-TOKEN"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> control(MvcResult result) throws Exception {
        Map<String, Object> body = objectMapper.readValue(
                result.getResponse().getContentAsByteArray(), new TypeReference<>() {
                });
        return (Map<String, Object>) body.get("control");
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder webWrite(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
            WebClient web) {
        return request.session(web.session())
                .header("X-MBP-XSRF-TOKEN", web.csrfToken())
                .cookie(web.csrfCookie());
    }

    private static Path createDataDirectory() {
        try {
            return Files.createTempDirectory("breakhub-control-test-");
        } catch (Exception error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    private record WebClient(MockHttpSession session, String csrfToken, Cookie csrfCookie) {
    }

    private record StartAttempt(String instance, MvcResult result) {
    }
}
