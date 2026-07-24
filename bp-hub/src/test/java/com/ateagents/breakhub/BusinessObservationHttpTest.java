package com.ateagents.breakhub;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.ateagents.breakhub.domain.ControlIdentity;
import com.ateagents.breakhub.domain.CurrentSessionService;
import com.ateagents.breakhub.domain.DebugControlService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class BusinessObservationHttpTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void completesPersistentBeforeBreakpointPauseAndContinueLoop() throws Exception {
        Path directory = Files.createTempDirectory("breakhub-basic-loop-");
        ReportingLeaseTestServer switchServer = startSwitchServer();
        ConfigurableApplicationContext context = start(writeConfig(directory, switchServer.getAddress().getPort()));
        try {
            URI base = base(context);
            WebClient web = login(base);
            BusinessSimulator business = new BusinessSimulator(base);

            HttpResponse<String> created = web.write("POST", "/api/v1/breakpoints", """
                    {"name":"","object":"Power","command":"set","pause_point":"before","conditions":[]}
                    """);
            assertThat(created.statusCode()).isEqualTo(200);
            JsonNode breakpoint = json(created);
            String breakpointId = breakpoint.path("breakpoint_id").asText();
            assertThat(breakpointId).isNotBlank();
            assertThat(breakpoint.path("name").asText()).contains("Power.set", "接口断点");
            assertThat(breakpoint.path("enabled").asBoolean()).isTrue();
            assertThat(breakpoint.path("conditions")).isEmpty();

            JsonNode unseenInterface = web.readJson("/api/v1/interfaces/detail?object=Power&command=set");
            assertThat(unseenInterface.path("interaction_count").asInt()).isZero();
            assertThat(unseenInterface.path("breakpoint_count").asInt()).isEqualTo(1);

            HttpResponse<String> edited = web.write("PATCH", "/api/v1/breakpoints/" + breakpointId, """
                    {"name":"Power 设置断点","object":"Power","command":"set","pause_point":"before","conditions":[]}
                    """);
            assertThat(edited.statusCode()).isEqualTo(200);
            assertThat(json(edited).path("breakpoint_id").asText()).isEqualTo(breakpointId);
            assertThat(json(edited).path("name").asText()).isEqualTo("Power 设置断点");

            assertThat(web.write("POST", "/api/v1/breakpoints/" + breakpointId + "/disable", null).statusCode())
                    .isEqualTo(200);
            assertThat(web.write("POST", "/api/v1/debugging/start", null).statusCode()).isEqualTo(200);
            JsonNode disabledBefore = json(business.before("""
                    {"interaction_id":"disabled-before","object":"Power","command":"set","params":{"voltage":9}}
                    """));
            assertThat(disabledBefore.path("wait_required").asBoolean()).isFalse();

            assertThat(web.write("POST", "/api/v1/breakpoints/" + breakpointId + "/enable", null).statusCode())
                    .isEqualTo(200);
            JsonNode pausedBefore = json(business.before("""
                    {"interaction_id":"paused-before","object":"Power","command":"set","params":{"voltage":12}}
                    """));
            assertThat(pausedBefore.path("wait_required").asBoolean()).isTrue();
            assertThat(pausedBefore.path("proceed").asBoolean()).isFalse();

            var waiting = business.waitForReleaseAsync("""
                    {"interaction_id":"paused-before","pause_point":"before"}
                    """);
            Thread.sleep(200);
            assertThat(waiting).isNotDone();

            JsonNode pausedInteraction = web.readJson("/api/v1/interactions/paused-before");
            assertThat(pausedInteraction.path("lifecycle").asText()).isEqualTo("running");
            assertThat(pausedInteraction.at("/current_pause/pause_point").asText()).isEqualTo("before");
            assertThat(pausedInteraction.at("/current_pause/status").asText()).isEqualTo("paused");
            assertThat(pausedInteraction.at("/current_pause/breakpoint_snapshots/0/breakpoint_id").asText())
                    .isEqualTo(breakpointId);
            assertThat(pausedInteraction.at("/current_pause/breakpoint_snapshots/0/name").asText())
                    .isEqualTo("Power 设置断点");
            assertThat(pausedInteraction.toString()).doesNotContain("pause_id");
            assertThat(web.readJson("/api/v1/interactions").at("/items/0/interaction_id").asText())
                    .isEqualTo("paused-before");

            assertThat(web.write("PATCH", "/api/v1/breakpoints/" + breakpointId, """
                    {"name":"命中后改名","object":"Power","command":"set","pause_point":"before","conditions":[]}
                    """).statusCode()).isEqualTo(200);
            assertThat(web.readJson("/api/v1/interactions/paused-before")
                    .at("/current_pause/breakpoint_snapshots/0/name").asText()).isEqualTo("Power 设置断点");

            HttpResponse<String> continued = web.write(
                    "POST", "/api/v1/interactions/paused-before/continue", """
                            {"pause_point":"before"}
                            """);
            assertThat(continued.statusCode()).isEqualTo(200);
            assertThat(json(continued).path("continued").asBoolean()).isTrue();
            JsonNode released = json(waiting.get(5, TimeUnit.SECONDS));
            assertThat(released.path("released").asBoolean()).isTrue();
            assertThat(released.path("result").asText()).isEqualTo("continued");
            assertThat(business.after("""
                    {"interaction_id":"paused-before","result":{"accepted":true}}
                    """).statusCode()).isEqualTo(200);

            assertThat(json(business.before("""
                    {"interaction_id":"stopped-pause","object":"Power","command":"set","params":{"voltage":1}}
                    """)).path("wait_required").asBoolean()).isTrue();
            assertThat(web.write("POST", "/api/v1/interactions/stopped-pause/inject", """
                    {"pause_point":"before","changes":{"voltage":2}}
                    """).statusCode()).isEqualTo(200);
            var stoppedWaiting = business.waitForReleaseAsync("""
                    {"interaction_id":"stopped-pause","pause_point":"before"}
                    """);
            Thread.sleep(100);
            assertThat(stoppedWaiting).isNotDone();
            assertThat(web.write("POST", "/api/v1/debugging/stop", null).statusCode()).isEqualTo(200);
            JsonNode stoppedRelease = json(stoppedWaiting.get(5, TimeUnit.SECONDS));
            assertThat(stoppedRelease.path("result").asText()).isEqualTo("safe_released");
            assertThat(stoppedRelease.path("content")).isEqualTo(objectMapper.readTree("{\"voltage\":1}"));
            JsonNode stoppedPause = web.readJson("/api/v1/interactions/stopped-pause").at("/pauses/0");
            assertThat(stoppedPause.path("injection_status").asText()).isEqualTo("discarded");
            assertThat(stoppedPause.path("resolution").asText()).isEqualTo("debug_stopped");
            assertNoPaused(web);
            assertThat(web.readJson("/api/v1/breakpoints/" + breakpointId).path("name").asText())
                    .isEqualTo("命中后改名");
            assertThat(web.write("POST", "/api/v1/debugging/start", null).statusCode()).isEqualTo(200);
            assertThat(json(business.before("""
                    {"interaction_id":"paused-after-restart","object":"Power","command":"set","params":{"voltage":3}}
                    """)).path("wait_required").asBoolean()).isTrue();
            assertThat(web.write("POST", "/api/v1/interactions/paused-after-restart/inject", """
                    {"pause_point":"before","changes":{"voltage":4}}
                    """).statusCode()).isEqualTo(200);
            var releasedWaiting = business.waitForReleaseAsync("""
                    {"interaction_id":"paused-after-restart","pause_point":"before"}
                    """);
            Thread.sleep(100);
            assertThat(releasedWaiting).isNotDone();
            assertThat(web.write("POST", "/api/v1/control/release", null).statusCode()).isEqualTo(200);
            JsonNode controlRelease = json(releasedWaiting.get(5, TimeUnit.SECONDS));
            assertThat(controlRelease.path("result").asText()).isEqualTo("safe_released");
            assertThat(controlRelease.path("content")).isEqualTo(objectMapper.readTree("{\"voltage\":3}"));
            JsonNode controlPause = web.readJson("/api/v1/interactions/paused-after-restart").at("/pauses/0");
            assertThat(controlPause.path("injection_status").asText()).isEqualTo("discarded");
            assertThat(controlPause.path("resolution").asText()).isEqualTo("control_released");
            assertNoPaused(web);
            assertThat(web.write("POST", "/api/v1/debugging/start", null).statusCode()).isEqualTo(200);

            assertThat(web.write("POST", "/api/v1/breakpoints/" + breakpointId + "/disable", null).statusCode())
                    .isEqualTo(200);
            assertThat(json(business.before("""
                    {"interaction_id":"disabled-after-restart","object":"Power","command":"set","params":{}}
                    """)).path("wait_required").asBoolean()).isFalse();
            assertThat(web.write("POST", "/api/v1/breakpoints/" + breakpointId + "/enable", null).statusCode())
                    .isEqualTo(200);
            assertThat(web.write("DELETE", "/api/v1/breakpoints/" + breakpointId, null).statusCode()).isEqualTo(200);
            assertThat(json(business.before("""
                    {"interaction_id":"deleted-breakpoint","object":"Power","command":"set","params":{}}
                    """)).path("wait_required").asBoolean()).isFalse();
        } finally {
            context.close();
            switchServer.stop(0);
        }
    }

    @Test
    void stopReleasesPausedBusinessBeforeABlackholedSwitchReturns() throws Exception {
        Path directory = Files.createTempDirectory("breakhub-local-first-blackhole-");
        CountDownLatch disableStarted = new CountDownLatch(1);
        CountDownLatch allowDisableToFinish = new CountDownLatch(1);
        ReportingLeaseTestServer switchServer = startBlockingDisableSwitchServer(
                disableStarted, allowDisableToFinish);
        ConfigurableApplicationContext context = start(writeConfig(directory, switchServer.getAddress().getPort()));
        ExecutorService controlRequest = Executors.newSingleThreadExecutor();
        try {
            URI base = base(context);
            WebClient web = login(base);
            BusinessSimulator business = new BusinessSimulator(base);
            assertThat(web.write("POST", "/api/v1/breakpoints", """
                    {"object":"Power","command":"blackhole","pause_point":"before","conditions":[]}
                    """).statusCode()).isEqualTo(200);
            assertThat(web.write("POST", "/api/v1/debugging/start", null).statusCode()).isEqualTo(200);
            assertThat(json(business.before("""
                    {"interaction_id":"blackhole-pause","object":"Power","command":"blackhole","params":{"mode":"original"}}
                    """)).path("wait_required").asBoolean()).isTrue();
            assertThat(web.write("POST", "/api/v1/interactions/blackhole-pause/inject", """
                    {"pause_point":"before","changes":{"mode":"modified"}}
                    """).statusCode()).isEqualTo(200);
            var waiting = business.waitForReleaseAsync("""
                    {"interaction_id":"blackhole-pause","pause_point":"before"}
                    """);
            Thread.sleep(100);
            assertThat(waiting).isNotDone();

            Future<HttpResponse<String>> stopping = controlRequest.submit(
                    () -> web.write("POST", "/api/v1/debugging/stop", null));
            assertThat(disableStarted.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(stopping).isNotDone();

            JsonNode released = json(waiting.get(1, TimeUnit.SECONDS));
            assertThat(released.path("result").asText()).isEqualTo("safe_released");
            assertThat(released.path("content")).isEqualTo(objectMapper.readTree("{\"mode\":\"original\"}"));

            allowDisableToFinish.countDown();
            assertThat(stopping.get(2, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
            JsonNode pause = web.readJson("/api/v1/interactions/blackhole-pause").at("/pauses/0");
            assertThat(pause.path("injection_status").asText()).isEqualTo("discarded");
            assertThat(pause.path("resolution").asText()).isEqualTo("debug_stopped");
        } finally {
            allowDisableToFinish.countDown();
            controlRequest.shutdownNow();
            context.close();
            switchServer.stop(0);
        }
    }

    @Test
    void controlReleaseCompletesLocalSafetyWhenTheSwitchIsUnreachable() throws Exception {
        Path directory = Files.createTempDirectory("breakhub-local-first-unreachable-");
        ReportingLeaseTestServer switchServer = startSwitchServer();
        boolean switchStopped = false;
        ConfigurableApplicationContext context = start(writeConfig(directory, switchServer.getAddress().getPort()));
        try {
            URI base = base(context);
            WebClient web = login(base);
            BusinessSimulator business = new BusinessSimulator(base);
            assertThat(web.write("POST", "/api/v1/breakpoints", """
                    {"object":"Power","command":"unreachable","pause_point":"before","conditions":[]}
                    """).statusCode()).isEqualTo(200);
            assertThat(web.write("POST", "/api/v1/debugging/start", null).statusCode()).isEqualTo(200);
            assertThat(json(business.before("""
                    {"interaction_id":"unreachable-pause","object":"Power","command":"unreachable","params":{"mode":"original"}}
                    """)).path("wait_required").asBoolean()).isTrue();
            assertThat(web.write("POST", "/api/v1/interactions/unreachable-pause/inject", """
                    {"pause_point":"before","changes":{"mode":"modified"}}
                    """).statusCode()).isEqualTo(200);
            var waiting = business.waitForReleaseAsync("""
                    {"interaction_id":"unreachable-pause","pause_point":"before"}
                    """);
            Thread.sleep(100);
            assertThat(waiting).isNotDone();

            switchServer.stop(0);
            switchStopped = true;
            assertThat(web.write("POST", "/api/v1/control/release", null).statusCode()).isEqualTo(200);

            JsonNode released = json(waiting.get(2, TimeUnit.SECONDS));
            assertThat(released.path("result").asText()).isEqualTo("safe_released");
            assertThat(released.path("content")).isEqualTo(objectMapper.readTree("{\"mode\":\"original\"}"));
            JsonNode pause = web.readJson("/api/v1/interactions/unreachable-pause").at("/pauses/0");
            assertThat(pause.path("injection_status").asText()).isEqualTo("discarded");
            assertThat(pause.path("resolution").asText()).isEqualTo("control_released");
            assertThat(web.readJson("/api/v1/overview").at("/debugging/status").asText()).isEqualTo("idle");
            assertThat(web.readJson("/api/v1/overview").at("/control/held").asBoolean()).isFalse();
        } finally {
            context.close();
            if (!switchStopped) {
                switchServer.stop(0);
            }
        }
    }

    @Test
    void failedPausePersistenceStillConvergesDebuggingAndStopsReporting() throws Exception {
        Path directory = Files.createTempDirectory("breakhub-local-first-database-");
        ReportingLeaseTestServer switchServer = startSwitchServer();
        ConfigurableApplicationContext context = start(writeConfig(directory, switchServer.getAddress().getPort()));
        try {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            DebugControlService controls = context.getBean(DebugControlService.class);
            CurrentSessionService sessions = context.getBean(CurrentSessionService.class);
            ControlIdentity actor = new ControlIdentity("mcp", "database-failure-test");
            String sessionId = sessions.current().sessionId();
            assertThat(controls.start(actor, () -> sessionId)).containsEntry("result", "started");

            jdbc.execute("ALTER TABLE product_pause RENAME TO product_pause_unavailable");
            try {
                assertThatThrownBy(() -> controls.stop(actor, sessionId))
                        .isInstanceOf(DataAccessException.class)
                        .hasMessageContaining("product_pause");
                assertThat(controls.activeDebuggingSession()).isEmpty();
            } finally {
                jdbc.execute("ALTER TABLE product_pause_unavailable RENAME TO product_pause");
            }

            assertThat(controls.debuggingSnapshot(sessionId)).containsEntry("status", "idle");
            assertThat(controls.controlSnapshot(java.util.Optional.of(actor))).containsEntry("held", true);
            assertThat(switchServer.activeLeaseId()).isNull();
            assertThat(switchServer.lastStoppedLeaseId()).isNotBlank();

            assertThat(controls.stop(actor, sessionId)).containsEntry("result", "already_stopped");
            assertThat(controls.activeDebuggingSession()).isEmpty();
        } finally {
            context.close();
            switchServer.stop(0);
        }
    }

    @Test
    void pausesAfterOnlyWhenExplicitResultConditionMatches() throws Exception {
        Path directory = Files.createTempDirectory("breakhub-result-condition-");
        ReportingLeaseTestServer switchServer = startSwitchServer();
        ConfigurableApplicationContext context = start(writeConfig(directory, switchServer.getAddress().getPort()));
        try {
            URI base = base(context);
            WebClient web = login(base);
            BusinessSimulator business = new BusinessSimulator(base);

            JsonNode breakpoint = json(web.write("POST", "/api/v1/breakpoints", """
                    {
                      "name":"失败结果断点",
                      "object":"Power",
                      "command":"result-condition",
                      "pause_point":"after",
                      "conditions":[
                        {"source":"result","field_path":"status","operator":"eq","value":"failed"}
                      ]
                    }
                    """));
            assertThat(breakpoint.at("/conditions/0/source").asText()).isEqualTo("result");

            assertThat(web.write("POST", "/api/v1/debugging/start", null).statusCode()).isEqualTo(200);
            assertThat(json(business.before("""
                    {"interaction_id":"result-miss","object":"Power","command":"result-condition","params":{}}
                    """)).path("wait_required").asBoolean()).isFalse();
            assertThat(json(business.after("""
                    {"interaction_id":"result-miss","result":{"status":"ok"}}
                    """)).path("wait_required").asBoolean()).isFalse();

            assertThat(json(business.before("""
                    {"interaction_id":"result-match","object":"Power","command":"result-condition","params":{}}
                    """)).path("wait_required").asBoolean()).isFalse();
            assertThat(json(business.after("""
                    {"interaction_id":"result-match","result":{"status":"failed"}}
                    """)).path("wait_required").asBoolean()).isTrue();
            assertThat(web.readJson("/api/v1/interactions/result-match")
                    .at("/current_pause/pause_point").asText()).isEqualTo("after");
        } finally {
            context.close();
            switchServer.stop(0);
        }
    }

    @Test
    void rejectsMissingOrNonExactConditionSourcesOnNewWrites() throws Exception {
        Path directory = Files.createTempDirectory("breakhub-condition-source-");
        ReportingLeaseTestServer switchServer = startSwitchServer();
        ConfigurableApplicationContext context = start(writeConfig(directory, switchServer.getAddress().getPort()));
        try {
            WebClient web = login(base(context));

            HttpResponse<String> response = web.write("POST", "/api/v1/breakpoints", """
                    {
                      "object":"Power",
                      "command":"missing-source",
                      "pause_point":"after",
                      "conditions":[
                        {"field_path":"status","operator":"eq","value":"failed"}
                      ]
                    }
                    """);

            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(json(response).path("code").asText()).isEqualTo("INVALID_BREAKPOINT_CONDITION");

            HttpResponse<String> paddedSource = web.write("POST", "/api/v1/breakpoints", """
                    {
                      "object":"Power",
                      "command":"padded-source",
                      "pause_point":"after",
                      "conditions":[
                        {"source":" result ","field_path":"status","operator":"eq","value":"failed"}
                      ]
                    }
                    """);
            assertThat(paddedSource.statusCode()).isEqualTo(400);
            assertThat(json(paddedSource).path("code").asText()).isEqualTo("INVALID_BREAKPOINT_CONDITION");
        } finally {
            context.close();
            switchServer.stop(0);
        }
    }

    @Test
    void discardsResultConditionsFromBeforeWritesAndReturnsWriteMetadata() throws Exception {
        Path directory = Files.createTempDirectory("breakhub-before-discard-");
        ReportingLeaseTestServer switchServer = startSwitchServer();
        ConfigurableApplicationContext context = start(writeConfig(directory, switchServer.getAddress().getPort()));
        try {
            URI base = base(context);
            WebClient web = login(base);
            BusinessSimulator business = new BusinessSimulator(base);
            String mixedRequest = """
                    {
                      "name":"混合来源 before",
                      "object":"Power",
                      "command":"discard-partial",
                      "pause_point":"before",
                      "conditions":[
                        {"source":"params","field_path":"mode","operator":"eq","value":"safe"},
                        {"source":"result","field_path":"status","operator":"eq","value":"ready"}
                      ]
                    }
                    """;

            JsonNode created = json(web.write("POST", "/api/v1/breakpoints", mixedRequest));
            assertThat(created.path("created").asBoolean()).isTrue();
            assertThat(created.path("conditions")).hasSize(1);
            assertThat(created.at("/conditions/0/source").asText()).isEqualTo("params");
            assertThat(created.path("discarded_conditions")).hasSize(1);
            assertThat(created.at("/discarded_conditions/0")).isEqualTo(objectMapper.readTree("""
                    {"source":"result","field_path":"status","operator":"eq","value":"ready"}
                    """));

            JsonNode repeated = json(web.write("POST", "/api/v1/breakpoints", mixedRequest));
            assertThat(repeated.path("created").asBoolean()).isFalse();
            assertThat(repeated.path("breakpoint_id").asText()).isEqualTo(created.path("breakpoint_id").asText());
            assertThat(repeated.path("discarded_conditions")).hasSize(1);
            assertThat(web.readJson("/api/v1/breakpoints/" + created.path("breakpoint_id").asText())
                    .has("discarded_conditions")).isFalse();

            JsonNode allDiscarded = json(web.write("POST", "/api/v1/breakpoints", """
                    {
                      "object":"Power",
                      "command":"discard-all",
                      "pause_point":"before",
                      "conditions":[
                        {"source":"result","field_path":"status","operator":"eq","value":"ready"}
                      ]
                    }
                    """));
            assertThat(allDiscarded.path("conditions")).isEmpty();
            assertThat(allDiscarded.path("discarded_conditions")).hasSize(1);

            JsonNode after = json(web.write("POST", "/api/v1/breakpoints", """
                    {
                      "name":"改为 before",
                      "object":"Power",
                      "command":"discard-update",
                      "pause_point":"after",
                      "conditions":[
                        {"source":"params","field_path":"mode","operator":"eq","value":"safe"},
                        {"source":"result","field_path":"status","operator":"eq","value":"ready"}
                      ]
                    }
                    """));
            assertThat(after.path("discarded_conditions")).isEmpty();
            JsonNode updated = json(web.write(
                    "PATCH",
                    "/api/v1/breakpoints/" + after.path("breakpoint_id").asText(),
                    "{\"pause_point\":\"before\"}"));
            assertThat(updated.path("conditions")).hasSize(1);
            assertThat(updated.path("discarded_conditions")).hasSize(1);
            JsonNode noDiscard = json(web.write(
                    "PATCH",
                    "/api/v1/breakpoints/" + after.path("breakpoint_id").asText(),
                    "{\"name\":\"仍是 before\"}"));
            assertThat(noDiscard.path("discarded_conditions")).isEmpty();

            assertThat(web.write("POST", "/api/v1/debugging/start", null).statusCode()).isEqualTo(200);
            assertThat(json(business.before("""
                    {"interaction_id":"discard-all-call","object":"Power","command":"discard-all","params":{}}
                    """)).path("wait_required").asBoolean()).isTrue();
        } finally {
            context.close();
            switchServer.stop(0);
        }
    }

    @Test
    void validatesCanonicalConditionsAndMatchesJsonTypesPrecisely() throws Exception {
        Path directory = Files.createTempDirectory("breakhub-conditions-");
        ReportingLeaseTestServer switchServer = startSwitchServer();
        ConfigurableApplicationContext context = start(writeConfig(directory, switchServer.getAddress().getPort()));
        try {
            URI base = base(context);
            WebClient web = login(base);
            BusinessSimulator business = new BusinessSimulator(base);

            HttpResponse<String> created = web.write("POST", "/api/v1/breakpoints", """
                    {
                      "name":"精确参数断点",
                      "object":"Power",
                      "command":"strict",
                      "pause_point":"before",
                      "conditions":[
                        {"source":"params","field_path":"amount","operator":"eq","value":9007199254740993},
                        {"source":"params","field_path":"enabled","operator":"eq","value":true}
                      ]
                    }
                    """);
            assertThat(created.statusCode()).isEqualTo(200);
            assertThat(json(created).path("created").asBoolean()).isTrue();
            String strictId = json(created).path("breakpoint_id").asText();

            HttpResponse<String> reordered = web.write("POST", "/api/v1/breakpoints", """
                    {
                      "name":"精确参数断点",
                      "object":"Power",
                      "command":"strict",
                      "pause_point":"before",
                      "conditions":[
                        {"source":"params","field_path":"enabled","operator":"eq","value":true},
                        {"source":"params","field_path":"amount","operator":"eq","value":9007199254740993}
                      ]
                    }
                    """);
            assertThat(reordered.statusCode()).isEqualTo(200);
            assertThat(json(reordered).path("created").asBoolean()).isFalse();
            assertThat(json(reordered).path("breakpoint_id").asText()).isEqualTo(strictId);
            assertThat(web.readJson("/api/v1/breakpoints").path("items")).hasSize(1);

            assertThat(web.write("POST", "/api/v1/breakpoints/" + strictId + "/disable", null).statusCode())
                    .isEqualTo(200);
            HttpResponse<String> disabledRetry = web.write("POST", "/api/v1/breakpoints", """
                    {
                      "name":"精确参数断点",
                      "object":"Power",
                      "command":"strict",
                      "pause_point":"before",
                      "conditions":[
                        {"source":"params","field_path":"amount","operator":"eq","value":9007199254740993},
                        {"source":"params","field_path":"enabled","operator":"eq","value":true}
                      ]
                    }
                    """);
            assertThat(disabledRetry.statusCode()).isEqualTo(200);
            assertThat(json(disabledRetry).path("created").asBoolean()).isFalse();
            assertThat(json(disabledRetry).path("breakpoint_id").asText()).isEqualTo(strictId);
            assertThat(json(disabledRetry).path("enabled").asBoolean()).isFalse();
            assertThat(web.write("POST", "/api/v1/breakpoints/" + strictId + "/enable", null).statusCode())
                    .isEqualTo(200);

            HttpResponse<String> contains = web.write("POST", "/api/v1/breakpoints", """
                    {
                      "name":"数组成员断点",
                      "object":"Power",
                      "command":"channels",
                      "pause_point":"before",
                      "conditions":[
                        {"source":"params","field_path":"channels","operator":"contains_any","value":[2,3]}
                      ]
                    }
                    """);
            assertThat(contains.statusCode()).isEqualTo(200);
            String containsId = json(contains).path("breakpoint_id").asText();
            String nullableId = json(web.write("POST", "/api/v1/breakpoints", """
                    {"name":"空值断点","object":"Power","command":"nullable","pause_point":"before","conditions":[
                      {"source":"params","field_path":"optional","operator":"eq","value":null}
                    ]}
                    """)).path("breakpoint_id").asText();
            assertThat(nullableId).isNotBlank();

            assertThat(web.write("POST", "/api/v1/breakpoints", """
                    {"object":"Power","command":"bad","pause_point":"before","conditions":[
                      {"source":"params","field_path":"payload","operator":"eq","value":{"nested":true}}
                    ]}
                    """).statusCode()).isEqualTo(400);
            assertThat(web.write("POST", "/api/v1/breakpoints", """
                    {"object":"Power","command":"bad","pause_point":"before","conditions":[
                      {"source":"params","field_path":"channels","operator":"contains_any","value":[]}
                    ]}
                    """).statusCode()).isEqualTo(400);
            assertThat(web.write("POST", "/api/v1/breakpoints", """
                    {"object":"Power","command":"bad","pause_point":"before","conditions":[
                      {"source":"params","field_path":"items.0.id","operator":"eq","value":1}
                    ]}
                    """).statusCode()).isEqualTo(400);
            for (String invalidPath : List.of(
                    "$.amount", "@", "request\\mode", "request.*", "request/value")) {
                var invalidPathRequest = objectMapper.createObjectNode();
                invalidPathRequest.put("object", "Power");
                invalidPathRequest.put("command", "bad-path");
                invalidPathRequest.put("pause_point", "before");
                invalidPathRequest.putArray("conditions").addObject()
                        .put("source", "params")
                        .put("field_path", invalidPath)
                        .put("operator", "eq")
                        .put("value", 1);
                assertThat(web.write(
                        "POST",
                        "/api/v1/breakpoints",
                        objectMapper.writeValueAsString(invalidPathRequest)).statusCode()).isEqualTo(400);
            }

            assertThat(web.write("POST", "/api/v1/debugging/start", null).statusCode()).isEqualTo(200);
            assertThat(json(business.before("""
                    {"interaction_id":"numeric-mismatch","object":"Power","command":"strict","params":{"amount":9007199254740992,"enabled":true}}
                    """)).path("wait_required").asBoolean()).isFalse();
            assertThat(json(business.before("""
                    {"interaction_id":"type-mismatch","object":"Power","command":"strict","params":{"amount":9007199254740993,"enabled":"true"}}
                    """)).path("wait_required").asBoolean()).isFalse();

            assertThat(json(business.before("""
                    {"interaction_id":"strict-match","object":"Power","command":"strict","params":{"amount":9007199254740993.0,"enabled":true}}
                    """)).path("wait_required").asBoolean()).isTrue();
            assertThat(web.write("POST", "/api/v1/interactions/strict-match/continue", """
                    {"pause_point":"before"}
                    """).statusCode()).isEqualTo(200);

            assertThat(json(business.before("""
                    {"interaction_id":"contains-scalar","object":"Power","command":"channels","params":{"channels":2}}
                    """)).path("wait_required").asBoolean()).isFalse();
            assertThat(json(business.before("""
                    {"interaction_id":"contains-type-mismatch","object":"Power","command":"channels","params":{"channels":["2"]}}
                    """)).path("wait_required").asBoolean()).isFalse();
            assertThat(json(business.before("""
                    {"interaction_id":"contains-match","object":"Power","command":"channels","params":{"channels":[1,2]}}
                    """)).path("wait_required").asBoolean()).isTrue();
            assertThat(web.write("POST", "/api/v1/interactions/contains-match/continue", """
                    {"pause_point":"before"}
                    """).statusCode()).isEqualTo(200);
            assertThat(json(business.before("""
                    {"interaction_id":"null-missing","object":"Power","command":"nullable","params":{}}
                    """)).path("wait_required").asBoolean()).isFalse();
            assertThat(json(business.before("""
                    {"interaction_id":"null-match","object":"Power","command":"nullable","params":{"optional":null}}
                    """)).path("wait_required").asBoolean()).isTrue();

            JsonNode strict = web.readJson("/api/v1/breakpoints/" + strictId);
            assertThat(strict.path("hit_count").asInt()).isEqualTo(1);
            assertThat(strict.path("last_hit_at").asText()).isNotBlank();
            JsonNode containsBreakpoint = web.readJson("/api/v1/breakpoints/" + containsId);
            assertThat(containsBreakpoint.path("hit_count").asInt()).isEqualTo(1);
            assertThat(web.readJson("/api/v1/breakpoints/" + nullableId).path("hit_count").asInt())
                    .isEqualTo(1);
        } finally {
            context.close();
            switchServer.stop(0);
        }
    }

    @Test
    void combinesOriginalParamsAndResultSemanticsWithoutDuplicatingAfterHits() throws Exception {
        Path directory = Files.createTempDirectory("breakhub-multiple-after-");
        ReportingLeaseTestServer switchServer = startSwitchServer();
        ConfigurableApplicationContext context = start(writeConfig(directory, switchServer.getAddress().getPort()));
        try {
            URI base = base(context);
            WebClient web = login(base);
            BusinessSimulator business = new BusinessSimulator(base);

            String modeBeforeId = json(web.write("POST", "/api/v1/breakpoints", """
                    {"name":"安全模式前暂停","object":"Mixer","command":"blend","pause_point":"before","conditions":[
                      {"source":"params","field_path":"request.mode","operator":"eq","value":"safe"}
                    ]}
                    """)).path("breakpoint_id").asText();
            String tagsBeforeId = json(web.write("POST", "/api/v1/breakpoints", """
                    {"name":"红色标签前暂停","object":"Mixer","command":"blend","pause_point":"before","conditions":[
                      {"source":"params","field_path":"tags","operator":"contains_any","value":["red"]}
                    ]}
                    """)).path("breakpoint_id").asText();
            String afterId = json(web.write("POST", "/api/v1/breakpoints", """
                    {"name":"安全且成功后暂停","object":"Mixer","command":"blend","pause_point":"after","conditions":[
                      {"source":"params","field_path":"request.mode","operator":"eq","value":"safe"},
                      {"source":"result","field_path":"accepted","operator":"eq","value":true},
                      {"source":"result","field_path":"optional","operator":"eq","value":null},
                      {"source":"result","field_path":"amount","operator":"eq","value":9007199254740993.0}
                    ]}
                    """)).path("breakpoint_id").asText();
            String resultTagsAfterId = json(web.write("POST", "/api/v1/breakpoints", """
                    {"name":"结果标签后暂停","object":"Mixer","command":"blend","pause_point":"after","conditions":[
                      {"source":"result","field_path":"tags","operator":"contains_any","value":["red",2,"red"]}
                    ]}
                    """)).path("breakpoint_id").asText();
            String unmatchedAfterId = json(web.write("POST", "/api/v1/breakpoints", """
                    {"name":"失败条件不留审计","object":"Mixer","command":"blend","pause_point":"after","conditions":[
                      {"source":"result","field_path":"accepted","operator":"eq","value":false}
                    ]}
                    """)).path("breakpoint_id").asText();
            String unconditionalRootAfterId = json(web.write("POST", "/api/v1/breakpoints", """
                    {"name":"任意根结果后暂停","object":"Mixer","command":"root","pause_point":"after","conditions":[]}
                    """)).path("breakpoint_id").asText();
            JsonNode sourceIdentity = json(web.write("POST", "/api/v1/breakpoints", """
                    {"name":"同路径不同来源","object":"Identity","command":"same-path","pause_point":"after","conditions":[
                      {"source":"params","field_path":"status","operator":"eq","value":"ready"},
                      {"source":"result","field_path":"status","operator":"eq","value":"ready"}
                    ]}
                    """));
            assertThat(modeBeforeId).isNotBlank();
            assertThat(tagsBeforeId).isNotBlank();
            assertThat(afterId).isNotBlank();
            assertThat(afterId).isNotEqualTo(modeBeforeId);
            assertThat(resultTagsAfterId).isNotBlank();
            assertThat(unmatchedAfterId).isNotBlank();
            assertThat(unconditionalRootAfterId).isNotBlank();
            assertThat(sourceIdentity.path("conditions")).hasSize(2);
            assertThat(sourceIdentity.at("/conditions/0/source").asText()).isEqualTo("params");
            assertThat(sourceIdentity.at("/conditions/1/source").asText()).isEqualTo("result");

            assertThat(web.write("POST", "/api/v1/debugging/start", null).statusCode()).isEqualTo(200);
            JsonNode before = json(business.before("""
                    {"interaction_id":"multiple-stage","object":"Mixer","command":"blend","params":{"request":{"mode":"safe"},"tags":["red","green"]}}
                    """));
            assertThat(before.path("wait_required").asBoolean()).isTrue();
            JsonNode beforeSnapshots = web.readJson("/api/v1/interactions/multiple-stage")
                    .at("/current_pause/breakpoint_snapshots");
            assertThat(beforeSnapshots).hasSize(2);
            assertThat(beforeSnapshots.get(0).path("breakpoint_id").asText()).isEqualTo(modeBeforeId);
            assertThat(beforeSnapshots.get(1).path("breakpoint_id").asText()).isEqualTo(tagsBeforeId);
            assertThat(beforeSnapshots.get(0).path("condition_evidence")).isEqualTo(objectMapper.readTree("""
                    [{"source":"params","field_path":"request.mode","operator":"eq","expected_value":"safe","actual_value":"safe"}]
                    """));
            assertThat(beforeSnapshots.get(1).path("condition_evidence")).isEqualTo(objectMapper.readTree("""
                    [{"source":"params","field_path":"tags","operator":"contains_any","expected_value":["red"],"actual_value":["red"]}]
                    """));
            assertThat(web.write("POST", "/api/v1/interactions/multiple-stage/inject", """
                    {"pause_point":"before","changes":{"request":{"mode":"unsafe"}}}
                    """).statusCode()).isEqualTo(200);
            assertThat(web.write("POST", "/api/v1/interactions/multiple-stage/continue", """
                    {"pause_point":"before"}
                    """).statusCode()).isEqualTo(200);
            assertThat(web.write("POST", "/api/v1/breakpoints/" + modeBeforeId + "/disable", null).statusCode())
                    .isEqualTo(200);
            assertThat(web.write("POST", "/api/v1/breakpoints/" + tagsBeforeId + "/disable", null).statusCode())
                    .isEqualTo(200);

            JsonNode after = json(business.after("""
                    {"interaction_id":"multiple-stage","result":{"accepted":true,"optional":null,"amount":9007199254740993,"tags":["blue","red"]}}
                    """));
            assertThat(after.path("operation").asText()).isEqualTo("completed");
            assertThat(after.path("wait_required").asBoolean()).isTrue();
            assertThat(after.path("proceed").asBoolean()).isFalse();
            JsonNode afterInteraction = web.readJson("/api/v1/interactions/multiple-stage");
            assertThat(afterInteraction.path("lifecycle").asText()).isEqualTo("completed");
            assertThat(afterInteraction.at("/current_pause/pause_point").asText()).isEqualTo("after");
            assertThat(afterInteraction.at("/current_pause/breakpoint_snapshots")).hasSize(2);
            assertThat(afterInteraction.at("/current_pause/breakpoint_snapshots/0/breakpoint_id").asText())
                    .isEqualTo(afterId);
            assertThat(afterInteraction.at("/current_pause/breakpoint_snapshots/1/breakpoint_id").asText())
                    .isEqualTo(resultTagsAfterId);
            JsonNode afterEvidence = afterInteraction
                    .at("/current_pause/breakpoint_snapshots/0/condition_evidence");
            assertThat(afterEvidence).hasSize(4);
            assertThat(afterEvidence.at("/0/source").asText()).isEqualTo("params");
            assertThat(afterEvidence.at("/0/field_path").asText()).isEqualTo("request.mode");
            assertThat(afterEvidence.at("/0/expected_value").asText()).isEqualTo("safe");
            assertThat(afterEvidence.at("/0/actual_value").asText()).isEqualTo("safe");
            assertThat(afterEvidence.at("/1/actual_value").asBoolean()).isTrue();
            assertThat(afterEvidence.at("/2/expected_value").toString()).isEqualTo("9007199254740993");
            assertThat(afterEvidence.at("/2/actual_value").toString()).isEqualTo("9007199254740993");
            assertThat(afterEvidence.at("/3/actual_value").isNull()).isTrue();
            JsonNode tagsEvidence = afterInteraction
                    .at("/current_pause/breakpoint_snapshots/1/condition_evidence/0");
            assertThat(tagsEvidence.path("operator").asText()).isEqualTo("contains_any");
            assertThat(tagsEvidence.path("expected_value").toString()).isEqualTo("[2,\"red\"]");
            assertThat(tagsEvidence.path("actual_value").toString()).isEqualTo("[\"red\"]");
            assertThat(tagsEvidence.toString()).doesNotContain("blue");
            JsonNode frozenAfterSnapshots = afterInteraction
                    .at("/current_pause/breakpoint_snapshots").deepCopy();
            JsonNode replayedWhilePaused = json(business.after("""
                    {"interaction_id":"multiple-stage","result":{"accepted":true,"optional":null,"amount":9007199254740993,"tags":["blue","red"]}}
                    """));
            assertThat(replayedWhilePaused.path("operation").asText()).isEqualTo("replayed");
            assertThat(replayedWhilePaused.path("wait_required").asBoolean()).isTrue();
            assertThat(web.readJson("/api/v1/breakpoints/" + afterId).path("hit_count").asInt()).isEqualTo(1);
            assertThat(web.readJson("/api/v1/breakpoints/" + resultTagsAfterId).path("hit_count").asInt())
                    .isEqualTo(1);
            assertThat(web.write("POST", "/api/v1/interactions/multiple-stage/inject", """
                    {"pause_point":"after","changes":{"accepted":false}}
                    """).statusCode()).isEqualTo(200);

            var waiting = business.waitForReleaseAsync("""
                    {"interaction_id":"multiple-stage","pause_point":"after"}
                    """);
            Thread.sleep(100);
            assertThat(waiting).isNotDone();
            assertThat(web.write("POST", "/api/v1/interactions/multiple-stage/continue", """
                    {"pause_point":"after"}
                    """).statusCode()).isEqualTo(200);
            assertThat(json(waiting.get(5, TimeUnit.SECONDS)).path("result").asText()).isEqualTo("continued");
            JsonNode completed = web.readJson("/api/v1/interactions/multiple-stage");
            assertThat(completed.at("/result/accepted").asBoolean()).isTrue();
            assertThat(completed.at("/pauses/1/effective_content/accepted").asBoolean()).isFalse();
            assertThat(json(business.after("""
                    {"interaction_id":"multiple-stage","result":{"accepted":true,"optional":null,"amount":9007199254740993,"tags":["blue","red"]}}
                    """)).path("wait_required").asBoolean()).isFalse();

            assertThat(json(business.before("""
                    {"interaction_id":"result-only-match","object":"Mixer","command":"blend","params":{"request":{"mode":"unsafe"},"tags":[]}}
                    """)).path("wait_required").asBoolean()).isFalse();
            JsonNode resultOnly = json(business.after("""
                    {"interaction_id":"result-only-match","result":{"accepted":true,"optional":null,"amount":9007199254740993,"tags":["blue"]}}
                    """));
            assertThat(resultOnly.path("wait_required").asBoolean()).isFalse();
            assertThat(resultOnly.path("proceed").asBoolean()).isTrue();
            assertThat(web.readJson("/api/v1/interactions/result-only-match").path("status").asText())
                    .isEqualTo("completed");

            for (String report : List.of(
                    "{\"interaction_id\":\"missing-null\",\"result\":{\"accepted\":true,\"amount\":9007199254740993,\"tags\":[\"blue\"]}}",
                    "{\"interaction_id\":\"strict-type\",\"result\":{\"accepted\":true,\"optional\":null,\"amount\":\"9007199254740993\",\"tags\":[\"blue\"]}}",
                    "{\"interaction_id\":\"contains-type\",\"result\":{\"tags\":[\"2\"]}}",
                    "{\"interaction_id\":\"contains-scalar\",\"result\":{\"tags\":\"red\"}}")) {
                String interactionId = objectMapper.readTree(report).path("interaction_id").asText();
                assertThat(json(business.before("""
                        {"interaction_id":"%s","object":"Mixer","command":"blend","params":{"request":{"mode":"safe"},"tags":[]}}
                        """.formatted(interactionId))).path("wait_required").asBoolean()).isFalse();
                assertThat(json(business.after(report)).path("wait_required").asBoolean()).isFalse();
            }
            for (String root : List.of("\"scalar\"", "[1,2]", "null")) {
                String suffix = Integer.toString(root.hashCode()).replace('-', 'n');
                assertThat(json(business.before("""
                        {"interaction_id":"conditional-root-%s","object":"Mixer","command":"blend","params":{"request":{"mode":"safe"}}}
                        """.formatted(suffix))).path("wait_required").asBoolean()).isFalse();
                assertThat(json(business.after("""
                        {"interaction_id":"conditional-root-%s","result":%s}
                        """.formatted(suffix, root))).path("wait_required").asBoolean()).isFalse();

                assertThat(json(business.before("""
                        {"interaction_id":"unconditional-root-%s","object":"Mixer","command":"root","params":{}}
                        """.formatted(suffix))).path("wait_required").asBoolean()).isFalse();
                assertThat(json(business.after("""
                        {"interaction_id":"unconditional-root-%s","result":%s}
                        """.formatted(suffix, root))).path("wait_required").asBoolean()).isTrue();
                assertThat(web.write("POST", "/api/v1/interactions/unconditional-root-" + suffix + "/continue", """
                        {"pause_point":"after"}
                        """).statusCode()).isEqualTo(200);
            }

            assertThat(web.readJson("/api/v1/breakpoints/" + modeBeforeId).path("hit_count").asInt())
                    .isEqualTo(1);
            assertThat(web.readJson("/api/v1/breakpoints/" + tagsBeforeId).path("hit_count").asInt())
                    .isEqualTo(1);
            assertThat(web.readJson("/api/v1/breakpoints/" + afterId).path("hit_count").asInt())
                    .isEqualTo(1);
            assertThat(web.readJson("/api/v1/breakpoints/" + resultTagsAfterId).path("hit_count").asInt())
                    .isEqualTo(1);
            assertThat(web.readJson("/api/v1/breakpoints/" + unmatchedAfterId).path("hit_count").asInt())
                    .isZero();
            assertThat(web.readJson("/api/v1/breakpoints/" + unconditionalRootAfterId).path("hit_count").asInt())
                    .isEqualTo(3);
            assertThat(web.write("PATCH", "/api/v1/breakpoints/" + afterId, """
                    {"name":"命中后已修改","object":"Mixer","command":"blend","pause_point":"after","conditions":[
                      {"source":"result","field_path":"accepted","operator":"eq","value":false}
                    ]}
                    """).statusCode()).isEqualTo(200);
            assertThat(web.write("POST", "/api/v1/breakpoints/" + afterId + "/disable", null).statusCode())
                    .isEqualTo(200);
            assertThat(web.write("DELETE", "/api/v1/breakpoints/" + resultTagsAfterId, null).statusCode())
                    .isEqualTo(200);
            JsonNode immutableHistory = web.readJson("/api/v1/interactions/multiple-stage")
                    .at("/pauses/1/breakpoint_snapshots");
            assertThat(immutableHistory).isEqualTo(frozenAfterSnapshots);
        } finally {
            context.close();
            switchServer.stop(0);
        }
    }

    @Test
    void directlyInjectsOnlyTheCurrentPauseAndCommitsOnExplicitContinue() throws Exception {
        Path directory = Files.createTempDirectory("breakhub-direct-injection-");
        ReportingLeaseTestServer switchServer = startSwitchServer();
        ConfigurableApplicationContext context = start(writeConfig(directory, switchServer.getAddress().getPort()));
        try {
            URI base = base(context);
            WebClient web = login(base);
            BusinessSimulator business = new BusinessSimulator(base);
            assertThat(web.write("POST", "/api/v1/breakpoints", """
                    {"name":"注入前暂停","object":"Mixer","command":"inject","pause_point":"before","conditions":[]}
                    """).statusCode()).isEqualTo(200);
            assertThat(web.write("POST", "/api/v1/breakpoints", """
                    {"name":"注入后暂停","object":"Mixer","command":"inject","pause_point":"after","conditions":[]}
                    """).statusCode()).isEqualTo(200);
            assertThat(web.write("POST", "/api/v1/debugging/start", null).statusCode()).isEqualTo(200);

            assertThat(json(business.before("""
                    {
                      "interaction_id":"direct-injection",
                      "object":"Mixer",
                      "command":"inject",
                      "params":{
                        "request":{"count":1,"label":"old"},
                        "tags":[1,2],
                        "nullable":null,
                        "enabled":true
                      }
                    }
                    """)).path("wait_required").asBoolean()).isTrue();

            HttpResponse<String> wrongBeforePoint = web.write(
                    "POST", "/api/v1/interactions/direct-injection/inject", """
                            {"pause_point":"after","changes":{"enabled":false}}
                            """);
            assertThat(wrongBeforePoint.statusCode()).isEqualTo(409);
            assertThat(json(wrongBeforePoint).path("code").asText()).isEqualTo("PAUSE_POINT_MISMATCH");

            JsonNode first = json(web.write("POST", "/api/v1/interactions/direct-injection/inject", """
                    {
                      "pause_point":"before",
                      "changes":{
                        "request":{"count":2,"missing":"ignored"},
                        "tags":[{"any":"json"},null],
                        "nullable":"not-allowed",
                        "enabled":"true"
                      }
                    }
                    """));
            assertThat(first.path("result").asText()).isEqualTo("partial");
            assertThat(first.path("modified")).isEqualTo(jsonArray("/request/count", "/tags"));
            assertThat(first.at("/skipped/missing")).isEqualTo(jsonArray("/request/missing"));
            assertThat(first.at("/skipped/type_mismatch")).isEqualTo(jsonArray("/enabled"));
            assertThat(first.at("/skipped/original_null")).isEqualTo(jsonArray("/nullable"));
            assertThat(first.path("effective_changed").asBoolean()).isTrue();

            JsonNode second = json(web.write("POST", "/api/v1/interactions/direct-injection/inject", """
                    {
                      "pause_point":"before",
                      "changes":{
                        "request":{"count":2.0,"label":"new"},
                        "tags":[{"any":"json"},null]
                      }
                    }
                    """));
            assertThat(second.path("result").asText()).isEqualTo("applied");
            assertThat(second.path("modified")).isEqualTo(jsonArray("/request/label"));
            assertThat(second.path("unchanged")).isEqualTo(jsonArray("/request/count", "/tags"));

            JsonNode repeated = json(web.write("POST", "/api/v1/interactions/direct-injection/inject", """
                    {
                      "pause_point":"before",
                      "changes":{"request":{"count":2,"label":"new"},"tags":[{"any":"json"},null]}
                    }
                    """));
            assertThat(repeated.path("result").asText()).isEqualTo("no_effect");
            assertThat(repeated.path("effective_changed").asBoolean()).isFalse();

            JsonNode pausedBefore = web.readJson("/api/v1/interactions/direct-injection");
            assertThat(pausedBefore.path("original_params")).isEqualTo(objectMapper.readTree("""
                    {"request":{"count":1,"label":"old"},"tags":[1,2],"nullable":null,"enabled":true}
                    """));
            assertThat(pausedBefore.at("/current_pause/effective_content")).isEqualTo(objectMapper.readTree("""
                    {"request":{"count":2,"label":"new"},"tags":[{"any":"json"},null],"nullable":null,"enabled":true}
                    """));
            assertThat(pausedBefore.at("/current_pause/injection_status").asText()).isEqualTo("pending");
            assertThat(pausedBefore.at("/current_pause/injection_audit")).hasSize(3);
            assertThat(pausedBefore.at("/current_pause/effective_change_count").asInt()).isEqualTo(2);

            var waitingBefore = business.waitForReleaseAsync("""
                    {"interaction_id":"direct-injection","pause_point":"before"}
                    """);
            Thread.sleep(100);
            assertThat(waitingBefore).isNotDone();
            assertThat(web.write("POST", "/api/v1/interactions/direct-injection/continue", """
                    {"pause_point":"before"}
                    """).statusCode()).isEqualTo(200);
            JsonNode releasedBefore = json(waitingBefore.get(5, TimeUnit.SECONDS));
            assertThat(releasedBefore.path("content_kind").asText()).isEqualTo("params");
            assertThat(releasedBefore.path("content")).isEqualTo(pausedBefore.at("/current_pause/effective_content"));

            assertThat(json(business.after("""
                    {
                      "interaction_id":"direct-injection",
                      "result":{"accepted":true,"response":{"code":200},"nullable":null}
                    }
                    """)).path("wait_required").asBoolean()).isTrue();
            HttpResponse<String> wrongAfterPoint = web.write(
                    "POST", "/api/v1/interactions/direct-injection/inject", """
                            {"pause_point":"before","changes":{"accepted":false}}
                            """);
            assertThat(wrongAfterPoint.statusCode()).isEqualTo(409);
            assertThat(json(wrongAfterPoint).path("code").asText()).isEqualTo("PAUSE_POINT_MISMATCH");

            JsonNode afterInjection = json(web.write(
                    "POST", "/api/v1/interactions/direct-injection/inject", """
                            {
                              "pause_point":"after",
                              "changes":{"accepted":false,"response":{"code":503},"nullable":"ignored"}
                            }
                            """));
            assertThat(afterInjection.path("result").asText()).isEqualTo("partial");
            assertThat(afterInjection.path("modified")).isEqualTo(jsonArray("/accepted", "/response/code"));
            assertThat(afterInjection.at("/skipped/original_null")).isEqualTo(jsonArray("/nullable"));

            var waitingAfter = business.waitForReleaseAsync("""
                    {"interaction_id":"direct-injection","pause_point":"after"}
                    """);
            assertThat(web.write("POST", "/api/v1/interactions/direct-injection/continue", """
                    {"pause_point":"after"}
                    """).statusCode()).isEqualTo(200);
            JsonNode releasedAfter = json(waitingAfter.get(5, TimeUnit.SECONDS));
            assertThat(releasedAfter.path("content_kind").asText()).isEqualTo("result");
            assertThat(releasedAfter.path("content")).isEqualTo(objectMapper.readTree("""
                    {"accepted":false,"response":{"code":503},"nullable":null}
                    """));

            JsonNode completed = web.readJson("/api/v1/interactions/direct-injection");
            assertThat(completed.path("result")).isEqualTo(objectMapper.readTree("""
                    {"accepted":true,"response":{"code":200},"nullable":null}
                    """));
            assertThat(completed.path("pauses")).hasSize(2);
            assertThat(completed.at("/pauses/0/injection_status").asText()).isEqualTo("committed");
            assertThat(completed.at("/pauses/1/injection_status").asText()).isEqualTo("committed");

            assertThat(web.write("POST", "/api/v1/breakpoints", """
                    {"name":"标量结果暂停","object":"Scalar","command":"result","pause_point":"after","conditions":[]}
                    """).statusCode()).isEqualTo(200);
            assertThat(json(business.before("""
                    {"interaction_id":"scalar-result","object":"Scalar","command":"result","params":{}}
                    """)).path("wait_required").asBoolean()).isFalse();
            assertThat(json(business.after("""
                    {"interaction_id":"scalar-result","result":"original"}
                    """)).path("wait_required").asBoolean()).isTrue();
            JsonNode scalarInjection = json(web.write(
                    "POST", "/api/v1/interactions/scalar-result/inject", """
                            {"pause_point":"after","changes":{"value":"new"}}
                            """));
            assertThat(scalarInjection.path("result").asText()).isEqualTo("no_effect");
            assertThat(scalarInjection.at("/skipped/type_mismatch")).isEqualTo(jsonArray("/"));
            var waitingScalar = business.waitForReleaseAsync("""
                    {"interaction_id":"scalar-result","pause_point":"after"}
                    """);
            assertThat(web.write("POST", "/api/v1/interactions/scalar-result/continue", """
                    {"pause_point":"after"}
                    """).statusCode()).isEqualTo(200);
            assertThat(json(waitingScalar.get(5, TimeUnit.SECONDS)).path("content").asText())
                    .isEqualTo("original");
        } finally {
            context.close();
            switchServer.stop(0);
        }
    }

    @Test
    void serializesConcurrentBeforeAndAfterRetriesThroughTransactionCommit() throws Exception {
        Path directory = Files.createTempDirectory("breakhub-concurrency-");
        ReportingLeaseTestServer switchServer = startSwitchServer();
        ConfigurableApplicationContext context = start(writeConfig(directory, switchServer.getAddress().getPort()));
        try {
            URI base = base(context);
            WebClient web = login(base);
            BusinessSimulator business = new BusinessSimulator(base);
            assertThat(web.write("POST", "/api/v1/debugging/start", null).statusCode()).isEqualTo(200);

            List<HttpResponse<String>> sameBefore = concurrently(20, () -> business.before("""
                    {"interaction_id":"same-before","object":"Power","command":"set","params":{"voltage":12}}
                    """));
            assertSuccessfulReplaySet(sameBefore, "created", "replayed");
            assertThat(web.readJson("/api/v1/interactions").path("items")).hasSize(1);

            List<HttpResponse<String>> conflictingBefore = concurrently(List.of(
                    () -> business.before("""
                            {"interaction_id":"conflicting-before","object":"Power","command":"set","params":{"voltage":12}}
                            """),
                    () -> business.before("""
                            {"interaction_id":"conflicting-before","object":"Power","command":"set","params":{"voltage":13}}
                            """)));
            assertThat(conflictingBefore).extracting(HttpResponse::statusCode)
                    .containsExactlyInAnyOrder(200, 409);

            List<HttpResponse<String>> sameAfter = concurrently(20, () -> business.after("""
                    {"interaction_id":"same-before","result":{"accepted":true}}
                    """));
            assertSuccessfulReplaySet(sameAfter, "completed", "replayed");

            assertThat(business.before("""
                    {"interaction_id":"conflicting-after","object":"Power","command":"set","params":{}}
                    """).statusCode()).isEqualTo(200);
            List<HttpResponse<String>> conflictingAfter = concurrently(List.of(
                    () -> business.after("""
                            {"interaction_id":"conflicting-after","result":{"accepted":true}}
                            """),
                    () -> business.after("""
                            {"interaction_id":"conflicting-after","result":{"accepted":false}}
                            """)));
            assertThat(conflictingAfter).extracting(HttpResponse::statusCode)
                    .containsExactlyInAnyOrder(200, 409);
            assertThat(web.readJson("/api/v1/interactions").path("items")).hasSize(3);
        } finally {
            context.close();
            switchServer.stop(0);
        }
    }

    @Test
    void shutdownSafelyResolvesPersistedPause() throws Exception {
        Path directory = Files.createTempDirectory("breakhub-shutdown-pause-");
        ReportingLeaseTestServer switchServer = startSwitchServer();
        Path config = writeConfig(directory, switchServer.getAddress().getPort());
        ConfigurableApplicationContext context = start(config);
        try {
            URI base = base(context);
            WebClient web = login(base);
            BusinessSimulator business = new BusinessSimulator(base);
            assertThat(web.write("POST", "/api/v1/breakpoints", """
                    {"object":"Power","command":"shutdown","pause_point":"before","conditions":[]}
                    """).statusCode()).isEqualTo(200);
            assertThat(web.write("POST", "/api/v1/debugging/start", null).statusCode()).isEqualTo(200);
            assertThat(json(business.before("""
                    {"interaction_id":"shutdown-pause","object":"Power","command":"shutdown","params":{"mode":"original"}}
                    """)).path("wait_required").asBoolean()).isTrue();
            assertThat(web.write("POST", "/api/v1/interactions/shutdown-pause/inject", """
                    {"pause_point":"before","changes":{"mode":"modified"}}
                    """).statusCode()).isEqualTo(200);
            var waiting = business.waitForReleaseAsync("""
                    {"interaction_id":"shutdown-pause","pause_point":"before"}
                    """);
            Thread.sleep(100);
            assertThat(waiting).isNotDone();

            context.close();
            context = null;
            try {
                assertThat(json(waiting.get(5, TimeUnit.SECONDS)).path("result").asText())
                        .isEqualTo("safe_released");
            } catch (java.util.concurrent.ExecutionException connectionClosed) {
                assertThat(connectionClosed.getCause()).isNotNull();
            }

            context = start(config);
            WebClient restored = login(base(context));
            assertNoPaused(restored);
            JsonNode restoredPause = restored.readJson("/api/v1/interactions/shutdown-pause").at("/pauses/0");
            assertThat(restoredPause.path("injection_status").asText()).isEqualTo("discarded");
            assertThat(restoredPause.path("resolution").asText()).isEqualTo("product_shutdown");
            assertThat(restoredPause.path("released_content"))
                    .isEqualTo(objectMapper.readTree("{\"mode\":\"original\"}"));
        } finally {
            if (context != null) {
                context.close();
            }
            switchServer.stop(0);
        }
    }

    @Test
    void continuesOnlyValidatedSelectedPausesWithoutPartialExecution() throws Exception {
        Path directory = Files.createTempDirectory("breakhub-selected-continue-");
        ReportingLeaseTestServer switchServer = startSwitchServer();
        ConfigurableApplicationContext context = start(writeConfig(directory, switchServer.getAddress().getPort()));
        try {
            URI base = base(context);
            WebClient web = login(base);
            WebClient otherWeb = login(base);
            BusinessSimulator business = new BusinessSimulator(base);
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);

            assertThat(web.write("POST", "/api/v1/breakpoints", """
                    {"object":"Selected","command":"before","pause_point":"before","conditions":[]}
                    """).statusCode()).isEqualTo(200);
            assertThat(web.write("POST", "/api/v1/breakpoints", """
                    {"object":"Selected","command":"after","pause_point":"after","conditions":[]}
                    """).statusCode()).isEqualTo(200);
            assertThat(web.write("POST", "/api/v1/debugging/start", null).statusCode()).isEqualTo(200);

            HttpResponse<String> emptySelection = web.write(
                    "POST", "/api/v1/interactions/continue-selected", "{\"targets\":[]}");
            assertThat(emptySelection.statusCode()).isEqualTo(400);
            assertThat(json(emptySelection).path("code").asText()).isEqualTo("INVALID_CONTINUE_SELECTION");
            HttpResponse<String> duplicateSelection = web.write(
                    "POST", "/api/v1/interactions/continue-selected", """
                            {"targets":[
                              {"interaction_id":"duplicate","pause_point":"before"},
                              {"interaction_id":"duplicate","pause_point":"before"}
                            ]}
                            """);
            assertThat(duplicateSelection.statusCode()).isEqualTo(400);
            assertThat(json(duplicateSelection).path("code").asText())
                    .isEqualTo("INVALID_CONTINUE_SELECTION");

            reportBeforePause(business, "selected-before");
            reportAfterPause(business, "selected-after");
            reportBeforePause(business, "not-selected");
            JsonNode continued = json(web.write("POST", "/api/v1/interactions/continue-selected", """
                    {"targets":[
                      {"interaction_id":"selected-before","pause_point":"before"},
                      {"interaction_id":"selected-after","pause_point":"after"}
                    ]}
                    """));
            assertThat(continued.path("continued_count").asInt()).isEqualTo(2);
            assertThat(continued.path("interactions")).containsExactly(
                    objectMapper.readTree("{\"interaction_id\":\"selected-before\",\"pause_point\":\"before\"}"),
                    objectMapper.readTree("{\"interaction_id\":\"selected-after\",\"pause_point\":\"after\"}"));
            assertThat(web.readJson("/api/v1/interactions/selected-before").has("current_pause")).isFalse();
            assertThat(web.readJson("/api/v1/interactions/selected-after").has("current_pause")).isFalse();
            assertThat(web.readJson("/api/v1/interactions/not-selected").at("/current_pause/status").asText())
                    .isEqualTo("paused");

            HttpResponse<String> gatewaySelection = gatewayWrite(
                    base, "/api/v1/interactions/continue-selected", """
                            {"targets":[{"interaction_id":"not-selected","pause_point":"before"}]}
                            """);
            assertThat(gatewaySelection.statusCode()).isEqualTo(403);
            assertThat(json(gatewaySelection).path("code").asText()).isEqualTo("FORBIDDEN");

            reportBeforePause(business, "pending-injection");
            reportBeforePause(business, "valid-peer");
            assertThat(web.write("POST", "/api/v1/interactions/pending-injection/inject", """
                    {"pause_point":"before","changes":{"value":"modified"}}
                    """).statusCode()).isEqualTo(200);
            HttpResponse<String> pendingSelection = web.write(
                    "POST", "/api/v1/interactions/continue-selected", """
                            {"targets":[
                              {"interaction_id":"pending-injection","pause_point":"before"},
                              {"interaction_id":"valid-peer","pause_point":"before"}
                            ]}
                            """);
            assertThat(pendingSelection.statusCode()).isEqualTo(409);
            assertThat(json(pendingSelection).path("code").asText())
                    .isEqualTo("PENDING_INJECTION_REVIEW_REQUIRED");
            assertPaused(web, "pending-injection", "before");
            assertPaused(web, "valid-peer", "before");

            HttpResponse<String> wrongPhase = web.write(
                    "POST", "/api/v1/interactions/continue-selected", """
                            {"targets":[{"interaction_id":"valid-peer","pause_point":"after"}]}
                            """);
            assertThat(wrongPhase.statusCode()).isEqualTo(409);
            assertThat(json(wrongPhase).path("code").asText()).isEqualTo("PAUSE_POINT_MISMATCH");
            assertPaused(web, "valid-peer", "before");

            reportBeforePause(business, "stale-selected");
            assertThat(web.write("POST", "/api/v1/interactions/stale-selected/continue", """
                    {"pause_point":"before"}
                    """).statusCode()).isEqualTo(200);
            HttpResponse<String> staleSelection = web.write(
                    "POST", "/api/v1/interactions/continue-selected", """
                            {"targets":[
                              {"interaction_id":"stale-selected","pause_point":"before"},
                              {"interaction_id":"valid-peer","pause_point":"before"}
                            ]}
                            """);
            assertThat(staleSelection.statusCode()).isEqualTo(409);
            assertThat(json(staleSelection).path("code").asText()).isEqualTo("INTERACTION_NOT_PAUSED");
            assertPaused(web, "valid-peer", "before");

            reportBeforePause(business, "atomic-fail-1");
            reportBeforePause(business, "atomic-fail-2");
            jdbc.execute("""
                    CREATE TRIGGER fail_selected_continue
                    BEFORE UPDATE OF status ON product_pause
                    WHEN NEW.interaction_id = 'atomic-fail-2' AND NEW.status = 'continued'
                    BEGIN
                      SELECT RAISE(ABORT, 'forced selected failure');
                    END
                    """);
            HttpResponse<String> atomicFailure = web.write(
                    "POST", "/api/v1/interactions/continue-selected", """
                            {"targets":[
                              {"interaction_id":"atomic-fail-1","pause_point":"before"},
                              {"interaction_id":"atomic-fail-2","pause_point":"before"}
                            ]}
                            """);
            assertThat(atomicFailure.statusCode()).isEqualTo(500);
            assertThat(json(atomicFailure).path("code").asText()).isEqualTo("SELECTED_CONTINUE_FAILED");
            assertPaused(web, "atomic-fail-1", "before");
            assertPaused(web, "atomic-fail-2", "before");
            jdbc.execute("DROP TRIGGER fail_selected_continue");

            HttpResponse<String> otherControl = otherWeb.write(
                    "POST", "/api/v1/interactions/continue-selected", """
                            {"targets":[{"interaction_id":"valid-peer","pause_point":"before"}]}
                            """);
            assertThat(otherControl.statusCode()).isEqualTo(409);
            assertThat(json(otherControl).path("code").asText()).isEqualTo("CONTROLLED_BY_WEB");
            assertPaused(web, "valid-peer", "before");
        } finally {
            context.close();
            switchServer.stop(0);
        }
    }

    @Test
    void continuesInteractionsIdempotentlyAndAtomicallyFromCommandSnapshot() throws Exception {
        Path directory = Files.createTempDirectory("breakhub-bulk-continue-");
        ReportingLeaseTestServer switchServer = startSwitchServer();
        ConfigurableApplicationContext context = start(writeConfig(directory, switchServer.getAddress().getPort()));
        try {
            URI base = base(context);
            WebClient web = login(base);
            BusinessSimulator business = new BusinessSimulator(base);
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);

            JsonNode breakpoint = json(web.write("POST", "/api/v1/breakpoints", """
                    {"object":"Bulk","command":"run","pause_point":"before","conditions":[]}
                    """));
            String breakpointId = breakpoint.path("breakpoint_id").asText();
            assertThat(web.write("POST", "/api/v1/debugging/start", null).statusCode()).isEqualTo(200);

            assertThat(json(business.before("""
                    {"interaction_id":"idempotent","object":"Bulk","command":"run","params":{"value":1}}
                    """)).path("wait_required").asBoolean()).isTrue();
            JsonNode firstContinue = json(web.write(
                    "POST", "/api/v1/interactions/idempotent/continue", """
                            {"pause_point":"before"}
                            """));
            assertThat(firstContinue.path("continued").asBoolean()).isTrue();
            JsonNode repeatedContinue = json(web.write(
                    "POST", "/api/v1/interactions/idempotent/continue", """
                            {"pause_point":"before"}
                            """));
            assertThat(repeatedContinue.path("continued").asBoolean()).isFalse();
            assertThat(repeatedContinue.path("result").asText()).isEqualTo("already_resolved");
            assertThat(repeatedContinue.path("status").asText()).isEqualTo("continued");
            assertThat(repeatedContinue.path("resolution").asText()).isEqualTo("continued_by_controller");
            assertThat(repeatedContinue.path("released_content"))
                    .isEqualTo(objectMapper.readTree("{\"value\":1}"));

            HttpResponse<String> missingInteraction = web.write(
                    "POST", "/api/v1/interactions/missing/continue", """
                            {"pause_point":"before"}
                            """);
            assertThat(missingInteraction.statusCode()).isEqualTo(404);
            assertThat(json(missingInteraction).path("code").asText()).isEqualTo("INTERACTION_NOT_FOUND");

            assertThat(web.write("POST", "/api/v1/breakpoints/" + breakpointId + "/disable", null).statusCode())
                    .isEqualTo(200);
            assertThat(json(business.before("""
                    {"interaction_id":"never-paused","object":"Bulk","command":"run","params":{}}
                    """)).path("wait_required").asBoolean()).isFalse();
            HttpResponse<String> missingPause = web.write(
                    "POST", "/api/v1/interactions/never-paused/continue", """
                            {"pause_point":"before"}
                            """);
            assertThat(missingPause.statusCode()).isEqualTo(404);
            assertThat(json(missingPause).path("code").asText()).isEqualTo("PAUSE_NOT_FOUND");

            HttpResponse<String> listBody = web.write("POST", "/api/v1/interactions/continue", """
                    {"interaction_ids":["idempotent"]}
                    """);
            assertThat(listBody.statusCode()).isEqualTo(400);
            assertThat(json(web.write("POST", "/api/v1/interactions/continue", null))
                    .path("continued_count").asInt()).isZero();

            assertThat(json(business.before("""
                    {"interaction_id":"bulk-new","object":"Bulk","command":"run","params":{"value":"new"}}
                    """)).path("wait_required").asBoolean()).isFalse();
            assertThat(web.write("POST", "/api/v1/breakpoints/" + breakpointId + "/enable", null).statusCode())
                    .isEqualTo(200);
            for (String interactionId : List.of("bulk-1", "bulk-2")) {
                assertThat(json(business.before("""
                        {"interaction_id":"%s","object":"Bulk","command":"run","params":{"value":"original"}}
                        """.formatted(interactionId))).path("wait_required").asBoolean()).isTrue();
            }
            assertThat(web.write("POST", "/api/v1/interactions/bulk-1/inject", """
                    {"pause_point":"before","changes":{"value":"modified"}}
                    """).statusCode()).isEqualTo(200);

            jdbc.execute("""
                    CREATE TRIGGER insert_pause_during_bulk_continue
                    AFTER UPDATE OF status ON product_pause
                    WHEN OLD.interaction_id = 'bulk-1' AND NEW.status = 'continued'
                    BEGIN
                      INSERT INTO product_pause(
                        interaction_id, pause_point, session_id, status,
                        breakpoint_snapshots_json, effective_content_json,
                        injection_audit_json, injection_status, paused_at)
                      SELECT interaction_id, 'before', session_id, 'paused', '[]', params_json,
                             '[]', 'none', '2999-01-01T00:00:00Z'
                      FROM product_interaction WHERE interaction_id = 'bulk-new';
                    END
                    """);
            JsonNode bulk = json(web.write("POST", "/api/v1/interactions/continue", null));
            assertThat(bulk.path("continued_count").asInt()).isEqualTo(2);
            assertThat(bulk.path("pending_injection_count").asInt()).isEqualTo(1);
            assertThat(web.readJson("/api/v1/interactions/bulk-new").at("/current_pause/status").asText())
                    .isEqualTo("paused");
            JsonNode bulkDetail = web.readJson("/api/v1/interactions/bulk-1");
            assertThat(bulkDetail.at("/pauses/0/status").asText()).isEqualTo("continued");
            assertThat(bulkDetail.at("/pauses/0/injection_status").asText()).isEqualTo("committed");
            assertThat(bulkDetail.at("/pauses/0/resolution").asText()).isEqualTo("continued_by_controller");
            assertThat(bulkDetail.at("/pauses/0/released_content"))
                    .isEqualTo(objectMapper.readTree("{\"value\":\"modified\"}"));
            assertThat(bulkDetail.path("timeline")).isNotEmpty();
            assertThat(bulkDetail.at("/payload_metadata/params/truncated").asBoolean()).isFalse();
            assertThat(bulkDetail.at("/payload_metadata/params/original_size_bytes").asLong()).isPositive();

            jdbc.execute("DROP TRIGGER insert_pause_during_bulk_continue");
            jdbc.update("UPDATE product_pause SET paused_at = '2000-01-01T00:00:00Z' WHERE interaction_id = 'bulk-new'");
            assertThat(json(web.write("POST", "/api/v1/interactions/continue", null))
                    .path("continued_count").asInt()).isEqualTo(1);

            for (String interactionId : List.of("bulk-fail-1", "bulk-fail-2")) {
                assertThat(json(business.before("""
                        {"interaction_id":"%s","object":"Bulk","command":"run","params":{}}
                        """.formatted(interactionId))).path("wait_required").asBoolean()).isTrue();
            }
            jdbc.execute("""
                    CREATE TRIGGER fail_bulk_continue
                    BEFORE UPDATE OF status ON product_pause
                    WHEN NEW.interaction_id = 'bulk-fail-2' AND NEW.status = 'continued'
                    BEGIN
                      SELECT RAISE(ABORT, 'forced bulk failure');
                    END
                    """);
            assertThat(web.write("POST", "/api/v1/interactions/continue", null).statusCode())
                    .isEqualTo(500);
            assertThat(web.readJson("/api/v1/interactions/bulk-fail-1").at("/current_pause/status").asText())
                    .isEqualTo("paused");
            assertThat(web.readJson("/api/v1/interactions/bulk-fail-2").at("/current_pause/status").asText())
                    .isEqualTo("paused");
            jdbc.execute("DROP TRIGGER fail_bulk_continue");
            assertThat(json(web.write("POST", "/api/v1/interactions/continue", null))
                    .path("continued_count").asInt()).isEqualTo(2);
        } finally {
            context.close();
            switchServer.stop(0);
        }
    }

    @Test
    void discardsPendingInjectionWhenPauseTimesOut() throws Exception {
        Path directory = Files.createTempDirectory("breakhub-injection-timeout-");
        ReportingLeaseTestServer switchServer = startSwitchServer();
        ConfigurableApplicationContext context = start(
                writeConfig(directory, switchServer.getAddress().getPort(), "250ms"));
        try {
            URI base = base(context);
            WebClient web = login(base);
            BusinessSimulator business = new BusinessSimulator(base);
            assertThat(web.write("POST", "/api/v1/breakpoints", """
                    {"object":"Safety","command":"timeout","pause_point":"before","conditions":[]}
                    """).statusCode()).isEqualTo(200);
            assertThat(web.write("POST", "/api/v1/debugging/start", null).statusCode()).isEqualTo(200);
            assertThat(json(business.before("""
                    {"interaction_id":"injection-timeout","object":"Safety","command":"timeout","params":{"value":"original"}}
                    """)).path("wait_required").asBoolean()).isTrue();
            assertThat(web.write("POST", "/api/v1/interactions/injection-timeout/inject", """
                    {"pause_point":"before","changes":{"value":"modified"}}
                    """).statusCode()).isEqualTo(200);

            JsonNode released = json(business.waitForReleaseAsync("""
                    {"interaction_id":"injection-timeout","pause_point":"before"}
                    """).get(5, TimeUnit.SECONDS));
            assertThat(released.path("result").asText()).isEqualTo("timed_out");
            assertThat(released.path("content")).isEqualTo(objectMapper.readTree("{\"value\":\"original\"}"));
            JsonNode pause = web.readJson("/api/v1/interactions/injection-timeout").at("/pauses/0");
            assertThat(pause.path("injection_status").asText()).isEqualTo("discarded");
            assertThat(pause.path("resolution").asText()).isEqualTo("pause_timeout");
            assertThat(web.readJson("/api/v1/overview").at("/debugging/status").asText())
                    .isEqualTo("debugging");
        } finally {
            context.close();
            switchServer.stop(0);
        }
    }

    @Test
    void discardsPendingInjectionWhenControlLeaseExpires() throws Exception {
        Path directory = Files.createTempDirectory("breakhub-injection-lease-");
        ReportingLeaseTestServer switchServer = startSwitchServer();
        ConfigurableApplicationContext context = start(
                writeConfig(directory, switchServer.getAddress().getPort(), "25m", "2s"));
        try {
            URI base = base(context);
            WebClient web = login(base);
            BusinessSimulator business = new BusinessSimulator(base);
            assertThat(web.write("POST", "/api/v1/breakpoints", """
                    {"object":"Safety","command":"lease","pause_point":"before","conditions":[]}
                    """).statusCode()).isEqualTo(200);
            assertThat(web.write("POST", "/api/v1/debugging/start", null).statusCode()).isEqualTo(200);
            assertThat(json(business.before("""
                    {"interaction_id":"injection-lease","object":"Safety","command":"lease","params":{"value":"original"}}
                    """)).path("wait_required").asBoolean()).isTrue();
            HttpResponse<String> injection = web.write("POST", "/api/v1/interactions/injection-lease/inject", """
                    {"pause_point":"before","changes":{"value":"modified"}}
                    """);
            assertThat(injection.statusCode()).as(injection.body()).isEqualTo(200);

            JsonNode released = json(business.waitForReleaseAsync("""
                    {"interaction_id":"injection-lease","pause_point":"before"}
                    """).get(5, TimeUnit.SECONDS));
            assertThat(released.path("result").asText()).isEqualTo("safe_released");
            assertThat(released.path("content")).isEqualTo(objectMapper.readTree("{\"value\":\"original\"}"));
            JsonNode pause = web.readJson("/api/v1/interactions/injection-lease").at("/pauses/0");
            assertThat(pause.path("injection_status").asText()).isEqualTo("discarded");
            assertThat(pause.path("resolution").asText()).isEqualTo("lease_expired");
        } finally {
            context.close();
            switchServer.stop(0);
        }
    }

    @Test
    void reportingLeaseRecoversAfterDemoProcessRestartWithinTheOriginalWindow() throws Exception {
        Path directory = Files.createTempDirectory("breakhub-reporting-recovery-");
        ReportingLeaseTestServer switchServer = startSwitchServer();
        ConfigurableApplicationContext context = start(writeConfig(directory, switchServer.getAddress().getPort()));
        try {
            URI base = base(context);
            WebClient web = login(base);
            String sessionId = web.readJson("/api/v1/sessions/current").path("session_id").asText();
            assertThat(web.write("POST", "/api/v1/debugging/start", null).statusCode()).isEqualTo(200);

            String oldLeaseId = switchServer.activeLeaseId();
            JsonNode initialOverview = web.readJson("/api/v1/overview");
            Instant initialConfirmedAt = Instant.parse(
                    initialOverview.at("/debugging/reporting/last_confirmed_at").asText());
            switchServer.simulateProcessRestart();

            JsonNode recoveredOverview = null;
            String recoveredLeaseId = null;
            Instant recoveryDeadline = Instant.now().plusSeconds(13);
            while (Instant.now().isBefore(recoveryDeadline)) {
                String candidateLeaseId = switchServer.activeLeaseId();
                JsonNode candidateOverview = web.readJson("/api/v1/overview");
                if (candidateLeaseId != null
                        && !candidateLeaseId.equals(oldLeaseId)
                        && "healthy".equals(candidateOverview.at(
                                "/debugging/reporting/status").asText())) {
                    recoveredLeaseId = candidateLeaseId;
                    recoveredOverview = candidateOverview;
                    break;
                }
                Thread.sleep(50);
            }

            assertThat(recoveredLeaseId).isNotNull().isNotEqualTo(oldLeaseId);
            assertThat(recoveredOverview).isNotNull();
            assertThat(recoveredOverview.at("/debugging/status").asText()).isEqualTo("debugging");
            assertThat(recoveredOverview.at("/debugging/session_id").asText()).isEqualTo(sessionId);
            assertThat(recoveredOverview.at("/control/held").asBoolean()).isTrue();
            Instant recoveredConfirmedAt = Instant.parse(
                    recoveredOverview.at("/debugging/reporting/last_confirmed_at").asText());
            Instant recoveredDeadlineAt = Instant.parse(
                    recoveredOverview.at("/debugging/reporting/server_deadline_at").asText());
            assertThat(recoveredConfirmedAt).isAfter(initialConfirmedAt);
            assertThat(recoveredDeadlineAt).isEqualTo(recoveredConfirmedAt.plusSeconds(30));
            assertThat(recoveredOverview.toString())
                    .doesNotContain("lease_id", oldLeaseId, recoveredLeaseId);
        } finally {
            context.close();
            switchServer.stop(0);
        }
    }

    @Test
    void reportingExpiryReleasesPauseAndKeepsTheOriginalControlLease() throws Exception {
        Path directory = Files.createTempDirectory("breakhub-reporting-expiry-");
        ReportingLeaseTestServer switchServer = startSwitchServer();
        ConfigurableApplicationContext context = start(writeConfig(directory, switchServer.getAddress().getPort()));
        CountDownLatch renewalStarted = new CountDownLatch(1);
        CountDownLatch allowRenewal = new CountDownLatch(1);
        try {
            URI base = base(context);
            WebClient web = login(base);
            BusinessSimulator business = new BusinessSimulator(base);
            assertThat(web.write("POST", "/api/v1/breakpoints", """
                    {"object":"Safety","command":"reportingExpiry","pause_point":"before","conditions":[]}
                    """).statusCode()).isEqualTo(200);
            assertThat(web.write("POST", "/api/v1/debugging/start", null).statusCode()).isEqualTo(200);

            JsonNode healthyOverview = web.readJson("/api/v1/overview");
            JsonNode healthyReporting = healthyOverview.at("/debugging/reporting");
            assertThat(healthyReporting.path("status").asText()).isEqualTo("healthy");
            Instant lastConfirmedAt = Instant.parse(healthyReporting.path("last_confirmed_at").asText());
            Instant serverDeadlineAt = Instant.parse(healthyReporting.path("server_deadline_at").asText());
            assertThat(serverDeadlineAt).isEqualTo(lastConfirmedAt.plusSeconds(30));
            assertThat(healthyOverview.toString()).doesNotContain("lease_id", "lease-test-");
            switchServer.blockRenewals(renewalStarted, allowRenewal);

            assertThat(json(business.before("""
                    {"interaction_id":"reporting-expiry","object":"Safety",
                     "command":"reportingExpiry","params":{"value":"original"}}
                    """)).path("wait_required").asBoolean()).isTrue();
            assertThat(web.write("POST", "/api/v1/interactions/reporting-expiry/inject", """
                    {"pause_point":"before","changes":{"value":"modified"}}
                    """).statusCode()).isEqualTo(200);
            var waiting = business.waitForReleaseAsync("""
                    {"interaction_id":"reporting-expiry","pause_point":"before"}
                    """);

            assertThat(renewalStarted.await(12, TimeUnit.SECONDS)).isTrue();

            JsonNode degradedOverview = null;
            Instant degradedObservedAt = null;
            Instant degradedDeadline = lastConfirmedAt.plusSeconds(16);
            while (Instant.now().isBefore(degradedDeadline)) {
                JsonNode candidate = web.readJson("/api/v1/overview");
                if ("degraded".equals(candidate.at("/debugging/reporting/status").asText())) {
                    degradedOverview = candidate;
                    degradedObservedAt = Instant.now();
                    break;
                }
                Thread.sleep(50);
            }
            assertThat(degradedOverview).isNotNull();
            assertThat(degradedObservedAt).isBeforeOrEqualTo(lastConfirmedAt.plusSeconds(16));
            assertThat(degradedOverview.at("/debugging/status").asText()).isEqualTo("debugging");
            assertThat(degradedOverview.at("/debugging/reporting/last_confirmed_at").asText())
                    .isEqualTo(lastConfirmedAt.toString());
            assertThat(degradedOverview.at("/debugging/reporting/server_deadline_at").asText())
                    .isEqualTo(serverDeadlineAt.toString());
            assertThat(degradedOverview.at("/debugging/reporting/last_error").asText())
                    .isEqualTo("REPORTING_LEASE_TIMEOUT");
            assertThat(degradedOverview.at("/control/held").asBoolean()).isTrue();
            assertThat(degradedOverview.at("/control/controller").asText()).isEqualTo("web");
            assertThat(degradedOverview.at("/control/owned_by_requester").asBoolean()).isTrue();
            assertThat(degradedOverview.toString()).doesNotContain("lease_id", "lease-test-");
            assertThat(waiting).isNotDone();

            JsonNode expiredOverview = null;
            Instant expiredObservedAt = null;
            Instant expiryPollDeadline = serverDeadlineAt.plusSeconds(2);
            while (Instant.now().isBefore(expiryPollDeadline)) {
                JsonNode candidate = web.readJson("/api/v1/overview");
                if ("expired".equals(candidate.at("/debugging/reporting/status").asText())) {
                    expiredOverview = candidate;
                    expiredObservedAt = Instant.now();
                    break;
                }
                Thread.sleep(50);
            }
            assertThat(expiredOverview).isNotNull();
            assertThat(expiredObservedAt).isBeforeOrEqualTo(serverDeadlineAt.plusSeconds(1));
            assertThat(expiredOverview.at("/debugging/status").asText()).isEqualTo("idle");
            assertThat(expiredOverview.at("/debugging/reporting/last_confirmed_at").asText())
                    .isEqualTo(lastConfirmedAt.toString());
            assertThat(expiredOverview.at("/debugging/reporting/server_deadline_at").asText())
                    .isEqualTo(serverDeadlineAt.toString());
            assertThat(expiredOverview.at("/control/held").asBoolean()).isTrue();
            assertThat(expiredOverview.at("/control/controller").asText()).isEqualTo("web");
            assertThat(expiredOverview.at("/control/owned_by_requester").asBoolean()).isTrue();
            assertThat(expiredOverview.toString()).doesNotContain("lease_id", "lease-test-");

            JsonNode released = json(waiting.get(2, TimeUnit.SECONDS));
            assertThat(released.path("result").asText()).isEqualTo("safe_released");
            assertThat(released.path("content"))
                    .isEqualTo(objectMapper.readTree("{\"value\":\"original\"}"));
            JsonNode pause = web.readJson("/api/v1/interactions/reporting-expiry").at("/pauses/0");
            assertThat(pause.path("status").asText()).isEqualTo("safe_released");
            assertThat(pause.path("injection_status").asText()).isEqualTo("discarded");
            assertThat(pause.path("resolution").asText()).isEqualTo("reporting_lease_expired");
            allowRenewal.countDown();
            assertThat(switchServer.renewalAttempts()).isGreaterThanOrEqualTo(1);
        } finally {
            allowRenewal.countDown();
            context.close();
            switchServer.stop(0);
        }
    }

    @Test
    void stopCannotBeOvertakenByConcurrentBeforePauseCommit() throws Exception {
        Path directory = Files.createTempDirectory("breakhub-stop-before-race-");
        ReportingLeaseTestServer switchServer = startSwitchServer();
        ConfigurableApplicationContext context = start(writeConfig(directory, switchServer.getAddress().getPort()));
        try {
            URI base = base(context);
            WebClient web = login(base);
            BusinessSimulator business = new BusinessSimulator(base);
            assertThat(web.write("POST", "/api/v1/breakpoints", """
                    {"object":"Power","command":"stopRace","pause_point":"before","conditions":[]}
                    """).statusCode()).isEqualTo(200);

            for (int round = 0; round < 5; round++) {
                assertThat(web.write("POST", "/api/v1/debugging/start", null).statusCode()).isEqualTo(200);
                List<java.util.concurrent.CompletableFuture<HttpResponse<String>>> beforeRequests = new ArrayList<>();
                for (int index = 0; index < 10; index++) {
                    beforeRequests.add(business.beforeAsync("""
                            {"interaction_id":"stop-race-%d-%d","object":"Power","command":"stopRace","params":{"index":%d}}
                            """.formatted(round, index, index)));
                }
                Thread.sleep(2);
                assertThat(web.write("POST", "/api/v1/debugging/stop", null).statusCode()).isEqualTo(200);
                for (var beforeRequest : beforeRequests) {
                    HttpResponse<String> before = beforeRequest.get(5, TimeUnit.SECONDS);
                    assertThat(before.statusCode()).isEqualTo(200);
                    JsonNode body = json(before);
                    if (body.path("wait_required").asBoolean()) {
                        JsonNode release = json(business.waitForReleaseAsync("""
                                {"interaction_id":"%s","pause_point":"before"}
                                """.formatted(body.path("interaction_id").asText())).get(5, TimeUnit.SECONDS));
                        assertThat(release.path("result").asText()).isEqualTo("safe_released");
                    }
                }
                assertNoPaused(web);
            }
        } finally {
            context.close();
            switchServer.stop(0);
        }
    }

    @Test
    void resolvesTimeoutAndContinueCompetitionWithoutServerErrors() throws Exception {
        Path directory = Files.createTempDirectory("breakhub-pause-race-");
        ReportingLeaseTestServer switchServer = startSwitchServer();
        ConfigurableApplicationContext context = start(
                writeConfig(directory, switchServer.getAddress().getPort(), "60ms"));
        try {
            URI base = base(context);
            WebClient web = login(base);
            BusinessSimulator business = new BusinessSimulator(base);
            assertThat(web.write("POST", "/api/v1/breakpoints", """
                    {"object":"Power","command":"race","pause_point":"before","conditions":[]}
                    """).statusCode()).isEqualTo(200);
            assertThat(web.write("POST", "/api/v1/debugging/start", null).statusCode()).isEqualTo(200);

            for (int index = 0; index < 20; index++) {
                String interactionId = "pause-race-" + index;
                assertThat(json(business.before("""
                        {"interaction_id":"%s","object":"Power","command":"race","params":{}}
                        """.formatted(interactionId))).path("wait_required").asBoolean()).isTrue();
                var waiting = business.waitForReleaseAsync("""
                        {"interaction_id":"%s","pause_point":"before"}
                        """.formatted(interactionId));
                Thread.sleep(55);
                HttpResponse<String> continued = web.write(
                        "POST", "/api/v1/interactions/" + interactionId + "/continue", """
                                {"pause_point":"before"}
                                """);
                assertThat(continued.statusCode()).isEqualTo(200);
                assertThat(json(continued).path("result").asText())
                        .isIn("continued", "already_resolved");
                assertThat(json(waiting.get(5, TimeUnit.SECONDS)).path("result").asText())
                        .isIn("continued", "timed_out");
            }
            assertNoPaused(web);
        } finally {
            context.close();
            switchServer.stop(0);
        }
    }

    @Test
    void observesOneBusinessCallAcrossBeforeWaitAndAfterWithSessionIsolation() throws Exception {
        Path directory = Files.createTempDirectory("breakhub-observation-");
        ReportingLeaseTestServer switchServer = startSwitchServer();
        Path config = writeConfig(directory, switchServer.getAddress().getPort());
        ConfigurableApplicationContext context = start(config);
        BusinessSimulator business;
        try {
            URI base = base(context);
            WebClient web = login(base);
            business = new BusinessSimulator(base);

            assertThat(business.managementRead().statusCode()).isEqualTo(403);
            assertThat(business.gatewayBefore().statusCode()).isEqualTo(403);

            HttpResponse<String> idleBefore = business.before("""
                    {"interaction_id":"idle-1","object":"Power","command":"set","params":{"voltage":12}}
                    """);
            assertThat(idleBefore.statusCode()).isEqualTo(200);
            assertThat(json(idleBefore).path("tracked").asBoolean()).isFalse();
            assertThat(json(idleBefore).path("proceed").asBoolean()).isTrue();
            assertThat(json(idleBefore).path("reason").asText()).isEqualTo("debugging_inactive");
            assertThat(web.readJson("/api/v1/interactions").path("items")).isEmpty();

            String firstSessionId = web.readJson("/api/v1/sessions/current").path("session_id").asText();
            assertThat(web.write("POST", "/api/v1/debugging/start", null).statusCode()).isEqualTo(200);

            HttpResponse<String> before = business.before("""
                    {
                      "interaction_id":"interaction-1",
                      "object":"Power",
                      "command":"set",
                      "params":{"voltage":12,"meta":{"enabled":true}}
                    }
                    """);
            assertThat(before.statusCode()).isEqualTo(200);
            assertThat(json(before).path("operation").asText()).isEqualTo("created");
            assertThat(json(before).path("wait_required").asBoolean()).isFalse();

            HttpResponse<String> waited = business.waitForRelease("""
                    {"interaction_id":"interaction-1","pause_point":"before"}
                    """);
            assertThat(waited.statusCode()).isEqualTo(200);
            assertThat(json(waited).path("result").asText()).isEqualTo("not_paused");
            assertThat(json(waited).path("released").asBoolean()).isTrue();

            HttpResponse<String> retriedBefore = business.before("""
                    {
                      "command":"set",
                      "params":{"meta":{"enabled":true},"voltage":12},
                      "object":"Power",
                      "interaction_id":"interaction-1"
                    }
                    """);
            assertThat(retriedBefore.statusCode()).isEqualTo(200);
            assertThat(json(retriedBefore).path("operation").asText()).isEqualTo("replayed");

            HttpResponse<String> conflictingBefore = business.before("""
                    {"interaction_id":"interaction-1","object":"Power","command":"set","params":{"voltage":13}}
                    """);
            assertThat(conflictingBefore.statusCode()).isEqualTo(409);
            assertThat(json(conflictingBefore).path("code").asText()).isEqualTo("INTERACTION_REPORT_CONFLICT");

            HttpResponse<String> after = business.after("""
                    {"interaction_id":"interaction-1","result":{"accepted":true}}
                    """);
            assertThat(after.statusCode()).isEqualTo(200);
            assertThat(json(after).path("operation").asText()).isEqualTo("completed");
            assertThat(json(business.after("""
                    {"interaction_id":"interaction-1","result":{"accepted":true}}
                    """)).path("operation").asText()).isEqualTo("replayed");
            HttpResponse<String> conflictingAfter = business.after("""
                    {"interaction_id":"interaction-1","result":{"accepted":false}}
                    """);
            assertThat(conflictingAfter.statusCode()).isEqualTo(409);
            assertThat(json(conflictingAfter).path("code").asText()).isEqualTo("INTERACTION_REPORT_CONFLICT");

            JsonNode firstInterface = web.readJson("/api/v1/interfaces/detail?object=Power&command=set");
            assertThat(firstInterface.path("interaction_count").asInt()).isEqualTo(1);
            assertThat(firstInterface.path("schema_changed").asBoolean()).isFalse();
            assertThat(firstInterface.at("/sample_ref/interaction_id").asText()).isEqualTo("interaction-1");
            assertThat(firstInterface.path("field_schema").toString())
                    .isEqualTo("[{\"path\":\"meta\",\"type\":\"object\"},{\"path\":\"meta.enabled\",\"type\":\"boolean\"},{\"path\":\"voltage\",\"type\":\"integer\"}]");

            JsonNode completed = web.readJson("/api/v1/interactions/interaction-1");
            assertThat(completed.path("lifecycle").asText()).isEqualTo("completed");
            assertThat(completed.path("phase").asText()).isEqualTo("after");
            assertThat(completed.at("/result/accepted").asBoolean()).isTrue();

            assertThat(business.before("""
                    {
                      "interaction_id":"interaction-2",
                      "object":"Power",
                      "command":"set",
                      "params":{"voltage":12.5,"label":"calibration"}
                    }
                    """).statusCode()).isEqualTo(200);
            assertThat(business.after("""
                    {"interaction_id":"interaction-2","result":null}
                    """).statusCode()).isEqualTo(200);

            JsonNode changedInterface = web.readJson("/api/v1/interfaces/detail?object=Power&command=set");
            assertThat(changedInterface.path("interaction_count").asInt()).isEqualTo(2);
            assertThat(changedInterface.path("schema_changed").asBoolean()).isTrue();
            assertThat(changedInterface.at("/sample_ref/interaction_id").asText()).isEqualTo("interaction-2");
            assertThat(changedInterface.path("field_schema").toString())
                    .isEqualTo("[{\"path\":\"label\",\"type\":\"string\"},{\"path\":\"voltage\",\"type\":\"number\"}]");
            assertThat(changedInterface.path("field_schema").toString()).doesNotContain("meta.enabled");

            JsonNode interfaceItems = web.readJson("/api/v1/interfaces").path("items");
            assertThat(interfaceItems).hasSize(1);
            assertThat(interfaceItems.get(0).path("object").asText()).isEqualTo("Power");
            assertThat(web.readJson("/api/v1/interfaces?view=current").path("items")).hasSize(1);
            JsonNode interactionItems = web.readJson("/api/v1/interactions").path("items");
            assertThat(interactionItems).hasSize(2);
            assertThat(interactionItems.get(0).path("interaction_id").asText()).isEqualTo("interaction-2");

            assertThat(web.write("POST", "/api/v1/debugging/stop", null).statusCode()).isEqualTo(200);
            assertThat(web.readJson("/api/v1/interfaces?view=current").path("items")).isEmpty();
            assertThat(web.readJson("/api/v1/interfaces").path("items")).hasSize(1);
            String secondSessionId = json(web.write("POST", "/api/v1/sessions", """
                    {"name":"隔离 Session"}
                    """)).path("session_id").asText();
            assertThat(web.write("POST", "/api/v1/sessions/" + secondSessionId + "/current", null).statusCode())
                    .isEqualTo(200);
            assertThat(web.write("POST", "/api/v1/debugging/start", null).statusCode()).isEqualTo(200);
            assertThat(business.before("""
                    {"interaction_id":"interaction-3","object":"Power","command":"set","params":{"channel":1}}
                    """).statusCode()).isEqualTo(200);
            assertThat(business.after("""
                    {"interaction_id":"interaction-3","result":{"accepted":true}}
                    """).statusCode()).isEqualTo(200);
            assertThat(web.readJson("/api/v1/interfaces").at("/items/0/interaction_count").asInt()).isEqualTo(1);
            assertThat(web.readJson("/api/v1/interfaces").at("/items/0/sample_ref/interaction_id").asText())
                    .isEqualTo("interaction-3");
            assertThat(web.readJson("/api/v1/interactions").path("items")).hasSize(1);

            assertThat(web.write("POST", "/api/v1/debugging/stop", null).statusCode()).isEqualTo(200);
            assertThat(web.write("POST", "/api/v1/sessions/" + firstSessionId + "/current", null).statusCode())
                    .isEqualTo(200);
            assertThat(web.readJson("/api/v1/interfaces").at("/items/0/interaction_count").asInt()).isEqualTo(2);
            assertThat(web.readJson("/api/v1/interactions").path("items")).hasSize(2);
            assertThat(web.write("DELETE", "/api/v1/sessions/" + secondSessionId, null).statusCode()).isEqualTo(200);

            HttpResponse<String> skippedAfterStop = business.before("""
                    {"interaction_id":"idle-2","object":"Power","command":"set","params":{"voltage":9}}
                    """);
            assertThat(json(skippedAfterStop).path("tracked").asBoolean()).isFalse();
            assertThat(web.readJson("/api/v1/interactions").path("items")).hasSize(2);
        } finally {
            context.close();
            switchServer.stop(0);
        }

        ConfigurableApplicationContext restarted = start(config);
        try {
            WebClient restored = login(base(restarted));
            assertThat(restored.readJson("/api/v1/interfaces").at("/items/0/interaction_count").asInt())
                    .isEqualTo(2);
            assertThat(restored.readJson("/api/v1/interactions").path("items")).hasSize(2);
        } finally {
            restarted.close();
        }

        JsonNode unavailable = business.beforeFailOpen("""
                {"interaction_id":"offline-1","object":"Power","command":"set","params":{}}
                """);
        assertThat(unavailable.path("proceed").asBoolean()).isTrue();
        assertThat(unavailable.path("product_available").asBoolean()).isFalse();
    }

    private Path writeConfig(Path directory, int switchPort) throws Exception {
        return writeConfig(directory, switchPort, "25m");
    }

    private Path writeConfig(Path directory, int switchPort, String pauseTimeout) throws Exception {
        return writeConfig(directory, switchPort, pauseTimeout, "30m");
    }

    private Path writeConfig(
            Path directory,
            int switchPort,
            String pauseTimeout,
            String controlLeaseTimeout) throws Exception {
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
                      url: http://127.0.0.1:%d/switch
                  security:
                    web-username: admin
                    web-password: admin-secret
                    gateway-token: gateway-secret
                    business-client-token: business-secret
                  control-lease:
                    timeout: %s
                  interaction:
                    pause-timeout: %s
                    max-payload-size: 16MB
                """.formatted(
                directory.resolve("data").toString().replace("\\", "/"),
                switchPort,
                controlLeaseTimeout,
                pauseTimeout), StandardCharsets.UTF_8);
        return config;
    }

    private ConfigurableApplicationContext start(Path config) {
        return BreakHubApplication.application().run("--spring.config.location=" + config.toUri());
    }

    private static URI base(ConfigurableApplicationContext context) {
        int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
        return URI.create("http://127.0.0.1:" + port);
    }

    private WebClient login(URI base) throws Exception {
        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).build();
        HttpResponse<String> login = client.send(HttpRequest.newBuilder(base.resolve("/api/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"username\":\"admin\",\"password\":\"admin-secret\"}"))
                .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(login.statusCode()).isEqualTo(200);
        HttpResponse<String> session = client.send(HttpRequest.newBuilder(base.resolve("/api/auth/session"))
                .GET()
                .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(session.statusCode()).isEqualTo(200);
        return new WebClient(base, client, json(session).path("csrf_token").asText());
    }

    private JsonNode json(HttpResponse<String> response) throws Exception {
        return objectMapper.readTree(response.body());
    }

    private JsonNode jsonArray(String... values) {
        var array = objectMapper.createArrayNode();
        for (String value : values) {
            array.add(value);
        }
        return array;
    }

    private void assertNoPaused(WebClient web) throws Exception {
        for (JsonNode interaction : web.readJson("/api/v1/interactions").path("items")) {
            assertThat(interaction.path("status").asText()).isNotEqualTo("paused");
        }
    }

    private void reportBeforePause(BusinessSimulator business, String interactionId) throws Exception {
        JsonNode report = json(business.before("""
                {"interaction_id":"%s","object":"Selected","command":"before","params":{"value":"original"}}
                """.formatted(interactionId)));
        assertThat(report.path("wait_required").asBoolean()).isTrue();
    }

    private void reportAfterPause(BusinessSimulator business, String interactionId) throws Exception {
        JsonNode before = json(business.before("""
                {"interaction_id":"%s","object":"Selected","command":"after","params":{"value":"original"}}
                """.formatted(interactionId)));
        assertThat(before.path("wait_required").asBoolean()).isFalse();
        JsonNode after = json(business.after("""
                {"interaction_id":"%s","result":{"value":"original"}}
                """.formatted(interactionId)));
        assertThat(after.path("wait_required").asBoolean()).isTrue();
    }

    private void assertPaused(WebClient web, String interactionId, String pausePoint) throws Exception {
        JsonNode pause = web.readJson("/api/v1/interactions/" + interactionId).path("current_pause");
        assertThat(pause.path("status").asText()).isEqualTo("paused");
        assertThat(pause.path("pause_point").asText()).isEqualTo(pausePoint);
    }

    private HttpResponse<String> gatewayWrite(URI base, String path, String body) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(base.resolve(path))
                .header("Authorization", "Bearer gateway-secret")
                .header("X-MBP-Control-Instance", "ticket-07-gateway")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private void assertSuccessfulReplaySet(
            List<HttpResponse<String>> responses,
            String initialOperation,
            String replayOperation) throws Exception {
        assertThat(responses).extracting(HttpResponse::statusCode).containsOnly(200);
        List<String> operations = new ArrayList<>();
        for (HttpResponse<String> response : responses) {
            operations.add(json(response).path("operation").asText());
        }
        assertThat(operations).containsOnly(initialOperation, replayOperation);
        assertThat(operations).filteredOn(initialOperation::equals).hasSize(1);
    }

    private <T> List<T> concurrently(int count, ConcurrentRequest<T> request) throws Exception {
        List<ConcurrentRequest<T>> requests = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            requests.add(request);
        }
        return concurrently(requests);
    }

    private <T> List<T> concurrently(List<ConcurrentRequest<T>> requests) throws Exception {
        CountDownLatch ready = new CountDownLatch(requests.size());
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(requests.size());
        try {
            List<Future<T>> futures = requests.stream()
                    .map(request -> executor.submit(() -> {
                        ready.countDown();
                        start.await();
                        return request.execute();
                    }))
                    .toList();
            ready.await();
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    @FunctionalInterface
    private interface ConcurrentRequest<T> {
        T execute() throws Exception;
    }

    private static ReportingLeaseTestServer startSwitchServer() {
        return ReportingLeaseTestServer.start();
    }

    private static ReportingLeaseTestServer startBlockingDisableSwitchServer(
            CountDownLatch disableStarted,
            CountDownLatch allowDisableToFinish) {
        ReportingLeaseTestServer server = ReportingLeaseTestServer.start();
        server.blockStops(disableStarted, allowDisableToFinish);
        return server;
    }

    private record WebClient(URI base, HttpClient client, String csrfToken) {

        private JsonNode readJson(String path) throws Exception {
            HttpResponse<String> response = client.send(HttpRequest.newBuilder(base.resolve(path)).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            assertThat(response.statusCode()).isEqualTo(200);
            return new ObjectMapper().readTree(response.body());
        }

        private HttpResponse<String> write(String method, String path, String body) throws Exception {
            HttpRequest.BodyPublisher publisher = body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body);
            return client.send(HttpRequest.newBuilder(base.resolve(path))
                    .header("X-MBP-XSRF-TOKEN", csrfToken)
                    .header("Content-Type", "application/json")
                    .method(method, publisher)
                    .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }
    }

    private final class BusinessSimulator {

        private final URI base;
        private final HttpClient client = HttpClient.newHttpClient();

        private BusinessSimulator(URI base) {
            this.base = base;
        }

        private HttpResponse<String> before(String body) throws Exception {
            return post("/api/business/interactions/before", body);
        }

        private java.util.concurrent.CompletableFuture<HttpResponse<String>> beforeAsync(String body) {
            return client.sendAsync(request("/api/business/interactions/before", body),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }

        private HttpResponse<String> after(String body) throws Exception {
            return post("/api/business/interactions/after", body);
        }

        private HttpResponse<String> waitForRelease(String body) throws Exception {
            return post("/api/business/interactions/wait", body);
        }

        private java.util.concurrent.CompletableFuture<HttpResponse<String>> waitForReleaseAsync(String body) {
            return client.sendAsync(request("/api/business/interactions/wait", body),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }

        private HttpResponse<String> managementRead() throws Exception {
            return client.send(HttpRequest.newBuilder(base.resolve("/api/v1/overview"))
                    .header("Authorization", "Bearer business-secret")
                    .GET()
                    .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }

        private HttpResponse<String> gatewayBefore() throws Exception {
            return client.send(HttpRequest.newBuilder(base.resolve("/api/business/interactions/before"))
                    .header("Authorization", "Bearer gateway-secret")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("""
                            {"interaction_id":"forbidden","object":"Power","command":"set","params":{}}
                            """))
                    .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }

        private JsonNode beforeFailOpen(String body) {
            try {
                HttpResponse<String> response = before(body);
                if (response.statusCode() < 500) {
                    return parse(response.body());
                }
            } catch (Exception ignored) {
                // Business integration treats an unavailable debugger as an instruction to proceed.
            }
            return parse("{\"proceed\":true,\"product_available\":false}");
        }

        private HttpResponse<String> post(String path, String body) throws Exception {
            return client.send(request(path, body), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }

        private HttpRequest request(String path, String body) {
            return HttpRequest.newBuilder(base.resolve(path))
                    .header("Authorization", "Bearer business-secret")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
        }

        private JsonNode parse(String body) {
            try {
                return objectMapper.readTree(body);
            } catch (Exception error) {
                throw new IllegalArgumentException(error);
            }
        }
    }
}
