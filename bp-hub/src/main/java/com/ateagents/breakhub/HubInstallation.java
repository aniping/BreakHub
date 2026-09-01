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

final class HubInstallation {

    private HubInstallation() {
    }

    static Path stateDirectory(Path installation) {
        return installation.toAbsolutePath().normalize().resolve("data").resolve(".runtime");
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

    static Path initializeConfiguration(Path installation) throws IOException {
        Path normalizedInstallation = installation.toAbsolutePath().normalize();
        Files.createDirectories(normalizedInstallation.resolve("data"));
        Files.createDirectories(normalizedInstallation.resolve("logs"));
        Path configuration = normalizedInstallation.resolve("application.yml");
        if (Files.isRegularFile(configuration)) {
            return configuration;
        }

        Path template = normalizedInstallation.resolve("application.yml.template");
        String source = Files.readString(template, StandardCharsets.UTF_8);
        Path temporary = Files.createTempFile(normalizedInstallation, "application-", ".yml.tmp");
        try {
            Files.writeString(
                    temporary,
                    source,
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

    static void recordLauncherFailure(Path installation, Throwable failure) {
        try {
            Path logs = installation.toAbsolutePath().normalize().resolve("logs");
            Files.createDirectories(logs);
            String message = "%s %s: %s%n".formatted(
                    Instant.now(),
                    failure.getClass().getName(),
                    String.valueOf(failure.getMessage()));
            Files.writeString(
                    logs.resolve("launcher-error.log"),
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
