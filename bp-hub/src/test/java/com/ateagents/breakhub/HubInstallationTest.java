package com.ateagents.breakhub;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

class HubInstallationTest {

    @Test
    void usesLocalApplicationDataForMutableProductState() {
        Path state = HubInstallation.stateDirectory(
                Map.of("LOCALAPPDATA", "C:\\Users\\tester\\AppData\\Local"),
                "C:\\Users\\tester");

        assertThat(state).isEqualTo(Path.of(
                "C:\\Users\\tester\\AppData\\Local", "BreakHub"));
    }

    @Test
    void createsConfigurationFromTheInstalledTemplateOnlyOnce() throws Exception {
        Path directory = Files.createTempDirectory("breakhub-installation-test-");
        Path installation = directory.resolve("application");
        Path state = directory.resolve("state");
        Files.createDirectories(installation);
        Files.writeString(installation.resolve("application.yml.template"), """
                breakhub:
                  data-directory: "@BREAKHUB_HOME@/data"
                logging:
                  file:
                    name: "@BREAKHUB_HOME@/logs/breakhub.log"
                """, StandardCharsets.UTF_8);

        Path config = HubInstallation.initializeConfiguration(installation, state);

        String normalizedHome = state.toAbsolutePath().normalize().toString().replace("\\", "/");
        assertThat(config).isEqualTo(state.resolve("application.yml"));
        assertThat(Files.readString(config, StandardCharsets.UTF_8))
                .contains("data-directory: \"" + normalizedHome + "/data\"")
                .contains("name: \"" + normalizedHome + "/logs/breakhub.log\"")
                .doesNotContain("@BREAKHUB_HOME@");
        assertThat(state.resolve("data")).isDirectory();
        assertThat(state.resolve("logs")).isDirectory();

        Files.writeString(config, "user-edited: true\n", StandardCharsets.UTF_8);
        HubInstallation.initializeConfiguration(installation, state);

        assertThat(Files.readString(config, StandardCharsets.UTF_8))
                .isEqualTo("user-edited: true\n");
    }

    @Test
    void resolvesTheInstallationBesideTheJpackageLauncher() {
        Path installation = HubInstallation.installationDirectory(
                "C:\\Users\\tester\\AppData\\Local\\Programs\\BreakHub\\BreakHub.exe");

        assertThat(installation).isEqualTo(Path.of(
                "C:\\Users\\tester\\AppData\\Local\\Programs\\BreakHub"));
    }

    @Test
    void authenticatesALocalStopRequestAndRemovesLifecycleState() throws Exception {
        Path state = Files.createTempDirectory("breakhub-control-test-");
        HubControl control = HubControl.open(state);
        CompletableFuture<Boolean> stopped = CompletableFuture.supplyAsync(() -> {
            try {
                return HubControl.requestStop(state);
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        });

        Thread.sleep(100);
        assertThat(stopped).isNotDone();
        control.awaitStop();
        control.close();

        assertThat(stopped.get(5, TimeUnit.SECONDS)).isTrue();
        assertThat(state.resolve("run.properties")).doesNotExist();
    }

    @Test
    void waitsForTheControlEndpointWhileTheHubIsStarting() throws Exception {
        Path state = Files.createTempDirectory("breakhub-starting-control-test-");
        Path lockFile = state.resolve("hub.lock");
        try (FileChannel channel = FileChannel.open(
                    lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                FileLock ignored = channel.lock()) {
            CompletableFuture<Boolean> stopped = CompletableFuture.supplyAsync(() -> {
                try {
                    return HubControl.requestStop(state);
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
            });

            Thread.sleep(100);
            assertThat(stopped).isNotDone();
            try (HubControl control = HubControl.open(state)) {
                control.awaitStop();
            }

            assertThat(stopped.get(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void exposesTheRunningHubAddressToAdditionalLauncherInvocations() throws Exception {
        Path state = Files.createTempDirectory("breakhub-browser-address-test-");
        URI browserUri = URI.create("http://127.0.0.1:18621/");

        try (FileChannel channel = FileChannel.open(
                    state.resolve("hub.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                FileLock ignored = channel.lock();
                HubControl control = HubControl.open(state)) {
            CompletableFuture<URI> exposed = CompletableFuture.supplyAsync(() -> {
                try {
                    return HubControl.awaitBrowserUri(state);
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
            });

            Thread.sleep(100);
            assertThat(exposed).isNotDone();
            control.publishBrowserUri(browserUri);

            assertThat(exposed.get(5, TimeUnit.SECONDS)).isEqualTo(browserUri);
        }
    }
}
