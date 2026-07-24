package com.ateagents.breakhub.probe;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.introspect.AnnotatedMember;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class AfterResultConverter {

    private static final Logger log = LoggerFactory.getLogger(AfterResultConverter.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private AfterResultConverter() {
    }

    static <T> T convert(T original, JsonNode content, String methodName) {
        if (original == null || content == null || content.isNull()) {
            return original;
        }

        try {
            JsonNode originalJson = OBJECT_MAPPER.valueToTree(original);
            if (jsonEquivalent(originalJson, content)) {
                return original;
            }
            if (!compatibleShape(originalJson, content)) {
                warn(methodName, "incompatible_content", null);
                return original;
            }

            Object converted = OBJECT_MAPPER.treeToValue(content, original.getClass());
            if (converted == null || !original.getClass().isInstance(converted)) {
                warn(methodName, "unexpected_result_type", null);
                return original;
            }
            if (!sameRuntimeShape(original, converted, new IdentityHashMap<>())) {
                warn(methodName, "runtime_type_changed", null);
                return original;
            }
            JsonNode roundTrip = OBJECT_MAPPER.valueToTree(converted);
            if (!jsonEquivalent(content, roundTrip)) {
                warn(methodName, "lossy_conversion", null);
                return original;
            }

            @SuppressWarnings("unchecked")
            T typed = (T) converted;
            return typed;
        } catch (Exception error) {
            warn(methodName, "conversion_failed", error);
            return original;
        }
    }

    private static boolean compatibleShape(JsonNode original, JsonNode candidate) {
        if (original == null || original.isNull()) {
            return candidate == null || candidate.isNull();
        }
        if (candidate == null) {
            return false;
        }
        if (candidate.isNull()) {
            return true;
        }
        if (original.isObject()) {
            if (!candidate.isObject() || original.size() != candidate.size()) {
                return false;
            }
            Iterator<Map.Entry<String, JsonNode>> fields = original.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (!candidate.has(field.getKey())
                        || !compatibleShape(field.getValue(), candidate.get(field.getKey()))) {
                    return false;
                }
            }
            return true;
        }
        if (original.isArray()) {
            if (!candidate.isArray() || original.size() != candidate.size()) {
                return false;
            }
            for (int index = 0; index < original.size(); index++) {
                if (!compatibleShape(original.get(index), candidate.get(index))) {
                    return false;
                }
            }
            return true;
        }
        if (original.isNumber()) {
            return candidate.isNumber();
        }
        return original.getNodeType() == candidate.getNodeType();
    }

    private static boolean jsonEquivalent(JsonNode left, JsonNode right) {
        if (left == null || right == null) {
            return left == right;
        }
        if (left.isNumber() && right.isNumber()) {
            return left.decimalValue().compareTo(right.decimalValue()) == 0;
        }
        if (left.isObject() && right.isObject()) {
            if (left.size() != right.size()) {
                return false;
            }
            Iterator<Map.Entry<String, JsonNode>> fields = left.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (!right.has(field.getKey())
                        || !jsonEquivalent(field.getValue(), right.get(field.getKey()))) {
                    return false;
                }
            }
            return true;
        }
        if (left.isArray() && right.isArray()) {
            if (left.size() != right.size()) {
                return false;
            }
            for (int index = 0; index < left.size(); index++) {
                if (!jsonEquivalent(left.get(index), right.get(index))) {
                    return false;
                }
            }
            return true;
        }
        return left.equals(right);
    }

    private static boolean sameRuntimeShape(
            Object original,
            Object candidate,
            IdentityHashMap<Object, Object> visited) {
        if (original == null) {
            return candidate == null;
        }
        if (candidate == null) {
            return true;
        }
        Class<?> type = original.getClass();
        if (!type.equals(candidate.getClass())) {
            return false;
        }
        if (!trustedContainerState(original, candidate)) {
            return false;
        }
        if (isScalarType(type)) {
            return true;
        }
        if (visited.containsKey(original)) {
            return visited.get(original) == candidate;
        }
        visited.put(original, candidate);

        if (original instanceof Map<?, ?> originalMap
                && candidate instanceof Map<?, ?> candidateMap) {
            return sameMapShape(originalMap, candidateMap, visited);
        }
        if (original instanceof List<?> originalList
                && candidate instanceof List<?> candidateList) {
            return sameOrderedValues(originalList, candidateList, visited);
        }
        if (type.isArray()) {
            int length = Array.getLength(original);
            if (length != Array.getLength(candidate)) {
                return false;
            }
            for (int index = 0; index < length; index++) {
                if (!sameRuntimeShape(
                        Array.get(original, index),
                        Array.get(candidate, index),
                        visited)) {
                    return false;
                }
            }
            return true;
        }
        if (original instanceof Collection<?> originalCollection
                && candidate instanceof Collection<?> candidateCollection) {
            return sameCollectionShape(originalCollection, candidateCollection, visited);
        }
        if (original instanceof Optional<?> originalOptional
                && candidate instanceof Optional<?> candidateOptional) {
            return sameRuntimeShape(
                    originalOptional.orElse(null),
                    candidateOptional.orElse(null),
                    visited);
        }
        return sameSerializedPropertyShape(original, candidate, visited);
    }

    private static boolean trustedContainerState(Object original, Object candidate) {
        Class<?> type = original.getClass();
        if (type.isArray()
                || type == HashMap.class
                || type == ArrayList.class
                || type == LinkedList.class) {
            return true;
        }
        if (type == LinkedHashMap.class) {
            return !isAccessOrdered((LinkedHashMap<?, ?>) original)
                    && !isAccessOrdered((LinkedHashMap<?, ?>) candidate);
        }
        return !(original instanceof Map<?, ?>)
                && !(original instanceof Collection<?>);
    }

    private static boolean isAccessOrdered(LinkedHashMap<?, ?> source) {
        @SuppressWarnings("unchecked")
        LinkedHashMap<Object, Object> probe =
                (LinkedHashMap<Object, Object>) source.clone();
        Object firstMarker = new Object();
        Object secondMarker = new Object();
        probe.put(firstMarker, firstMarker);
        probe.put(secondMarker, secondMarker);
        List<Object> beforeAccess = new ArrayList<>(probe.keySet());
        probe.get(firstMarker);
        return !beforeAccess.equals(new ArrayList<>(probe.keySet()));
    }

    private static boolean sameMapShape(
            Map<?, ?> original,
            Map<?, ?> candidate,
            IdentityHashMap<Object, Object> visited) {
        if (original.size() != candidate.size()) {
            return false;
        }
        for (Map.Entry<?, ?> originalEntry : original.entrySet()) {
            Map.Entry<?, ?> matchingEntry = null;
            for (Map.Entry<?, ?> candidateEntry : candidate.entrySet()) {
                if (Objects.equals(originalEntry.getKey(), candidateEntry.getKey())) {
                    matchingEntry = candidateEntry;
                    break;
                }
            }
            if (matchingEntry == null
                    || !sameRuntimeShape(
                            originalEntry.getKey(),
                            matchingEntry.getKey(),
                            visited)
                    || !sameRuntimeShape(
                            originalEntry.getValue(),
                            matchingEntry.getValue(),
                            visited)) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameOrderedValues(
            List<?> original,
            List<?> candidate,
            IdentityHashMap<Object, Object> visited) {
        if (original.size() != candidate.size()) {
            return false;
        }
        for (int index = 0; index < original.size(); index++) {
            if (!sameRuntimeShape(original.get(index), candidate.get(index), visited)) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameCollectionShape(
            Collection<?> original,
            Collection<?> candidate,
            IdentityHashMap<Object, Object> visited) {
        if (original.size() != candidate.size()) {
            return false;
        }
        Iterator<?> originalValues = original.iterator();
        Iterator<?> candidateValues = candidate.iterator();
        while (originalValues.hasNext()) {
            if (!sameRuntimeShape(
                    originalValues.next(),
                    candidateValues.next(),
                    visited)) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameSerializedPropertyShape(
            Object original,
            Object candidate,
            IdentityHashMap<Object, Object> visited) {
        try {
            BeanDescription description = OBJECT_MAPPER.getSerializationConfig()
                    .introspect(OBJECT_MAPPER.constructType(original.getClass()));
            JsonNode originalState = OBJECT_MAPPER.valueToTree(original);
            JsonNode candidateState = OBJECT_MAPPER.valueToTree(candidate);
            boolean inspected = false;
            Set<Field> serializedFields = new HashSet<>();
            for (BeanPropertyDefinition property : description.findProperties()) {
                AnnotatedMember accessor = property.getAccessor();
                if (!property.couldSerialize() || accessor == null) {
                    continue;
                }
                accessor.fixAccess(true);
                Object originalValue = accessor.getValue(original);
                Object candidateValue = accessor.getValue(candidate);
                if (!sameRuntimeShape(originalValue, candidateValue, visited)) {
                    return false;
                }
                Field field = resolveDirectField(
                        original.getClass(), accessor, property.getInternalName());
                if (field != null) {
                    boolean unchanged = propertyUnchanged(
                            originalState, candidateState, property.getName());
                    if (!fieldStateSafe(
                            field, accessor, original, candidate,
                            originalValue, candidateValue, unchanged)) {
                        return false;
                    }
                    serializedFields.add(field);
                }
                inspected = true;
            }
            AnnotatedMember anyGetter = description.findAnyGetter();
            if (anyGetter != null) {
                anyGetter.fixAccess(true);
                Object originalValue = anyGetter.getValue(original);
                Object candidateValue = anyGetter.getValue(candidate);
                if (!sameRuntimeShape(originalValue, candidateValue, visited)) {
                    return false;
                }
                if (!addDirectFieldState(
                        original, candidate, anyGetter,
                        originalValue, candidateValue, serializedFields)) {
                    return false;
                }
                inspected = true;
            }
            AnnotatedMember jsonValue = description.findJsonValueAccessor();
            if (jsonValue != null) {
                jsonValue.fixAccess(true);
                Object originalValue = jsonValue.getValue(original);
                Object candidateValue = jsonValue.getValue(candidate);
                if (!sameRuntimeShape(originalValue, candidateValue, visited)) {
                    return false;
                }
                if (!addDirectFieldState(
                        original, candidate, jsonValue,
                        originalValue, candidateValue, serializedFields)) {
                    return false;
                }
                inspected = true;
            }
            return inspected && allInstanceFieldsCovered(
                    original.getClass(), serializedFields);
        } catch (Exception error) {
            return false;
        }
    }

    private static Field resolveDirectField(
            Class<?> beanType,
            AnnotatedMember accessor,
            String fieldName) throws NoSuchFieldException {
        if (accessor.getMember() instanceof Field field) {
            return field;
        }
        if (!beanType.isRecord()
                || fieldName == null
                || !(accessor.getMember() instanceof Method method)) {
            return null;
        }
        for (RecordComponent component : beanType.getRecordComponents()) {
            if (component.getName().equals(fieldName)
                    && component.getAccessor().equals(method)) {
                return beanType.getDeclaredField(component.getName());
            }
        }
        return null;
    }

    private static boolean propertyUnchanged(
            JsonNode originalState,
            JsonNode candidateState,
            String propertyName) {
        if (originalState.isObject() && candidateState.isObject()) {
            return jsonEquivalent(
                    originalState.get(propertyName),
                    candidateState.get(propertyName));
        }
        return jsonEquivalent(originalState, candidateState);
    }

    private static boolean fieldStateSafe(
            Field field,
            AnnotatedMember accessor,
            Object original,
            Object candidate,
            Object originalValue,
            Object candidateValue,
            boolean unchanged) throws IllegalAccessException {
        field.setAccessible(true);
        Object originalFieldValue = field.get(original);
        Object candidateFieldValue = field.get(candidate);
        if (accessor.getMember() instanceof Method
                && (!directAccessorValue(field, originalFieldValue, originalValue)
                || !directAccessorValue(field, candidateFieldValue, candidateValue))) {
            return false;
        }
        return !unchanged || Objects.deepEquals(originalFieldValue, candidateFieldValue);
    }

    private static boolean directAccessorValue(
            Field field,
            Object fieldValue,
            Object accessorValue) {
        return field.getType().isPrimitive()
                ? Objects.deepEquals(fieldValue, accessorValue)
                : fieldValue == accessorValue;
    }

    private static boolean addDirectFieldState(
            Object original,
            Object candidate,
            AnnotatedMember accessor,
            Object originalValue,
            Object candidateValue,
            Set<Field> serializedFields) throws IllegalAccessException {
        if (!(accessor.getMember() instanceof Field field)) {
            return true;
        }
        boolean unchanged = jsonEquivalent(
                OBJECT_MAPPER.valueToTree(originalValue),
                OBJECT_MAPPER.valueToTree(candidateValue));
        if (!fieldStateSafe(
                field, accessor, original, candidate,
                originalValue, candidateValue, unchanged)) {
            return false;
        }
        serializedFields.add(field);
        return true;
    }

    private static boolean allInstanceFieldsCovered(
            Class<?> type,
            Set<Field> serializedFields) {
        for (Class<?> current = type;
                current != null && current != Object.class;
                current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                    continue;
                }
                if (!serializedFields.contains(field)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean isScalarType(Class<?> type) {
        return type.isPrimitive()
                || type.isEnum()
                || CharSequence.class.isAssignableFrom(type)
                || Number.class.isAssignableFrom(type)
                || type == Boolean.class
                || type == Character.class
                || type == UUID.class
                || type == Class.class
                || Date.class.isAssignableFrom(type)
                || TemporalAccessor.class.isAssignableFrom(type);
    }

    private static void warn(String methodName, String reason, Exception error) {
        log.warn("[BreakHub] after result injection skipped. method={}, reason={}, errorType={}",
                methodName == null ? "unknown" : methodName,
                reason,
                error == null ? "none" : error.getClass().getSimpleName());
    }
}
