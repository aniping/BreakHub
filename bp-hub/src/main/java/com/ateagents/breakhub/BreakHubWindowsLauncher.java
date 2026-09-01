package com.ateagents.breakhub;

import java.io.IOException;
import java.net.URI;
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
            HubLaunchFeedback.showStartupFailure(state);
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
                openBrowser(state, HubControl.awaitBrowserUri(state));
                return;
            }
            try (lock; HubControl control = HubControl.open(state)) {
                Path configuration = HubInstallation.initializeConfiguration(installation, state);
                ConfigurableApplicationContext context = BreakHubApplication.application().run(
                        "--spring.config.location=" + configuration.toUri());
                try (context) {
                    publishAndOpenBrowser(state, control, context);
                    control.awaitStop();
                }
            }
        }
    }

    private static void publishAndOpenBrowser(
            Path state, HubControl control, ConfigurableApplicationContext context) {
        URI browserUri;
        try {
            String address = context.getEnvironment().getProperty("server.address", "127.0.0.1");
            Integer localPort = context.getEnvironment().getProperty("local.server.port", Integer.class);
            int port = localPort != null
                    ? localPort
                    : context.getEnvironment().getRequiredProperty("server.port", Integer.class);
            browserUri = HubBrowser.uri(address, port);
        } catch (RuntimeException failure) {
            HubInstallation.recordLauncherFailure(state, failure);
            return;
        }
        try {
            control.publishBrowserUri(browserUri);
        } catch (IOException failure) {
            HubInstallation.recordLauncherFailure(state, failure);
        }
        openBrowser(state, browserUri);
    }

    private static void openBrowser(Path state, URI browserUri) {
        try {
            HubBrowser.open(browserUri);
        } catch (Exception failure) {
            HubInstallation.recordLauncherFailure(state, failure);
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
