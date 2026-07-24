package com.ateagents.breakhub;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.doAnswer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.ateagents.breakhub.domain.CurrentSessionService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;

@SpringBootTest
@AutoConfigureMockMvc
class LocalSessionWorkspaceApiTest {

    private static final Path DATA_DIRECTORY = createDataDirectory();
    private static final ReportingLeaseTestServer SWITCH_SERVER = ReportingLeaseTestServer.start();

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @SpyBean
    private CurrentSessionService sessions;

    @DynamicPropertySource
    static void productProperties(DynamicPropertyRegistry registry) {
        registry.add("breakhub.data-directory", DATA_DIRECTORY::toString);
        registry.add("breakhub.equipment.id", () -> "equipment-01");
        registry.add("breakhub.equipment.display-name", () -> "一号装备");
        registry.add("breakhub.equipment.debugger-switch.url", () -> SWITCH_SERVER.endpoint().toString());
        registry.add("breakhub.security.web-username", () -> "admin");
        registry.add("breakhub.security.web-password", () -> "admin-secret");
        registry.add("breakhub.security.gateway-token", () -> "gateway-secret");
        registry.add("breakhub.security.business-client-token", () -> "business-secret");
        registry.add("breakhub.control-lease.timeout", () -> "30m");
        registry.add("breakhub.interaction.pause-timeout", () -> "25m");
        registry.add("breakhub.interaction.max-payload-size", () -> "16MB");
        registry.add("server.address", () -> "127.0.0.1");
        registry.add("server.port", () -> "18601");
    }

    @AfterAll
    static void stopSwitchServer() {
        SWITCH_SERVER.close();
    }

