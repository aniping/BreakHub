package com.ateagents.breakhub;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import org.springframework.context.ConfigurableApplicationContext;

public final class BreakHubWindowsLauncher {

    private BreakHubWindowsLauncher() {
    }

    public static void main(String[] args) {
        Path state = HubInstallation.stateDirectory();
        try {
            run(HubInstallation.installationDirectory(), state);
        } catch (Throwable failure) {
            HubInstallation.recordLauncherFailure(state, failure);
            failure.printStackTrace(System.err);
            System.exit(1);
        }
    }

    static void run(Path installation, Path state) throws Exception {
        Files.createDirectories(state);
        try (FileChannel channel = FileChannel.open(
                state.resolve("hub.lock"),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE)) {
            FileLock lock = tryLock(channel);
            if (lock == null) {
                return;
            }
            try (lock; HubControl control = HubControl.open(state)) {
                Path configuration = HubInstallation.initializeConfiguration(installation, state);
                ConfigurableApplicationContext context = BreakHubApplication.application().run(
                        "--spring.config.location=" + configuration.toUri());
                try (context) {
                    control.awaitStop();
                }
            }
        }
    }

    private static FileLock tryLock(FileChannel channel) throws IOException {
        try {
            return channel.tryLock();
        } catch (OverlappingFileLockException ignored) {
            return null;
        }
    }
}
