package com.ateagents.breakhub.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class JsonChangeEngineTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JsonChangeEngine changes = new JsonChangeEngine();

    @Test
    void appliesNestedChangesAgainstImmutableOriginalShape() throws Exception {
        JsonNode original = json("""
                {
                  "nested":{"count":1,"label":"old"},
                  "tags":[1,2],
                  "nullable":null,
                  "enabled":true
                }
                """);

        JsonChangeEngine.ChangeResult result = changes.apply(original, original.deepCopy(), json("""
                {
                  "nested":{"count":2,"missing":"new"},
                  "tags":[{"any":"json"},null],
                  "nullable":"not-allowed",
                  "enabled":"true"
                }
                """));

        assertThat(result.result()).isEqualTo("partial");
        assertThat(result.modifiedFields()).containsExactly("/nested/count", "/tags");
        assertThat(result.unchangedFields()).isEmpty();
        assertThat(result.skippedMissingFields()).containsExactly("/nested/missing");
        assertThat(result.skippedTypeMismatchFields()).containsExactly("/enabled");
        assertThat(result.skippedNullSourceFields()).containsExactly("/nullable");
        assertThat(result.effectiveContent()).isEqualTo(json("""
                {
                  "nested":{"count":2,"label":"old"},
                  "tags":[{"any":"json"},null],
                  "nullable":null,
                  "enabled":true
                }
                """));
    }

    @Test
    void accumulatesOnCurrentContentWithoutChangingOriginalTypeAnchor() throws Exception {
        JsonNode original = json("""
                {"count":1,"label":"old","enabled":true,"value":"present"}
                """);
        JsonNode current = changes.apply(original, original.deepCopy(), json("""
                {"count":2,"value":null}
                """)).effectiveContent();

        JsonChangeEngine.ChangeResult result = changes.apply(original, current, json("""
                {"count":2.0,"label":"new","enabled":false,"value":"restored"}
                """));

        assertThat(result.result()).isEqualTo("applied");
        assertThat(result.modifiedFields()).containsExactly("/label", "/enabled", "/value");
        assertThat(result.unchangedFields()).containsExactly("/count");
        assertThat(result.skippedFields()).isEmpty();
        assertThat(result.effectiveContent()).isEqualTo(json("""
                {"count":2,"label":"new","enabled":false,"value":"restored"}
                """));
        assertThat(original).isEqualTo(json("""
                {"count":1,"label":"old","enabled":true,"value":"present"}
                """));
    }

    @Test
    void returnsNoEffectForRepeatedValuesAndNonObjectRoots() throws Exception {
        JsonNode original = json("{\"count\":1,\"items\":[1,2]}");

        JsonChangeEngine.ChangeResult repeated = changes.apply(original, original.deepCopy(), json("""
                {"count":1.0,"items":[1,2]}
                """));
        assertThat(repeated.result()).isEqualTo("no_effect");
        assertThat(repeated.modifiedFields()).isEmpty();
        assertThat(repeated.unchangedFields()).containsExactly("/count", "/items");

        JsonChangeEngine.ChangeResult scalarPayload = changes.apply(
                json("\"original\""), json("\"original\""), json("{\"value\":\"new\"}"));
        assertThat(scalarPayload.result()).isEqualTo("no_effect");
        assertThat(scalarPayload.skippedTypeMismatchFields()).containsExactly("/");

        JsonChangeEngine.ChangeResult scalarChanges = changes.apply(
                original, original.deepCopy(), json("\"not-an-object\""));
        assertThat(scalarChanges.result()).isEqualTo("no_effect");
        assertThat(scalarChanges.skippedTypeMismatchFields()).containsExactly("/");
    }

    private JsonNode json(String value) throws Exception {
        return objectMapper.readTree(value);
    }
}
