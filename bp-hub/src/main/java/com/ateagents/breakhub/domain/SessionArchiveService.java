package com.ateagents.breakhub.domain;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ateagents.breakhub.api.ProductException;
import com.ateagents.breakhub.config.ProductProperties;
import com.ateagents.breakhub.domain.CurrentSessionService.SessionWorkspace;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

@Service
public class SessionArchiveService {

    public static final String FORMAT = "breakhub-session-v1";

    private static final Set<String> ROOT_FIELDS = Set.of(
            "format", "exported_at", "session", "source_equipment",
            "breakpoints", "interactions", "pauses");
    private static final Set<String> SESSION_FIELDS = Set.of(
            "session_id", "name", "created_at", "updated_at");
    private static final Set<String> EQUIPMENT_FIELDS = Set.of("equipment_id", "display_name");
    private static final Set<String> BREAKPOINT_FIELDS = Set.of(
            "breakpoint_id", "name", "object", "command", "pause_point", "enabled",
            "conditions", "hit_count", "last_hit_at", "created_at", "updated_at");
    private static final Set<String> CONDITION_FIELDS = Set.of("source", "field_path", "operator", "value");
    private static final Set<String> INTERACTION_FIELDS = Set.of(
            "interaction_id", "object", "command", "params", "field_schema", "schema_changed",
            "lifecycle", "before_at", "after_at", "result", "updated_at");
    private static final Set<String> PAUSE_FIELDS = Set.of(
            "interaction_id", "pause_point", "status", "breakpoint_snapshots", "content_kind",
            "original_content", "effective_content", "injection_audit", "injection_status",
            "paused_at", "resolved_at", "resolution", "released_content");
    private static final Set<String> SNAPSHOT_FIELDS = Set.of(
            "breakpoint_id", "name", "object", "command", "pause_point", "enabled",
            "conditions", "condition_evidence", "matched_at");
    private static final Set<String> CONDITION_EVIDENCE_FIELDS = Set.of(
            "source", "field_path", "operator", "expected_value", "actual_value");
    private static final Set<String> INJECTION_FIELDS = Set.of(
            "injected_at", "result", "changes", "modified", "unchanged", "skipped",
            "effective_changed");
    private static final Set<String> SKIPPED_FIELDS = Set.of(
            "missing", "type_mismatch", "original_null");

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final CurrentSessionService sessions;
    private final ProductProperties properties;
    private final InteractionFieldSchema fieldSchemas;

