package com.ateagents.breakhub;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import com.ateagents.breakhub.domain.BreakpointService;
import com.ateagents.breakhub.domain.BreakpointService.BreakpointDefinition;
import com.ateagents.breakhub.domain.ControlIdentity;
import com.ateagents.breakhub.domain.CurrentSessionService;
import com.ateagents.breakhub.domain.DebugControlService;
import com.ateagents.breakhub.domain.InteractionObservationService;
import com.ateagents.breakhub.domain.PauseService;
import com.ateagents.breakhub.domain.SessionArchiveService;
import com.fasterxml.jackson.databind.ObjectMapper;

class InteractionRetentionTest {

    @Test
    void deletesOldestCompletedNonHitsByBeforeTimeThenInsertionOrder() throws Exception {
        try (Fixture fixture = Fixture.create(3)) {
            fixture.complete("first-inserted", "plain");
            fixture.complete("second-inserted", "plain");
            fixture.complete("third-inserted", "plain");
            fixture.jdbc.update("""
                    UPDATE product_interaction SET before_at = CASE interaction_id
                        WHEN 'first-inserted' THEN '2001-01-01T00:00:00.100Z'
                        WHEN 'second-inserted' THEN '2001-01-01T00:00:00Z'
                        ELSE '2001-01-01T00:00:00.100001Z' END
                    """);

            fixture.before("fourth", "plain");
            assertThat(fixture.ids()).containsExactly("first-inserted", "third-inserted", "fourth");
            fixture.after("fourth");
            fixture.complete("fifth", "plain");
            assertThat(fixture.ids()).containsExactly("third-inserted", "fourth", "fifth");
            fixture.jdbc.update("""
                    UPDATE product_interaction SET before_at = '2000-01-01T00:00:00Z'
                    WHERE interaction_id IN ('third-inserted', 'fourth')
                    """);
            fixture.complete("sixth", "plain");
            assertThat(fixture.ids()).containsExactly("fourth", "fifth", "sixth");
        }
    }

    @Test
    void protectsHistoricalBeforeAndAfterHitsAfterResolutionAndRuleDeletion() throws Exception {
        try (Fixture fixture = Fixture.create(3)) {
            String beforeRule = fixture.breakpoint("before-hit", "before");
            fixture.before("continued-hit", "before-hit");
            fixture.pauses.continuePause("continued-hit", "before");
            fixture.after("continued-hit");

            fixture.before("released-hit", "before-hit");
            fixture.pauses.safeRelease(fixture.sessions.current().sessionId(), "test_release");
            fixture.after("released-hit");

            fixture.before("timed-out-hit", "before-hit");
            fixture.jdbc.update("""
                    UPDATE product_pause SET paused_at = '2000-01-01T00:00:00Z'
                    WHERE interaction_id = 'timed-out-hit'
                    """);
            assertThat(fixture.observations.waitForRelease("timed-out-hit", "before"))
                    .containsEntry("result", "timed_out");
            fixture.after("timed-out-hit");
            fixture.breakpoints.delete(beforeRule);

            String afterRule = fixture.breakpoint("after-hit", "after");
            fixture.before("after-hit", "after-hit");
            assertThat(fixture.after("after-hit")).containsEntry("wait_required", true);
            fixture.pauses.continuePause("after-hit", "after");
            fixture.breakpoints.delete(afterRule);
            fixture.complete("new-non-hit", "plain");

            assertThat(fixture.ids())
                    .containsExactly("continued-hit", "released-hit", "timed-out-hit", "after-hit");
            assertThat(fixture.jdbc.queryForList(
                    "SELECT status FROM product_pause ORDER BY rowid", String.class))
                    .containsExactly("continued", "safe_released", "timed_out", "continued");
        }
    }

    @Test
    void keepsRunningCallsUntilCompletionCanDetermineAfterHits() throws Exception {
        try (Fixture fixture = Fixture.create(3)) {
            fixture.breakpoint("after-hit", "after");
            fixture.before("plain-running", "plain");
            for (String id : List.of("after-running-1", "after-running-2", "after-running-3")) {
                fixture.before(id, "after-hit");
            }
            assertThat(fixture.ids()).hasSize(4);
            assertThat(fixture.after("after-running-1")).containsEntry("wait_required", true);
            assertThat(fixture.ids()).hasSize(4);

            fixture.after("plain-running");
            assertThat(fixture.ids())
                    .containsExactly("after-running-1", "after-running-2", "after-running-3");
            assertThat(fixture.after("after-running-2")).containsEntry("wait_required", true);
            assertThat(fixture.jdbc.queryForObject("""
                    SELECT lifecycle FROM product_interaction WHERE interaction_id = 'after-running-3'
                    """, String.class)).isEqualTo("running");
        }
    }

    @Test
    void replayingRetainedBeforeAndAfterAtCapacityDoesNotDeleteNewerCalls() throws Exception {
        try (Fixture fixture = Fixture.create(3)) {
            for (String id : List.of("first", "second", "third")) {
                fixture.complete(id, "plain");
            }
            assertThat(fixture.before("first", "plain")).containsEntry("operation", "replayed");
            assertThat(fixture.after("first")).containsEntry("operation", "replayed");
            assertThat(fixture.ids()).containsExactly("first", "second", "third");
        }
    }

