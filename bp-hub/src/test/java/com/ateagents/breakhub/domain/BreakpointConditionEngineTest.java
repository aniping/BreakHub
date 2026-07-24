package com.ateagents.breakhub.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

class BreakpointConditionEngineTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void canonicalizesHugeExponentsWithoutExpandingThem() {
        String canonical = BreakpointConditionEngine.canonicalNumber(new BigDecimal("1e100000000"));

        assertThat(canonical).isEqualTo("1:-100000000");
        assertThat(canonical).hasSizeLessThan(32);
        assertThat(BreakpointConditionEngine.canonicalNumber(new BigDecimal("10e99999999")))
                .isEqualTo(canonical);
    }

    @Test
    void returnsPrecisionPreservingCompactEvidenceOnlyWhenEveryConditionMatches() {
        BreakpointConditionEngine engine = new BreakpointConditionEngine(objectMapper);
        ArrayNode requested = objectMapper.createArrayNode();
        requested.addObject()
                .put("source", "result")
                .put("field_path", "amount")
                .put("operator", "eq")
                .set("value", objectMapper.getNodeFactory().numberNode(new BigDecimal("9007199254740993.0")));
        requested.addObject()
                .put("source", "result")
                .put("field_path", "tags")
                .put("operator", "contains_any")
                .putArray("value")
                .add(1)
                .add("red");
        ArrayNode conditions = engine.normalize(requested);
        ObjectNode result = objectMapper.createObjectNode();
        result.set("amount", objectMapper.getNodeFactory().numberNode(new BigDecimal("9007199254740993")));
        ArrayNode actualTags = result.putArray("tags");
        for (int index = 0; index < 500; index++) {
            actualTags.add("unmatched-" + index);
        }
        actualTags.add("red");
        actualTags.add("red");
        actualTags.add(objectMapper.getNodeFactory().numberNode(new BigDecimal("1.00")));

        Optional<ArrayNode> matched = engine.matchEvidence(
                conditions,
                objectMapper.createObjectNode(),
                result);

        assertThat(matched).isPresent();
        JsonNode evidence = matched.orElseThrow();
        assertThat(evidence).hasSize(2);
        assertThat(evidence.at("/0/expected_value").toString()).isEqualTo("9007199254740993.0");
        assertThat(evidence.at("/0/actual_value").toString()).isEqualTo("9007199254740993");
        assertThat(evidence.at("/1/expected_value").toString()).isEqualTo("[1,\"red\"]");
        assertThat(evidence.at("/1/actual_value").toString()).isEqualTo("[1.00,\"red\"]");
        assertThat(evidence.toString()).doesNotContain("unmatched-");

        result.put("amount", 0);
        assertThat(engine.matchEvidence(conditions, objectMapper.createObjectNode(), result)).isEmpty();
    }

    @Test
    void matchesLargeContainsAnyEvidenceWithoutCartesianComparison() {
        BreakpointConditionEngine engine = new BreakpointConditionEngine(objectMapper);
        ObjectNode condition = objectMapper.createObjectNode();
        condition.put("source", "result");
        condition.put("field_path", "tags");
        condition.put("operator", "contains_any");
        ArrayNode expected = condition.putArray("value");
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode actual = result.putArray("tags");
        for (int index = 0; index < 10_000; index++) {
            expected.add("expected-" + index);
            actual.add("actual-" + index);
        }
        actual.add("expected-9999");
        ArrayNode conditions = objectMapper.createArrayNode().add(condition);

        Optional<ArrayNode> matched = assertTimeoutPreemptively(
                Duration.ofSeconds(2),
                () -> engine.matchEvidence(conditions, objectMapper.createObjectNode(), result));

        assertThat(matched).isPresent();
        assertThat(matched.orElseThrow().at("/0/actual_value").toString())
                .isEqualTo("[\"expected-9999\"]");
    }
}
