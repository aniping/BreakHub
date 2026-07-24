package com.ateagents.breakhub.domain;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

@Component
public class JsonChangeEngine {

    public ChangeResult apply(JsonNode original, JsonNode current, JsonNode changes) {
        JsonNode effective = current.deepCopy();
        ChangeCollector collector = new ChangeCollector();
        if (!original.isObject() || !effective.isObject() || !changes.isObject()) {
            collector.skippedTypeMismatchFields.add("/");
            return collector.result(effective);
        }
        applyObject((ObjectNode) original, (ObjectNode) effective, (ObjectNode) changes, "", collector);
        return collector.result(effective);
    }

    private void applyObject(
            ObjectNode original,
            ObjectNode current,
            ObjectNode changes,
            String parentPath,
            ChangeCollector collector) {
        changes.fields().forEachRemaining(entry -> {
            String name = entry.getKey();
            String path = parentPath + "/" + pointerToken(name);
            if (!original.has(name)) {
                collector.skippedMissingFields.add(path);
                return;
            }

            JsonNode originalValue = original.get(name);
            JsonNode currentValue = current.get(name);
            JsonNode requestedValue = entry.getValue();
            if (requestedValue.isObject()) {
                if (originalValue.isObject() && currentValue != null && currentValue.isObject()) {
                    applyObject(
                            (ObjectNode) originalValue,
                            (ObjectNode) currentValue,
                            (ObjectNode) requestedValue,
                            path,
                            collector);
                } else {
                    collector.skippedTypeMismatchFields.add(path);
                }
                return;
            }

            if (originalValue.isNull()) {
                if (requestedValue.isNull()) {
                    collector.unchangedFields.add(path);
                } else {
                    collector.skippedNullSourceFields.add(path);
                }
                return;
            }
            if (!requestedValue.isNull() && !sameType(originalValue, requestedValue)) {
                collector.skippedTypeMismatchFields.add(path);
                return;
            }
            if (jsonEquals(currentValue, requestedValue)) {
                collector.unchangedFields.add(path);
                return;
            }
            current.set(name, requestedValue.deepCopy());
            collector.modifiedFields.add(path);
        });
    }

    private static boolean sameType(JsonNode original, JsonNode requested) {
        if (original.isNumber()) {
            return requested.isNumber();
        }
        return original.getNodeType() == requested.getNodeType();
    }

    private static boolean jsonEquals(JsonNode left, JsonNode right) {
        if (left == null || right == null) {
            return left == right;
        }
        if (left.isNumber() && right.isNumber()) {
            return left.decimalValue().compareTo(right.decimalValue()) == 0;
        }
        if (left.isArray() && right.isArray()) {
            if (left.size() != right.size()) {
                return false;
            }
            for (int index = 0; index < left.size(); index++) {
                if (!jsonEquals(left.get(index), right.get(index))) {
                    return false;
                }
            }
            return true;
        }
        if (left.isObject() && right.isObject()) {
            if (left.size() != right.size()) {
                return false;
            }
            var names = left.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                if (!right.has(name) || !jsonEquals(left.get(name), right.get(name))) {
                    return false;
                }
            }
            return true;
        }
        return left.equals(right);
    }

    private static String pointerToken(String value) {
        return value.replace("~", "~0").replace("/", "~1");
    }

    public record ChangeResult(
            String result,
            List<String> modifiedFields,
            List<String> unchangedFields,
            List<String> skippedMissingFields,
            List<String> skippedTypeMismatchFields,
            List<String> skippedNullSourceFields,
            JsonNode effectiveContent) {

        public List<String> skippedFields() {
            List<String> fields = new ArrayList<>();
            fields.addAll(skippedMissingFields);
            fields.addAll(skippedTypeMismatchFields);
            fields.addAll(skippedNullSourceFields);
            return List.copyOf(fields);
        }
    }

    private static final class ChangeCollector {
        private final List<String> modifiedFields = new ArrayList<>();
        private final List<String> unchangedFields = new ArrayList<>();
        private final List<String> skippedMissingFields = new ArrayList<>();
        private final List<String> skippedTypeMismatchFields = new ArrayList<>();
        private final List<String> skippedNullSourceFields = new ArrayList<>();

        private ChangeResult result(JsonNode effectiveContent) {
            boolean skipped = !skippedMissingFields.isEmpty()
                    || !skippedTypeMismatchFields.isEmpty()
                    || !skippedNullSourceFields.isEmpty();
            String result = modifiedFields.isEmpty() ? "no_effect" : skipped ? "partial" : "applied";
            return new ChangeResult(
                    result,
                    List.copyOf(modifiedFields),
                    List.copyOf(unchangedFields),
                    List.copyOf(skippedMissingFields),
                    List.copyOf(skippedTypeMismatchFields),
                    List.copyOf(skippedNullSourceFields),
                    effectiveContent);
        }
    }
}