    public SessionArchiveService(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            CurrentSessionService sessions,
            ProductProperties properties,
            InteractionFieldSchema fieldSchemas) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.sessions = sessions;
        this.properties = properties;
        this.fieldSchemas = fieldSchemas;
    }

    @Transactional(readOnly = true)
    public ObjectNode archive(String sessionId) {
        SessionWorkspace session = sessions.get(sessionId);
        if (session.readOnly()) {
            return storedArchive(sessionId).orElseThrow(() -> new IllegalStateException(
                    "IMPORTED_SESSION_ARCHIVE_MISSING：" + sessionId));
        }
        return localArchive(session);
    }

    @Transactional
    public SessionWorkspace importArchive(JsonNode requested) {
        ObjectNode validated = validate(requested);
        String name = validated.path("session").path("name").asText();
        SessionWorkspace imported = sessions.createImported(name);
        jdbc.update("""
                INSERT INTO product_session_archive(session_id, archive_json)
                VALUES (?, ?)
                """, imported.sessionId(), text(validated));
        return imported;
    }

    private ObjectNode localArchive(SessionWorkspace session) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("format", FORMAT);
        root.put("exported_at", Instant.now().toString());

        ObjectNode metadata = root.putObject("session");
        metadata.put("session_id", session.sessionId());
        metadata.put("name", session.name());
        metadata.put("created_at", session.createdAt());
        metadata.put("updated_at", session.updatedAt());

        ObjectNode equipment = root.putObject("source_equipment");
        equipment.put("equipment_id", properties.equipment().id());
        equipment.put("display_name", properties.equipment().displayName());

        root.set("breakpoints", breakpoints(session.sessionId()));
        root.set("interactions", interactions(session.sessionId()));
        root.set("pauses", pauses(session.sessionId()));
        return root;
    }

    private ArrayNode breakpoints(String sessionId) {
        ArrayNode items = objectMapper.createArrayNode();
        jdbc.query("""
                SELECT breakpoint_id, name, object_name, command_name, pause_point, enabled,
                       conditions_json, hit_count, last_hit_at, created_at, updated_at
                FROM product_breakpoint
                WHERE session_id = ?
                ORDER BY created_at, breakpoint_id
                """, result -> {
            ObjectNode item = items.addObject();
            item.put("breakpoint_id", result.getString("breakpoint_id"));
            item.put("name", result.getString("name"));
            item.put("object", result.getString("object_name"));
            item.put("command", result.getString("command_name"));
            item.put("pause_point", result.getString("pause_point"));
            item.put("enabled", result.getBoolean("enabled"));
            item.set("conditions", json(result.getString("conditions_json")));
            item.put("hit_count", result.getLong("hit_count"));
            nullableText(item, "last_hit_at", result.getString("last_hit_at"));
            item.put("created_at", result.getString("created_at"));
            item.put("updated_at", result.getString("updated_at"));
        }, sessionId);
        return items;
    }

    private ArrayNode interactions(String sessionId) {
        ArrayNode items = objectMapper.createArrayNode();
        jdbc.query("""
                SELECT interaction_id, object_name, command_name, params_json, field_schema_json,
                       schema_changed, lifecycle, before_at, after_at, result_json, updated_at
                FROM product_interaction
                WHERE session_id = ?
                ORDER BY before_at, interaction_id
                """, result -> {
            ObjectNode item = items.addObject();
            item.put("interaction_id", result.getString("interaction_id"));
            item.put("object", result.getString("object_name"));
            item.put("command", result.getString("command_name"));
            item.set("params", json(result.getString("params_json")));
            item.set("field_schema", json(result.getString("field_schema_json")));
            item.put("schema_changed", result.getBoolean("schema_changed"));
            item.put("lifecycle", result.getString("lifecycle"));
            item.put("before_at", result.getString("before_at"));
            nullableText(item, "after_at", result.getString("after_at"));
            String resultJson = result.getString("result_json");
            item.set("result", resultJson == null ? objectMapper.nullNode() : json(resultJson));
            item.put("updated_at", result.getString("updated_at"));
        }, sessionId);
        return items;
    }

    private ArrayNode pauses(String sessionId) {
        ArrayNode items = objectMapper.createArrayNode();
        jdbc.query("""
                SELECT pause.interaction_id, pause.pause_point, pause.status,
                       pause.breakpoint_snapshots_json, pause.effective_content_json,
                       pause.injection_audit_json, pause.injection_status,
                       CASE pause.pause_point
                           WHEN 'before' THEN interaction.params_json
                           ELSE interaction.result_json
                       END AS original_content_json,
                       pause.paused_at, pause.resolved_at, pause.resolution
                FROM product_pause pause
                JOIN product_interaction interaction
                  ON interaction.interaction_id = pause.interaction_id
                WHERE pause.session_id = ?
                ORDER BY pause.paused_at, pause.interaction_id, pause.pause_point
                """, (org.springframework.jdbc.core.RowCallbackHandler) result -> addPause(items, result), sessionId);
        return items;
    }

    private void addPause(ArrayNode items, ResultSet result) throws SQLException {
        ObjectNode item = items.addObject();
        String pausePoint = result.getString("pause_point");
        String status = result.getString("status");
        String originalJson = result.getString("original_content_json");
        String effectiveJson = result.getString("effective_content_json");
        item.put("interaction_id", result.getString("interaction_id"));
        item.put("pause_point", pausePoint);
        item.put("status", status);
        item.set("breakpoint_snapshots", json(result.getString("breakpoint_snapshots_json")));
        item.put("content_kind", "before".equals(pausePoint) ? "params" : "result");
        item.set("original_content", json(originalJson));
        item.set("effective_content", json(effectiveJson));
        item.set("injection_audit", json(result.getString("injection_audit_json")));
        item.put("injection_status", result.getString("injection_status"));
        item.put("paused_at", result.getString("paused_at"));
        String resolvedAt = result.getString("resolved_at");
        nullableText(item, "resolved_at", resolvedAt);
        nullableText(item, "resolution", result.getString("resolution"));
        item.set("released_content", resolvedAt == null
                ? objectMapper.nullNode()
                : json("continued".equals(status) ? effectiveJson : originalJson));
    }

    private Optional<ObjectNode> storedArchive(String sessionId) {
        return jdbc.query("""
                SELECT archive_json FROM product_session_archive WHERE session_id = ?
                """, (result, row) -> (ObjectNode) json(result.getString("archive_json")), sessionId)
                .stream()
                .findFirst();
    }

    private ObjectNode validate(JsonNode requested) {
        ObjectNode root = object(requested, "$", ROOT_FIELDS).deepCopy();
        String format = textValue(root.get("format"), "$.format", 200);
        if (!FORMAT.equals(format)) {
            throw new ProductException(
                    HttpStatus.BAD_REQUEST,
                    "UNSUPPORTED_SESSION_ARCHIVE",
                    "只支持 " + FORMAT + " 格式的 .mbsession 文件，不兼容旧 .mbrec");
        }
        instant(root.get("exported_at"), "$.exported_at");

        ObjectNode session = object(root.get("session"), "$.session", SESSION_FIELDS);
        textValue(session.get("session_id"), "$.session.session_id", 200);
        textValue(session.get("name"), "$.session.name", 120);
        instant(session.get("created_at"), "$.session.created_at");
        instant(session.get("updated_at"), "$.session.updated_at");

        ObjectNode equipment = object(root.get("source_equipment"), "$.source_equipment", EQUIPMENT_FIELDS);
        textValue(equipment.get("equipment_id"), "$.source_equipment.equipment_id", 200);
        textValue(equipment.get("display_name"), "$.source_equipment.display_name", 200);

        ArrayNode breakpoints = array(root.get("breakpoints"), "$.breakpoints");
        Set<String> breakpointIds = new HashSet<>();
        for (int index = 0; index < breakpoints.size(); index++) {
            validateBreakpoint(breakpoints.get(index), index, breakpointIds);
        }

        ArrayNode interactions = array(root.get("interactions"), "$.interactions");
        Set<String> interactionIds = new HashSet<>();
        for (int index = 0; index < interactions.size(); index++) {
            validateInteraction(interactions.get(index), index, interactionIds);
        }

        ArrayNode pauses = array(root.get("pauses"), "$.pauses");
        Set<String> pauseKeys = new HashSet<>();
        for (int index = 0; index < pauses.size(); index++) {
            validatePause(pauses.get(index), index, interactionIds, pauseKeys);
        }
        return root;
    }

    private void validateBreakpoint(JsonNode value, int index, Set<String> ids) {
        String path = "$.breakpoints[" + index + "]";
        ObjectNode item = object(value, path, BREAKPOINT_FIELDS);
        unique(ids, textValue(item.get("breakpoint_id"), path + ".breakpoint_id", 200), path + ".breakpoint_id");
        textValue(item.get("name"), path + ".name", 200);
        textValue(item.get("object"), path + ".object", 200);
        textValue(item.get("command"), path + ".command", 200);
        pausePoint(item.get("pause_point"), path + ".pause_point");
        bool(item.get("enabled"), path + ".enabled");
        validateConditions(item.get("conditions"), path + ".conditions");
        nonNegativeInteger(item.get("hit_count"), path + ".hit_count");
        nullableInstant(item.get("last_hit_at"), path + ".last_hit_at");
        instant(item.get("created_at"), path + ".created_at");
        instant(item.get("updated_at"), path + ".updated_at");
    }

    private void validateInteraction(JsonNode value, int index, Set<String> ids) {
        String path = "$.interactions[" + index + "]";
        ObjectNode item = object(value, path, INTERACTION_FIELDS);
        unique(ids, textValue(item.get("interaction_id"), path + ".interaction_id", 200), path + ".interaction_id");
        textValue(item.get("object"), path + ".object", 200);
        textValue(item.get("command"), path + ".command", 200);
        ObjectNode params = object(item.get("params"), path + ".params");
        JsonNode archivedFieldSchema = item.get("field_schema");
        if (archivedFieldSchema == null
                || !archivedFieldSchema.isArray()
                || !fieldSchemas.describe(params).equals(archivedFieldSchema)) {
            invalid(path + ".field_schema 必须与 params 派生的字段结构完全一致");
        }
        bool(item.get("schema_changed"), path + ".schema_changed");
        String lifecycle = textValue(item.get("lifecycle"), path + ".lifecycle", 20);
        if (!("running".equals(lifecycle) || "completed".equals(lifecycle))) {
            invalid(path + ".lifecycle 必须是 running 或 completed");
        }
        instant(item.get("before_at"), path + ".before_at");
        nullableInstant(item.get("after_at"), path + ".after_at");
        instant(item.get("updated_at"), path + ".updated_at");
        if ("completed".equals(lifecycle) && item.get("after_at").isNull()) {
            invalid(path + ".after_at 在 completed 状态下不能为空");
        }
        if ("running".equals(lifecycle) && (!item.get("after_at").isNull() || !item.get("result").isNull())) {
            invalid(path + " 的 running Interaction 不能包含 after 结果");
        }
    }

    private void validatePause(
            JsonNode value,
            int index,
            Set<String> interactionIds,
            Set<String> keys) {
        String path = "$.pauses[" + index + "]";
        ObjectNode item = object(value, path, PAUSE_FIELDS);
        String interactionId = textValue(item.get("interaction_id"), path + ".interaction_id", 200);
        if (!interactionIds.contains(interactionId)) {
            invalid(path + ".interaction_id 没有对应的 Interaction");
        }
        String pausePoint = pausePoint(item.get("pause_point"), path + ".pause_point");
        unique(keys, interactionId + "\u0000" + pausePoint, path);
        String status = textValue(item.get("status"), path + ".status", 30);
        if (!Set.of("paused", "continued", "timed_out", "safe_released").contains(status)) {
            invalid(path + ".status 不是合法 Pause 状态");
        }
        ArrayNode snapshots = array(item.get("breakpoint_snapshots"), path + ".breakpoint_snapshots");
        for (int snapshotIndex = 0; snapshotIndex < snapshots.size(); snapshotIndex++) {
            validateSnapshot(
                    snapshots.get(snapshotIndex),
                    path + ".breakpoint_snapshots[" + snapshotIndex + "]",
                    pausePoint);
        }
        String contentKind = textValue(item.get("content_kind"), path + ".content_kind", 20);
        if (!("before".equals(pausePoint) ? "params" : "result").equals(contentKind)) {
            invalid(path + ".content_kind 与 pause_point 不一致");
        }
        ArrayNode audit = array(item.get("injection_audit"), path + ".injection_audit");
        for (int auditIndex = 0; auditIndex < audit.size(); auditIndex++) {
            validateInjection(audit.get(auditIndex), path + ".injection_audit[" + auditIndex + "]");
        }
        String injectionStatus = textValue(item.get("injection_status"), path + ".injection_status", 20);
        if (!Set.of("none", "pending", "committed", "discarded").contains(injectionStatus)) {
            invalid(path + ".injection_status 不是合法状态");
        }
        instant(item.get("paused_at"), path + ".paused_at");
        nullableInstant(item.get("resolved_at"), path + ".resolved_at");
        nullableTextValue(item.get("resolution"), path + ".resolution", 100);
        if ("paused".equals(status)) {
            if (!item.get("resolved_at").isNull() || !item.get("resolution").isNull()
                    || !item.get("released_content").isNull()) {
                invalid(path + " 的 paused 状态不能包含释放结果");
            }
        } else if (item.get("resolved_at").isNull() || item.get("resolution").isNull()) {
            invalid(path + " 的已释放状态必须包含 resolved_at 和 resolution");
        }
    }

    private void validateSnapshot(JsonNode value, String path, String matchedPausePoint) {
        ObjectNode item = object(value, path, SNAPSHOT_FIELDS);
        textValue(item.get("breakpoint_id"), path + ".breakpoint_id", 200);
        textValue(item.get("name"), path + ".name", 200);
        textValue(item.get("object"), path + ".object", 200);
        textValue(item.get("command"), path + ".command", 200);
        String snapshotPausePoint = pausePoint(item.get("pause_point"), path + ".pause_point");
        if (!matchedPausePoint.equals(snapshotPausePoint)) {
            invalid(path + ".pause_point 必须与 Pause 的 pause_point 一致");
        }
        bool(item.get("enabled"), path + ".enabled");
        validateConditions(item.get("conditions"), path + ".conditions");
        validateConditionEvidence(
                (ArrayNode) item.get("conditions"),
                item.get("condition_evidence"),
                path + ".condition_evidence");
        instant(item.get("matched_at"), path + ".matched_at");
    }

    private void validateConditionEvidence(ArrayNode conditions, JsonNode value, String path) {
        ArrayNode evidence = array(value, path);
        if (evidence.size() != conditions.size()) {
            invalid(path + " 必须与 conditions 一一对应");
        }
        for (int index = 0; index < evidence.size(); index++) {
            String entryPath = path + "[" + index + "]";
            ObjectNode entry = object(evidence.get(index), entryPath, CONDITION_EVIDENCE_FIELDS);
            ObjectNode condition = (ObjectNode) conditions.get(index);
            String source = textValue(entry.get("source"), entryPath + ".source", 20);
            String fieldPath = fieldPath(entry.get("field_path"), entryPath + ".field_path");
            String operator = textValue(entry.get("operator"), entryPath + ".operator", 20);
            if (!source.equals(condition.path("source").asText())
                    || !fieldPath.equals(condition.path("field_path").asText())
                    || !operator.equals(condition.path("operator").asText())
                    || !entry.get("expected_value").equals(condition.get("value"))) {
                invalid(entryPath + " 必须对应同位置的 condition");
            }
            JsonNode actual = entry.get("actual_value");
            if ("eq".equals(operator)) {
                scalar(actual, entryPath + ".actual_value");
                if (!BreakpointConditionEngine.sameScalar(actual, condition.get("value"))) {
                    invalid(entryPath + ".actual_value 必须等于期望值");
                }
                continue;
            }
            ArrayNode actualValues = array(actual, entryPath + ".actual_value");
            if (actualValues.isEmpty()) {
                invalid(entryPath + ".actual_value 必须包含实际交集");
            }
            Set<String> expectedKeys = new HashSet<>();
            condition.path("value").forEach(candidate ->
                    expectedKeys.add(BreakpointConditionEngine.scalarKey(candidate)));
            String previousKey = null;
            for (int actualIndex = 0; actualIndex < actualValues.size(); actualIndex++) {
                JsonNode actualValue = actualValues.get(actualIndex);
                scalar(actualValue, entryPath + ".actual_value[" + actualIndex + "]");
                String key = BreakpointConditionEngine.scalarKey(actualValue);
                if (!expectedKeys.contains(key) || (previousKey != null && previousKey.compareTo(key) >= 0)) {
                    invalid(entryPath + ".actual_value 必须是去重且稳定排序的实际交集");
                }
                previousKey = key;
            }
        }
    }

    private void validateInjection(JsonNode value, String path) {
        ObjectNode item = object(value, path, INJECTION_FIELDS);
        instant(item.get("injected_at"), path + ".injected_at");
        String result = textValue(item.get("result"), path + ".result", 100);
        if (!Set.of("applied", "partial", "no_effect").contains(result)) {
            invalid(path + ".result 不是合法注入结果");
        }
        object(item.get("changes"), path + ".changes");
        jsonPointerArray(item.get("modified"), path + ".modified");
        jsonPointerArray(item.get("unchanged"), path + ".unchanged");
        ObjectNode skipped = object(item.get("skipped"), path + ".skipped", SKIPPED_FIELDS);
        for (String field : SKIPPED_FIELDS) {
            jsonPointerArray(skipped.get(field), path + ".skipped." + field);
        }
        bool(item.get("effective_changed"), path + ".effective_changed");
        boolean effectiveChanged = item.get("effective_changed").asBoolean();
        if (effectiveChanged == "no_effect".equals(result)) {
            invalid(path + ".effective_changed 与 result 不一致");
        }
    }

    private void validateConditions(JsonNode value, String path) {
        ArrayNode conditions = array(value, path);
        for (int index = 0; index < conditions.size(); index++) {
            String conditionPath = path + "[" + index + "]";
            ObjectNode condition = object(conditions.get(index), conditionPath);
            object(condition, conditionPath, CONDITION_FIELDS);
            String source = textValue(condition.get("source"), conditionPath + ".source", 20);
            if (!("params".equals(source) || "result".equals(source))) {
                invalid(conditionPath + ".source 只能是 params 或 result");
            }
            fieldPath(condition.get("field_path"), conditionPath + ".field_path");
            String operator = textValue(condition.get("operator"), conditionPath + ".operator", 20);
            JsonNode requestedValue = condition.get("value");
            if ("eq".equals(operator)) {
                scalar(requestedValue, conditionPath + ".value");
            } else if ("contains_any".equals(operator)) {
                ArrayNode candidates = array(requestedValue, conditionPath + ".value");
                if (candidates.isEmpty()) {
                    invalid(conditionPath + ".value 必须是非空 JSON 标量数组");
                }
                for (int candidate = 0; candidate < candidates.size(); candidate++) {
                    scalar(candidates.get(candidate), conditionPath + ".value[" + candidate + "]");
                }
            } else {
                invalid(conditionPath + ".operator 只能是 eq 或 contains_any");
            }
        }
    }

    private String fieldPath(JsonNode value, String path) {
        String fieldPath = textValue(value, path, 500);
        if (!BreakpointFieldPath.isValid(fieldPath)) {
            invalid(path + " 必须是点分隔的对象字段路径");
        }
        return fieldPath;
    }

    private void scalar(JsonNode value, String path) {
        if (value == null || !(value.isNull() || value.isTextual() || value.isNumber() || value.isBoolean())) {
            invalid(path + " 只能是 JSON 标量或 null");
        }
    }

    private ObjectNode object(JsonNode value, String path, Set<String> exactFields) {
        ObjectNode object = object(value, path);
        Set<String> actual = new LinkedHashSet<>();
        object.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(exactFields)) {
            invalid(path + " 字段必须恰好为 " + exactFields);
        }
        return object;
    }

    private ObjectNode object(JsonNode value, String path) {
        if (value == null || !value.isObject()) {
            invalid(path + " 必须是 JSON 对象");
        }
        return (ObjectNode) value;
    }

    private ArrayNode array(JsonNode value, String path) {
        if (value == null || !value.isArray()) {
            invalid(path + " 必须是 JSON 数组");
        }
        return (ArrayNode) value;
    }

    private void jsonPointerArray(JsonNode value, String path) {
        ArrayNode array = array(value, path);
        for (int index = 0; index < array.size(); index++) {
            JsonNode pointer = array.get(index);
            if (pointer == null || !pointer.isTextual() || !isJsonPointer(pointer.asText())) {
                invalid(path + "[" + index + "] 必须是非空 JSON Pointer");
            }
        }
    }

    private boolean isJsonPointer(String value) {
        if (value.isBlank() || !value.startsWith("/")) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) != '~') {
                continue;
            }
            if (index + 1 >= value.length()) {
                return false;
            }
            char escaped = value.charAt(index + 1);
            if (escaped != '0' && escaped != '1') {
                return false;
            }
            index++;
        }
        return true;
    }

    private String pausePoint(JsonNode value, String path) {
        String pausePoint = textValue(value, path, 20);
        if (!("before".equals(pausePoint) || "after".equals(pausePoint))) {
            invalid(path + " 必须是 before 或 after");
        }
        return pausePoint;
    }

    private String textValue(JsonNode value, String path, int maxLength) {
        if (value == null || !value.isTextual()) {
            invalid(path + " 必须是字符串");
        }
        String text = value.asText();
        if (text.isBlank() || text.length() > maxLength) {
            invalid(path + " 必须为 1 到 " + maxLength + " 个字符");
        }
        return text;
    }

    private void nullableTextValue(JsonNode value, String path, int maxLength) {
        if (value == null || value.isNull()) {
            return;
        }
        textValue(value, path, maxLength);
    }

    private void bool(JsonNode value, String path) {
        if (value == null || !value.isBoolean()) {
            invalid(path + " 必须是布尔值");
        }
    }

    private void nonNegativeInteger(JsonNode value, String path) {
        if (value == null || !value.isIntegralNumber() || value.asLong() < 0) {
            invalid(path + " 必须是非负整数");
        }
    }

    private void instant(JsonNode value, String path) {
        String text = textValue(value, path, 100);
        try {
            Instant.parse(text);
        } catch (DateTimeParseException error) {
            invalid(path + " 必须是 ISO-8601 时间");
        }
    }

    private void nullableInstant(JsonNode value, String path) {
        if (value == null || value.isNull()) {
            return;
        }
        instant(value, path);
    }

    private void unique(Set<String> values, String value, String path) {
        if (!values.add(value)) {
            invalid(path + " 不能重复");
        }
    }

    private void invalid(String message) {
        throw new ProductException(HttpStatus.BAD_REQUEST, "INVALID_SESSION_ARCHIVE", message);
    }

    private JsonNode json(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (Exception error) {
            throw new IllegalStateException("Session 证据 JSON 无法读取", error);
        }
    }

    private String text(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalStateException("Session 归档 JSON 无法写入", error);
        }
    }

    private static void nullableText(ObjectNode object, String field, String value) {
        if (value == null) {
            object.putNull(field);
        } else {
            object.put(field, value);
        }
    }
}
