package com.ateagents.breakhub;

import java.io.IOException;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;

import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

public final class BreakHubWindowsLauncher {

    private BreakHubWindowsLauncher() {
    }

    public static void main(String[] args) {
        Path installation = HubInstallation.installationDirectory();
        Path state = HubInstallation.stateDirectory(installation);
        try {
            run(installation, state);
        } catch (Throwable failure) {
            HubInstallation.recordLauncherFailure(installation, failure);
            HubLaunchFeedback.showStartupFailure(installation);
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
                try {
                    openBrowser(installation, HubControl.awaitBrowserUri(state));
                } catch (Exception failure) {
                    HubInstallation.recordLauncherFailure(installation, failure);
                    HubLaunchFeedback.showBrowserFailure(installation, null);
                }
                return;
            }
            try (lock; HubControl control = HubControl.open(state)) {
                Path configuration = HubInstallation.initializeConfiguration(installation);
                SpringApplication application = BreakHubApplication.application();
                application.setDefaultProperties(Map.of(
                        "breakhub.home",
                        installation.toAbsolutePath().normalize().toString().replace("\\", "/")));
                ConfigurableApplicationContext context = application.run(
                        "--spring.config.location=" + configuration.toUri());
                try (context) {
                    publishAndOpenBrowser(installation, state, control, context);
                    control.awaitStop();
                }
            }
        }
    }

    private static void publishAndOpenBrowser(
            Path installation,
            Path state,
            HubControl control,
            ConfigurableApplicationContext context) {
        URI browserUri;
        try {
            String address = context.getEnvironment().getProperty("server.address", "127.0.0.1");
            Integer localPort = context.getEnvironment().getProperty("local.server.port", Integer.class);
            int port = localPort != null
                    ? localPort
                    : context.getEnvironment().getRequiredProperty("server.port", Integer.class);
            browserUri = HubBrowser.uri(address, port);
        } catch (RuntimeException failure) {
            HubInstallation.recordLauncherFailure(installation, failure);
            HubLaunchFeedback.showBrowserFailure(installation, null);
            return;
        }
        try {
            control.publishBrowserUri(browserUri);
        } catch (IOException failure) {
            HubInstallation.recordLauncherFailure(installation, failure);
        }
        openBrowser(installation, browserUri);
    }

    private static void openBrowser(Path installation, URI browserUri) {
        try {
            HubBrowser.open(browserUri);
        } catch (Exception failure) {
            HubInstallation.recordLauncherFailure(installation, failure);
            HubLaunchFeedback.showBrowserFailure(installation, browserUri);
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
