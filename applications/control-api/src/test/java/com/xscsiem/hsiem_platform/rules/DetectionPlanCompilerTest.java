package com.xscsiem.hsiem_platform.rules;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DetectionPlanCompilerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final DetectionPlanCompiler compiler = new DetectionPlanCompiler();

    @Test
    void compilesAllCategoriesToCanonicalV2Plans() throws Exception {
        DetectionPlanCompiler.CompiledPlan singlePlan = compiler.compile(singleEvent());
        JsonNode single = assertCommon(singlePlan, "rule-single", "single_event");
        assertEquals("eq", single.at("/detection/condition/operator").asText());
        assertEquals("event.action", single.at("/detection/condition/field").asText());
        assertEquals("login", single.at("/detection/condition/value").asText());
        assertEquals(60, single.at("/detection/suppression/duration_minutes").asInt());
        assertEquals("processing_time", single.at("/detection/suppression/time_basis").asText());

        JsonNode window = assertCommon(compiler.compile(window()), "rule-window", "window");
        assertEquals("source.ip", window.at("/detection/key_field").asText());
        assertEquals("sliding", window.at("/detection/window/type").asText());
        assertEquals("event_time", window.at("/detection/window/time_basis").asText());
        assertEquals(5, window.at("/detection/window/size_minutes").asInt());
        assertEquals(1, window.at("/detection/window/slide_minutes").asInt());
        assertEquals(5, window.at("/detection/threshold").asInt());
        assertEquals("source.ip", window.at("/detection/suppression/primary_entity_field").asText());
        assertEquals(10, window.at("/detection/suppression/duration_minutes").asInt());

        JsonNode cep = assertCommon(compiler.compile(cep()), "rule-cep", "cep");
        assertEquals("source.ip", cep.at("/detection/key_field").asText());
        assertEquals("event_time", cep.at("/detection/cep/time_basis").asText());
        assertEquals(10, cep.at("/detection/cep/within_minutes").asInt());
        assertEquals(2, cep.at("/detection/cep/pattern").size());
        assertEquals("failures", cep.at("/detection/cep/pattern/0/name").asText());
        assertEquals("begin", cep.at("/detection/cep/pattern/0/type").asText());
        assertEquals(5, cep.at("/detection/cep/pattern/0/times_min").asInt());
        assertEquals(100, cep.at("/detection/cep/pattern/0/times_max").asInt());
        assertEquals("success", cep.at("/detection/cep/output/success_step").asText());
        assertEquals("bruteforce_success", cep.at("/detection/cep/output/type").asText());

        JsonNode baseline = assertCommon(compiler.compile(baseline()), "rule-baseline", "baseline");
        assertEquals("eq", baseline.at("/detection/condition/operator").asText());
        assertEquals("event.action", baseline.at("/detection/condition/field").asText());
        assertEquals("authentication_failure", baseline.at("/detection/condition/value").asText());
        assertEquals("host.name", baseline.at("/detection/baseline/key_field").asText());
        assertEquals("event_time", baseline.at("/detection/baseline/time_basis").asText());
        assertEquals(24, baseline.at("/detection/baseline/baseline_hours").asInt());
        assertEquals(3, baseline.at("/detection/baseline/min_baseline_hours").asInt());
        assertEquals("mean_sigma", baseline.at("/detection/baseline/algorithm/type").asText());
        assertEquals(3.0, baseline.at("/detection/baseline/algorithm/sigma_multiplier").asDouble());
        assertTrue(baseline.at("/detection/baseline/algorithm/require_positive_threshold").asBoolean());
    }

    @Test
    void sameRuleIsDeterministicAndRevisionDoesNotAffectIdentity() {
        DetectionPlanCompiler.CompiledPlan first = compiler.compile(singleEvent(), 1);
        DetectionPlanCompiler.CompiledPlan second = compiler.compile(singleEvent(), 99);

        assertEquals(first.json(), second.json());
        assertEquals(first.hash(), second.hash());
    }

    @Test
    void enabledAndReferencesAreOperationalMetadataOutsidePlanIdentity() {
        DetectionPlanCompiler.CompiledPlan original = compiler.compile(singleEvent());
        Map<String, Object> disabled = copy(singleEvent());
        disabled.put("enabled", false);
        Map<String, Object> differentReferences = copy(singleEvent());
        differentReferences.put("references", List.of("https://example.test/changed"));

        assertEquals(original.hash(), compiler.compile(disabled).hash());
        assertEquals(original.hash(), compiler.compile(differentReferences).hash());
        assertEquals(original.json(), compiler.compile(disabled).json());
    }

    @Test
    void runtimeMetadataAndCategorySemanticsAffectPlanIdentity() {
        Map<String, Object> single = singleEvent();
        String singleHash = compiler.compile(single).hash();
        Map<String, Object> metadata = Map.of(
                "name", "Changed rule",
                "type", "other_detection",
                "severity", "critical",
                "description", "changed description",
                "riskScore", 41,
                "tags", List.of("changed-tag"),
                "status", "deprecated",
                "version", "2.0");
        metadata.forEach((field, value) -> assertHashChanges(single, field, value));

        Map<String, Object> changedCondition = copy(single);
        changedCondition.put("condition", condition("event.action", "logout"));
        assertNotEquals(singleHash, compiler.compile(changedCondition).hash());

        Map<String, Object> changedWindow = copy(window());
        changedWindow.put("windowMinutes", 6);
        assertHashChanges(window(), "windowMinutes", 6);
        assertNotEquals(compiler.compile(window()).hash(), compiler.compile(changedWindow).hash());

        Map<String, Object> changedCep = copy(cep());
        Map<String, Object> cepBody = new LinkedHashMap<>(map(changedCep.get("cep")));
        cepBody.put("withinMinutes", 20);
        changedCep.put("cep", cepBody);
        assertNotEquals(compiler.compile(cep()).hash(), compiler.compile(changedCep).hash());

        Map<String, Object> changedBaseline = copy(baseline());
        Map<String, Object> baselineBody = new LinkedHashMap<>(map(changedBaseline.get("baseline")));
        baselineBody.put("baselineHours", 48);
        changedBaseline.put("baseline", baselineBody);
        assertNotEquals(compiler.compile(baseline()).hash(), compiler.compile(changedBaseline).hash());
    }

    @Test
    void rejectsRequiredAndBoundedInvalidInputs() {
        Map<String, Object> missingName = copy(singleEvent());
        missingName.remove("name");
        assertRejects(missingName);

        Map<String, Object> shortId = copy(singleEvent());
        shortId.put("id", "ab");
        assertRejects(shortId);

        Map<String, Object> badRisk = copy(singleEvent());
        badRisk.put("riskScore", 101);
        assertRejects(badRisk);

        Map<String, Object> badWindow = copy(window());
        badWindow.put("windowMinutes", 0);
        assertRejects(badWindow);
        Map<String, Object> badSlide = copy(window());
        badSlide.put("slidingMinutes", 6);
        assertRejects(badSlide);
        Map<String, Object> badThreshold = copy(window());
        badThreshold.put("threshold", 1);
        assertRejects(badThreshold);

        Map<String, Object> badCep = copy(cep());
        Map<String, Object> cepBody = new LinkedHashMap<>(map(badCep.get("cep")));
        cepBody.put("pattern", List.of());
        badCep.put("cep", cepBody);
        assertRejects(badCep);

        Map<String, Object> badBaseline = copy(baseline());
        Map<String, Object> baselineBody = new LinkedHashMap<>(map(badBaseline.get("baseline")));
        baselineBody.put("minBaselineHours", 25);
        badBaseline.put("baseline", baselineBody);
        assertRejects(badBaseline);
    }

    private JsonNode assertCommon(DetectionPlanCompiler.CompiledPlan plan,
                                  String ruleKey, String category) throws Exception {
        JsonNode root = MAPPER.readTree(plan.json());
        assertEquals(plan.json(), MAPPER.writeValueAsString(root));
        assertEquals(DetectionPlanCompiler.SCHEMA_VERSION, root.at("/schema_version").asText());
        assertEquals(DetectionPlanCompiler.VERSION, root.at("/compiler_version").asText());
        assertEquals(ruleKey, root.at("/rule_key").asText());
        assertEquals("siem-events", root.at("/input/source").asText());
        assertEquals(category, root.at("/detection/type").asText());
        assertEquals("medium", root.at("/alert/severity").asText());
        assertEquals("test_detection", root.at("/alert/type").asText());
        assertEquals(40, root.at("/alert/risk_score").asInt());
        assertEquals("stable", root.at("/alert/status").asText());
        assertEquals("1.0", root.at("/alert/version").asText());
        return root;
    }

    private void assertHashChanges(Map<String, Object> original, String field, Object value) {
        Map<String, Object> changed = copy(original);
        changed.put(field, value);
        assertNotEquals(compiler.compile(original).hash(), compiler.compile(changed).hash(), field);
    }

    private void assertRejects(Map<String, Object> rule) {
        assertThrows(IllegalArgumentException.class, () -> compiler.compile(rule));
    }

    private static Map<String, Object> singleEvent() {
        Map<String, Object> rule = base("rule-single", "single_event");
        rule.put("condition", condition("event.action", "login"));
        return rule;
    }

    private static Map<String, Object> window() {
        Map<String, Object> rule = base("rule-window", "window");
        rule.put("keyField", "source.ip");
        rule.put("windowMinutes", 5);
        rule.put("slidingMinutes", 1);
        rule.put("threshold", 5);
        rule.put("alertSuppressionMinutes", 10);
        rule.put("condition", condition("event.action", "authentication_failure"));
        return rule;
    }

    private static Map<String, Object> cep() {
        Map<String, Object> rule = base("rule-cep", "cep");
        rule.put("keyField", "source.ip");
        rule.put("cep", Map.of(
                "withinMinutes", 10,
                "pattern", List.of(
                        Map.of("name", "failures", "type", "begin", "timesMin", 5,
                                "timesMax", 100, "condition", condition("event.action", "authentication_failure")),
                        Map.of("name", "success", "type", "next",
                                "condition", condition("event.action", "authentication_success")))));
        return rule;
    }

    private static Map<String, Object> baseline() {
        Map<String, Object> rule = base("rule-baseline", "baseline");
        rule.put("baseline", Map.of(
                "keyField", "host.name",
                "windowHours", 1,
                "baselineHours", 24,
                "minBaselineHours", 3));
        return rule;
    }

    private static Map<String, Object> base(String id, String category) {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("id", id);
        rule.put("name", "Rule " + id);
        rule.put("category", category);
        rule.put("type", "test_detection");
        rule.put("enabled", true);
        rule.put("severity", "medium");
        rule.put("description", "test description");
        rule.put("riskScore", 40);
        rule.put("tags", List.of("test-tag"));
        rule.put("status", "stable");
        rule.put("version", "1.0");
        rule.put("references", List.of("https://example.test/reference"));
        return rule;
    }

    private static Map<String, Object> condition(String field, String value) {
        return Map.of("type", "field_equals", "field", field, "value", value);
    }

    private static Map<String, Object> copy(Map<String, Object> source) {
        return new LinkedHashMap<>(source);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }
}
