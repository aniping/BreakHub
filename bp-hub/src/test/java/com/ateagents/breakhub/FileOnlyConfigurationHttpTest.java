package com.ateagents.breakhub;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

class FileOnlyConfigurationHttpTest {

    @Test
    void fileConfigurationWinsOverEnvironmentCommandLineAndSystemProperties() throws Exception {
        Path directory = Files.createTempDirectory("breakhub-file-config-test-");
        Path config = directory.resolve("application.yml");
        Files.writeString(config, """
                server:
                  address: 127.0.0.1
                  port: 0
                breakhub:
                  data-directory: %s
                  equipment:
                    id: file-equipment
                    display-name: 文件装备
                    debugger-switch:
                      url: http://127.0.0.1:9/debugger
                  security:
                    web-username: file-admin
                    web-password: file-password
                    gateway-token: file-gateway-token
                    business-client-token: file-business-token
                  control-lease:
                    timeout: 30m
                  interaction:
                    pause-timeout: 25m
                    max-payload-size: 16MB
                """.formatted(directory.resolve("data").toString().replace("\\", "/")), StandardCharsets.UTF_8);

        String propertyName = "breakhub.equipment.display-name";
        String previous = System.getProperty(propertyName);
        System.setProperty(propertyName, "JVM 覆盖装备");
        SpringApplication application = BreakHubApplication.application();
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().replace(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new MapPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, Map.of(
                        "breakhub.equipment.id", "environment-equipment",
                        "breakhub.security.gateway-token", "environment-gateway-token")));
        application.setEnvironment(environment);
        try (ConfigurableApplicationContext context = application.run(
                "--spring.config.location=" + config.toUri(),
                "--breakhub.equipment.id=cli-equipment",
                "--breakhub.security.gateway-token=cli-gateway-token")) {
            int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
            HttpClient client = HttpClient.newHttpClient();

            HttpResponse<String> overview = client.send(HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + "/api/v1/overview"))
                    .header("Authorization", "Bearer file-gateway-token")
                    .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            assertThat(overview.statusCode()).isEqualTo(200);
            assertThat(overview.body())
                    .contains("\"equipment_id\":\"file-equipment\"")
                    .contains("\"display_name\":\"文件装备\"")
                    .doesNotContain("environment-equipment")
                    .doesNotContain("cli-equipment")
                    .doesNotContain("JVM 覆盖装备");

            HttpResponse<String> settings = client.send(HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + "/api/v1/settings"))
                    .header("Authorization", "Bearer file-gateway-token")
                    .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            assertThat(settings.statusCode()).isEqualTo(200);
            assertThat(settings.body()).contains("\"port\":" + port);

            HttpResponse<String> environmentToken = client.send(HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + "/api/v1/overview"))
                    .header("Authorization", "Bearer environment-gateway-token")
                    .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            assertThat(environmentToken.statusCode()).isEqualTo(401);

            HttpResponse<String> commandLineToken = client.send(HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + "/api/v1/overview"))
                    .header("Authorization", "Bearer cli-gateway-token")
                    .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            assertThat(commandLineToken.statusCode()).isEqualTo(401);
        } finally {
            if (previous == null) {
                System.clearProperty(propertyName);
            } else {
                System.setProperty(propertyName, previous);
            }
        }
    }
}
