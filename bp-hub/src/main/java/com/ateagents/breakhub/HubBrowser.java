package com.ateagents.breakhub;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;

final class HubBrowser {

    private HubBrowser() {
    }

    static URI uri(String serverAddress, int port) {
        String host = serverAddress == null ? "" : serverAddress.strip();
        if (host.isEmpty() || "0.0.0.0".equals(host) || "::".equals(host) || "[::]".equals(host)) {
            host = "127.0.0.1";
        }
        try {
            return new URI("http", null, host, port, "/", null, null);
        } catch (URISyntaxException failure) {
            throw new IllegalArgumentException("Invalid BreakHub server address", failure);
        }
    }

    static void open(URI uri) throws IOException {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            try {
                Desktop.getDesktop().browse(uri);
                return;
            } catch (IOException | RuntimeException ignored) {
                // Use the native Windows URL handler as a fallback.
            }
        }
        new ProcessBuilder(
                "rundll32.exe",
                "url.dll,FileProtocolHandler",
                uri.toASCIIString()).start();
    }
}
