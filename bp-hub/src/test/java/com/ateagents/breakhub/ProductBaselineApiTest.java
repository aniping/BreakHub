package com.ateagents.breakhub;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.blankOrNullString;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.jayway.jsonpath.JsonPath;

@SpringBootTest
@AutoConfigureMockMvc
class ProductBaselineApiTest {

    private static final Path DATA_DIRECTORY = createDataDirectory();

    @Autowired
    private MockMvc mvc;

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
    void gatewayReadsEquipmentAndStableDefaultCurrentSession() throws Exception {
        MvcResult overview = mvc.perform(get("/api/v1/overview")
                        .header("Authorization", "Bearer gateway-secret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.product.name").value("BreakHub"))
                .andExpect(jsonPath("$.product.version", not(blankOrNullString())))
                .andExpect(jsonPath("$.equipment.equipment_id").value("equipment-01"))
                .andExpect(jsonPath("$.equipment.display_name").value("一号装备"))
                .andExpect(jsonPath("$.current_session.name").value("默认 Session"))
                .andExpect(jsonPath("$.current_session.source").value("local"))
                .andExpect(jsonPath("$.current_session.read_only").value(false))
                .andExpect(jsonPath("$.health.status").value("healthy"))
                .andExpect(jsonPath("$.health.database").value("healthy"))
                .andExpect(jsonPath("$.debugging.reporting.status").value("idle"))
                .andReturn();

        String sessionId = JsonPath.read(overview.getResponse().getContentAsString(), "$.current_session.session_id");

        mvc.perform(get("/api/v1/sessions/current")
                        .header("Authorization", "Bearer gateway-secret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.session_id").value(sessionId))
                .andExpect(jsonPath("$.name").value("默认 Session"));

        mvc.perform(get("/api/v1/equipment")
                        .header("Authorization", "Bearer gateway-secret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.equipment_id").value("equipment-01"))
                .andExpect(jsonPath("$.id").doesNotExist());

        mvc.perform(get("/api/v1/overview")
                        .header("Authorization", "Bearer business-secret"))
                .andExpect(status().isForbidden());
    }

    @Test
    void settingsAreReadOnlyAndMaskedAndLegacyProductContractsAreGone() throws Exception {
        MvcResult settings = mvc.perform(get("/api/v1/settings")
                        .header("Authorization", "Bearer gateway-secret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configuration_source").value("file"))
                .andExpect(jsonPath("$.restart_required").value(true))
                .andExpect(jsonPath("$.server.address").value("127.0.0.1"))
                .andExpect(jsonPath("$.server.port").value(18601))
                .andExpect(jsonPath("$.equipment.debugger_switch.url")
                        .value("http://127.0.0.1:9/debugger"))
                .andExpect(jsonPath("$.equipment.debugger_switch.token").doesNotExist())
                .andExpect(jsonPath("$.equipment.debugger_switch.timeout_seconds").doesNotExist())
                .andExpect(jsonPath("$.security.web_username").value("admin"))
                .andExpect(jsonPath("$.security.web_password").value("configured"))
                .andExpect(jsonPath("$.security.gateway_token").value("configured"))
                .andExpect(jsonPath("$.security.business_client_token").value("configured"))
                .andExpect(jsonPath("$.limits.control_lease_timeout_seconds").value(1800))
                .andExpect(jsonPath("$.limits.pause_timeout_seconds").value(1500))
                .andExpect(jsonPath("$.limits.max_payload_bytes").value(16777216))
                .andExpect(jsonPath("$.health.database").value("healthy"))
                .andReturn();

        String body = settings.getResponse().getContentAsString();
        assertThat(body)
                .doesNotContain("admin-secret")
                .doesNotContain("gateway-secret")
                .doesNotContain("business-secret")
                .doesNotContain("switch-secret");

        mvc.perform(get("/api/v1/debug-cycle/current")
                        .header("Authorization", "Bearer gateway-secret"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/debug-cycles")
                        .header("Authorization", "Bearer gateway-secret"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/bp-rules")
                        .header("Authorization", "Bearer gateway-secret"))
                .andExpect(status().isNotFound());
    }

    private static Path createDataDirectory() {
        try {
            return Files.createTempDirectory("breakhub-baseline-test-");
        } catch (Exception error) {
            throw new ExceptionInInitializerError(error);
        }
    }
}