    @Test
    void restartAppliesLowerLimitAcrossLocalSessionsAndLeavesImportedArchiveUntouched() throws Exception {
        Path directory = Files.createTempDirectory("breakhub-retention-restart-");
        try (ReportingLeaseTestServer switchServer = ReportingLeaseTestServer.start()) {
            String importedSessionId;
            String storedArchiveJson;
            try (Fixture fixture = new Fixture(directory, switchServer, 20)) {
                fixture.complete("old-local-1", "plain");
                fixture.complete("old-local-2", "plain");
                SessionArchiveService archives = fixture.context.getBean(SessionArchiveService.class);
                importedSessionId = archives.importArchive(
                        archives.archive(fixture.sessions.current().sessionId())).sessionId();
                storedArchiveJson = fixture.jdbc.queryForObject("""
                        SELECT archive_json FROM product_session_archive WHERE session_id = ?
                        """, String.class, importedSessionId);
                fixture.control.stop(fixture.actor, fixture.sessions.current().sessionId());
                fixture.sessions.selectCurrent(fixture.sessions.create("第二个本机会话").sessionId());
                fixture.startDebugging();
                fixture.complete("new-local-1", "plain");
                fixture.complete("new-local-2", "plain");
                fixture.breakpoint("before-hit", "before");
                fixture.before("retained-hit", "before-hit");
                fixture.pauses.continuePause("retained-hit", "before");
                fixture.after("retained-hit");

            }
            try (Fixture restarted = new Fixture(directory, switchServer, 3)) {
                assertThat(restarted.ids()).containsExactly("new-local-1", "new-local-2", "retained-hit");
                assertThat(restarted.jdbc.queryForObject("""
                        SELECT archive_json FROM product_session_archive WHERE session_id = ?
                        """, String.class, importedSessionId)).isEqualTo(storedArchiveJson);
                assertThat(restarted.context.getBean(SessionArchiveService.class).archive(importedSessionId)
                        .path("interactions").findValuesAsText("interaction_id"))
                        .containsExactly("old-local-1", "old-local-2");
            }
        }
    }

    private static final class Fixture implements AutoCloseable {

        private final ReportingLeaseTestServer switchServer;
        private final boolean ownsSwitchServer;
        private final ConfigurableApplicationContext context;
        private final ControlIdentity actor = new ControlIdentity("gateway", "retention-test");
        private final ObjectMapper mapper;
        private final JdbcTemplate jdbc;
        private final CurrentSessionService sessions;
        private final DebugControlService control;
        private final InteractionObservationService observations;
        private final BreakpointService breakpoints;
        private final PauseService pauses;

        static Fixture create(int maxRecords) throws Exception {
            return new Fixture(Files.createTempDirectory("breakhub-retention-"),
                    ReportingLeaseTestServer.start(), maxRecords, true);
        }

        Fixture(Path directory, ReportingLeaseTestServer switchServer, int maxRecords) throws Exception {
            this(directory, switchServer, maxRecords, false);
        }

        private Fixture(Path directory, ReportingLeaseTestServer switchServer,
                int maxRecords, boolean ownsSwitchServer) throws Exception {
            this.switchServer = switchServer;
            this.ownsSwitchServer = ownsSwitchServer;
            Path config = directory.resolve("application.yml");
            Files.writeString(config, """
                    server:
                      address: 127.0.0.1
                      port: 0
                    breakhub:
                      data-directory: %s
                      equipment:
                        id: equipment-01
                        display-name: 一号装备
                        debugger-switch:
                          url: %s
                      security:
                        web-username: admin
                        web-password: admin-secret
                        gateway-token: gateway-secret
                        business-client-token: business-secret
                      control-lease:
                        timeout: 30m
                      interaction:
                        pause-timeout: 25m
                        max-payload-size: 16MB
                        max-records: %d
                    """.formatted(directory.resolve("data").toString().replace("\\", "/"),
                    switchServer.endpoint(), maxRecords), StandardCharsets.UTF_8);
            context = BreakHubApplication.application().run("--spring.config.location=" + config.toUri());
            mapper = context.getBean(ObjectMapper.class);
            jdbc = context.getBean(JdbcTemplate.class);
            sessions = context.getBean(CurrentSessionService.class);
            control = context.getBean(DebugControlService.class);
            observations = context.getBean(InteractionObservationService.class);
            breakpoints = context.getBean(BreakpointService.class);
            pauses = context.getBean(PauseService.class);
            startDebugging();
        }

        void startDebugging() {
            control.start(actor, () -> sessions.current().sessionId());
        }

        Map<String, Object> before(String id, String command) {
            return observations.before(id, "Power", command, mapper.createObjectNode());
        }

        Map<String, Object> after(String id) {
            return observations.after(id, mapper.createObjectNode());
        }

        void complete(String id, String command) {
            before(id, command);
            after(id);
        }

        String breakpoint(String command, String point) {
            return breakpoints.create(new BreakpointDefinition(
                    "保留命中", "Power", command, point, mapper.createArrayNode()))
                    .get("breakpoint_id").toString();
        }

        List<String> ids() {
            return jdbc.queryForList(
                    "SELECT interaction_id FROM product_interaction ORDER BY rowid", String.class);
        }

        @Override
        public void close() {
            context.close();
            if (ownsSwitchServer) {
                switchServer.close();
            }
        }
    }
}
