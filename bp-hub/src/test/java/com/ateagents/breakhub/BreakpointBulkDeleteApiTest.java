package com.ateagents.breakhub;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.Cookie;

@SpringBootTest
@AutoConfigureMockMvc
class BreakpointBulkDeleteApiTest {

    private static final Path DATA_DIRECTORY = createDataDirectory();
    private static final String GATEWAY_INSTANCE_HEADER = "X-MBP-Control-Instance";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @DynamicPropertySource
    static void productProperties(DynamicPropertyRegistry registry) {
        registry.add("breakhub.data-directory", DATA_DIRECTORY::toString);
        registry.add("breakhub.equipment.id", () -> "equipment-01");
        registry.add("breakhub.equipment.display-name", () -> "一号装备");
        registry.add("breakhub.equipment.debugger-switch.url", () -> "http://127.0.0.1:9/switch");
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

    @Test
    void atomicallyDeletesOnlyCurrentSessionAndHonorsExclusiveControl() throws Exception {
        WebClient web = loginWeb();
        String firstSessionId = mvc.perform(get("/api/v1/sessions/current").session(web.session()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        firstSessionId = objectMapper.readTree(firstSessionId).path("session_id").asText();

        createBreakpoint(web, "Power", "read");
        createBreakpoint(web, "Power", "write");

        mvc.perform(delete("/api/v1/breakpoints")
                        .header("Authorization", "Bearer gateway-secret")
                        .header(GATEWAY_INSTANCE_HEADER, "gateway-a"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONTROLLED_BY_WEB"));
        mvc.perform(get("/api/v1/breakpoints").session(web.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2));

        MvcResult second = mvc.perform(webWrite(post("/api/v1/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"保留断点的 Session\"}"), web))
                .andExpect(status().isCreated())
                .andReturn();
        String secondSessionId = objectMapper.readTree(second.getResponse().getContentAsString())
                .path("session_id").asText();
        mvc.perform(webWrite(post("/api/v1/sessions/" + secondSessionId + "/current"), web))
                .andExpect(status().isOk());
        createBreakpoint(web, "Other", "read");

        mvc.perform(webWrite(post("/api/v1/sessions/" + firstSessionId + "/current"), web))
                .andExpect(status().isOk());
        mvc.perform(webWrite(delete("/api/v1/breakpoints"), web))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted_count").value(2));
        mvc.perform(get("/api/v1/breakpoints").session(web.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());

        mvc.perform(webWrite(post("/api/v1/sessions/" + secondSessionId + "/current"), web))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/breakpoints").session(web.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1));

        mvc.perform(webWrite(post("/api/v1/control/release"), web))
                .andExpect(status().isOk());
        mvc.perform(delete("/api/v1/breakpoints")
                        .header("Authorization", "Bearer gateway-secret")
                        .header(GATEWAY_INSTANCE_HEADER, "gateway-a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted_count").value(1));
        mvc.perform(get("/api/v1/breakpoints")
                        .header("Authorization", "Bearer gateway-secret")
                        .header(GATEWAY_INSTANCE_HEADER, "gateway-a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
    }

    private void createBreakpoint(WebClient web, String object, String command) throws Exception {
        mvc.perform(webWrite(post("/api/v1/breakpoints")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"object":"%s","command":"%s","pause_point":"before","conditions":[]}
                                """.formatted(object, command)), web))
                .andExpect(status().isOk());
    }

    private WebClient loginWeb() throws Exception {
        MvcResult login = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"admin-secret\"}"))
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

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder webWrite(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
            WebClient web) {
        return request.session(web.session())
                .header("X-MBP-XSRF-TOKEN", web.csrfToken())
                .cookie(web.csrfCookie());
    }

    private static Path createDataDirectory() {
        try {
            return Files.createTempDirectory("breakhub-bulk-delete-test-");
        } catch (Exception error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    private record WebClient(MockHttpSession session, String csrfToken, Cookie csrfCookie) {
    }
}
