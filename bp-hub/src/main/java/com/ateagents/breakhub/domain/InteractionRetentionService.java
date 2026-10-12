package com.ateagents.breakhub.domain;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ateagents.breakhub.config.ProductProperties;

@Service
public class InteractionRetentionService {

    private final JdbcTemplate jdbc;
    private final int maxRecords;

    public InteractionRetentionService(JdbcTemplate jdbc, ProductProperties properties) {
        this.jdbc = jdbc;
        this.maxRecords = properties.interaction().maxRecords();
    }

    @Transactional
    public int prune() {
        // Instant.toString() has variable fractional precision; strip Z for chronological prefix ordering.
        return jdbc.update("""
                DELETE FROM product_interaction
                WHERE interaction_id IN (
                    SELECT interaction.interaction_id
                    FROM product_interaction interaction
                    JOIN product_session session ON session.id = interaction.session_id
                    WHERE session.source = 'local' AND session.read_only = 0
                      AND interaction.lifecycle = 'completed'
                      AND NOT EXISTS (
                          SELECT 1 FROM product_pause pause
                          WHERE pause.interaction_id = interaction.interaction_id)
                    ORDER BY rtrim(interaction.before_at, 'Z'), interaction.rowid
                    LIMIT MAX(0, (
                        SELECT COUNT(*) FROM product_interaction counted
                        JOIN product_session counted_session ON counted_session.id = counted.session_id
                        WHERE counted_session.source = 'local' AND counted_session.read_only = 0
                    ) - ?)
                )
                """, maxRecords);
    }
}
