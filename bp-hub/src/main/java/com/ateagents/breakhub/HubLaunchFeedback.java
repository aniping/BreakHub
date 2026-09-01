package com.ateagents.breakhub;

import java.awt.GraphicsEnvironment;
import java.nio.file.Path;

import javax.swing.JOptionPane;

final class HubLaunchFeedback {

    private HubLaunchFeedback() {
    }

    static String startupFailureMessage(Path state) {
        return "BreakHub 启动失败。\n\n诊断日志：\n"
                + state.resolve("launcher-error.log")
                + "\n"
                + state.resolve("logs").resolve("breakhub.log");
    }

    static void showStartupFailure(Path state) {
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }
        try {
            JOptionPane.showMessageDialog(
                    null,
                    startupFailureMessage(state),
                    "BreakHub",
                    JOptionPane.ERROR_MESSAGE);
        } catch (RuntimeException ignored) {
            // The diagnostic files remain available when Windows cannot show a dialog.
        }
    }
}
