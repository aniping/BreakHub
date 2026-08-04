package com.ateagents.breakhub;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class InteractionListApiTest {

    private static final Path DATA_DIRECTORY = createDataDirectory();

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    private String sessionId;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("breakhub.data-directory", DATA_DIRECTORY::toString);
        registry.add("breakhub.equipment.id", () -> "list-test");
        registry.add("breakhub.equipment.display-name", () -> "List Test");
        registry.add("breakhub.equipment.debugger-switch.url", () -> "http://127.0.0.1:9/debugger");
        registry.add("breakhub.security.web-username", () -> "admin");
        registry.add("breakhub.security.web-password", () -> "admin-secret");
        registry.add("breakhub.security.gateway-token", () -> "gateway-secret");
        registry.add("breakhub.security.business-client-token", () -> "business-secret");
        registry.add("breakhub.control-lease.timeout", () -> "30m");
        registry.add("breakhub.interaction.pause-timeout", () -> "25m");
        registry.add("breakhub.interaction.max-payload-size", () -> "16MB");
        registry.add("server.address", () -> "127.0.0.1");
        registry.add("server.port", () -> "0");
    }

    @BeforeEach
    void resetInteractions() {
        jdbc.update("DELETE FROM product_pause");
        jdbc.update("DELETE FROM product_interaction");
        sessionId = jdbc.queryForObject("SELECT current_session_id FROM product_state", String.class);
    }

    @Test
    void pagesLightweightSummariesWhileKeepingFullDetailAvailable() throws Exception {
        insertCompleted("oldest", "Alpha", "2026-08-04T00:00:01Z");
        insertCompleted("middle", "Beta", "2026-08-04T00:00:02Z");
        insertCompleted("newest", "Gamma", "2026-08-04T00:00:03Z");

        MvcResult result = mvc.perform(get("/api/v1/interactions?page=0&size=2")
                        .header("Authorization", "Bearer gateway-secret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.total_pages").value(2))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].interaction_id").value("newest"))
                .andExpect(jsonPath("$.items[1].interaction_id").value("middle"))
                .andExpect(jsonPath("$.items[0].original_params").doesNotExist())
                .andExpect(jsonPath("$.items[0].result").doesNotExist())
                .andExpect(jsonPath("$.items[0].timeline").doesNotExist())
                .andReturn();

        assertThat(result.getResponse().getContentAsByteArray()).hasSizeLessThan(4_000);
        mvc.perform(get("/api/v1/interactions/newest")
                        .header("Authorization", "Bearer gateway-secret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.original_params.value").value("payload-newest"))
                .andExpect(jsonPath("$.result.code").value(200))
                .andExpect(jsonPath("$.timeline.length()").value(2));
    }

    @Test
    void filtersSummariesAndKeepsPauseActionsAvailable() throws Exception {
        insertInteraction("completed", "Power", "completed", "2026-08-04T00:00:01Z");
        insertInteraction("running", "Sensor", "running", "2026-08-04T00:00:02Z");
        insertInteraction("paused", "Power", "completed", "2026-08-04T00:00:03Z");
        insertPaused("paused");

        mvc.perform(get("/api/v1/interactions?status=paused&pause_point=before&query=PAUSED")
                        .header("Authorization", "Bearer gateway-secret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.session_total").value(3))
                .andExpect(jsonPath("$.paused_total").value(1))
                .andExpect(jsonPath("$.items[0].interaction_id").value("paused"))
                .andExpect(jsonPath("$.items[0].status").value("paused"))
                .andExpect(jsonPath("$.items[0].hit_count").value(2))
                .andExpect(jsonPath("$.items[0].injection_count").value(2))
                .andExpect(jsonPath("$.items[0].current_pause.breakpoint_snapshots.length()").value(2))
                .andExpect(jsonPath("$.items[0].current_pause.original_content").doesNotExist())
                .andExpect(jsonPath("$.items[0].current_pause.effective_content").doesNotExist());

        mvc.perform(get("/api/v1/interactions?status=in_progress&object=Sensor")
                        .header("Authorization", "Bearer gateway-secret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].interaction_id").value("running"));
        mvc.perform(get("/api/v1/interactions?object=Sensor&command=missing")
                        .header("Authorization", "Bearer gateway-secret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
        mvc.perform(get("/api/v1/interactions?size=101")
                        .header("Authorization", "Bearer gateway-secret"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INTERACTION_FILTER"));
    }

    @Test
    void boundsTheDefaultPageAndResponseWhenHistoryPayloadsAreLarge() throws Exception {
        insertLargeHistory(10_000, "x".repeat(1_000));
        mvc.perform(get("/api/v1/interactions").header("Authorization", "Bearer gateway-secret"))
                .andExpect(status().isOk());

        long started = System.nanoTime();
        MvcResult result = mvc.perform(get("/api/v1/interactions")
                        .header("Authorization", "Bearer gateway-secret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(10_000))
                .andExpect(jsonPath("$.items.length()").value(100))
                .andReturn();

        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertThat(elapsedMs).isLessThan(1_000);
        assertThat(result.getResponse().getContentAsByteArray()).hasSizeLessThan(100_000);
    }

    private void insertCompleted(String id, String object, String at) {
        insertInteraction(id, object, "completed", at);
    }

    private void insertInteraction(String id, String object, String lifecycle, String at) {
        String result = "completed".equals(lifecycle) ? "{\"code\":200}" : null;
        jdbc.update("""
                INSERT INTO product_interaction(
                    interaction_id, session_id, object_name, command_name,
                    params_json, field_schema_json, schema_changed, lifecycle,
                    before_at, after_at, result_json, updated_at)
                VALUES (?, ?, ?, 'run', ?, '[]', 0, ?, ?, ?, ?, ?)
                """, id, sessionId, object, "{\"value\":\"payload-" + id + "\"}", lifecycle,
                at, result == null ? null : at, result, at);
    }

    private void insertPaused(String interactionId) {
        jdbc.update("""
                INSERT INTO product_pause(
                    interaction_id, pause_point, session_id, status,
                    breakpoint_snapshots_json, effective_content_json,
                    injection_audit_json, injection_status, paused_at)
                VALUES (?, 'before', ?, 'paused', ?, '{}', '[{},{}]', 'none', '2026-08-04T00:00:04Z')
                """, interactionId, sessionId, "[{\"name\":\"规则一\"},{\"name\":\"规则二\"}]");
    }

    private void insertLargeHistory(int count, String payload) {
        String sql = """
                INSERT INTO product_interaction(
                    interaction_id, session_id, object_name, command_name,
                    params_json, field_schema_json, schema_changed, lifecycle,
                    before_at, after_at, result_json, updated_at)
                VALUES (?, ?, 'Load', 'run', ?, '[]', 0, 'completed', ?, ?, ?, ?)
                """;
        List<Object[]> rows = new ArrayList<>(count);
        String params = "{\"value\":\"" + payload + "\"}";
        String result = "{\"code\":200,\"payload\":\"" + payload + "\"}";
        for (int index = 0; index < count; index++) {
            String id = "large-%05d".formatted(index);
            String at = "2026-08-04T%02d:%02d:%02dZ".formatted(
                    (index / 3_600) % 24, (index / 60) % 60, index % 60);
            rows.add(new Object[] {id, sessionId, params, at, at, result, at});
        }
        jdbc.batchUpdate(sql, rows);
    }

    private static Path createDataDirectory() {
        try {
            return Files.createTempDirectory("breakhub-list-api-");
        } catch (Exception error) {
            throw new ExceptionInInitializerError(error);
        }
    }
}