    @Test
    void webManagesLocalWorkspacesWithoutConfusingBrowsingAndCurrentSession() throws Exception {
        WebClient firstWeb = loginWeb();
        WebClient secondWeb = loginWeb();

        mvc.perform(get("/api/v1/sessions")
                        .header("Authorization", "Bearer gateway-secret")
                        .header("X-MBP-Control-Instance", "gateway-a"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WEB_SESSION_MANAGEMENT_ONLY"));

        MvcResult initial = mvc.perform(get("/api/v1/sessions").session(firstWeb.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].name").value("默认 Session"))
                .andExpect(jsonPath("$.items[0].current").value(true))
                .andReturn();
        String defaultSessionId = body(initial).get("current_session_id").toString();

        MvcResult created = mvc.perform(webWrite(post("/api/v1/sessions"), firstWeb)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"版本 A"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("版本 A"))
                .andExpect(jsonPath("$.source").value("local"))
                .andExpect(jsonPath("$.read_only").value(false))
                .andExpect(jsonPath("$.current").value(false))
                .andReturn();
        Map<String, Object> createdBody = body(created);
        String versionAId = createdBody.get("session_id").toString();
        Instant createdAt = Instant.parse(createdBody.get("created_at").toString());

        mvc.perform(webWrite(post("/api/v1/sessions"), secondWeb)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"不应创建"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONTROLLED_BY_WEB"));

        mvc.perform(get("/api/v1/sessions/{sessionId}", versionAId).session(secondWeb.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.session_id").value(versionAId))
                .andExpect(jsonPath("$.current").value(false));
        mvc.perform(get("/api/v1/sessions/current").session(firstWeb.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.session_id").value(defaultSessionId));

        Thread.sleep(2);
        MvcResult renamed = mvc.perform(webWrite(patch("/api/v1/sessions/{sessionId}", versionAId), firstWeb)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"版本 A · 已整理"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("版本 A · 已整理"))
                .andReturn();
        assertThat(Instant.parse(body(renamed).get("updated_at").toString())).isAfter(createdAt);

        mvc.perform(webWrite(post("/api/v1/sessions/{sessionId}/current", versionAId), firstWeb))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.session_id").value(versionAId))
                .andExpect(jsonPath("$.current").value(true));
        mvc.perform(webWrite(delete("/api/v1/sessions/{sessionId}", versionAId), firstWeb))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CURRENT_SESSION_DELETE_FORBIDDEN"));
        mvc.perform(webWrite(delete("/api/v1/sessions/{sessionId}", defaultSessionId), firstWeb))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(true));

        MvcResult versionB = mvc.perform(webWrite(post("/api/v1/sessions"), firstWeb)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"版本 B"}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        String versionBId = body(versionB).get("session_id").toString();

        mvc.perform(webWrite(post("/api/v1/debugging/start"), firstWeb)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"session_id":"browser-cannot-change-current"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.session_id").value(versionAId));
        mvc.perform(webWrite(post("/api/v1/sessions/{sessionId}/current", versionAId), firstWeb))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.session_id").value(versionAId))
                .andExpect(jsonPath("$.current").value(true));
        mvc.perform(webWrite(post("/api/v1/sessions/{sessionId}/current", versionBId), firstWeb))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SESSION_SWITCH_WHILE_DEBUGGING"));
        mvc.perform(get("/api/v1/sessions/current").session(firstWeb.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.session_id").value(versionAId));

        mvc.perform(webWrite(post("/api/v1/debugging/stop"), firstWeb))
                .andExpect(status().isOk());
        mvc.perform(webWrite(post("/api/v1/sessions/{sessionId}/current", versionBId), firstWeb))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.session_id").value(versionBId));

        MvcResult versionC = mvc.perform(webWrite(post("/api/v1/sessions"), firstWeb)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"版本 C"}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        String versionCId = body(versionC).get("session_id").toString();

        verifyStartAndSwitchAreSerialized(firstWeb, versionBId, versionCId);
        verifySessionListSnapshotIsConsistent(firstWeb, versionCId);
    }

    private void verifyStartAndSwitchAreSerialized(
            WebClient web,
            String currentSessionId,
            String nextSessionId) throws Exception {
        CountDownLatch currentReadEntered = new CountDownLatch(1);
        CountDownLatch allowCurrentRead = new CountDownLatch(1);
        AtomicBoolean blockOnce = new AtomicBoolean(true);
        doAnswer(invocation -> {
            if (blockOnce.compareAndSet(true, false)) {
                currentReadEntered.countDown();
                assertThat(allowCurrentRead.await(5, TimeUnit.SECONDS)).isTrue();
            }
            return invocation.callRealMethod();
        }).when(sessions).current();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch switchFinished = new CountDownLatch(1);
        try {
            Future<MvcResult> start = executor.submit(() -> mvc.perform(webWrite(
                            post("/api/v1/debugging/start"), web))
                    .andReturn());
            assertThat(currentReadEntered.await(5, TimeUnit.SECONDS)).isTrue();

            Future<MvcResult> select = executor.submit(() -> {
                try {
                    return mvc.perform(webWrite(
                                    post("/api/v1/sessions/{sessionId}/current", nextSessionId), web))
                            .andReturn();
                } finally {
                    switchFinished.countDown();
                }
            });

            boolean switchCompletedBeforeStartReadReturned = switchFinished.await(300, TimeUnit.MILLISECONDS);
            allowCurrentRead.countDown();

            assertThat(start.get(5, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
            assertThat(body(start.get()).get("session_id")).isEqualTo(currentSessionId);
            assertThat(switchCompletedBeforeStartReadReturned).isFalse();
            assertThat(select.get(5, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(409);
            assertThat(body(select.get()).get("code")).isEqualTo("SESSION_SWITCH_WHILE_DEBUGGING");
        } finally {
            allowCurrentRead.countDown();
            executor.shutdownNow();
        }

        mvc.perform(webWrite(post("/api/v1/debugging/stop"), web))
                .andExpect(status().isOk());
    }

    private void verifySessionListSnapshotIsConsistent(WebClient web, String nextSessionId) throws Exception {
        CountDownLatch currentReadEntered = new CountDownLatch(1);
        CountDownLatch allowCurrentRead = new CountDownLatch(1);
        AtomicBoolean blockOnce = new AtomicBoolean(true);
        doAnswer(invocation -> {
            if (blockOnce.compareAndSet(true, false)) {
                currentReadEntered.countDown();
                assertThat(allowCurrentRead.await(5, TimeUnit.SECONDS)).isTrue();
            }
            return invocation.callRealMethod();
        }).when(sessions).current();

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<MvcResult> listed = executor.submit(() -> mvc.perform(
                            get("/api/v1/sessions").session(web.session()))
                    .andReturn());
            currentReadEntered.await(300, TimeUnit.MILLISECONDS);

            mvc.perform(webWrite(post("/api/v1/sessions/{sessionId}/current", nextSessionId), web))
                    .andExpect(status().isOk());
            allowCurrentRead.countDown();

            MvcResult result = listed.get(5, TimeUnit.SECONDS);
            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            Map<String, Object> response = body(result);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) response.get("items");
            List<String> currentIds = items.stream()
                    .filter(item -> Boolean.TRUE.equals(item.get("current")))
                    .map(item -> item.get("session_id").toString())
                    .toList();
            assertThat(currentIds).containsExactly(response.get("current_session_id").toString());
        } finally {
            allowCurrentRead.countDown();
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
        Map<String, Object> body = body(sessionResult);
        return new WebClient(
                session,
                body.get("csrf_token").toString(),
                sessionResult.getResponse().getCookie("MBP-XSRF-TOKEN"));
    }

    private Map<String, Object> body(MvcResult result) throws Exception {
        return objectMapper.readValue(result.getResponse().getContentAsByteArray(), new TypeReference<>() {
        });
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
            return Files.createTempDirectory("breakhub-sessions-test-");
        } catch (Exception error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    private record WebClient(MockHttpSession session, String csrfToken, Cookie csrfCookie) {
    }
}
