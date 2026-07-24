package com.ateagents.breakhub;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.Cookie;

@SpringBootTest
@AutoConfigureMockMvc
class SessionArchiveApiTest {

    private static final Path DATA_DIRECTORY = createDataDirectory();
    private static final ReportingLeaseTestServer SWITCH_SERVER = ReportingLeaseTestServer.start();

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @DynamicPropertySource
    static void productProperties(DynamicPropertyRegistry registry) {
        registry.add("breakhub.data-directory", DATA_DIRECTORY::toString);
        registry.add("breakhub.equipment.id", () -> "archive-equipment");
        registry.add("breakhub.equipment.display-name", () -> "归档测试装备");
        registry.add("breakhub.equipment.debugger-switch.url", () -> SWITCH_SERVER.endpoint().toString());
        registry.add("breakhub.security.web-username", () -> "admin");
        registry.add("breakhub.security.web-password", () -> "web-archive-secret");
        registry.add("breakhub.security.gateway-token", () -> "gateway-archive-secret");
        registry.add("breakhub.security.business-client-token", () -> "business-archive-secret");
        registry.add("breakhub.control-lease.timeout", () -> "30m");
        registry.add("breakhub.interaction.pause-timeout", () -> "25m");
        registry.add("breakhub.interaction.max-payload-size", () -> "16MB");
        registry.add("server.address", () -> "127.0.0.1");
        registry.add("server.port", () -> "18608");
    }

    @AfterAll
    static void stopSwitchServer() {
        SWITCH_SERVER.close();
    }

