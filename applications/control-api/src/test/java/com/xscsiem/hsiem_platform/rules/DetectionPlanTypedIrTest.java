package com.xscsiem.hsiem_platform.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DetectionPlanTypedIrTest {
    private final DetectionPlanCompiler compiler = new DetectionPlanCompiler();
    private final DetectionPlanCodec codec = new DetectionPlanCodec();

    @Test
    void allCategoriesRoundTripThroughTypedIrWithoutChangingCanonicalBytes() {
        for (Map<String, Object> rule : List.of(single(), window(), cep(), baseline())) {
            DetectionPlanCompiler.CompiledPlan compiled = compiler.compile(rule);
            String expectedHash =
                    switch (String.valueOf(rule.get("category"))) {
                        case "single_event" ->
                                "e6f93b4aaf0d76921dbc90e4a34e76290d02170c143e997311e54b7cbf71f8d4";
                        case "window" ->
                                "85142ff473ca06babcd527d53edbafe3b75ab94debc1b2f98d750761275a313c";
                        case "cep" ->
                                "ee63da45bc46b3d59c0ae53c59040b50d45df90513cea08370280e9e5966e3c3";
                        case "baseline" ->
                                "366d884c20ff70c4fc89c1ab32b0c4b8d5d760c32ebc51eb6bebd1ea5a56bd03";
                        default -> throw new AssertionError();
                    };
            assertEquals(expectedHash, compiled.hash());
            DetectionPlan typed = codec.decode(compiled.json());
            assertEquals(compiled.json(), codec.encode(typed));
            assertEquals(compiled.hash(), compiler.compile(rule).hash());
            assertTrue(typed.detection() instanceof DetectionPlan.DetectionSpec);
        }
    }

    @Test
    void authoringProfilesShareGrammarButEditorRejectsCodeOwnedCategories() {
        RuleAuthoringGrammar grammar = new RuleAuthoringGrammar();
        assertEquals(
                "cep",
                grammar.normalize(cep(), RuleAuthoringGrammar.ValidationProfile.CATALOG)
                        .category());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        grammar.normalize(
                                cep(), RuleAuthoringGrammar.ValidationProfile.VISUAL_EDITOR));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        grammar.normalize(
                                singleWithInvalidField(),
                                RuleAuthoringGrammar.ValidationProfile.CATALOG));
    }

    private static Map<String, Object> single() {
        Map<String, Object> r = base("rule-single", "single_event");
        r.put("condition", eq("event.action", "login"));
        return r;
    }

    private static Map<String, Object> window() {
        Map<String, Object> r = base("rule-window", "window");
        r.put("keyField", "source.ip");
        r.put("windowMinutes", 5);
        r.put("slidingMinutes", 1);
        r.put("threshold", 5);
        r.put("condition", eq("event.action", "authentication_failure"));
        return r;
    }

    private static Map<String, Object> cep() {
        Map<String, Object> r = base("rule-cep", "cep");
        r.put("keyField", "source.ip");
        r.put(
                "cep",
                Map.of(
                        "withinMinutes",
                        10,
                        "pattern",
                        List.of(
                                Map.of(
                                        "name",
                                        "failures",
                                        "type",
                                        "begin",
                                        "timesMin",
                                        2,
                                        "timesMax",
                                        5,
                                        "condition",
                                        eq("event.action", "authentication_failure")),
                                Map.of(
                                        "name",
                                        "success",
                                        "type",
                                        "next",
                                        "condition",
                                        eq("event.action", "authentication_success")))));
        return r;
    }

    private static Map<String, Object> baseline() {
        Map<String, Object> r = base("rule-baseline", "baseline");
        r.put(
                "baseline",
                Map.of(
                        "keyField",
                        "host.name",
                        "windowHours",
                        1,
                        "baselineHours",
                        24,
                        "minBaselineHours",
                        3));
        return r;
    }

    private static Map<String, Object> singleWithInvalidField() {
        Map<String, Object> r = single();
        r.put("condition", eq("bad field", "login"));
        return r;
    }

    private static Map<String, Object> eq(String field, String value) {
        return Map.of("type", "field_equals", "field", field, "value", value);
    }

    private static Map<String, Object> base(String id, String category) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", id);
        r.put("name", id);
        r.put("category", category);
        r.put("type", "test_detection");
        r.put("severity", "medium");
        r.put("description", "");
        r.put("riskScore", 40);
        r.put("tags", List.of());
        r.put("status", "stable");
        r.put("version", "1.0");
        return r;
    }
}
