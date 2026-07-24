package com.ateagents.breakhub.domain;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ateagents.breakhub.api.ProductException;

@Service
public class CurrentSessionService {

    private final JdbcTemplate jdbc;

    public CurrentSessionService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public SessionWorkspace current() {
        return findCurrent().stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("CURRENT_SESSION_NOT_INITIALIZED"));
    }

    @Transactional(readOnly = true)
    public SessionListSnapshot snapshot() {
        List<SessionWorkspace> items = findAll();
        String currentSessionId = items.stream()
                .filter(SessionWorkspace::current)
                .map(SessionWorkspace::sessionId)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("CURRENT_SESSION_NOT_INITIALIZED"));
        return new SessionListSnapshot(currentSessionId, items);
    }

    private List<SessionWorkspace> findAll() {
        return jdbc.query("""
                SELECT session.id, session.name, session.source, session.read_only,
                       session.created_at, session.updated_at,
                       CASE WHEN state.current_session_id = session.id THEN 1 ELSE 0 END AS current
                FROM product_session session
                LEFT JOIN product_state state ON state.singleton_id = 1
                ORDER BY current DESC, session.updated_at DESC, session.id
                """, (result, row) -> workspace(result));
    }

    @Transactional(readOnly = true)
    public SessionWorkspace get(String sessionId) {
        return find(sessionId).stream().findFirst().orElseThrow(() -> notFound(sessionId));
    }

    @Transactional
    public SessionWorkspace create(String requestedName) {
        String name = validName(requestedName);
        String now = Instant.now().toString();
        String sessionId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO product_session(id, name, source, read_only, created_at, updated_at)
                VALUES (?, ?, 'local', 0, ?, ?)
                """, sessionId, name, now, now);
        return get(sessionId);
    }

    @Transactional
    public SessionWorkspace createImported(String requestedName) {
        String name = validName(requestedName);
        String now = Instant.now().toString();
        String sessionId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO product_session(id, name, source, read_only, created_at, updated_at)
                VALUES (?, ?, 'imported', 1, ?, ?)
                """, sessionId, name, now, now);
        return get(sessionId);
    }

    @Transactional
    public Map<String, Object> clearCurrentInteractions() {
        SessionWorkspace current = current();
        Integer pausedCount = jdbc.queryForObject("""
                SELECT COUNT(*) FROM product_pause
                WHERE session_id = ? AND status = 'paused'
                """, Integer.class, current.sessionId());
        if (pausedCount != null && pausedCount > 0) {
            throw new ProductException(
                    HttpStatus.CONFLICT,
                    "SESSION_HAS_PAUSED_INTERACTIONS",
                    "Current Session 仍有暂停调用，请先继续或安全释放后再清空调用记录");
        }
        Integer interactionCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM product_interaction WHERE session_id = ?",
                Integer.class,
                current.sessionId());
        Integer pauseCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM product_pause WHERE session_id = ?",
                Integer.class,
                current.sessionId());
        Integer breakpointCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM product_breakpoint WHERE session_id = ?",
                Integer.class,
                current.sessionId());
        jdbc.update("DELETE FROM product_pause WHERE session_id = ?", current.sessionId());
        jdbc.update("DELETE FROM product_interaction WHERE session_id = ?", current.sessionId());
        jdbc.update("UPDATE product_session SET updated_at = ? WHERE id = ?",
                Instant.now().toString(), current.sessionId());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("session_id", current.sessionId());
        result.put("cleared_interaction_count", interactionCount == null ? 0 : interactionCount);
        result.put("cleared_pause_count", pauseCount == null ? 0 : pauseCount);
        result.put("retained_breakpoint_count", breakpointCount == null ? 0 : breakpointCount);
        return result;
    }

    @Transactional
    public SessionWorkspace rename(String sessionId, String requestedName) {
        SessionWorkspace existing = get(sessionId);
        if (existing.readOnly()) {
            throw new ProductException(
                    HttpStatus.CONFLICT,
                    "IMPORTED_SESSION_READ_ONLY",
                    "导入 Session 只能查看，不能重命名");
        }
        String name = validName(requestedName);
        jdbc.update("UPDATE product_session SET name = ?, updated_at = ? WHERE id = ?",
                name, Instant.now().toString(), sessionId);
        return get(sessionId);
    }

    @Transactional
    public SessionWorkspace selectCurrent(String sessionId) {
        SessionWorkspace selected = get(sessionId);
        if (selected.readOnly() || !"local".equals(selected.source())) {
            throw new ProductException(
                    HttpStatus.CONFLICT,
                    "IMPORTED_SESSION_READ_ONLY",
                    "导入 Session 不能设为 Current Session");
        }
        jdbc.update("UPDATE product_state SET current_session_id = ? WHERE singleton_id = 1", sessionId);
        return get(sessionId);
    }

    @Transactional
    public SessionWorkspace delete(String sessionId) {
        SessionWorkspace existing = get(sessionId);
        if (existing.current()) {
            throw new ProductException(
                    HttpStatus.CONFLICT,
                    "CURRENT_SESSION_DELETE_FORBIDDEN",
                    "Current Session 不能删除，请先切换到其他本机 Session");
        }
        jdbc.update("DELETE FROM product_pause WHERE session_id = ?", sessionId);
        jdbc.update("DELETE FROM product_interaction WHERE session_id = ?", sessionId);
        jdbc.update("DELETE FROM product_breakpoint WHERE session_id = ?", sessionId);
        jdbc.update("DELETE FROM product_session WHERE id = ?", sessionId);
        return existing;
    }

    @Transactional
    public void initializeDefault() {
        if (!findCurrent().isEmpty()) {
            return;
        }

        String now = Instant.now().toString();
        String sessionId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO product_session(id, name, source, read_only, created_at, updated_at)
                VALUES (?, ?, 'local', 0, ?, ?)
                """, sessionId, "默认 Session", now, now);
        jdbc.update("""
                INSERT INTO product_state(singleton_id, current_session_id)
                VALUES (1, ?)
                """, sessionId);
    }

    private List<SessionWorkspace> findCurrent() {
        return jdbc.query("""
                SELECT session.id, session.name, session.source, session.read_only,
                       session.created_at, session.updated_at, 1 AS current
                FROM product_state state
                JOIN product_session session ON session.id = state.current_session_id
                WHERE state.singleton_id = 1
                """, (result, row) -> workspace(result));
    }

    private List<SessionWorkspace> find(String sessionId) {
        return jdbc.query("""
                SELECT session.id, session.name, session.source, session.read_only,
                       session.created_at, session.updated_at,
                       CASE WHEN state.current_session_id = session.id THEN 1 ELSE 0 END AS current
                FROM product_session session
                LEFT JOIN product_state state ON state.singleton_id = 1
                WHERE session.id = ?
                """, (result, row) -> workspace(result), sessionId);
    }

    private static SessionWorkspace workspace(java.sql.ResultSet result) throws java.sql.SQLException {
        return new SessionWorkspace(
                result.getString("id"),
                result.getString("name"),
                result.getString("source"),
                result.getBoolean("read_only"),
                result.getString("created_at"),
                result.getString("updated_at"),
                result.getBoolean("current"));
    }

    private static String validName(String value) {
        String name = value == null ? "" : value.trim();
        if (name.isEmpty() || name.length() > 120) {
            throw new ProductException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_SESSION_NAME",
                    "Session 名称必须为 1 到 120 个字符");
        }
        return name;
    }

    private static ProductException notFound(String sessionId) {
        return new ProductException(
                HttpStatus.NOT_FOUND,
                "SESSION_NOT_FOUND",
                "Session 不存在：" + sessionId);
    }

    public record SessionWorkspace(
            String sessionId,
            String name,
            String source,
            boolean readOnly,
            String createdAt,
            String updatedAt,
            boolean current) {
    }

    public record SessionListSnapshot(String currentSessionId, List<SessionWorkspace> items) {
    }
}
