package com.ateagents.breakhub.domain;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.ateagents.breakhub.api.ProductException;
import com.ateagents.breakhub.config.ProductProperties;
import com.ateagents.breakhub.domain.BreakpointService.BreakpointRecord;
import com.ateagents.breakhub.domain.DebugControlService.ActiveDebuggingSession;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

@Service
public class InteractionObservationService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final DebugControlService control;
    private final CurrentSessionService sessions;
    private final BreakpointService breakpoints;
    private final PauseService pauses;
    private final InteractionFieldSchema fieldSchemas;
    private final long maxPayloadBytes;
    private final TransactionTemplate transactions;

    public InteractionObservationService(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            DebugControlService control,
            CurrentSessionService sessions,
            BreakpointService breakpoints,
            PauseService pauses,
            InteractionFieldSchema fieldSchemas,
            ProductProperties properties,
            PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.control = control;
        this.sessions = sessions;
        this.breakpoints = breakpoints;
        this.pauses = pauses;
        this.fieldSchemas = fieldSchemas;
        this.maxPayloadBytes = properties.interaction().maxPayloadSize().toBytes();
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public synchronized Map<String, Object> before(
            String interactionId,
            String object,
            String command,
            JsonNode params) {
        Map<String, Object> replay = transactions.execute(
                status -> replayBeforeInTransaction(interactionId, object, command, params));
        if (replay != null) {
            return replay;
        }
        return control.performWhileDebugging(active -> transactions.execute(
                status -> createBeforeInTransaction(active, interactionId, object, command, params)))
                .orElseGet(() -> beforeResult(
                        interactionId, "skipped", false, false, "debugging_inactive"));
    }

    private Map<String, Object> replayBeforeInTransaction(
            String interactionId,
            String object,
            String command,
            JsonNode params) {
        Optional<InteractionRecord> existing = find(interactionId);
        if (existing.isPresent()) {
            validatePayload(params);
            InteractionRecord interaction = existing.get();
            if (!interaction.object().equals(object)
                    || !interaction.command().equals(command)
                    || !json(interaction.paramsJson()).equals(params)) {
                throw reportConflict(interactionId, "before 的目标或 params 与首次上报不一致");
            }
            return beforeResult(interactionId, "replayed", true, pauses.isPaused(interactionId), null);
        }
        return null;
    }

    private Map<String, Object> createBeforeInTransaction(
            ActiveDebuggingSession active,
            String interactionId,
            String object,
            String command,
            JsonNode params) {
        validatePayload(params);
        String fieldSchemaJson = text(fieldSchemas.describe(params));
        boolean schemaChanged = latestSchema(active.sessionId(), object, command)
                .map(previous -> !json(previous).equals(json(fieldSchemaJson)))
                .orElse(false);
        Instant observedAt = Instant.now();
        String now = observedAt.toString();
        ArrayNode breakpointSnapshots = breakpoints.matchingSnapshots(
                active.sessionId(), object, command, "before", params, null, observedAt);
        jdbc.update("""
                INSERT INTO product_interaction(
                    interaction_id, session_id, object_name, command_name,
                    params_json, field_schema_json, schema_changed, lifecycle,
                    before_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'running', ?, ?)
                """,
                interactionId,
                active.sessionId(),
                object,
                command,
                text(params),
                fieldSchemaJson,
                schemaChanged ? 1 : 0,
                now,
                now);
        boolean waitRequired = !breakpointSnapshots.isEmpty();
        if (waitRequired) {
            pauses.create(active.sessionId(), interactionId, "before", breakpointSnapshots, params, observedAt);
        }
        return beforeResult(interactionId, "created", true, waitRequired, null);
    }

    public synchronized Map<String, Object> after(String interactionId, JsonNode result) {
        return control.performWithDebuggingState(active -> transactions.execute(
                status -> afterInTransaction(interactionId, result, active)));
    }

    private Map<String, Object> afterInTransaction(
            String interactionId,
            JsonNode result,
            Optional<ActiveDebuggingSession> active) {
        Optional<InteractionRecord> found = find(interactionId);
        if (found.isEmpty()) {
            return skipped(interactionId, "interaction_not_tracked");
        }
        validatePayload(result);
        InteractionRecord interaction = found.get();
        if (interaction.resultJson() != null) {
            if (!json(interaction.resultJson()).equals(result)) {
                throw reportConflict(interactionId, "after 的 result 与首次上报不一致");
            }
            return afterResult(interactionId, "replayed", pauses.isPaused(interactionId, "after"));
        }

        Instant observedAt = Instant.now();
        String now = observedAt.toString();
        ArrayNode breakpointSnapshots = active
                .filter(value -> value.sessionId().equals(interaction.sessionId()))
                .map(value -> breakpoints.matchingSnapshots(
                        interaction.sessionId(),
                        interaction.object(),
                        interaction.command(),
                        "after",
                        json(interaction.paramsJson()),
                        result,
                        observedAt))
                .orElseGet(objectMapper::createArrayNode);
        jdbc.update("""
                UPDATE product_interaction
                SET result_json = ?, lifecycle = 'completed', after_at = ?, updated_at = ?
                WHERE interaction_id = ?
                """, text(result), now, now, interactionId);
        boolean waitRequired = !breakpointSnapshots.isEmpty();
        if (waitRequired) {
            pauses.create(interaction.sessionId(), interactionId, "after", breakpointSnapshots, result, observedAt);
        }
        return afterResult(interactionId, "completed", waitRequired);
    }

    public Map<String, Object> waitForRelease(String interactionId, String pausePoint) {
        if (find(interactionId).isEmpty()) {
            return skipped(interactionId, "interaction_not_tracked");
        }
        return pauses.waitForRelease(interactionId, pausePoint);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> interfaces(String view) {
        if (!("all".equals(view) || "current".equals(view))) {
            throw new ProductException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_INTERFACE_VIEW",
                    "Interface 视图只能是 all 或 current");
        }
        String sessionId = sessions.current().sessionId();
        List<InteractionRecord> interactions = findBySession(sessionId).stream()
                .sorted(Comparator.comparing(
                        (InteractionRecord value) -> Instant.parse(value.beforeAt())).reversed()
                        .thenComparing(InteractionRecord::interactionId))
                .toList();
        Map<InterfaceKey, Integer> counts = new HashMap<>();
        Map<InterfaceKey, InteractionRecord> latest = new LinkedHashMap<>();
        for (InteractionRecord interaction : interactions) {
            InterfaceKey key = new InterfaceKey(interaction.object(), interaction.command());
            counts.merge(key, 1, Integer::sum);
            latest.putIfAbsent(key, interaction);
        }
        Map<InterfaceKey, List<BreakpointRecord>> interfaceBreakpoints = new LinkedHashMap<>();
        for (BreakpointRecord breakpoint : breakpoints.findBySession(sessionId)) {
            InterfaceKey key = new InterfaceKey(breakpoint.object(), breakpoint.command());
            interfaceBreakpoints.computeIfAbsent(key, ignored -> new ArrayList<>()).add(breakpoint);
            if (!latest.containsKey(key)) {
                latest.put(key, null);
            }
        }

        Optional<ActiveDebuggingSession> active = control.activeDebuggingSession()
                .filter(value -> value.sessionId().equals(sessionId));
        List<InterfaceSummary> summaries = latest.entrySet().stream()
                .map(entry -> summary(
                        sessionId,
                        entry.getKey(),
                        entry.getValue(),
                        counts.getOrDefault(entry.getKey(), 0),
                        interfaceBreakpoints.getOrDefault(entry.getKey(), List.of()),
                        active))
                .filter(summary -> "all".equals(view) || summary.currentRelated())
                .sorted(Comparator
                        .comparing(InterfaceSummary::currentRelated).reversed()
                        .thenComparing(
                                InterfaceSummary::lastSeenAt,
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(InterfaceSummary::object)
                        .thenComparing(InterfaceSummary::command))
                .toList();
        return Map.of(
                "current_session_id", sessionId,
                "view", view,
                "items", summaries.stream().map(InterfaceSummary::body).toList());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> interfaceDetail(String object, String command) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) interfaces("all").get("items");
        return items.stream()
                .filter(item -> object.equals(item.get("object")) && command.equals(item.get("command")))
                .findFirst()
                .orElseThrow(() -> new ProductException(
                        HttpStatus.NOT_FOUND,
                        "INTERFACE_NOT_FOUND",
                        "Current Session 中没有观察到该 Interface"));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> interactions() {
        String sessionId = sessions.current().sessionId();
        List<Map<String, Object>> items = findBySession(sessionId).stream()
                .sorted(Comparator
                        .comparing((InteractionRecord value) -> pauses.isPaused(value.interactionId())).reversed()
                        .thenComparing(InteractionRecord::updatedAt, Comparator.reverseOrder())
                        .thenComparing(InteractionRecord::interactionId))
                .map(this::interactionBody)
                .toList();
        return Map.of(
                "current_session_id", sessionId,
                "items", items);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> interaction(String interactionId) {
        String sessionId = sessions.current().sessionId();
        return find(interactionId)
                .filter(value -> value.sessionId().equals(sessionId))
                .map(this::interactionBody)
                .orElseThrow(() -> new ProductException(
                        HttpStatus.NOT_FOUND,
                        "INTERACTION_NOT_FOUND",
                        "Current Session 中不存在该 Interaction"));
    }

    private InterfaceSummary summary(
            String sessionId,
            InterfaceKey key,
            InteractionRecord latest,
            int count,
            List<BreakpointRecord> relatedBreakpoints,
            Optional<ActiveDebuggingSession> active) {
        boolean currentlyObserved = latest != null && active
                .map(value -> !Instant.parse(latest.beforeAt()).isBefore(value.startedAt()))
                .orElse(false);
        long enabledBreakpointCount = relatedBreakpoints.stream().filter(BreakpointRecord::enabled).count();
        boolean currentRelated = currentlyObserved
                || enabledBreakpointCount > 0
                || pauses.hasPausedTarget(sessionId, key.object(), key.command());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("object", key.object());
        body.put("command", key.command());
        body.put("current_related", currentRelated);
        body.put("field_schema", latest == null ? objectMapper.createArrayNode() : json(latest.fieldSchemaJson()));
        body.put("schema_changed", latest != null && latest.schemaChanged());
        body.put("last_seen_at", latest == null ? null : latest.beforeAt());
        body.put("sample_ref", latest == null ? null : Map.of(
                "interaction_id", latest.interactionId(),
                "content", "params"));
        body.put("interaction_count", count);
        body.put("breakpoint_count", relatedBreakpoints.size());
        body.put("enabled_breakpoint_count", enabledBreakpointCount);
        body.put("breakpoints", relatedBreakpoints.stream().map(breakpoint -> Map.of(
                "breakpoint_id", breakpoint.breakpointId(),
                "name", breakpoint.name(),
                "pause_point", breakpoint.pausePoint(),
                "enabled", breakpoint.enabled())).toList());
        return new InterfaceSummary(
                key.object(), key.command(), currentRelated, latest == null ? null : latest.beforeAt(), body);
    }

    private Map<String, Object> interactionBody(InteractionRecord interaction) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("interaction_id", interaction.interactionId());
        body.put("object", interaction.object());
        body.put("command", interaction.command());
        body.put("lifecycle", interaction.lifecycle());
        body.put("phase", interaction.resultJson() == null ? "before" : "after");
        body.put("original_params", json(interaction.paramsJson()));
        body.put("schema_changed", interaction.schemaChanged());
        body.put("before_at", interaction.beforeAt());
        List<Map<String, Object>> pauseHistory = pauses.history(interaction.interactionId());
        Optional<Map<String, Object>> currentPause = pauses.currentPause(interaction.interactionId());
        body.put("status", currentPause.isPresent() ? "paused" : interaction.lifecycle());
        currentPause.ifPresent(value -> body.put("current_pause", value));
        body.put("pauses", pauseHistory);
        if (interaction.afterAt() != null) {
            body.put("after_at", interaction.afterAt());
            body.put("result", json(interaction.resultJson()));
        }
        body.put("timeline", timeline(interaction, pauseHistory));
        Map<String, Object> payloadMetadata = new LinkedHashMap<>();
        payloadMetadata.put("params", payloadMetadata(interaction.paramsJson()));
        if (interaction.resultJson() != null) {
            payloadMetadata.put("result", payloadMetadata(interaction.resultJson()));
        }
        body.put("payload_metadata", payloadMetadata);
        return body;
    }

    private List<Map<String, Object>> timeline(
            InteractionRecord interaction,
            List<Map<String, Object>> pauseHistory) {
        List<Map<String, Object>> events = new ArrayList<>();
        events.add(timelineEvent("before_reported", "before", interaction.beforeAt(), null, null));
        for (Map<String, Object> pause : pauseHistory) {
            String pausePoint = (String) pause.get("pause_point");
            events.add(timelineEvent(
                    "pause_started",
                    pausePoint,
                    (String) pause.get("paused_at"),
                    (String) pause.get("status"),
                    null));
            if (pause.containsKey("resolved_at")) {
                events.add(timelineEvent(
                        "pause_resolved",
                        pausePoint,
                        (String) pause.get("resolved_at"),
                        (String) pause.get("status"),
                        (String) pause.get("resolution")));
            }
        }
        if (interaction.afterAt() != null) {
            events.add(timelineEvent("after_reported", "after", interaction.afterAt(), null, null));
        }
        events.sort(Comparator.comparing(event -> Instant.parse((String) event.get("at"))));
        return events;
    }

    private static Map<String, Object> timelineEvent(
            String event,
            String phase,
            String at,
            String status,
            String resolution) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("event", event);
        body.put("phase", phase);
        body.put("at", at);
        if (status != null) {
            body.put("status", status);
        }
        if (resolution != null) {
            body.put("resolution", resolution);
        }
        return body;
    }

    private static Map<String, Object> payloadMetadata(String json) {
        long bytes = json.getBytes(StandardCharsets.UTF_8).length;
        return Map.of(
                "truncated", false,
                "original_size_bytes", bytes,
                "captured_size_bytes", bytes);
    }

    private Optional<InteractionRecord> find(String interactionId) {
        return jdbc.query("""
                SELECT interaction_id, session_id, object_name, command_name,
                       params_json, field_schema_json, schema_changed, lifecycle,
                       before_at, after_at, result_json, updated_at
                FROM product_interaction
                WHERE interaction_id = ?
                """, (result, row) -> record(result), interactionId).stream().findFirst();
    }

    private List<InteractionRecord> findBySession(String sessionId) {
        return jdbc.query("""
                SELECT interaction_id, session_id, object_name, command_name,
                       params_json, field_schema_json, schema_changed, lifecycle,
                       before_at, after_at, result_json, updated_at
                FROM product_interaction
                WHERE session_id = ?
                ORDER BY updated_at DESC, interaction_id DESC
                """, (result, row) -> record(result), sessionId);
    }

    private Optional<String> latestSchema(String sessionId, String object, String command) {
        return jdbc.query("""
                SELECT field_schema_json
                FROM product_interaction
                WHERE session_id = ? AND object_name = ? AND command_name = ?
                ORDER BY before_at DESC, interaction_id DESC
                LIMIT 1
                """, (result, row) -> result.getString("field_schema_json"), sessionId, object, command)
                .stream()
                .findFirst();
    }

    private static InteractionRecord record(ResultSet result) throws SQLException {
        return new InteractionRecord(
                result.getString("interaction_id"),
                result.getString("session_id"),
                result.getString("object_name"),
                result.getString("command_name"),
                result.getString("params_json"),
                result.getString("field_schema_json"),
                result.getBoolean("schema_changed"),
                result.getString("lifecycle"),
                result.getString("before_at"),
                result.getString("after_at"),
                result.getString("result_json"),
                result.getString("updated_at"));
    }

    private void validatePayload(JsonNode payload) {
        try {
            if (objectMapper.writeValueAsBytes(payload).length > maxPayloadBytes) {
                throw new ProductException(
                        HttpStatus.PAYLOAD_TOO_LARGE,
                        "INTERACTION_PAYLOAD_TOO_LARGE",
                        "Interaction payload 超过产品配置上限");
            }
        } catch (ProductException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalStateException("无法序列化 Interaction payload", error);
        }
    }

    private JsonNode json(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (Exception error) {
            throw new IllegalStateException("持久化 JSON 无法读取", error);
        }
    }

    private String text(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalStateException("JSON 无法持久化", error);
        }
    }

    private static ProductException reportConflict(String interactionId, String detail) {
        return new ProductException(
                HttpStatus.CONFLICT,
                "INTERACTION_REPORT_CONFLICT",
                "Interaction " + interactionId + " 重复上报冲突：" + detail);
    }

    private static Map<String, Object> beforeResult(
            String interactionId,
            String operation,
            boolean tracked,
            boolean waitRequired,
            String reason) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("interaction_id", interactionId);
        result.put("operation", operation);
        result.put("tracked", tracked);
        result.put("proceed", !waitRequired);
        result.put("wait_required", waitRequired);
        if (reason != null) {
            result.put("reason", reason);
        }
        return result;
    }

    private static Map<String, Object> afterResult(
            String interactionId,
            String operation,
            boolean waitRequired) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("interaction_id", interactionId);
        result.put("operation", operation);
        result.put("tracked", true);
        result.put("proceed", !waitRequired);
        result.put("wait_required", waitRequired);
        result.put("lifecycle", "completed");
        return result;
    }

    private static Map<String, Object> skipped(String interactionId, String reason) {
        return Map.of(
                "interaction_id", interactionId,
                "operation", "skipped",
                "tracked", false,
                "proceed", true,
                "wait_required", false,
                "reason", reason);
    }

    private record InterfaceKey(String object, String command) {
    }

    private record InterfaceSummary(
            String object,
            String command,
            boolean currentRelated,
            String lastSeenAt,
            Map<String, Object> body) {
    }

    private record InteractionRecord(
            String interactionId,
            String sessionId,
            String object,
            String command,
            String paramsJson,
            String fieldSchemaJson,
            boolean schemaChanged,
            String lifecycle,
            String beforeAt,
            String afterAt,
            String resultJson,
            String updatedAt) {
    }
}
