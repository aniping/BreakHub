package com.ateagents.breakhub.domain;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;

import com.ateagents.breakhub.api.ProductException;

record InteractionListQuery(
        Page pageState,
        Filters filters,
        TimeRange timeRange) {

    private static final int DEFAULT_SIZE = 100;
    private static final Set<String> STATUSES = Set.of("paused", "in_progress", "completed");
    private static final Set<String> PAUSE_POINTS = Set.of("before", "after");

    static InteractionListQuery from(Map<String, String> parameters) {
        int page = integer(parameters.get("page"), 0, "page");
        int size = integer(parameters.get("size"), DEFAULT_SIZE, "size");
        String query = text(parameters.get("query"), "query");
        String object = text(parameters.get("object"), "object");
        String command = text(parameters.get("command"), "command");
        String status = option(parameters.get("status"), STATUSES, "status");
        String pausePoint = option(parameters.get("pause_point"), PAUSE_POINTS, "pause_point");
        if (page < 0 || size < 1 || size > DEFAULT_SIZE) {
            throw invalid("page 必须大于等于 0，size 必须为 1 到 100");
        }
        return new InteractionListQuery(
                new Page(page, size),
                new Filters(query, object, command, status, pausePoint),
                new TimeRange(instant(parameters.get("from"), "from"), instant(parameters.get("to"), "to")));
    }

    int page() { return pageState.page(); }
    int size() { return pageState.size(); }
    String query() { return filters.query(); }
    String object() { return filters.object(); }
    String command() { return filters.command(); }
    String status() { return filters.status(); }
    String pausePoint() { return filters.pausePoint(); }
    String from() { return timeRange.from(); }
    String to() { return timeRange.to(); }

    private static int integer(String value, int fallback, String field) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException error) {
            throw invalid(field + " 必须是整数");
        }
    }

    private static String text(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() > 200) throw invalid(field + " 不能超过 200 个字符");
        return normalized;
    }

    private static String option(String value, Set<String> options, String field) {
        String normalized = text(value, field);
        if (!normalized.isEmpty() && !options.contains(normalized)) {
            throw invalid(field + " 取值无效");
        }
        return normalized;
    }

    private static String instant(String value, String field) {
        String normalized = text(value, field);
        if (normalized.isEmpty()) return "";
        try {
            return Instant.parse(normalized).toString();
        } catch (DateTimeParseException error) {
            throw invalid(field + " 必须是 ISO-8601 时间");
        }
    }

    private static ProductException invalid(String message) {
        return new ProductException(HttpStatus.BAD_REQUEST, "INVALID_INTERACTION_FILTER", message);
    }

    private record Page(int page, int size) {
    }

    private record Filters(String query, String object, String command, String status, String pausePoint) {
    }

    private record TimeRange(String from, String to) {
    }
}
