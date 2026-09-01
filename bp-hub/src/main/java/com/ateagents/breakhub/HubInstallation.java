package com.ateagents.breakhub;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Map;

final class HubInstallation {

    private static final String PRODUCT_DIRECTORY = "BreakHub";
    private static final String HOME_PLACEHOLDER = "@BREAKHUB_HOME@";

    private HubInstallation() {
    }

    static Path stateDirectory() {
        return stateDirectory(System.getenv(), System.getProperty("user.home"));
    }

    static Path stateDirectory(Map<String, String> environment, String userHome) {
        String localApplicationData = environment.get("LOCALAPPDATA");
        if (localApplicationData != null && !localApplicationData.isBlank()) {
            return Path.of(localApplicationData, PRODUCT_DIRECTORY).toAbsolutePath().normalize();
        }
        if (userHome == null || userHome.isBlank()) {
            throw new IllegalStateException("Neither LOCALAPPDATA nor user.home is available");
        }
        return Path.of(userHome, ".breakhub").toAbsolutePath().normalize();
    }

    static Path installationDirectory() {
        return installationDirectory(System.getProperty("jpackage.app-path"));
    }

    static Path installationDirectory(String launcherPath) {
        if (launcherPath == null || launcherPath.isBlank()) {
            throw new IllegalStateException("BreakHub must be started by its packaged launcher");
        }
        Path parent = Path.of(launcherPath).toAbsolutePath().normalize().getParent();
        if (parent == null) {
            throw new IllegalStateException("Packaged launcher has no installation directory");
        }
        return parent;
    }

    static Path initializeConfiguration(Path installation, Path state) throws IOException {
        Path normalizedState = state.toAbsolutePath().normalize();
        Files.createDirectories(normalizedState);
        Files.createDirectories(normalizedState.resolve("data"));
        Files.createDirectories(normalizedState.resolve("logs"));
        Path configuration = normalizedState.resolve("application.yml");
        if (Files.isRegularFile(configuration)) {
            return configuration;
        }

        Path template = installation.resolve("application.yml.template");
        String source = Files.readString(template, StandardCharsets.UTF_8);
        if (!source.contains(HOME_PLACEHOLDER)) {
            throw new IOException("Installer configuration template is missing " + HOME_PLACEHOLDER);
        }
        String yamlHome = normalizedState.toString().replace("\\", "/");
        Path temporary = Files.createTempFile(normalizedState, "application-", ".yml.tmp");
        try {
            Files.writeString(
                    temporary,
                    source.replace(HOME_PLACEHOLDER, yamlHome),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING);
            moveNewFile(temporary, configuration);
        } catch (FileAlreadyExistsException ignored) {
            Files.deleteIfExists(temporary);
        } finally {
            Files.deleteIfExists(temporary);
        }
        return configuration;
    }

    static void recordLauncherFailure(Path state, Throwable failure) {
        try {
            Files.createDirectories(state);
            String message = "%s %s: %s%n".formatted(
                    Instant.now(),
                    failure.getClass().getName(),
                    String.valueOf(failure.getMessage()));
            Files.writeString(
                    state.resolve("launcher-error.log"),
                    message,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // The original startup failure remains the authoritative error.
        }
    }

    private static void moveNewFile(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, destination);
        }
    }
}
