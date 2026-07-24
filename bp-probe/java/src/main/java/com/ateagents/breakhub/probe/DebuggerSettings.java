package com.ateagents.breakhub.probe;

public class DebuggerSettings {

    public static volatile boolean enabled = false;

    public static String serverUrl = "http://127.0.0.1:18621";

    public static String businessClientToken = "";

    public static String serviceName = "instrument-service";

    public static int connectTimeoutMs = 300;

    public static int readTimeoutMs = 1000;

    public static int breakpointTimeoutMs = 300000;

    private DebuggerSettings() {
    }
}
