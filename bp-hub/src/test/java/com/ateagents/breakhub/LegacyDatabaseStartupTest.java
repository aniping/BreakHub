package com.ateagents.breakhub;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;

import org.junit.jupiter.api.Test;

class LegacyDatabaseStartupTest {

    @Test
    void legacyDatabaseIsRejectedInsteadOfMigratedOrSilentlyReused() throws Exception {
        Path directory = Files.createTempDirectory("breakhub-legacy-database-test-");
        Path dataDirectory = directory.resolve("data");
        Files.createDirectories(dataDirectory);
        try (var connection = DriverManager.getConnection(
                "jdbc:sqlite:" + dataDirectory.resolve("breakhub.sqlite3"));
                var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE debug_cycle(id TEXT PRIMARY KEY)");
        }

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
                      url: http://127.0.0.1:9/debugger
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
                """.formatted(dataDirectory.toString().replace("\\", "/")), StandardCharsets.UTF_8);

        Throwable failure = catchThrowable(() -> {
            try (var ignored = BreakHubApplication.application().run(
                    "--spring.config.location=" + config.toUri())) {
                throw new AssertionError("旧数据库不应成功启动");
            }
        });

        assertThat(failure)
                .isNotNull()
                .hasStackTraceContaining("LEGACY_DATABASE_UNSUPPORTED")
                .hasStackTraceContaining("请使用新的数据目录");
    }
}
