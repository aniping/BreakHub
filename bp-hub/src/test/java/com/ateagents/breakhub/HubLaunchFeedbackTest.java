package com.ateagents.breakhub;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class HubLaunchFeedbackTest {

    @Test
    void startupFailureMessagePointsToTheDiagnosticLog() {
        String message = HubLaunchFeedback.startupFailureMessage(Path.of("C:\\BreakHubState"));

        assertThat(message)
                .contains("BreakHub 启动失败")
                .contains("C:\\BreakHubState\\launcher-error.log");
    }
}
