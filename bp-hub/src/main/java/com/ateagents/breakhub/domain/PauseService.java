package com.ateagents.breakhub.domain;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ateagents.breakhub.api.ProductException;
import com.ateagents.breakhub.config.ProductProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

@Service
public class PauseService {

    private static final long WAIT_POLL_MILLIS = 50;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final JsonChangeEngine changes;
    private final CurrentSessionService sessions;
    private final Duration pauseTimeout;

    public PauseService(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            JsonChangeEngine changes,
            CurrentSessionService sessions,
            ProductProperties properties) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.changes = changes;
        this.sessions = sessions;
        this.pauseTimeout = properties.interaction().pauseTimeout();
    }

    @Transactional
    public void create(
            String sessionId,
            String interactionId,
            String pausePoint,
            JsonNode breakpointSnapshots,
            JsonNode originalContent,
            Instant pausedAt) {
        jdbc.update("""
                INSERT INTO product_pause(
                    interaction_id, pause_point, session_id, status,
                    breakpoint_snapshots_json, effective_content_json,
                    injection_audit_json, injection_status, paused_at)
                VALUES (?, ?, ?, 'paused', ?, ?, '[]', 'none', ?)
                """,
                interactionId,
                pausePoint,
                sessionId,
                text(breakpointSnapshots),
                text(originalContent),
                pausedAt.toString());
    }

    public Map<String, Object> waitForRelease(String interactionId, String pausePoint) {
        Optional<PauseRecord> found = find(interactionId, pausePoint);
        if (found.isEmpty()) {
            return Map.of(
                    "tracked", true,
                    "proceed", true,
                    "released", true,
                    "result", "not_paused",
                    "interaction_id", interactionId,
                    "pause_point", pausePoint);
        }

        Instant deadline = Instant.parse(found.get().pausedAt()).plus(pauseTimeout);
        while (true) {
            PauseRecord pause = find(interactionId, pausePoint).orElseThrow();
            if (!"paused".equals(pause.status())) {
                return releaseResult(pause);
            }
            if (!deadline.isAfter(Instant.now())) {
                resolveIfPaused(interactionId, pausePoint, "timed_out", "pause_timeout");
                continue;
            }
            try {
                Thread.sleep(Math.min(
                        WAIT_POLL_MILLIS,
                        Math.max(1, Duration.between(Instant.now(), deadline).toMillis())));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return Map.of(
                        "tracked", true,
                        "proceed", true,
                        "released", false,
                        "result", "wait_interrupted",
                        "interaction_id", interactionId,
                        "pause_point", pausePoint);
            }
        }
    }

    @Transactional
    public synchronized Map<String, Object> continuePause(String interactionId, String pausePoint) {
        String sessionId = sessions.current().sessionId();
        int updated = jdbc.update("""
                UPDATE product_pause
                SET status = 'continued',
                    injection_status = CASE injection_status WHEN 'pending' THEN 'committed' ELSE injection_status END,
                    resolution = 'continued_by_controller', resolved_at = ?
                WHERE interaction_id = ? AND pause_point = ? AND session_id = ? AND status = 'paused'
                """, Instant.now().toString(), interactionId, pausePoint, sessionId);
        if (updated == 1) {
            PauseRecord resolved = find(interactionId, pausePoint).orElseThrow();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("interaction_id", interactionId);
            result.put("pause_point", pausePoint);
            result.put("continued", true);
            result.put("result", "continued");
            result.put("resolved_at", resolved.resolvedAt());
            return result;
        }
        if (!interactionExists(sessionId, interactionId)) {
            throw new ProductException(
                    HttpStatus.NOT_FOUND,
                    "INTERACTION_NOT_FOUND",
                    "Current Session 中不存在该 Interaction");
        }
        PauseRecord pause = find(interactionId, pausePoint)
                .filter(value -> value.sessionId().equals(sessionId))
                .orElseThrow(() -> new ProductException(
                        HttpStatus.NOT_FOUND,
                        "PAUSE_NOT_FOUND",
                        "该 Interaction 从未产生指定暂停点"));
        return alreadyResolved(pause);
    }

    @Transactional
    public synchronized Map<String, Object> continueSelected(List<PauseTarget> requestedTargets) {
        List<PauseTarget> targets = List.copyOf(requestedTargets);
        if (targets.isEmpty() || new HashSet<>(targets).size() != targets.size()) {
            throw new ProductException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_CONTINUE_SELECTION",
                    "继续所选必须包含至少一个且不能重复的 Pause 目标");
        }

        String sessionId = sessions.current().sessionId();
        for (PauseTarget target : targets) {
            if (!interactionExists(sessionId, target.interactionId())) {
                throw new ProductException(
                        HttpStatus.NOT_FOUND,
                        "INTERACTION_NOT_FOUND",
                        "Current Session 中不存在该 Interaction");
            }
            List<PauseRecord> activePauses = findByInteraction(target.interactionId()).stream()
                    .filter(pause -> pause.sessionId().equals(sessionId) && "paused".equals(pause.status()))
                    .toList();
            if (activePauses.isEmpty()) {
                throw new ProductException(
                        HttpStatus.CONFLICT,
                        "INTERACTION_NOT_PAUSED",
                        "所选 Interaction 已不再暂停");
            }
            if (activePauses.size() != 1) {
                throw new ProductException(
                        HttpStatus.CONFLICT,
                        "CONTINUE_SELECTION_CHANGED",
                        "所选 Interaction 的活动 Pause 状态不唯一");
            }
            PauseRecord activePause = activePauses.get(0);
            if (!activePause.pausePoint().equals(target.pausePoint())) {
                throw new ProductException(
                        HttpStatus.CONFLICT,
                        "PAUSE_POINT_MISMATCH",
                        "所选 Interaction 当前暂停阶段已变化");
            }
            if ("pending".equals(activePause.injectionStatus())) {
                throw new ProductException(
                        HttpStatus.CONFLICT,
                        "PENDING_INJECTION_REVIEW_REQUIRED",
                        "所选 Pause 有待提交注入，必须打开详情复核");
            }
            if (!"none".equals(activePause.injectionStatus())) {
                throw new ProductException(
                        HttpStatus.CONFLICT,
                        "CONTINUE_SELECTION_CHANGED",
                        "所选 Pause 的注入状态已变化");
            }
        }

        String resolvedAt = Instant.now().toString();
        try {
            for (PauseTarget target : targets) {
                int updated = jdbc.update("""
                        UPDATE product_pause
                        SET status = 'continued', resolution = 'continued_by_controller', resolved_at = ?
                        WHERE interaction_id = ? AND pause_point = ? AND session_id = ?
                          AND status = 'paused' AND injection_status = 'none'
                        """,
                        resolvedAt,
                        target.interactionId(),
                        target.pausePoint(),
                        sessionId);
                if (updated != 1) {
                    throw new ProductException(
                            HttpStatus.CONFLICT,
                            "CONTINUE_SELECTION_CHANGED",
                            "所选 Pause 已发生变化，本次没有继续任何记录");
                }
            }
        } catch (DataAccessException error) {
            throw new ProductException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "SELECTED_CONTINUE_FAILED",
                    "所选 Pause 未能原子继续，本次没有提交任何更改");
        }

        List<Map<String, Object>> interactions = targets.stream()
                .map(target -> Map.<String, Object>of(
                        "interaction_id", target.interactionId(),
                        "pause_point", target.pausePoint()))
                .toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("result", "continued");
        result.put("continued_count", targets.size());
        result.put("resolved_at", resolvedAt);
        result.put("interactions", interactions);
        return result;
    }

    @Transactional
    public synchronized Map<String, Object> continueAll() {
        String sessionId = sessions.current().sessionId();
        String commandStartedAt = Instant.now().toString();
        String resolvedAt = Instant.now().toString();
        List<BulkPauseResult> continued;
        try {
            continued = jdbc.query("""
                    UPDATE product_pause
                    SET status = 'continued',
                        injection_status = CASE injection_status WHEN 'pending' THEN 'committed' ELSE injection_status END,
                        resolution = 'continued_by_controller', resolved_at = ?
                    WHERE session_id = ? AND status = 'paused'
                      AND julianday(paused_at) <= julianday(?)
                    RETURNING interaction_id, pause_point, injection_status
                    """, (result, row) -> new BulkPauseResult(
                    result.getString("interaction_id"),
                    result.getString("pause_point"),
                    "committed".equals(result.getString("injection_status"))),
                    resolvedAt,
                    sessionId,
                    commandStartedAt);
        } catch (DataAccessException error) {
            throw new ProductException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "BULK_CONTINUE_FAILED",
                    "Current Session 的暂停调用未能原子继续，未提交任何更改");
        }
        return bulkContinueResult(commandStartedAt, resolvedAt, continued);
    }

    @Transactional
    public synchronized Map<String, Object> inject(
            String interactionId,
            String pausePoint,
            JsonNode requestedChanges) {
        String sessionId = sessions.current().sessionId();
        if (!interactionExists(sessionId, interactionId)) {
            throw new ProductException(
                    HttpStatus.NOT_FOUND,
                    "INTERACTION_NOT_FOUND",
                    "Current Session 中不存在该 Interaction");
        }
        PauseRecord pause = findByInteraction(interactionId).stream()
                .filter(value -> value.sessionId().equals(sessionId) && "paused".equals(value.status()))
                .findFirst()
                .orElseThrow(() -> new ProductException(
                        HttpStatus.CONFLICT,
                        "INTERACTION_NOT_PAUSED",
                        "该 Interaction 当前没有暂停，不能注入"));
        if (!pause.pausePoint().equals(pausePoint)) {
            throw new ProductException(
                    HttpStatus.CONFLICT,
                    "PAUSE_POINT_MISMATCH",
                    "该 Interaction 当前暂停在 " + pause.pausePoint() + "，不能按 " + pausePoint + " 注入");
        }

        JsonChangeEngine.ChangeResult change = changes.apply(
                json(pause.originalContentJson()),
                json(pause.effectiveContentJson()),
                requestedChanges);
        String injectedAt = Instant.now().toString();
        ArrayNode audit = (ArrayNode) json(pause.injectionAuditJson());
        ObjectNode entry = audit.addObject();
        entry.put("injected_at", injectedAt);
        entry.put("result", change.result());
        entry.set("changes", requestedChanges.deepCopy());
        entry.set("modified", stringArray(change.modifiedFields()));
        entry.set("unchanged", stringArray(change.unchangedFields()));
        entry.set("skipped", skipped(change));
        boolean effectiveChanged = !change.modifiedFields().isEmpty();
        entry.put("effective_changed", effectiveChanged);
        String injectionStatus = effectiveChanged ? "pending" : pause.injectionStatus();
        int updated = jdbc.update("""
                UPDATE product_pause
                SET effective_content_json = ?, injection_audit_json = ?, injection_status = ?
                WHERE interaction_id = ? AND pause_point = ? AND session_id = ? AND status = 'paused'
                """,
                text(change.effectiveContent()),
                text(audit),
                injectionStatus,
                interactionId,
                pausePoint,
                sessionId);
        if (updated != 1) {
            throw new ProductException(
                    HttpStatus.CONFLICT,
                    "INTERACTION_NOT_PAUSED",
                    "该 Interaction 已不再暂停，注入未生效");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("interaction_id", interactionId);
        result.put("pause_point", pausePoint);
        result.put("result", change.result());
        result.put("modified", change.modifiedFields());
        result.put("unchanged", change.unchangedFields());
        result.put("skipped", skippedMap(change));
        result.put("effective_changed", effectiveChanged);
        result.put("effective_change_count", effectiveChangeCount(audit));
        result.put("injected_at", injectedAt);
        result.put("effective_content", change.effectiveContent());
        return result;
    }

    @Transactional
    public synchronized int safeRelease(String sessionId, String reason) {
        String now = Instant.now().toString();
        return jdbc.update("""
                UPDATE product_pause
                SET status = 'safe_released',
                    injection_status = CASE injection_status WHEN 'pending' THEN 'discarded' ELSE injection_status END,
                    resolution = ?, resolved_at = ?
                WHERE session_id = ? AND status = 'paused'
                """, reason, now, sessionId);
    }

    public Optional<Map<String, Object>> currentPause(String interactionId) {
        return findByInteraction(interactionId).stream()
                .filter(value -> "paused".equals(value.status()))
                .findFirst()
                .map(this::body);
    }

    public List<Map<String, Object>> history(String interactionId) {
        return findByInteraction(interactionId).stream().map(this::body).toList();
    }

    public boolean isPaused(String interactionId) {
        return currentPause(interactionId).isPresent();
    }

    public boolean isPaused(String interactionId, String pausePoint) {
        return find(interactionId, pausePoint)
                .filter(value -> "paused".equals(value.status()))
                .isPresent();
    }

    public boolean hasPausedTarget(String sessionId, String object, String command) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM product_pause pause
                JOIN product_interaction interaction ON interaction.interaction_id = pause.interaction_id
                WHERE pause.session_id = ? AND pause.status = 'paused'
                  AND interaction.object_name = ? AND interaction.command_name = ?
                """, Integer.class, sessionId, object, command);
        return count != null && count > 0;
    }

    private synchronized void resolveIfPaused(
            String interactionId,
            String pausePoint,
            String status,
            String resolution) {
        jdbc.update("""
                UPDATE product_pause
                SET status = ?,
                    injection_status = CASE injection_status WHEN 'pending' THEN 'discarded' ELSE injection_status END,
                    resolution = ?, resolved_at = ?
                WHERE interaction_id = ? AND pause_point = ? AND status = 'paused'
                """, status, resolution, Instant.now().toString(), interactionId, pausePoint);
    }

    private boolean interactionExists(String sessionId, String interactionId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM product_interaction
                WHERE session_id = ? AND interaction_id = ?
                """, Integer.class, sessionId, interactionId);
        return count != null && count > 0;
    }

    private Optional<PauseRecord> find(String interactionId, String pausePoint) {
        return jdbc.query("""
                SELECT pause.interaction_id, pause.pause_point, pause.session_id, pause.status,
                       pause.breakpoint_snapshots_json, pause.effective_content_json,
                       pause.injection_audit_json, pause.injection_status,
                       CASE pause.pause_point
                           WHEN 'before' THEN interaction.params_json
                           ELSE interaction.result_json
                       END AS original_content_json,
                       pause.paused_at, pause.resolved_at, pause.resolution
                FROM product_pause pause
                JOIN product_interaction interaction ON interaction.interaction_id = pause.interaction_id
                WHERE pause.interaction_id = ? AND pause.pause_point = ?
                """, (result, row) -> record(result), interactionId, pausePoint)
                .stream()
                .findFirst();
    }

    private List<PauseRecord> findByInteraction(String interactionId) {
        return jdbc.query("""
                SELECT pause.interaction_id, pause.pause_point, pause.session_id, pause.status,
                       pause.breakpoint_snapshots_json, pause.effective_content_json,
                       pause.injection_audit_json, pause.injection_status,
                       CASE pause.pause_point
                           WHEN 'before' THEN interaction.params_json
                           ELSE interaction.result_json
                       END AS original_content_json,
                       pause.paused_at, pause.resolved_at, pause.resolution
                FROM product_pause pause
                JOIN product_interaction interaction ON interaction.interaction_id = pause.interaction_id
                WHERE pause.interaction_id = ?
                ORDER BY CASE pause.pause_point WHEN 'before' THEN 0 ELSE 1 END
                """, (result, row) -> record(result), interactionId);
    }

    private Map<String, Object> body(PauseRecord pause) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("interaction_id", pause.interactionId());
        body.put("pause_point", pause.pausePoint());
        body.put("status", pause.status());
        body.put("breakpoint_snapshots", json(pause.breakpointSnapshotsJson()));
        body.put("content_kind", contentKind(pause));
        body.put("original_content", json(pause.originalContentJson()));
        body.put("effective_content", json(pause.effectiveContentJson()));
        body.put("injection_status", pause.injectionStatus());
        JsonNode audit = json(pause.injectionAuditJson());
        body.put("injection_audit", audit);
        body.put("effective_change_count", effectiveChangeCount(audit));
        body.put("has_pending_injection", "pending".equals(pause.injectionStatus()));
        body.put("paused_at", pause.pausedAt());
        if (pause.resolvedAt() != null) {
            body.put("resolved_at", pause.resolvedAt());
            body.put("resolution", pause.resolution());
            body.put("released_content", releasedContent(pause));
        }
        return body;
    }

    private Map<String, Object> releaseResult(PauseRecord pause) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tracked", true);
        result.put("proceed", true);
        result.put("released", true);
        result.put("result", pause.status());
        result.put("interaction_id", pause.interactionId());
        result.put("pause_point", pause.pausePoint());
        result.put("resolved_at", pause.resolvedAt());
        result.put("resolution", pause.resolution());
        result.put("content_kind", contentKind(pause));
        result.put("content", releasedContent(pause));
        return result;
    }

    private JsonNode releasedContent(PauseRecord pause) {
        return json("continued".equals(pause.status())
                ? pause.effectiveContentJson()
                : pause.originalContentJson());
    }

    private static String contentKind(PauseRecord pause) {
        return "before".equals(pause.pausePoint()) ? "params" : "result";
    }

    private ObjectNode skipped(JsonChangeEngine.ChangeResult change) {
        ObjectNode skipped = objectMapper.createObjectNode();
        skipped.set("missing", stringArray(change.skippedMissingFields()));
        skipped.set("type_mismatch", stringArray(change.skippedTypeMismatchFields()));
        skipped.set("original_null", stringArray(change.skippedNullSourceFields()));
        return skipped;
    }

    private static Map<String, Object> skippedMap(JsonChangeEngine.ChangeResult change) {
        Map<String, Object> skipped = new LinkedHashMap<>();
        skipped.put("missing", change.skippedMissingFields());
        skipped.put("type_mismatch", change.skippedTypeMismatchFields());
        skipped.put("original_null", change.skippedNullSourceFields());
        return skipped;
    }

    private ArrayNode stringArray(List<String> values) {
        ArrayNode array = objectMapper.createArrayNode();
        values.forEach(array::add);
        return array;
    }

    private static long effectiveChangeCount(JsonNode audit) {
        long count = 0;
        for (JsonNode entry : audit) {
            if (entry.path("effective_changed").asBoolean()) {
                count++;
            }
        }
        return count;
    }

    private Map<String, Object> alreadyResolved(PauseRecord pause) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("interaction_id", pause.interactionId());
        result.put("pause_point", pause.pausePoint());
        result.put("continued", false);
        result.put("result", "already_resolved");
        result.put("status", pause.status());
        result.put("resolution", pause.resolution());
        result.put("resolved_at", pause.resolvedAt());
        result.put("content_kind", contentKind(pause));
        result.put("released_content", releasedContent(pause));
        return result;
    }

    private static Map<String, Object> bulkContinueResult(
            String commandStartedAt,
            String resolvedAt,
            List<BulkPauseResult> continued) {
        long pendingInjectionCount = continued.stream()
                .filter(BulkPauseResult::hadPendingInjection)
                .count();
        List<Map<String, Object>> interactions = continued.stream().map(pause -> Map.<String, Object>of(
                "interaction_id", pause.interactionId(),
                "pause_point", pause.pausePoint(),
                "had_pending_injection", pause.hadPendingInjection())).toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("result", continued.isEmpty() ? "nothing_to_continue" : "continued");
        result.put("continued_count", continued.size());
        result.put("pending_injection_count", pendingInjectionCount);
        result.put("command_started_at", commandStartedAt);
        result.put("resolved_at", resolvedAt);
        result.put("interactions", interactions);
        return result;
    }

    private JsonNode json(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (Exception error) {
            throw new IllegalStateException("持久化 Pause JSON 无法读取", error);
        }
    }

    private String text(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalStateException("Pause JSON 无法持久化", error);
        }
    }

    private static PauseRecord record(ResultSet result) throws SQLException {
        return new PauseRecord(
                result.getString("interaction_id"),
                result.getString("pause_point"),
                result.getString("session_id"),
                result.getString("status"),
                result.getString("breakpoint_snapshots_json"),
                result.getString("original_content_json"),
                result.getString("effective_content_json"),
                result.getString("injection_audit_json"),
                result.getString("injection_status"),
                result.getString("paused_at"),
                result.getString("resolved_at"),
                result.getString("resolution"));
    }

    private record PauseRecord(
            String interactionId,
            String pausePoint,
            String sessionId,
            String status,
            String breakpointSnapshotsJson,
            String originalContentJson,
            String effectiveContentJson,
            String injectionAuditJson,
            String injectionStatus,
            String pausedAt,
            String resolvedAt,
            String resolution) {
    }

    private record BulkPauseResult(
            String interactionId,
            String pausePoint,
            boolean hadPendingInjection) {
    }

    public record PauseTarget(String interactionId, String pausePoint) {
    }
}
