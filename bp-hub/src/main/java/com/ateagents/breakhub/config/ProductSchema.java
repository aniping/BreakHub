package com.ateagents.breakhub.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class ProductSchema implements InitializingBean {

    private final JdbcTemplate jdbc;

    public ProductSchema(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void afterPropertiesSet() {
        Integer legacyTableCount = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM sqlite_master
                WHERE type = 'table'
                  AND name IN ('debug_cycle', 'bp_rule', 'interaction_record', 'interaction_pause')
                """, Integer.class);
        if (legacyTableCount != null && legacyTableCount > 0) {
            throw new IllegalStateException(
                    "LEGACY_DATABASE_UNSUPPORTED：检测到旧断点产品数据库，请使用新的数据目录");
        }
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS product_session (
                    id TEXT PRIMARY KEY,
                    name TEXT NOT NULL,
                    source TEXT NOT NULL CHECK (source IN ('local', 'imported')),
                    read_only INTEGER NOT NULL CHECK (read_only IN (0, 1)),
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS product_state (
                    singleton_id INTEGER PRIMARY KEY CHECK (singleton_id = 1),
                    current_session_id TEXT NOT NULL UNIQUE,
                    FOREIGN KEY (current_session_id) REFERENCES product_session(id)
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS product_session_archive (
                    session_id TEXT PRIMARY KEY,
                    archive_json TEXT NOT NULL,
                    FOREIGN KEY (session_id) REFERENCES product_session(id) ON DELETE CASCADE
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS product_interaction (
                    interaction_id TEXT PRIMARY KEY,
                    session_id TEXT NOT NULL,
                    object_name TEXT NOT NULL,
                    command_name TEXT NOT NULL,
                    params_json TEXT NOT NULL,
                    field_schema_json TEXT NOT NULL,
                    schema_changed INTEGER NOT NULL CHECK (schema_changed IN (0, 1)),
                    lifecycle TEXT NOT NULL CHECK (lifecycle IN ('running', 'completed')),
                    before_at TEXT NOT NULL,
                    after_at TEXT,
                    result_json TEXT,
                    updated_at TEXT NOT NULL,
                    FOREIGN KEY (session_id) REFERENCES product_session(id) ON DELETE CASCADE
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS product_breakpoint (
                    breakpoint_id TEXT PRIMARY KEY,
                    session_id TEXT NOT NULL,
                    name TEXT NOT NULL,
                    object_name TEXT NOT NULL,
                    command_name TEXT NOT NULL,
                    pause_point TEXT NOT NULL CHECK (pause_point IN ('before', 'after')),
                    enabled INTEGER NOT NULL CHECK (enabled IN (0, 1)),
                    conditions_json TEXT NOT NULL,
                    hit_count INTEGER NOT NULL DEFAULT 0,
                    last_hit_at TEXT,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    FOREIGN KEY (session_id) REFERENCES product_session(id) ON DELETE CASCADE
                )
                """);
        ensureBreakpointColumn("hit_count", "INTEGER NOT NULL DEFAULT 0");
        ensureBreakpointColumn("last_hit_at", "TEXT");
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS idx_product_breakpoint_session_target
                ON product_breakpoint(session_id, object_name, command_name, pause_point, enabled)
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS product_pause (
                    interaction_id TEXT NOT NULL,
                    pause_point TEXT NOT NULL CHECK (pause_point IN ('before', 'after')),
                    session_id TEXT NOT NULL,
                    status TEXT NOT NULL CHECK (status IN ('paused', 'continued', 'timed_out', 'safe_released')),
                    breakpoint_snapshots_json TEXT NOT NULL,
                    effective_content_json TEXT,
                    injection_audit_json TEXT NOT NULL DEFAULT '[]',
                    injection_status TEXT NOT NULL DEFAULT 'none'
                        CHECK (injection_status IN ('none', 'pending', 'committed', 'discarded')),
                    paused_at TEXT NOT NULL,
                    resolved_at TEXT,
                    resolution TEXT,
                    PRIMARY KEY (interaction_id, pause_point),
                    FOREIGN KEY (interaction_id) REFERENCES product_interaction(interaction_id) ON DELETE CASCADE,
                    FOREIGN KEY (session_id) REFERENCES product_session(id) ON DELETE CASCADE
                )
                """);
        ensurePauseColumn("effective_content_json", "TEXT");
        ensurePauseColumn("injection_audit_json", "TEXT NOT NULL DEFAULT '[]'");
        ensurePauseColumn("injection_status", "TEXT NOT NULL DEFAULT 'none'");
        jdbc.execute("""
                UPDATE product_pause
                SET effective_content_json = CASE pause_point
                    WHEN 'before' THEN (
                        SELECT params_json FROM product_interaction
                        WHERE product_interaction.interaction_id = product_pause.interaction_id)
                    ELSE (
                        SELECT result_json FROM product_interaction
                        WHERE product_interaction.interaction_id = product_pause.interaction_id)
                    END
                WHERE effective_content_json IS NULL
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS idx_product_pause_session_status
                ON product_pause(session_id, status, paused_at DESC)
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS idx_product_interaction_session_updated
                ON product_interaction(session_id, updated_at DESC)
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS idx_product_interaction_interface_seen
                ON product_interaction(session_id, object_name, command_name, before_at DESC)
                """);
    }

    private void ensureBreakpointColumn(String name, String definition) {
        boolean exists = jdbc.queryForList("PRAGMA table_info(product_breakpoint)").stream()
                .anyMatch(column -> name.equals(column.get("name")));
        if (!exists) {
            jdbc.execute("ALTER TABLE product_breakpoint ADD COLUMN " + name + " " + definition);
        }
    }

    private void ensurePauseColumn(String name, String definition) {
        boolean exists = jdbc.queryForList("PRAGMA table_info(product_pause)").stream()
                .anyMatch(column -> name.equals(column.get("name")));
        if (!exists) {
            jdbc.execute("ALTER TABLE product_pause ADD COLUMN " + name + " " + definition);
        }
    }
}
