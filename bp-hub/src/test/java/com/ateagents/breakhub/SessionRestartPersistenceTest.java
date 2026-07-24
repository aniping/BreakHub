package com.ateagents.breakhub;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import com.ateagents.breakhub.domain.CurrentSessionService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class SessionRestartPersistenceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void currentSessionSelectionSurvivesAProductRestart() throws Exception {
        Path directory = Files.createTempDirectory("breakhub-session-restart-");
        Path config = writeConfig(directory);

        String selectedSessionId;
        ConfigurableApplicationContext first = start(config);
        try {
            ProductClient client = login(first);
            HttpResponse<String> created = client.write("POST", "/api/v1/sessions", """
                    {"name":"重启后继续使用"}
                    """);
            assertThat(created.statusCode()).isEqualTo(201);
            selectedSessionId = json(created.body()).get("session_id").toString();

            HttpResponse<String> selected = client.write(
                    "POST", "/api/v1/sessions/" + selectedSessionId + "/current", null);
            assertThat(selected.statusCode()).isEqualTo(200);
            assertThat(json(selected.body()).get("session_id")).isEqualTo(selectedSessionId);
        } finally {
            first.close();
        }

        ConfigurableApplicationContext restarted = start(config);
        try {
            ProductClient client = login(restarted);
            HttpResponse<String> current = client.read("/api/v1/sessions/current");
            assertThat(current.statusCode()).isEqualTo(200);
            assertThat(json(current.body()))
                    .containsEntry("session_id", selectedSessionId)
                    .containsEntry("name", "重启后继续使用")
                    .containsEntry("current", true);
        } finally {
            restarted.close();
        }
    }

    @Test
    void crashLeftoverPauseIsSafelyReleasedBeforeRestartServesRequests() throws Exception {
        Path directory = Files.createTempDirectory("breakhub-crash-pause-");
        Path config = writeConfig(directory);

        String currentSessionId;
        ConfigurableApplicationContext initialized = start(config);
        try {
            currentSessionId = initialized.getBean(CurrentSessionService.class)
                    .current()
                    .sessionId();
        } finally {
            initialized.close();
        }
        insertCrashSnapshot(directory, currentSessionId);

        ConfigurableApplicationContext restarted = start(config);
        try {
            URI base = base(restarted);
            ProductClient client = login(restarted);
            JsonNode overview = objectMapper.readTree(client.read("/api/v1/overview").body());
            assertThat(overview.at("/debugging/status").asText()).isEqualTo("idle");

            JsonNode listItem = objectMapper.readTree(client.read("/api/v1/interactions").body())
                    .at("/items/0");
            assertThat(listItem.path("status").asText()).isEqualTo("running");

            JsonNode detail = objectMapper.readTree(
                    client.read("/api/v1/interactions/crash-pause").body());
            assertThat(detail.path("current_pause").isMissingNode()).isTrue();
            JsonNode pause = detail.at("/pauses/0");
            assertThat(pause.path("status").asText()).isEqualTo("safe_released");
            assertThat(pause.path("injection_status").asText()).isEqualTo("discarded");
            assertThat(pause.path("resolution").asText()).isEqualTo("product_restart");
            assertThat(pause.path("released_content"))
                    .isEqualTo(objectMapper.readTree("{\"mode\":\"original\"}"));

            HttpResponse<String> waited = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(base.resolve("/api/business/interactions/wait"))
                            .header("Authorization", "Bearer business-secret")
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("""
                                    {"interaction_id":"crash-pause","pause_point":"before"}
                                    """))
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            assertThat(waited.statusCode()).isEqualTo(200);
            JsonNode released = objectMapper.readTree(waited.body());
            assertThat(released.path("result").asText()).isEqualTo("safe_released");
            assertThat(released.path("resolution").asText()).isEqualTo("product_restart");
            assertThat(released.path("content"))
                    .isEqualTo(objectMapper.readTree("{\"mode\":\"original\"}"));
        } finally {
            restarted.close();
        }
    }

    private Path writeConfig(Path directory) throws Exception {
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
                      url: http://127.0.0.1:9/switch
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
                """.formatted(directory.resolve("data").toString().replace("\\", "/")), StandardCharsets.UTF_8);
        return config;
    }

    private void insertCrashSnapshot(Path directory, String sessionId) throws Exception {
        Path database = directory.resolve("data").resolve("breakhub.sqlite3");
        String observedAt = Instant.now().toString();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
            connection.createStatement().execute("PRAGMA foreign_keys = ON");
            try (PreparedStatement interaction = connection.prepareStatement("""
                    INSERT INTO product_interaction(
                        interaction_id, session_id, object_name, command_name,
                        params_json, field_schema_json, schema_changed, lifecycle,
                        before_at, updated_at)
                    VALUES (?, ?, 'VNA', 'start', '{"mode":"original"}', '[]', 0, 'running', ?, ?)
                    """)) {
                interaction.setString(1, "crash-pause");
                interaction.setString(2, sessionId);
                interaction.setString(3, observedAt);
                interaction.setString(4, observedAt);
                interaction.executeUpdate();
            }
            try (PreparedStatement pause = connection.prepareStatement("""
                    INSERT INTO product_pause(
                        interaction_id, pause_point, session_id, status,
                        breakpoint_snapshots_json, effective_content_json,
                        injection_audit_json, injection_status, paused_at)
                    VALUES ('crash-pause', 'before', ?, 'paused', '[]', '{"mode":"modified"}',
                            '[{"effective_changed":true}]', 'pending', ?)
                    """)) {
                pause.setString(1, sessionId);
                pause.setString(2, observedAt);
                pause.executeUpdate();
            }
        }
    }

    private ConfigurableApplicationContext start(Path config) {
        return BreakHubApplication.application().run("--spring.config.location=" + config.toUri());
    }

    private static URI base(ConfigurableApplicationContext context) {
        int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
        return URI.create("http://127.0.0.1:" + port);
    }

    private ProductClient login(ConfigurableApplicationContext context) throws Exception {
        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).build();
        URI base = base(context);
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
        return new ProductClient(base, client, json(session.body()).get("csrf_token").toString());
    }

    private Map<String, Object> json(String body) throws Exception {
        return objectMapper.readValue(body, new TypeReference<>() {
        });
    }

    private record ProductClient(URI base, HttpClient client, String csrfToken) {

        private HttpResponse<String> read(String path) throws Exception {
            return client.send(HttpRequest.newBuilder(base.resolve(path)).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }

        private HttpResponse<String> write(String method, String path, String body) throws Exception {
            HttpRequest.BodyPublisher publisher = body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body);
            HttpRequest request = HttpRequest.newBuilder(base.resolve(path))
                    .header("X-MBP-XSRF-TOKEN", csrfToken)
                    .header("Content-Type", "application/json")
                    .method(method, publisher)
                    .build();
            return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }
    }
}
