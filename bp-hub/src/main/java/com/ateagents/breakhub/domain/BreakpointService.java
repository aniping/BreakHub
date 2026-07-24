package com.ateagents.breakhub.domain;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ateagents.breakhub.api.ProductException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

@Service
public class BreakpointService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final CurrentSessionService sessions;
    private final BreakpointConditionEngine conditionEngine;

    public BreakpointService(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            CurrentSessionService sessions,
            BreakpointConditionEngine conditionEngine) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.sessions = sessions;
        this.conditionEngine = conditionEngine;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> list() {
        String sessionId = sessions.current().sessionId();
        return Map.of(
                "current_session_id", sessionId,
                "items", findBySession(sessionId).stream().map(this::body).toList());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(String breakpointId) {
        return body(requireCurrent(breakpointId));
    }

    @Transactional
    public Map<String, Object> create(BreakpointDefinition requested) {
        String sessionId = sessions.current().sessionId();
        NormalizedWrite normalized = normalizeWrite(requested);
        BreakpointDefinition definition = normalized.definition();
        Optional<BreakpointRecord> equivalent = findBySession(sessionId).stream()
                .filter(existing -> sameDefinition(existing, definition))
                .findFirst();
        if (equivalent.isPresent()) {
            return createResult(equivalent.get(), false, normalized.discardedConditions());
        }
        String breakpointId = UUID.randomUUID().toString();
        String now = Instant.now().toString();
        jdbc.update("""
                INSERT INTO product_breakpoint(
                    breakpoint_id, session_id, name, object_name, command_name,
                    pause_point, enabled, conditions_json, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?, ?)
                """,
                breakpointId,
                sessionId,
                definition.name(),
                definition.object(),
                definition.command(),
                definition.pausePoint(),
                text(definition.conditions()),
                now,
                now);
        return createResult(requireCurrent(breakpointId), true, normalized.discardedConditions());
    }

    @Transactional
    public Map<String, Object> update(String breakpointId, BreakpointPatch patch) {
        BreakpointRecord existing = requireCurrent(breakpointId);
        NormalizedWrite normalized = normalizeWrite(new BreakpointDefinition(
                patch.namePresent() ? patch.name() : existing.name(),
                patch.object() == null ? existing.object() : patch.object(),
                patch.command() == null ? existing.command() : patch.command(),
                patch.pausePoint() == null ? existing.pausePoint() : patch.pausePoint(),
                patch.conditions() == null ? json(existing.conditionsJson()) : patch.conditions()));
        BreakpointDefinition definition = normalized.definition();
        jdbc.update("""
                UPDATE product_breakpoint
                SET name = ?, object_name = ?, command_name = ?, pause_point = ?,
                    conditions_json = ?, updated_at = ?
                WHERE breakpoint_id = ?
                """,
                definition.name(),
                definition.object(),
                definition.command(),
                definition.pausePoint(),
                text(definition.conditions()),
                Instant.now().toString(),
                breakpointId);
        return writeResult(requireCurrent(breakpointId), normalized.discardedConditions());
    }

    @Transactional
    public Map<String, Object> setEnabled(String breakpointId, boolean enabled) {
        BreakpointRecord existing = requireCurrent(breakpointId);
        boolean changed = existing.enabled() != enabled;
        if (changed) {
            jdbc.update("""
                    UPDATE product_breakpoint
                    SET enabled = ?, updated_at = ?
                    WHERE breakpoint_id = ?
                    """, enabled ? 1 : 0, Instant.now().toString(), breakpointId);
        }
        Map<String, Object> result = new LinkedHashMap<>(body(requireCurrent(breakpointId)));
        result.put("changed", changed);
        return result;
    }

    @Transactional
    public Map<String, Object> delete(String breakpointId) {
        String sessionId = sessions.current().sessionId();
        int deleted = jdbc.update(
                "DELETE FROM product_breakpoint WHERE breakpoint_id = ? AND session_id = ?",
                breakpointId,
                sessionId);
        return Map.of(
                "breakpoint_id", breakpointId,
                "deleted", deleted == 1,
                "result", deleted == 1 ? "deleted" : "already_absent");
    }

    @Transactional
    public Map<String, Object> deleteAll() {
        String sessionId = sessions.current().sessionId();
        int deleted = jdbc.update(
                "DELETE FROM product_breakpoint WHERE session_id = ?",
                sessionId);
        return Map.of("deleted_count", deleted);
    }

    @Transactional
    public ArrayNode matchingSnapshots(
            String sessionId,
            String object,
            String command,
            String pausePoint,
            JsonNode params,
            JsonNode result,
            Instant matchedAt) {
        ArrayNode snapshots = objectMapper.createArrayNode();
        for (BreakpointRecord breakpoint : findMatching(sessionId, object, command, pausePoint)) {
            var evidence = conditionEngine.matchEvidence(
                    json(breakpoint.conditionsJson()), params, result);
            if (evidence.isEmpty()) {
                continue;
            }
            ObjectNode snapshot = snapshots.addObject();
            snapshot.put("breakpoint_id", breakpoint.breakpointId());
            snapshot.put("name", breakpoint.name());
            snapshot.put("object", breakpoint.object());
            snapshot.put("command", breakpoint.command());
            snapshot.put("pause_point", breakpoint.pausePoint());
            snapshot.put("enabled", breakpoint.enabled());
            snapshot.set("conditions", json(breakpoint.conditionsJson()));
            snapshot.set("condition_evidence", evidence.orElseThrow());
            snapshot.put("matched_at", matchedAt.toString());
            jdbc.update("""
                    UPDATE product_breakpoint
                    SET hit_count = hit_count + 1, last_hit_at = ?
                    WHERE breakpoint_id = ?
                    """, matchedAt.toString(), breakpoint.breakpointId());
        }
        return snapshots;
    }

    @Transactional(readOnly = true)
    List<BreakpointRecord> findBySession(String sessionId) {
        return jdbc.query("""
                SELECT breakpoint_id, session_id, name, object_name, command_name,
                       pause_point, enabled, conditions_json, hit_count, last_hit_at,
                       created_at, updated_at
                FROM product_breakpoint
                WHERE session_id = ?
                ORDER BY updated_at DESC, breakpoint_id
                """, (result, row) -> record(result), sessionId);
    }

    private List<BreakpointRecord> findMatching(
            String sessionId,
            String object,
            String command,
            String pausePoint) {
        return jdbc.query("""
                SELECT breakpoint_id, session_id, name, object_name, command_name,
                       pause_point, enabled, conditions_json, hit_count, last_hit_at,
                       created_at, updated_at
                FROM product_breakpoint
                WHERE session_id = ? AND object_name = ? AND command_name = ?
                  AND pause_point = ? AND enabled = 1
                ORDER BY created_at, breakpoint_id
                """, (result, row) -> record(result), sessionId, object, command, pausePoint);
    }

    private BreakpointRecord requireCurrent(String breakpointId) {
        String sessionId = sessions.current().sessionId();
        return jdbc.query("""
                SELECT breakpoint_id, session_id, name, object_name, command_name,
                       pause_point, enabled, conditions_json, hit_count, last_hit_at,
                       created_at, updated_at
                FROM product_breakpoint
                WHERE breakpoint_id = ? AND session_id = ?
                """, (result, row) -> record(result), breakpointId, sessionId)
                .stream()
                .findFirst()
                .orElseThrow(() -> new ProductException(
                        HttpStatus.NOT_FOUND,
                        "BREAKPOINT_NOT_FOUND",
                        "Current Session 中不存在该 Breakpoint"));
    }

    private NormalizedWrite normalizeWrite(BreakpointDefinition requested) {
        String object = required(requested.object(), "object", 200);
        String command = required(requested.command(), "command", 200);
        String pausePoint = required(requested.pausePoint(), "pause_point", 20);
        if (!("before".equals(pausePoint) || "after".equals(pausePoint))) {
            throw new ProductException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_BREAKPOINT_PAUSE_POINT",
                    "pause_point 只能是 before 或 after");
        }
        ArrayNode normalizedConditions = conditionEngine.normalize(requested.conditions());
        ArrayNode conditions = objectMapper.createArrayNode();
        ArrayNode discardedConditions = objectMapper.createArrayNode();
        normalizedConditions.forEach(condition -> {
            ArrayNode target = "before".equals(pausePoint)
                    && "result".equals(condition.path("source").asText())
                    ? discardedConditions
                    : conditions;
            target.add(condition.deepCopy());
        });
        String name = requested.name() == null ? "" : requested.name().trim();
        if (name.length() > 200) {
            throw new ProductException(HttpStatus.BAD_REQUEST, "INVALID_BREAKPOINT_NAME", "Breakpoint 名称不能超过 200 个字符");
        }
        if (name.isEmpty()) {
            String summary = conditions.isEmpty() ? "接口断点" : conditions.size() + " 个条件";
            name = object + "." + command + " · " + pausePoint + " · " + summary;
        }
        return new NormalizedWrite(
                new BreakpointDefinition(name, object, command, pausePoint, conditions),
                discardedConditions);
    }

    private boolean sameDefinition(BreakpointRecord existing, BreakpointDefinition requested) {
        return existing.name().equals(requested.name())
                && existing.object().equals(requested.object())
                && existing.command().equals(requested.command())
                && existing.pausePoint().equals(requested.pausePoint())
                && conditionEngine.equivalent(json(existing.conditionsJson()), requested.conditions());
    }

    private Map<String, Object> body(BreakpointRecord breakpoint) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("breakpoint_id", breakpoint.breakpointId());
        body.put("name", breakpoint.name());
        body.put("object", breakpoint.object());
        body.put("command", breakpoint.command());
        body.put("pause_point", breakpoint.pausePoint());
        body.put("enabled", breakpoint.enabled());
        body.put("conditions", json(breakpoint.conditionsJson()));
        body.put("hit_count", breakpoint.hitCount());
        body.put("last_hit_at", breakpoint.lastHitAt());
        body.put("created_at", breakpoint.createdAt());
        body.put("updated_at", breakpoint.updatedAt());
        return body;
    }

    private Map<String, Object> createResult(
            BreakpointRecord breakpoint,
            boolean created,
            ArrayNode discardedConditions) {
        Map<String, Object> result = new LinkedHashMap<>(writeResult(breakpoint, discardedConditions));
        result.put("created", created);
        return result;
    }

    private Map<String, Object> writeResult(BreakpointRecord breakpoint, ArrayNode discardedConditions) {
        Map<String, Object> result = new LinkedHashMap<>(body(breakpoint));
        result.put("discarded_conditions", discardedConditions.deepCopy());
        return result;
    }

    private JsonNode json(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (Exception error) {
            throw new IllegalStateException("持久化 Breakpoint JSON 无法读取", error);
        }
    }

    private String text(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalStateException("Breakpoint JSON 无法持久化", error);
        }
    }

    private static BreakpointRecord record(ResultSet result) throws SQLException {
        return new BreakpointRecord(
                result.getString("breakpoint_id"),
                result.getString("session_id"),
                result.getString("name"),
                result.getString("object_name"),
                result.getString("command_name"),
                result.getString("pause_point"),
                result.getBoolean("enabled"),
                result.getString("conditions_json"),
                result.getLong("hit_count"),
                result.getString("last_hit_at"),
                result.getString("created_at"),
                result.getString("updated_at"));
    }

    private static String required(String value, String field, int maxLength) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.length() > maxLength) {
            throw new ProductException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_BREAKPOINT_DEFINITION",
                    field + " 必须为 1 到 " + maxLength + " 个字符");
        }
        return normalized;
    }

    public record BreakpointDefinition(
            String name,
            String object,
            String command,
            String pausePoint,
            JsonNode conditions) {
    }

    private record NormalizedWrite(
            BreakpointDefinition definition,
            ArrayNode discardedConditions) {
    }

    public record BreakpointPatch(
            boolean namePresent,
            String name,
            String object,
            String command,
            String pausePoint,
            JsonNode conditions) {
    }

    record BreakpointRecord(
            String breakpointId,
            String sessionId,
            String name,
            String object,
            String command,
            String pausePoint,
            boolean enabled,
            String conditionsJson,
            long hitCount,
            String lastHitAt,
            String createdAt,
            String updatedAt) {
    }
}
