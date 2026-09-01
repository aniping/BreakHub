package com.ateagents.breakhub;

import java.awt.GraphicsEnvironment;
import java.net.URI;
import java.nio.file.Path;

import javax.swing.JOptionPane;

final class HubLaunchFeedback {

    private HubLaunchFeedback() {
    }

    static String startupFailureMessage(Path installation) {
        return "BreakHub 启动失败。\n\n诊断日志：\n"
                + installation.resolve("logs").resolve("launcher-error.log")
                + "\n"
                + installation.resolve("logs").resolve("breakhub.log");
    }

    static void showStartupFailure(Path installation) {
        showError(startupFailureMessage(installation));
    }

    static String browserFailureMessage(Path installation, URI browserUri) {
        String address = browserUri == null
                ? "无法确定访问地址。"
                : "请手动访问：\n" + browserUri;
        return "BreakHub 已启动，但无法自动打开浏览器。\n\n"
                + address
                + "\n\n诊断日志：\n"
                + installation.resolve("logs").resolve("launcher-error.log");
    }

    static void showBrowserFailure(Path installation, URI browserUri) {
        showError(browserFailureMessage(installation, browserUri));
    }

    static String startupWaitFailureMessage(Path installation) {
        return "BreakHub 尚未就绪或启动失败，无法打开浏览器。\n\n诊断日志：\n"
                + installation.resolve("logs").resolve("launcher-error.log")
                + "\n"
                + installation.resolve("logs").resolve("breakhub.log");
    }

    static void showStartupWaitFailure(Path installation) {
        showError(startupWaitFailureMessage(installation));
    }

    private static void showError(String message) {
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }
        try {
            JOptionPane.showMessageDialog(
                    null,
                    message,
                    "BreakHub",
                    JOptionPane.ERROR_MESSAGE);
        } catch (RuntimeException ignored) {
            // The diagnostic files remain available when Windows cannot show a dialog.
        }
    }
}
