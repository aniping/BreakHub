package com.ateagents.breakhub.probe;

public record ValueResult(int code, String message, Object data) {

    public static ValueResult success(String message) {
        return new ValueResult(0, message, null);
    }

    public static ValueResult success(String message, Object data) {
        return new ValueResult(0, message, data);
    }
}
