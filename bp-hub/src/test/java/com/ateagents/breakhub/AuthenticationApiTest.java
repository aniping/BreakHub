package com.ateagents.breakhub;

import static org.assertj.core.api.Assertions.assertThat;
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
class AuthenticationApiTest {

    private static final Path DATA_DIRECTORY = createDataDirectory();

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @DynamicPropertySource
    static void productProperties(DynamicPropertyRegistry registry) {
        registry.add("breakhub.data-directory", DATA_DIRECTORY::toString);
        registry.add("breakhub.equipment.id", () -> "equipment-01");
        registry.add("breakhub.equipment.display-name", () -> "一号装备");
        registry.add("breakhub.equipment.debugger-switch.url", () -> "http://127.0.0.1:9/debugger");
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
    void adminAndGatewayReadProductButBusinessClientCannot() throws Exception {
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"admin","password":"wrong"}
                                """))
                .andExpect(status().isUnauthorized());

        MvcResult login = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"admin","password":"admin-secret"}
                                """))
                .andExpect(status().isOk())
                .andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);

        mvc.perform(get("/api/v1/overview").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.equipment.equipment_id").value("equipment-01"));

        MvcResult sessionResult = mvc.perform(get("/api/auth/session").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.csrf_token").isString())
                .andReturn();
        Map<String, Object> sessionBody = objectMapper.readValue(
                sessionResult.getResponse().getContentAsByteArray(), new TypeReference<>() {
                });
        Cookie csrfCookie = sessionResult.getResponse().getCookie("MBP-XSRF-TOKEN");

        MvcResult gatewayRead = mvc.perform(get("/api/v1/overview")
                        .header("Authorization", "Bearer gateway-secret"))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(gatewayRead.getRequest().getSession(false)).isNull();

        mvc.perform(get("/api/v1/overview")
                        .header("Authorization", "Bearer business-secret"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/overview"))
                .andExpect(status().isUnauthorized());

        mvc.perform(post("/api/auth/logout")
                        .session(session)
                        .cookie(csrfCookie)
                        .header("X-MBP-XSRF-TOKEN", sessionBody.get("csrf_token")))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/auth/session").session(session))
                .andExpect(status().isUnauthorized());
    }

    private static Path createDataDirectory() {
        try {
            return Files.createTempDirectory("breakhub-auth-test-");
        } catch (Exception error) {
            throw new ExceptionInInitializerError(error);
        }
    }
}
