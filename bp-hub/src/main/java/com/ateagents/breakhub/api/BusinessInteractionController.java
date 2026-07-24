package com.ateagents.breakhub.api;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ateagents.breakhub.domain.InteractionObservationService;
import com.fasterxml.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/business/interactions")
public class BusinessInteractionController {

    private final InteractionObservationService observations;

    public BusinessInteractionController(InteractionObservationService observations) {
        this.observations = observations;
    }

    @PostMapping("/before")
    public Map<String, Object> before(@RequestBody JsonNode body) {
        JsonNode params = body.get("params");
        if (params == null || !params.isObject()) {
            throw invalid("before.params 必须是 JSON 对象");
        }
        return observations.before(
                text(body, "interaction_id", 200),
                text(body, "object", 200),
                text(body, "command", 200),
                params);
    }

    @PostMapping("/after")
    public Map<String, Object> after(@RequestBody JsonNode body) {
        if (!body.has("result")) {
            throw invalid("after.result 必须存在，允许值为 null");
        }
        return observations.after(
                text(body, "interaction_id", 200),
                body.get("result"));
    }

    @PostMapping("/wait")
    public Map<String, Object> waitForRelease(@RequestBody JsonNode body) {
        String pausePoint = text(body, "pause_point", 20);
        if (!("before".equals(pausePoint) || "after".equals(pausePoint))) {
            throw invalid("pause_point 只能是 before 或 after");
        }
        return observations.waitForRelease(
                text(body, "interaction_id", 200),
                pausePoint);
    }

    static String text(JsonNode body, String field, int maxLength) {
        JsonNode value = body.get(field);
        String text = value == null || !value.isTextual() ? "" : value.asText().trim();
        if (text.isEmpty() || text.length() > maxLength) {
            throw invalid(field + " 必须为 1 到 " + maxLength + " 个字符");
        }
        return text;
    }

    static ProductException invalid(String message) {
        return new ProductException(HttpStatus.BAD_REQUEST, "INVALID_INTERACTION_REPORT", message);
    }
}
