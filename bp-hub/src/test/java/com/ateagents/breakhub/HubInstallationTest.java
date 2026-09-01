package com.ateagents.breakhub;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

class HubInstallationTest {

    @Test
    void keepsLifecycleStateUnderTheInstallationDataDirectory() {
        Path installation = Path.of("C:\\Program Files\\BreakHub");
        Path state = HubInstallation.stateDirectory(installation);

        assertThat(state).isEqualTo(installation.resolve("data").resolve(".runtime"));
    }

    @Test
    void createsConfigurationFromTheInstalledTemplateOnlyOnce() throws Exception {
        Path directory = Files.createTempDirectory("breakhub-installation-test-");
        Path installation = directory.resolve("application");
        Files.createDirectories(installation);
        Files.writeString(installation.resolve("application.yml.template"), """
                breakhub:
                  data-directory: "${breakhub.home}/data"
                logging:
                  file:
                    name: "${breakhub.home}/logs/breakhub.log"
                """, StandardCharsets.UTF_8);

        Path config = HubInstallation.initializeConfiguration(installation);

        assertThat(config).isEqualTo(installation.resolve("application.yml"));
        assertThat(Files.readString(config, StandardCharsets.UTF_8))
                .contains("data-directory: \"${breakhub.home}/data\"")
                .contains("name: \"${breakhub.home}/logs/breakhub.log\"");
        assertThat(installation.resolve("data")).isDirectory();
        assertThat(installation.resolve("logs")).isDirectory();

        Files.writeString(config, "user-edited: true\n", StandardCharsets.UTF_8);
        HubInstallation.initializeConfiguration(installation);

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

    @Test
    void ignoresAStaleBrowserAddressWhileAReplacementHubStarts() throws Exception {
        Path state = Files.createTempDirectory("breakhub-stale-browser-address-test-");
        URI staleUri = URI.create("http://127.0.0.1:18000/");
        URI currentUri = URI.create("http://127.0.0.1:18621/");
        Files.writeString(state.resolve("run.properties"), """
                pid=9223372036854775807
                browser-uri=http://127.0.0.1:18000/
                """, StandardCharsets.UTF_8);

        try (FileChannel channel = FileChannel.open(
                    state.resolve("hub.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                FileLock ignored = channel.lock()) {
            CompletableFuture<URI> exposed = CompletableFuture.supplyAsync(() -> {
                try {
                    return HubControl.awaitBrowserUri(state);
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
            });

            Thread.sleep(100);
            assertThat(exposed).isNotDone();
            try (HubControl control = HubControl.open(state)) {
                control.publishBrowserUri(currentUri);
                assertThat(exposed.get(5, TimeUnit.SECONDS))
                        .isEqualTo(currentUri)
                        .isNotEqualTo(staleUri);
            }
        }
    }
}