    @Test
    void webClearsCurrentEvidenceAndRoundTripsReadOnlySessionArchiveAtomically() throws Exception {
        WebClient web = loginWeb();
        String sourceSessionId = readJson(mvc.perform(get("/api/v1/sessions/current").session(web.session()))
                .andExpect(status().isOk()).andReturn()).path("session_id").asText();

        mvc.perform(webWrite(post("/api/v1/breakpoints"), web)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name":"归档证据断点",
                                  "object":"Archive",
                                  "command":"echo",
                                  "pause_point":"before",
                                  "conditions":[
                                    {"source":"params","field_path":"message","operator":"eq","value":"original"},
                                    {"source":"params","field_path":"tags","operator":"contains_any","value":["red","green"]}
                                  ]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(true));
        mvc.perform(webWrite(post("/api/v1/breakpoints"), web)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name":"归档出参断点",
                                  "object":"Archive",
                                  "command":"future",
                                  "pause_point":"after",
                                  "conditions":[
                                    {"source":"result","field_path":"status","operator":"eq","value":"ready"}
                                  ]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(true));
        mvc.perform(webWrite(post("/api/v1/debugging/start"), web))
                .andExpect(status().isOk());
        String longFieldName = "x".repeat(501);
        ObjectNode observedParams = objectMapper.createObjectNode();
        observedParams.put("message", "original");
        observedParams.putArray("tags").add("blue").add("red");
        observedParams.put("count", 1);
        observedParams.put("a/b", 2);
        observedParams.put("items[0]", true);
        observedParams.put("0", "numeric-key");
        observedParams.putObject("").put("nested", "empty-parent-key");
        observedParams.put(longFieldName, "long-key");
        ObjectNode beforeRequest = objectMapper.createObjectNode();
        beforeRequest.put("interaction_id", "archive-interaction");
        beforeRequest.put("object", "Archive");
        beforeRequest.put("command", "echo");
        beforeRequest.set("params", observedParams);
        mvc.perform(business(post("/api/business/interactions/before"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(beforeRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.wait_required").value(true));

        mvc.perform(webWrite(post("/api/v1/sessions/current/interactions/clear"), web))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SESSION_HAS_PAUSED_INTERACTIONS"));

        ObjectNode injectionRequest = objectMapper.createObjectNode();
        injectionRequest.put("pause_point", "before");
        injectionRequest.putObject("changes")
                .put("message", "injected")
                .put(longFieldName, "injected-long-key");
        mvc.perform(webWrite(post("/api/v1/interactions/archive-interaction/inject"), web)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(injectionRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("applied"))
                .andExpect(jsonPath("$.modified.length()").value(2));
        mvc.perform(webWrite(post("/api/v1/interactions/archive-interaction/continue"), web)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pause_point\":\"before\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.continued").value(true));
        mvc.perform(business(post("/api/business/interactions/after"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"interaction_id":"archive-interaction","result":{"ok":true}}
                                """))
                .andExpect(status().isOk());

        MvcResult exported = mvc.perform(get("/api/v1/sessions/{sessionId}/export", sourceSessionId)
                        .session(web.session()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString(".mbsession")))
                .andReturn();
        JsonNode archive = readJson(exported);
        assertThat(archive.path("format").asText()).isEqualTo("breakhub-session-v1");
        assertThat(archive.path("session").path("session_id").asText()).isEqualTo(sourceSessionId);
        assertThat(archive.path("source_equipment").path("equipment_id").asText())
                .isEqualTo("archive-equipment");
        assertThat(archive.path("breakpoints")).hasSize(2);
        assertThat(archive.at("/breakpoints/0/conditions/0/source").asText()).isEqualTo("params");
        assertThat(archive.at("/breakpoints/1/conditions/0/source").asText()).isEqualTo("result");
        assertThat(archive.path("interactions")).hasSize(1);
        assertThat(archive.path("pauses")).hasSize(1);
        assertThat(archive.at("/pauses/0/breakpoint_snapshots/0/conditions/0/source").asText())
                .isEqualTo("params");
        assertThat(archive.at("/pauses/0/breakpoint_snapshots/0/condition_evidence"))
                .isEqualTo(objectMapper.readTree("""
                        [
                          {"source":"params","field_path":"message","operator":"eq","expected_value":"original","actual_value":"original"},
                          {"source":"params","field_path":"tags","operator":"contains_any","expected_value":["green","red"],"actual_value":["red"]}
                        ]
                        """));
        assertThat(archive.path("interactions").get(0).path("params").toString())
                .isEqualTo(observedParams.toString());
        assertThat(archive.path("pauses").get(0).path("injection_audit")).hasSize(1);
        String archiveText = objectMapper.writeValueAsString(archive);
        assertThat(archiveText)
                .doesNotContain("switch-archive-secret")
                .doesNotContain("web-archive-secret")
                .doesNotContain("gateway-archive-secret")
                .doesNotContain("business-archive-secret")
                .doesNotContain(DATA_DIRECTORY.toString())
                .doesNotContain("debugger_switch")
                .doesNotContain("control_lease")
                .doesNotContain("current_session")
                .doesNotContain("revision");

        ObjectNode largeContainsArchive = archive.deepCopy();
        ArrayNode largeCandidates = objectMapper.createArrayNode();
        for (int index = 0; index < 20_000; index++) {
            largeCandidates.add("value-" + String.format("%05d", index));
        }
        ((ObjectNode) largeContainsArchive.withArray("breakpoints").get(0)
                .withArray("conditions").get(1)).set("value", largeCandidates.deepCopy());
        ObjectNode largeSnapshot = (ObjectNode) largeContainsArchive.withArray("pauses").get(0)
                .withArray("breakpoint_snapshots").get(0);
        ((ObjectNode) largeSnapshot.withArray("conditions").get(1))
                .set("value", largeCandidates.deepCopy());
        ObjectNode largeEvidence = (ObjectNode) largeSnapshot.withArray("condition_evidence").get(1);
        largeEvidence.set("expected_value", largeCandidates.deepCopy());
        largeEvidence.set("actual_value", largeCandidates.deepCopy());
        byte[] largeArchiveBytes = objectMapper.writeValueAsBytes(largeContainsArchive);
        MvcResult largeImport = assertTimeoutPreemptively(Duration.ofSeconds(3), () ->
                mvc.perform(webWrite(post("/api/v1/sessions/import"), web)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(largeArchiveBytes))
                        .andExpect(status().isCreated())
                        .andReturn());
        String largeImportedSessionId = readJson(largeImport).path("session_id").asText();
        mvc.perform(webWrite(delete("/api/v1/sessions/{sessionId}", largeImportedSessionId), web))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(true));

        mvc.perform(webWrite(post("/api/v1/sessions/current/interactions/clear"), web))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cleared_interaction_count").value(1))
                .andExpect(jsonPath("$.cleared_pause_count").value(1));
        mvc.perform(get("/api/v1/interactions").session(web.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(get("/api/v1/breakpoints").session(web.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2));

        int beforeInvalidImport = readJson(mvc.perform(get("/api/v1/sessions").session(web.session()))
                .andReturn()).path("items").size();
        ObjectNode corrupted = archive.deepCopy();
        ((ObjectNode) corrupted.withArray("pauses").get(0)).put("pause_point", "middle");
        ObjectNode malformedCondition = archive.deepCopy();
        ((ObjectNode) malformedCondition.withArray("breakpoints").get(0))
                .set("conditions", objectMapper.createArrayNode().add(42));
        ObjectNode invalidConditionOperator = archive.deepCopy();
        ((ObjectNode) invalidConditionOperator.withArray("breakpoints").get(0))
                .set("conditions", objectMapper.createArrayNode().addObject()
                        .put("source", "params")
                        .put("field_path", "message")
                        .put("operator", "starts_with")
                        .put("value", "x"));
        ObjectNode malformedSnapshotCondition = archive.deepCopy();
        ((ObjectNode) malformedSnapshotCondition.withArray("pauses").get(0)
                .withArray("breakpoint_snapshots").get(0))
                .set("conditions", objectMapper.createArrayNode().add(42));
        ObjectNode invalidConditionEvidence = archive.deepCopy();
        ((ObjectNode) invalidConditionEvidence.withArray("pauses").get(0)
                .withArray("breakpoint_snapshots").get(0)
                .withArray("condition_evidence").get(1))
                .putArray("actual_value").add("blue");
        ObjectNode malformedFieldSchema = archive.deepCopy();
        ((ObjectNode) malformedFieldSchema.withArray("interactions").get(0))
                .set("field_schema", objectMapper.createArrayNode().add("broken"));
        ObjectNode invalidInjectionResult = archive.deepCopy();
        ((ObjectNode) invalidInjectionResult.withArray("pauses").get(0)
                .withArray("injection_audit").get(0)).put("result", "forged");
        ObjectNode invalidAuditPointer = archive.deepCopy();
        ((ObjectNode) invalidAuditPointer.withArray("pauses").get(0)
                .withArray("injection_audit").get(0))
                .withArray("modified").set(0, objectMapper.getNodeFactory().textNode("/bad~2escape"));
        ObjectNode sourceLessArchive = archive.deepCopy();
        ((ObjectNode) sourceLessArchive.withArray("breakpoints").get(0)
                .withArray("conditions").get(0)).remove("source");
        for (ObjectNode invalidArchive : List.of(
                corrupted,
                malformedCondition,
                invalidConditionOperator,
                malformedSnapshotCondition,
                invalidConditionEvidence,
                malformedFieldSchema,
                invalidInjectionResult,
                invalidAuditPointer,
                sourceLessArchive)) {
            mvc.perform(webWrite(post("/api/v1/sessions/import"), web)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsBytes(invalidArchive)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_SESSION_ARCHIVE"));
        }
        ObjectNode legacy = archive.deepCopy();
        legacy.put("format", "breakhub-record-v1");
        mvc.perform(webWrite(post("/api/v1/sessions/import"), web)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(legacy)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_SESSION_ARCHIVE"));
        mvc.perform(get("/api/v1/sessions").session(web.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(beforeInvalidImport));

        MvcResult imported = mvc.perform(webWrite(post("/api/v1/sessions/import"), web)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(archive)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.source").value("imported"))
                .andExpect(jsonPath("$.read_only").value(true))
                .andExpect(jsonPath("$.current").value(false))
                .andReturn();
        String importedSessionId = readJson(imported).path("session_id").asText();
        assertThat(importedSessionId).isNotEqualTo(sourceSessionId);

        MvcResult importedArchive = mvc.perform(
                        get("/api/v1/sessions/{sessionId}/archive", importedSessionId).session(web.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.format").value("breakhub-session-v1"))
                .andExpect(jsonPath("$.session.session_id").value(sourceSessionId))
                .andExpect(jsonPath("$.pauses[0].breakpoint_snapshots[0].condition_evidence[1].actual_value[0]")
                        .value("red"))
                .andExpect(jsonPath("$.pauses[0].injection_audit.length()").value(1))
                .andReturn();
        assertThat(readJson(importedArchive).at("/pauses/0/breakpoint_snapshots/0/condition_evidence"))
                .isEqualTo(archive.at("/pauses/0/breakpoint_snapshots/0/condition_evidence"));
        mvc.perform(get("/api/v1/sessions/{sessionId}/export", importedSessionId).session(web.session()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString(".mbsession")))
                .andExpect(jsonPath("$.session.session_id").value(sourceSessionId));
        mvc.perform(webWrite(patch("/api/v1/sessions/{sessionId}", importedSessionId), web)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"禁止修改\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IMPORTED_SESSION_READ_ONLY"));
        mvc.perform(webWrite(post("/api/v1/sessions/{sessionId}/current", importedSessionId), web))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IMPORTED_SESSION_READ_ONLY"));
        mvc.perform(get("/api/v1/sessions/current").session(web.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.session_id").value(sourceSessionId));
        mvc.perform(webWrite(delete("/api/v1/sessions/{sessionId}", importedSessionId), web))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(true));
    }

    private WebClient loginWeb() throws Exception {
        MvcResult login = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"web-archive-secret\"}"))
                .andExpect(status().isOk())
                .andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        MvcResult authenticated = mvc.perform(get("/api/auth/session").session(session))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = readJson(authenticated);
        return new WebClient(
                session,
                body.path("csrf_token").asText(),
                authenticated.getResponse().getCookie("MBP-XSRF-TOKEN"));
    }

    private JsonNode readJson(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder webWrite(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
            WebClient web) {
        return request.session(web.session())
                .header("X-MBP-XSRF-TOKEN", web.csrfToken())
                .cookie(web.csrfCookie());
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder business(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) {
        return request.header("Authorization", "Bearer business-archive-secret");
    }

    private static Path createDataDirectory() {
        try {
            return Files.createTempDirectory("breakhub-session-archive-test-");
        } catch (Exception error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    private record WebClient(MockHttpSession session, String csrfToken, Cookie csrfCookie) {
    }
}
