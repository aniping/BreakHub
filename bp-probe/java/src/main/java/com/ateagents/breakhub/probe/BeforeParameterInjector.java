package com.ateagents.breakhub.probe;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class BeforeParameterInjector {

    private static final Logger log = LoggerFactory.getLogger(BeforeParameterInjector.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private BeforeParameterInjector() {
    }

    static void apply(Map<String, Object> target, JsonNode content, String methodName) {
        if (target == null || content == null || !content.isObject()) {
            return;
        }

        synchronized (target) {
            Map<String, Object> prepared;
            try {
                prepared = normalizeMap(target, (ObjectNode) content);
            } catch (InvalidParameterContentException error) {
                warn(methodName, "invalid_content", error);
                return;
            }

            List<String> originalOrder = stringKeys(target);
            LinkedHashMap<String, Object> snapshot = new LinkedHashMap<>();
            for (String key : originalOrder) {
                snapshot.put(key, target.get(key));
            }

            List<String> changedKeys = new ArrayList<>();
            for (String key : originalOrder) {
                if (!Objects.equals(snapshot.get(key), prepared.get(key))) {
                    changedKeys.add(key);
                }
            }
            if (changedKeys.isEmpty()) {
                return;
            }

            try {
                for (String key : changedKeys) {
                    target.put(key, prepared.get(key));
                }
                if (!sameKeyOrder(target, originalOrder) || !sameValues(target, prepared)) {
                    throw new IllegalStateException("parameter map postcondition failed");
                }
            } catch (RuntimeException mutationError) {
                if (sameSnapshotReferences(target, snapshot, originalOrder)) {
                    warn(methodName, "map_not_writable", mutationError);
                    return;
                }
                restoreSnapshot(target, snapshot);
                if (sameSnapshotReferences(target, snapshot, originalOrder)) {
                    warn(methodName, "write_rolled_back", mutationError);
                    return;
                }
                throw new DebugIntegrationException();
            }
        }
    }

    private static Map<String, Object> normalizeMap(Map<?, ?> original, ObjectNode candidate) {
        if (candidate.size() != original.size()) {
            throw new InvalidParameterContentException();
        }

        LinkedHashMap<String, Object> normalized = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : original.entrySet()) {
            if (!(entry.getKey() instanceof String key) || !candidate.has(key)) {
                throw new InvalidParameterContentException();
            }
            normalized.put(key, normalizeValue(entry.getValue(), candidate.get(key)));
        }
        return normalized;
    }

    private static Object normalizeValue(Object original, JsonNode candidate) {
        if (original == null) {
            if (candidate == null || !candidate.isNull()) {
                throw new InvalidParameterContentException();
            }
            return null;
        }
        if (candidate == null) {
            throw new InvalidParameterContentException();
        }
        if (candidate.isNull()) {
            return null;
        }
        if (original instanceof Map<?, ?> originalMap) {
            if (!candidate.isObject()) {
                throw new InvalidParameterContentException();
            }
            return normalizeMap(originalMap, (ObjectNode) candidate);
        }
        if (original instanceof List<?>) {
            if (!candidate.isArray()) {
                throw new InvalidParameterContentException();
            }
            return toJavaJson(candidate);
        }
        if (original instanceof String) {
            if (!candidate.isTextual()) {
                throw new InvalidParameterContentException();
            }
            return candidate.textValue();
        }
        if (original instanceof Boolean) {
            if (!candidate.isBoolean()) {
                throw new InvalidParameterContentException();
            }
            return candidate.booleanValue();
        }
        if (original instanceof Number number) {
            return normalizeNumber(number, candidate);
        }

        try {
            if (OBJECT_MAPPER.valueToTree(original).equals(candidate)) {
                return original;
            }
        } catch (RuntimeException ignored) {
            // Unsupported business objects are safe only when their JSON form is unchanged.
        }
        throw new InvalidParameterContentException();
    }

    private static Object normalizeNumber(Number original, JsonNode candidate) {
        if (!candidate.isNumber()) {
            throw new InvalidParameterContentException();
        }
        BigDecimal decimal = candidate.decimalValue();
        try {
            if (original instanceof Integer) {
                return decimal.intValueExact();
            }
            if (original instanceof Long) {
                return decimal.longValueExact();
            }
            if (original instanceof Short) {
                return decimal.shortValueExact();
            }
            if (original instanceof Byte) {
                return decimal.byteValueExact();
            }
            if (original instanceof BigInteger) {
                return decimal.toBigIntegerExact();
            }
            if (original instanceof BigDecimal) {
                return decimal;
            }
            if (original instanceof Double) {
                double value = candidate.doubleValue();
                if (!Double.isFinite(value)) {
                    throw new InvalidParameterContentException();
                }
                return value;
            }
            if (original instanceof Float) {
                float value = candidate.floatValue();
                if (!Float.isFinite(value)) {
                    throw new InvalidParameterContentException();
                }
                return value;
            }
        } catch (ArithmeticException error) {
            throw new InvalidParameterContentException();
        }
        throw new InvalidParameterContentException();
    }

    private static Object toJavaJson(JsonNode node) {
        if (node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            LinkedHashMap<String, Object> value = new LinkedHashMap<>();
            node.fields().forEachRemaining(entry -> value.put(entry.getKey(), toJavaJson(entry.getValue())));
            return value;
        }
        if (node.isArray()) {
            List<Object> value = new ArrayList<>();
            node.forEach(item -> value.add(toJavaJson(item)));
            return value;
        }
        if (node.isTextual()) {
            return node.textValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isNumber()) {
            return node.numberValue();
        }
        throw new InvalidParameterContentException();
    }

    private static List<String> stringKeys(Map<String, Object> map) {
        List<String> keys = new ArrayList<>();
        for (Object key : map.keySet()) {
            if (!(key instanceof String value)) {
                throw new InvalidParameterContentException();
            }
            keys.add(value);
        }
        return keys;
    }

    private static boolean sameKeyOrder(Map<String, Object> current, List<String> originalOrder) {
        try {
            return stringKeys(current).equals(originalOrder);
        } catch (RuntimeException error) {
            return false;
        }
    }

    private static boolean sameValues(Map<String, Object> current, Map<String, Object> expected) {
        if (current.size() != expected.size()) {
            return false;
        }
        for (Map.Entry<String, Object> entry : expected.entrySet()) {
            if (!current.containsKey(entry.getKey())
                    || !Objects.equals(current.get(entry.getKey()), entry.getValue())) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameSnapshotReferences(
            Map<String, Object> current,
            Map<String, Object> snapshot,
            List<String> originalOrder) {
        if (!sameKeyOrder(current, originalOrder) || current.size() != snapshot.size()) {
            return false;
        }
        for (Map.Entry<String, Object> entry : snapshot.entrySet()) {
            if (!current.containsKey(entry.getKey()) || current.get(entry.getKey()) != entry.getValue()) {
                return false;
            }
        }
        return true;
    }

    private static void restoreSnapshot(Map<String, Object> target, Map<String, Object> snapshot) {
        try {
            List<String> currentKeys = stringKeys(target);
            for (String key : currentKeys) {
                if (!snapshot.containsKey(key)) {
                    target.remove(key);
                }
            }
            for (Map.Entry<String, Object> entry : snapshot.entrySet()) {
                target.put(entry.getKey(), entry.getValue());
            }
        } catch (RuntimeException ignored) {
            // The caller verifies reference-level restoration before allowing business execution.
        }
    }

    private static void warn(String methodName, String reason, RuntimeException error) {
        log.warn("[BreakHub] before parameter injection skipped. method={}, reason={}, errorType={}",
                methodName == null ? "unknown" : methodName,
                reason,
                error.getClass().getSimpleName());
    }

    private static final class InvalidParameterContentException extends RuntimeException {
    }
}

final class DebugIntegrationException extends RuntimeException {

    DebugIntegrationException() {
        super("BREAKHUB_PARAMETER_MAP_CORRUPTED");
    }
}
