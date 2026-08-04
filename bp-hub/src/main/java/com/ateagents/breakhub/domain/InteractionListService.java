package com.ateagents.breakhub.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class InteractionListService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final CurrentSessionService sessions;

    public InteractionListService(JdbcTemplate jdbc, ObjectMapper objectMapper, CurrentSessionService sessions) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.sessions = sessions;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> interactions(Map<String, String> parameters) {
        InteractionListQuery query = InteractionListQuery.from(parameters);
        String sessionId = sessions.current().sessionId();
        FilterSql filter = filter(sessionId, query);
        int total = jdbc.queryForObject("SELECT COUNT(*) FROM product_interaction i " + filter.where(),
                Integer.class, filter.arguments().toArray());
        int sessionTotal = jdbc.queryForObject(
                "SELECT COUNT(*) FROM product_interaction WHERE session_id = ?", Integer.class, sessionId);
        int pausedTotal = jdbc.queryForObject(
                "SELECT COUNT(*) FROM product_pause WHERE session_id = ? AND status = 'paused'",
                Integer.class, sessionId);
        List<Map<String, Object>> items = jdbc.query(
                summarySql(filter.where()),
                (result, row) -> summary(result),
                pageArguments(filter.arguments(), query).toArray());
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("current_session_id", sessionId);
        response.put("items", items);
        response.put("total", total);
        response.put("session_total", sessionTotal);
        response.put("paused_total", pausedTotal);
        response.put("page", query.page());
        response.put("size", query.size());
        response.put("total_pages", total == 0 ? 0 : (total + query.size() - 1) / query.size());
        return response;
    }

    private FilterSql filter(String sessionId, InteractionListQuery query) {
        List<String> clauses = new ArrayList<>();
        List<Object> arguments = new ArrayList<>();
        clauses.add("i.session_id = ?");
        arguments.add(sessionId);
        addSearchFilter(query, clauses, arguments);
        addValueFilter("i.object_name = ?", query.object(), clauses, arguments);
        addValueFilter("i.command_name = ?", query.command(), clauses, arguments);
        addStatusFilter(query.status(), clauses);
        if (!query.pausePoint().isEmpty()) {
            clauses.add("EXISTS (SELECT 1 FROM product_pause p WHERE p.interaction_id = i.interaction_id AND p.pause_point = ?)");
            arguments.add(query.pausePoint());
        }
        addValueFilter("i.before_at >= ?", query.from(), clauses, arguments);
        addValueFilter("i.before_at <= ?", query.to(), clauses, arguments);
        return new FilterSql("WHERE " + String.join(" AND ", clauses), arguments);
    }

    private static void addSearchFilter(
            InteractionListQuery query,
            List<String> clauses,
            List<Object> arguments) {
        if (query.query().isEmpty()) return;
        clauses.add("(LOWER(i.interaction_id) LIKE ? ESCAPE '\\' OR LOWER(i.object_name) LIKE ? ESCAPE '\\' "
                + "OR LOWER(i.command_name) LIKE ? ESCAPE '\\')");
        String value = "%" + escapeLike(query.query().toLowerCase()) + "%";
        arguments.add(value);
        arguments.add(value);
        arguments.add(value);
    }

    private static void addStatusFilter(String status, List<String> clauses) {
        String paused = "EXISTS (SELECT 1 FROM product_pause p WHERE p.interaction_id = i.interaction_id AND p.status = 'paused')";
        if ("paused".equals(status)) clauses.add(paused);
        if ("in_progress".equals(status)) clauses.add("i.lifecycle = 'running' AND NOT " + paused);
        if ("completed".equals(status)) clauses.add("i.lifecycle = 'completed' AND NOT " + paused);
    }

    private static void addValueFilter(
            String clause,
            String value,
            List<String> clauses,
            List<Object> arguments) {
        if (value.isEmpty()) return;
        clauses.add(clause);
        arguments.add(value);
    }

    private static List<Object> pageArguments(List<Object> filterArguments, InteractionListQuery query) {
        List<Object> arguments = new ArrayList<>(filterArguments);
        arguments.add(query.size());
        arguments.add(query.page() * query.size());
        return arguments;
    }

    private String summarySql(String where) {
        // Pause 在分页查询内一次聚合，避免历史数量增长时出现逐条查询；原始 Payload 只由详情接口读取。
        return """
                WITH pause_summary AS (
                    SELECT interaction_id, COUNT(*) pause_count,
                           SUM(json_array_length(breakpoint_snapshots_json)) hit_count,
                           SUM(json_array_length(injection_audit_json)) injection_count
                    FROM product_pause GROUP BY interaction_id
                ), ranked_pause AS (
                    SELECT *, ROW_NUMBER() OVER (
                        PARTITION BY interaction_id
                        ORDER BY CASE pause_point WHEN 'after' THEN 1 ELSE 0 END DESC) pause_rank
                    FROM product_pause
                )
                SELECT i.interaction_id, i.object_name, i.command_name, i.lifecycle,
                       i.before_at, i.after_at, i.result_json IS NOT NULL has_result, i.schema_changed,
                       summary.pause_count, summary.hit_count, summary.injection_count,
                       latest.pause_point latest_pause_point, latest.status latest_pause_status,
                       latest.resolution latest_resolution,
                       latest.breakpoint_snapshots_json latest_snapshots,
                       current.pause_point current_pause_point, current.status current_pause_status,
                       current.injection_status current_injection_status, current.paused_at current_paused_at,
                       current.breakpoint_snapshots_json current_snapshots
                FROM product_interaction i
                LEFT JOIN pause_summary summary ON summary.interaction_id = i.interaction_id
                LEFT JOIN ranked_pause latest ON latest.interaction_id = i.interaction_id AND latest.pause_rank = 1
                LEFT JOIN product_pause current ON current.interaction_id = i.interaction_id AND current.status = 'paused'
                """ + where + """

                ORDER BY CASE WHEN current.interaction_id IS NULL THEN 1 ELSE 0 END,
                         i.updated_at DESC, i.interaction_id
                LIMIT ? OFFSET ?
                """;
    }

    private Map<String, Object> summary(java.sql.ResultSet result) throws java.sql.SQLException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("interaction_id", result.getString("interaction_id"));
        body.put("object", result.getString("object_name"));
        body.put("command", result.getString("command_name"));
        body.put("lifecycle", result.getString("lifecycle"));
        body.put("phase", result.getBoolean("has_result") ? "after" : "before");
        body.put("before_at", result.getString("before_at"));
        putNullable(body, "after_at", result.getString("after_at"));
        body.put("schema_changed", result.getBoolean("schema_changed"));
        body.put("status", result.getString("current_pause_status") == null
                ? result.getString("lifecycle") : "paused");
        body.put("pause_count", result.getInt("pause_count"));
        body.put("hit_count", result.getInt("hit_count"));
        body.put("injection_count", result.getInt("injection_count"));
        body.put("payload_metadata", payloadMetadata(result.getBoolean("has_result")));
        body.put("pauses", compactPauseHistory(result));
        putCurrentPause(body, result);
        return body;
    }

    private static Map<String, Object> payloadMetadata(boolean hasResult) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("params", Map.of("truncated", false));
        if (hasResult) metadata.put("result", Map.of("truncated", false));
        return metadata;
    }

    private List<Map<String, Object>> compactPauseHistory(java.sql.ResultSet result) throws java.sql.SQLException {
        String pausePoint = result.getString("latest_pause_point");
        if (pausePoint == null) return List.of();
        Map<String, Object> pause = new LinkedHashMap<>();
        pause.put("pause_point", pausePoint);
        pause.put("status", result.getString("latest_pause_status"));
        putNullable(pause, "resolution", result.getString("latest_resolution"));
        pause.put("breakpoint_snapshots", snapshotNames(result.getString("latest_snapshots")));
        return List.of(pause);
    }

    private void putCurrentPause(Map<String, Object> body, java.sql.ResultSet result) throws java.sql.SQLException {
        String pausePoint = result.getString("current_pause_point");
        if (pausePoint == null) return;
        Map<String, Object> pause = new LinkedHashMap<>();
        pause.put("pause_point", pausePoint);
        pause.put("status", result.getString("current_pause_status"));
        String injectionStatus = result.getString("current_injection_status");
        pause.put("injection_status", injectionStatus);
        pause.put("has_pending_injection", "pending".equals(injectionStatus));
        pause.put("paused_at", result.getString("current_paused_at"));
        pause.put("breakpoint_snapshots", snapshotNames(result.getString("current_snapshots")));
        body.put("current_pause", pause);
    }

    private List<Map<String, Object>> snapshotNames(String snapshotsJson) {
        if (snapshotsJson == null) return List.of();
        List<Map<String, Object>> snapshots = new ArrayList<>();
        for (JsonNode snapshot : json(snapshotsJson)) {
            String name = snapshot.path("name").asText("");
            if (!name.isEmpty()) snapshots.add(Map.of("name", name));
        }
        return snapshots;
    }

    private JsonNode json(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (Exception error) {
            throw new IllegalStateException("数据库中的 Interaction JSON 无法解析", error);
        }
    }

    private static void putNullable(Map<String, Object> target, String key, Object value) {
        if (value != null) target.put(key, value);
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private record FilterSql(String where, List<Object> arguments) {
    }
}
