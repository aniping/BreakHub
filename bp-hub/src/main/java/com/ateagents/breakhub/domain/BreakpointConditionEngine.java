package com.ateagents.breakhub.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.ateagents.breakhub.api.ProductException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

@Component
class BreakpointConditionEngine {

    private final ObjectMapper objectMapper;

    BreakpointConditionEngine(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    ArrayNode normalize(JsonNode requested) {
        if (requested == null) {
            return objectMapper.createArrayNode();
        }
        if (!requested.isArray()) {
            throw invalid("conditions 必须是数组");
        }
        Map<String, ObjectNode> normalized = new TreeMap<>();
        for (JsonNode raw : requested) {
            if (!raw.isObject()) {
                throw invalid("每个 condition 必须是对象");
            }
            JsonNode sourceNode = raw.get("source");
            String source = sourceNode != null && sourceNode.isTextual()
                    ? sourceNode.asText()
                    : "";
            if (!("params".equals(source) || "result".equals(source))) {
                throw invalid("condition.source 只能是 params 或 result");
            }
            JsonNode fieldNode = raw.get("field_path");
            String fieldPath = fieldNode != null && fieldNode.isTextual() ? fieldNode.asText().trim() : "";
            validateFieldPath(fieldPath);
            JsonNode operatorNode = raw.get("operator");
            String operator = operatorNode != null && operatorNode.isTextual()
                    ? operatorNode.asText().trim()
                    : "";
            if (!("eq".equals(operator) || "contains_any".equals(operator))) {
                throw invalid("operator 只能是 eq 或 contains_any");
            }
            if (!raw.has("value")) {
                throw invalid("condition.value 必须存在");
            }
            ObjectNode condition = objectMapper.createObjectNode();
            condition.put("source", source);
            condition.put("field_path", fieldPath);
            condition.put("operator", operator);
            JsonNode value = raw.get("value");
            if ("eq".equals(operator)) {
                if (!isScalar(value)) {
                    throw invalid("eq.value 只能是 JSON 标量或 null");
                }
                condition.set("value", value.deepCopy());
            } else {
                condition.set("value", normalizeCandidates(value));
            }
            normalized.putIfAbsent(conditionKey(condition), condition);
        }
        ArrayNode result = objectMapper.createArrayNode();
        normalized.values().forEach(result::add);
        return result;
    }

    boolean equivalent(JsonNode left, JsonNode right) {
        return conditionKeys(left).equals(conditionKeys(right));
    }

    Optional<ArrayNode> matchEvidence(JsonNode conditions, JsonNode params, JsonNode result) {
        ArrayNode evidence = objectMapper.createArrayNode();
        for (JsonNode condition : conditions) {
            JsonNode source = "result".equals(condition.path("source").asText()) ? result : params;
            Optional<JsonNode> actual = fieldValue(source, condition.path("field_path").asText());
            if (actual.isEmpty()) {
                return Optional.empty();
            }
            ObjectNode entry = objectMapper.createObjectNode();
            entry.put("source", condition.path("source").asText());
            entry.put("field_path", condition.path("field_path").asText());
            entry.put("operator", condition.path("operator").asText());
            entry.set("expected_value", condition.get("value").deepCopy());
            if ("eq".equals(condition.path("operator").asText())) {
                if (!sameScalar(actual.get(), condition.get("value"))) {
                    return Optional.empty();
                }
                entry.set("actual_value", actual.get().deepCopy());
            } else {
                if (!actual.get().isArray()) {
                    return Optional.empty();
                }
                ArrayNode intersection = intersection(actual.get(), condition.path("value"));
                if (intersection.isEmpty()) {
                    return Optional.empty();
                }
                entry.set("actual_value", intersection);
            }
            evidence.add(entry);
        }
        return Optional.of(evidence);
    }

    private ArrayNode normalizeCandidates(JsonNode value) {
        if (!value.isArray() || value.isEmpty()) {
            throw invalid("contains_any.value 必须是非空 JSON 标量数组");
        }
        Map<String, JsonNode> values = new TreeMap<>();
        for (JsonNode item : value) {
            if (!isScalar(item)) {
                throw invalid("contains_any.value 只能包含 JSON 标量或 null");
            }
            values.putIfAbsent(scalarKey(item), item.deepCopy());
        }
        ArrayNode candidates = objectMapper.createArrayNode();
        values.values().forEach(candidates::add);
        return candidates;
    }

    private void validateFieldPath(String fieldPath) {
        if (!BreakpointFieldPath.isValid(fieldPath)) {
            throw invalid("field_path 必须是点分隔的对象字段路径");
        }
    }

    private List<String> conditionKeys(JsonNode conditions) {
        List<String> keys = new ArrayList<>();
        conditions.forEach(condition -> keys.add(conditionKey((ObjectNode) condition)));
        keys.sort(Comparator.naturalOrder());
        return keys;
    }

    private String conditionKey(ObjectNode condition) {
        ArrayNode key = objectMapper.createArrayNode();
        key.add(condition.path("source").asText());
        key.add(condition.path("field_path").asText());
        key.add(condition.path("operator").asText());
        if ("contains_any".equals(condition.path("operator").asText())) {
            ArrayNode values = key.addArray();
            List<String> scalarKeys = new ArrayList<>();
            condition.path("value").forEach(value -> scalarKeys.add(scalarKey(value)));
            scalarKeys.stream().sorted().forEach(values::add);
        } else {
            key.add(scalarKey(condition.get("value")));
        }
        return key.toString();
    }

    static String scalarKey(JsonNode value) {
        if (value == null || value.isNull()) {
            return "null:null";
        }
        if (value.isNumber()) {
            return "number:" + canonicalNumber(value.decimalValue());
        }
        if (value.isBoolean()) {
            return "boolean:" + value.asBoolean();
        }
        return "string:" + value.toString();
    }

    static String canonicalNumber(BigDecimal value) {
        BigDecimal normalized = value.stripTrailingZeros();
        return normalized.unscaledValue() + ":" + normalized.scale();
    }

    private Optional<JsonNode> fieldValue(JsonNode params, String fieldPath) {
        JsonNode current = params;
        for (String segment : fieldPath.split("\\.")) {
            if (current == null || !current.isObject() || !current.has(segment)) {
                return Optional.empty();
            }
            current = current.get(segment);
        }
        return Optional.of(current);
    }

    private ArrayNode intersection(JsonNode actualValues, JsonNode expectedValues) {
        Set<String> expectedKeys = new HashSet<>();
        for (JsonNode expected : expectedValues) {
            expectedKeys.add(scalarKey(expected));
        }
        Map<String, JsonNode> matched = new TreeMap<>();
        for (JsonNode actual : actualValues) {
            if (!isScalar(actual)) {
                continue;
            }
            String actualKey = scalarKey(actual);
            if (expectedKeys.contains(actualKey)) {
                matched.putIfAbsent(actualKey, actual.deepCopy());
            }
        }
        ArrayNode result = objectMapper.createArrayNode();
        matched.values().forEach(result::add);
        return result;
    }

    static boolean sameScalar(JsonNode actual, JsonNode expected) {
        if (!isScalar(actual) || !isScalar(expected)) {
            return false;
        }
        if (actual.isNumber() || expected.isNumber()) {
            return actual.isNumber()
                    && expected.isNumber()
                    && actual.decimalValue().compareTo(expected.decimalValue()) == 0;
        }
        return actual.equals(expected);
    }

    private static boolean isScalar(JsonNode value) {
        return value != null
                && (value.isNull() || value.isTextual() || value.isNumber() || value.isBoolean());
    }

    private static ProductException invalid(String message) {
        return new ProductException(HttpStatus.BAD_REQUEST, "INVALID_BREAKPOINT_CONDITION", message);
    }
}
