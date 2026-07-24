package com.ateagents.breakhub;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

class ProductShutdownSafetyTest {

    @Test
    void closingTheProductSafelyStopsActiveDebugging() throws Exception {
        try (ReportingLeaseTestServer switchServer = ReportingLeaseTestServer.start()) {
            Path directory = Files.createTempDirectory("breakhub-shutdown-test-");
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
                    """.formatted(
                            directory.resolve("data").toString().replace("\\", "/"),
                            switchServer.endpoint()), StandardCharsets.UTF_8);

            Set<Thread> threadsBeforeStart = Thread.getAllStackTraces().keySet();
            ConfigurableApplicationContext context = BreakHubApplication.application().run(
                    "--spring.config.location=" + config.toUri());
            String createdLeaseId;
            Thread reportingRenewalThread;
            Thread controlExpiryThread;
            try {
                int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
                HttpResponse<String> started = HttpClient.newHttpClient().send(HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + port + "/api/v1/debugging/start"))
                        .header("Authorization", "Bearer gateway-secret")
                        .header("X-MBP-Control-Instance", "gateway-a")
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                assertThat(started.statusCode()).isEqualTo(200);
                assertThat(switchServer.enabled()).isTrue();
                createdLeaseId = switchServer.activeLeaseId();
                reportingRenewalThread = findNewThread(
                        threadsBeforeStart, "breakhub-reporting-renewal");
                controlExpiryThread = findNewThread(
                        threadsBeforeStart, "breakhub-control-expiry");
            } finally {
                context.close();
            }

            assertThreadStops(reportingRenewalThread);
            assertThreadStops(controlExpiryThread);
            assertThat(switchServer.enabled()).isFalse();
            assertThat(switchServer.attempts()).isEqualTo(2);
            assertThat(switchServer.lastStoppedLeaseId()).isEqualTo(createdLeaseId);
        }
    }

    private static Thread findNewThread(Set<Thread> existingThreads, String name)
            throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        do {
            Thread match = Thread.getAllStackTraces().keySet().stream()
                    .filter(thread -> !existingThreads.contains(thread))
                    .filter(thread -> name.equals(thread.getName()))
                    .findFirst()
                    .orElse(null);
            if (match != null) {
                return match;
            }
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Expected product thread was not started: " + name);
    }

    private static void assertThreadStops(Thread thread) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (thread.isAlive() && System.nanoTime() < deadline) {
            thread.join(10);
        }
        assertThat(thread.isAlive()).isFalse();
    }
}
