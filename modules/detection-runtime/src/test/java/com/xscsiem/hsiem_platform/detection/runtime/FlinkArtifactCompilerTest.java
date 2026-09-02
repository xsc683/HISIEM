package com.xscsiem.hsiem_platform.detection.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xscsiem.hsiem_platform.rules.DetectionPlanCompiler;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlinkArtifactCompilerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> COMMON_FIELDS = Set.of(
            "id", "name", "category", "type", "enabled", "severity", "description",
            "riskScore", "tags", "status", "version");

    private final FlinkArtifactCompiler compiler = new FlinkArtifactCompiler();

    @Test
    void compilesCanonicalPlansToPhysicalRuleDeclFields() throws Exception {
        JsonNode single = output(plan("rule-login", singleEvent()));
        assertCommon(single, "rule-login", "single_event", Set.of(
                "condition", "alertSuppressionMinutes"));
        assertEquals("field_equals", single.at("/condition/type").asText());
        assertEquals("event.action", single.at("/condition/field").asText());
        assertEquals("login", single.at("/condition/value").asText());
        assertEquals(60, single.at("/alertSuppressionMinutes").asInt());

        JsonNode window = output(plan("rule-burst", window()));
        assertCommon(window, "rule-burst", "window", Set.of(
                "keyField", "condition", "windowMinutes", "slidingMinutes",
                "alertSuppressionMinutes", "threshold"));
        assertEquals("source.ip", window.at("/keyField").asText());
        assertEquals("all", window.at("/condition/type").asText());
        assertEquals("field_in", window.at("/condition/conditions/0/type").asText());
        assertEquals("event.action", window.at("/condition/conditions/0/field").asText());
        assertEquals("authentication_failure",
                window.at("/condition/conditions/0/values/0").asText());
        assertEquals("not", window.at("/condition/conditions/1/type").asText());
        assertEquals("field_equals",
                window.at("/condition/conditions/1/conditions/0/type").asText());
        assertEquals(10, window.at("/windowMinutes").asInt());
        assertEquals(2, window.at("/slidingMinutes").asInt());
        assertEquals(5, window.at("/threshold").asInt());
        assertEquals(15, window.at("/alertSuppressionMinutes").asInt());

        JsonNode cep = output(plan("rule-cep", cep()));
        assertCommon(cep, "rule-cep", "cep", Set.of("keyField", "cep"));
        assertEquals("source.ip", cep.at("/keyField").asText());
        assertEquals(15, cep.at("/cep/withinMinutes").asInt());
        assertEquals("field_equals", cep.at("/cep/pattern/0/condition/type").asText());
        assertEquals(3, cep.at("/cep/pattern/0/timesMin").asInt());
        assertEquals(20, cep.at("/cep/pattern/0/timesMax").asInt());
        assertEquals("next", cep.at("/cep/pattern/1/type").asText());
        assertFalse(cep.at("/cep/pattern/1").has("timesMin"));
        assertEquals("failures", cep.at("/cep/failureStep").asText());
        assertEquals("success", cep.at("/cep/successStep").asText());

        JsonNode baseline = output(plan("rule-baseline", baseline()));
        assertCommon(baseline, "rule-baseline", "baseline", Set.of("condition", "baseline"));
        assertEquals("field_equals", baseline.at("/condition/type").asText());
        assertEquals("event.action", baseline.at("/condition/field").asText());
        assertEquals("authentication_failure", baseline.at("/condition/value").asText());
        assertEquals("host.name", baseline.at("/baseline/keyField").asText());
        assertEquals(24, baseline.at("/baseline/windowHours").asInt());
        assertEquals(168, baseline.at("/baseline/baselineHours").asInt());
        assertEquals(24, baseline.at("/baseline/minBaselineHours").asInt());
        assertEquals(3.0, baseline.at("/baseline/sigmaMultiplier").asDouble());
    }

    @Test
    void rejectsUnsupportedRootAndNestedFields() throws Exception {
        ObjectNode root = object(plan("rule-login", singleEvent()));
        root.put("unexpected", true);
        assertRejected(json(root));

        root = object(plan("rule-login", singleEvent()));
        ((ObjectNode) root.at("/detection/condition")).put("unexpected", true);
        assertRejected(json(root));

        root = object(plan("rule-burst", window()));
        ((ObjectNode) root.at("/detection/window")).put("unexpected", true);
        assertRejected(json(root));
    }

    @Test
    void rejectsMissingRequiredRootAndNestedFields() throws Exception {
        ObjectNode root = object(plan("rule-login", singleEvent()));
        root.remove("rule_key");
        assertRejected(json(root));

        root = object(plan("rule-login", singleEvent()));
        ((ObjectNode) root.at("/alert")).remove("severity");
        assertRejected(json(root));

        root = object(plan("rule-burst", window()));
        ((ObjectNode) root.at("/detection/window")).remove("size_minutes");
        assertRejected(json(root));

        root = object(plan("rule-cep", cep()));
        ((ObjectNode) root.at("/detection/cep/output")).remove("success_step");
        assertRejected(json(root));
    }

    @Test
    void rejectsSchemaAndCompilerVersionMismatches() throws Exception {
        ObjectNode root = object(plan("rule-login", singleEvent()));
        root.put("schema_version", "1");
        assertRejected(json(root));

        root = object(plan("rule-login", singleEvent()));
        root.put("compiler_version", "older-compiler");
        assertRejected(json(root));
        assertThrows(IllegalArgumentException.class,
                () -> compiler.compile(plan("rule-login", singleEvent()), "older-compiler"));
    }

    @Test
    void rejectsValuesThatBreakFixedCanonicalSemantics() throws Exception {
        ObjectNode root = object(plan("rule-login", singleEvent()));
        ((ObjectNode) root.at("/input")).put("source", "other-events");
        assertRejected(json(root));

        root = object(plan("rule-login", singleEvent()));
        ((ObjectNode) root.at("/detection/suppression")).put("time_basis", "event_time");
        assertRejected(json(root));
        root = object(plan("rule-login", singleEvent()));
        ((ObjectNode) root.at("/detection/suppression")).put("fallback_entity", "host");
        assertRejected(json(root));
        root = object(plan("rule-login", singleEvent()));
        ((ObjectNode) root.at("/detection/suppression")).put("emission", "every_event");
        assertRejected(json(root));
        root = object(plan("rule-login", singleEvent()));
        ((ObjectNode) root.at("/detection/suppression")).putArray("fallback_entity_fields")
                .add("host.name");
        assertRejected(json(root));

        root = object(plan("rule-burst", window()));
        ((ObjectNode) root.at("/detection/window")).put("time_basis", "processing_time");
        assertRejected(json(root));
        root = object(plan("rule-burst", window()));
        ((ObjectNode) root.at("/detection/suppression")).put("primary_entity_field", "host.name");
        assertRejected(json(root));

        root = object(plan("rule-cep", cep()));
        ((ObjectNode) root.at("/detection/cep")).put("time_basis", "processing_time");
        assertRejected(json(root));
        root = object(plan("rule-cep", cep()));
        ((ObjectNode) root.at("/detection/cep/output")).put("type", "bruteforce_failure");
        assertRejected(json(root));

        root = object(plan("rule-baseline", baseline()));
        ((ObjectNode) root.at("/detection/baseline")).put("time_basis", "processing_time");
        assertRejected(json(root));
        root = object(plan("rule-baseline", baseline()));
        ((ObjectNode) root.at("/detection/baseline/algorithm")).put("type", "median_sigma");
        assertRejected(json(root));
        root = object(plan("rule-baseline", baseline()));
        ((ObjectNode) root.at("/detection/baseline/algorithm")).put("comparison", "less_than");
        assertRejected(json(root));
        root = object(plan("rule-baseline", baseline()));
        ((ObjectNode) root.at("/detection/baseline/algorithm")).put("require_positive_threshold", false);
        assertRejected(json(root));
        root = object(plan("rule-baseline", baseline()));
        ((ObjectNode) root.at("/detection/baseline/algorithm")).put("sigma_multiplier", 0);
        assertRejected(json(root));
    }

    private JsonNode output(String plan) throws Exception {
        return MAPPER.readTree(compiler.compile(plan));
    }

    private static void assertCommon(JsonNode output, String id, String category,
                                     Set<String> categoryFields) {
        Set<String> expected = new HashSet<>(COMMON_FIELDS);
        expected.addAll(categoryFields);
        assertEquals(expected, fields(output));
        assertEquals(id, output.at("/id").asText());
        assertEquals(id + " detection", output.at("/name").asText());
        assertEquals(category, output.at("/category").asText());
        assertEquals("test_detection", output.at("/type").asText());
        assertTrue(output.at("/enabled").asBoolean());
        assertEquals("high", output.at("/severity").asText());
        assertEquals("canonical fixture", output.at("/description").asText());
        assertEquals(75, output.at("/riskScore").asInt());
        assertEquals("runtime", output.at("/tags/0").asText());
        assertEquals("stable", output.at("/status").asText());
        assertEquals("1.0", output.at("/version").asText());
    }

    private void assertRejected(String plan) {
        assertThrows(IllegalArgumentException.class, () -> compiler.compile(plan));
    }

    private static ObjectNode object(String plan) throws Exception {
        return (ObjectNode) MAPPER.readTree(plan);
    }

    private static String json(JsonNode node) throws Exception {
        return MAPPER.writeValueAsString(node);
    }

    private static Set<String> fields(JsonNode node) {
        Set<String> fields = new HashSet<>();
        node.fieldNames().forEachRemaining(fields::add);
        return fields;
    }

    private static String plan(String key, String detection) {
        return """
                {
                  "schema_version":"%s",
                  "compiler_version":"%s",
                  "rule_key":"%s",
                  "input":{"source":"siem-events"},
                  "detection":%s,
                  "alert":{
                    "name":"%s detection",
                    "type":"test_detection",
                    "severity":"high",
                    "description":"canonical fixture",
                    "risk_score":75,
                    "tags":["runtime","contract"],
                    "status":"stable",
                    "version":"1.0"
                  }
                }
                """.formatted(DetectionPlanCompiler.SCHEMA_VERSION,
                DetectionPlanCompiler.VERSION, key, detection, key);
    }

    private static String singleEvent() {
        return """
                {"type":"single_event",
                 "condition":{"operator":"eq","field":"event.action","value":"login"},
                 "suppression":{"duration_minutes":60,"time_basis":"processing_time",
                   "primary_entity_field":null,"fallback_entity_fields":["source.ip","user.name"],
                   "fallback_entity":"unknown","emission":"first_and_final_count"}}
                """;
    }

    private static String window() {
        return """
                {"type":"window","key_field":"source.ip",
                 "condition":{"operator":"all","conditions":[
                   {"operator":"in","field":"event.action","values":["authentication_failure","login"]},
                   {"operator":"not","conditions":[
                     {"operator":"eq","field":"user.name","value":"service-account"}]}]},
                 "window":{"time_basis":"event_time","type":"sliding","size_minutes":10,"slide_minutes":2},
                 "threshold":5,
                 "suppression":{"duration_minutes":15,"time_basis":"processing_time",
                   "primary_entity_field":"source.ip","fallback_entity_fields":["source.ip","user.name"],
                   "fallback_entity":"unknown","emission":"first_and_final_count"}}
                """;
    }

    private static String cep() {
        return """
                {"type":"cep","key_field":"source.ip","cep":{
                  "time_basis":"event_time","within_minutes":15,
                  "pattern":[
                    {"name":"failures","type":"begin",
                     "condition":{"operator":"eq","field":"event.action","value":"authentication_failure"},
                     "times_min":3,"times_max":20},
                    {"name":"success","type":"next",
                     "condition":{"operator":"eq","field":"event.action","value":"authentication_success"}}],
                  "output":{"type":"bruteforce_success","failure_step":"failures","success_step":"success"}}}
                """;
    }

    private static String baseline() {
        return """
                {"type":"baseline","condition":{"operator":"eq","field":"event.action",
                  "value":"authentication_failure"},"baseline":{
                  "key_field":"host.name","time_basis":"event_time","window_hours":24,
                  "baseline_hours":168,"min_baseline_hours":24,
                  "algorithm":{"type":"mean_sigma","sigma_multiplier":3.0,
                    "comparison":"greater_than","require_positive_threshold":true}}}
                """;
    }
}
