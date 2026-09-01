package com.ateagents.breakhub;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Properties;

final class HubControl implements AutoCloseable {

    private static final String STOP = "STOP ";
    private static final String STOPPING = "STOPPING";
    private static final int SOCKET_TIMEOUT_MILLIS = 3_000;
    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(30);

    private final ServerSocket server;
    private final Path controlFile;
    private final String token;

    private HubControl(ServerSocket server, Path controlFile, String token) {
        this.server = server;
        this.controlFile = controlFile;
        this.token = token;
    }

    static HubControl open(Path state) throws IOException {
        Files.createDirectories(state);
        ServerSocket server = new ServerSocket();
        server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 4);
        String token = randomToken();
        Path controlFile = state.resolve("run.properties");
        Properties properties = new Properties();
        properties.setProperty("pid", Long.toString(ProcessHandle.current().pid()));
        properties.setProperty("port", Integer.toString(server.getLocalPort()));
        properties.setProperty("token", token);
        Path temporary = Files.createTempFile(state, "run-", ".properties.tmp");
        try (BufferedWriter writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
            properties.store(writer, "BreakHub local lifecycle control");
        }
        try {
            Files.move(
                    temporary,
                    controlFile,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException failure) {
            Files.deleteIfExists(temporary);
            server.close();
            throw failure;
        }
        return new HubControl(server, controlFile, token);
    }

    void awaitStop() throws IOException {
        while (!server.isClosed()) {
            try (Socket socket = server.accept()) {
                socket.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
                BufferedReader reader = new BufferedReader(new InputStreamReader(
                        socket.getInputStream(), StandardCharsets.UTF_8));
                BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                        socket.getOutputStream(), StandardCharsets.UTF_8));
                String request = reader.readLine();
                if (request != null && request.startsWith(STOP)
                        && tokensMatch(token, request.substring(STOP.length()))) {
                    writer.write(STOPPING);
                    writer.newLine();
                    writer.flush();
                    return;
                }
                writer.write("DENIED");
                writer.newLine();
                writer.flush();
            }
        }
    }

    static boolean requestStop(Path state) throws IOException, InterruptedException {
        Path controlFile = state.resolve("run.properties");
        if (!Files.isRegularFile(controlFile)) {
            return false;
        }
        Endpoint endpoint = readEndpoint(controlFile);
        if (!ProcessHandle.of(endpoint.pid()).map(ProcessHandle::isAlive).orElse(false)) {
            Files.deleteIfExists(controlFile);
            return false;
        }
        try (Socket socket = new Socket()) {
            socket.connect(
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), endpoint.port()),
                    SOCKET_TIMEOUT_MILLIS);
            socket.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                    socket.getOutputStream(), StandardCharsets.UTF_8));
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    socket.getInputStream(), StandardCharsets.UTF_8));
            writer.write(STOP + endpoint.token());
            writer.newLine();
            writer.flush();
            if (!STOPPING.equals(reader.readLine())) {
                throw new IOException("BreakHub rejected the local stop request");
            }
        }
        long deadline = System.nanoTime() + SHUTDOWN_TIMEOUT.toNanos();
        while (Files.exists(controlFile) && System.nanoTime() < deadline) {
            Thread.sleep(100);
        }
        if (Files.exists(controlFile)) {
            throw new IOException("BreakHub did not stop within " + SHUTDOWN_TIMEOUT.toSeconds() + " seconds");
        }
        return true;
    }

    @Override
    public void close() throws IOException {
        try {
            server.close();
        } finally {
            Files.deleteIfExists(controlFile);
        }
    }

    private static Endpoint readEndpoint(Path controlFile) throws IOException {
        Properties properties = new Properties();
        try (BufferedReader reader = Files.newBufferedReader(controlFile, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        try {
            return new Endpoint(
                    Long.parseLong(properties.getProperty("pid")),
                    Integer.parseInt(properties.getProperty("port")),
                    properties.getProperty("token"));
        } catch (RuntimeException failure) {
            throw new IOException("BreakHub lifecycle state is invalid", failure);
        }
    }

    private static boolean tokensMatch(String expected, String actual) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    private static String randomToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private record Endpoint(long pid, int port, String token) {
    }
}
