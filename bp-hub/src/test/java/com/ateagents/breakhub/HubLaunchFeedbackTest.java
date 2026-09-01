package com.ateagents.breakhub;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class HubLaunchFeedbackTest {

    @Test
    void startupFailureMessagePointsToTheDiagnosticLog() {
        String message = HubLaunchFeedback.startupFailureMessage(Path.of("C:\\BreakHubState"));

        assertThat(message)
                .contains("BreakHub 启动失败")
                .contains("C:\\BreakHubState\\logs\\launcher-error.log");
    }

    @Test
    void browserFailureMessageShowsTheAddressAndDiagnosticLog() {
        String message = HubLaunchFeedback.browserFailureMessage(
                Path.of("C:\\BreakHubState"),
                URI.create("http://127.0.0.1:18621/"));

        assertThat(message)
                .contains("BreakHub 已启动")
                .contains("http://127.0.0.1:18621/")
                .contains("C:\\BreakHubState\\logs\\launcher-error.log");
    }
}
