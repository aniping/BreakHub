package com.ateagents.breakhub.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

@Component
class InteractionFieldSchema {

    private final ObjectMapper objectMapper;

    InteractionFieldSchema(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    ArrayNode describe(JsonNode params) {
        ArrayNode fields = objectMapper.createArrayNode();
        appendFields(fields, "", params);
        return fields;
    }

    private void appendFields(ArrayNode fields, String prefix, JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        names.sort(String::compareTo);
        for (String name : names) {
            JsonNode value = node.get(name);
            String path = prefix.isEmpty() ? name : prefix + "." + name;
            ObjectNode field = fields.addObject();
            field.put("path", path);
            field.put("type", type(value));
            if (value.isArray()) {
                Set<String> itemTypes = new TreeSet<>();
                value.forEach(item -> itemTypes.add(type(item)));
                ArrayNode types = field.putArray("item_types");
                itemTypes.forEach(types::add);
            } else if (value.isObject()) {
                appendFields(fields, path, value);
            }
        }
    }

    private static String type(JsonNode value) {
        if (value == null || value.isNull()) {
            return "null";
        }
        if (value.isBoolean()) {
            return "boolean";
        }
        if (value.isIntegralNumber()) {
            return "integer";
        }
        if (value.isNumber()) {
            return "number";
        }
        if (value.isTextual()) {
            return "string";
        }
        if (value.isArray()) {
            return "array";
        }
        return "object";
    }
}
