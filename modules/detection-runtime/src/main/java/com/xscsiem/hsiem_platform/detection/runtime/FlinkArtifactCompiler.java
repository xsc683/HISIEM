package com.xscsiem.hsiem_platform.detection.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.xscsiem.hsiem_platform.rules.DetectionPlanCompiler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Converts a v2 DetectionPlan into the existing Flink RuleDecl JSON shape. */
public final class FlinkArtifactCompiler {

    private static final String INPUT_SOURCE = "siem-events";
    private static final Set<String> CATEGORIES = Set.of("single_event", "window", "cep", "baseline");

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .configure(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, true);

    /** Compile a stored plan, checking the compiler version stored beside it. */
    public String compile(String planJson, String compilerVersion) {
        if (!DetectionPlanCompiler.VERSION.equals(compilerVersion)) {
            throw new IllegalArgumentException("unsupported detection plan compiler version: "
                    + compilerVersion);
        }
        JsonNode root = parse(planJson);
        fields(root, "plan", "schema_version", "compiler_version", "rule_key", "input",
                "detection", "alert");
        String schemaVersion = text(root, "schema_version", null, true);
        if (!DetectionPlanCompiler.SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("unsupported detection plan schema_version: "
                    + schemaVersion);
        }
        if (!compilerVersion.equals(text(root, "compiler_version", null, true))) {
            throw new IllegalArgumentException("detection plan compiler_version does not match stored version");
        }
        String ruleKey = text(root, "rule_key", null, true);
        JsonNode input = object(root, "input");
        fields(input, "input", "source");
        if (!INPUT_SOURCE.equals(text(input, "source", null, true))) {
            throw new IllegalArgumentException("unsupported detection input source");
        }
        JsonNode detection = object(root, "detection");
        String category = text(detection, "type", null, true);
        if (!CATEGORIES.contains(category)) {
            throw new IllegalArgumentException("unsupported detection category: " + category);
        }
        JsonNode alert = object(root, "alert");
        fields(alert, "alert", "name", "type", "severity", "description", "risk_score",
                "tags", "status", "version");

        Map<String, Object> declaration = new LinkedHashMap<>();
        declaration.put("id", ruleKey);
        declaration.put("name", text(alert, "name", null, true));
        declaration.put("category", category);
        declaration.put("type", text(alert, "type", null, true));
        declaration.put("enabled", true);
        declaration.put("severity", text(alert, "severity", null, true));
        declaration.put("description", nullableText(alert, "description", ""));
        declaration.put("riskScore", integer(alert, "risk_score", 0, true));
        declaration.put("tags", strings(alert, "tags"));
        declaration.put("status", text(alert, "status", null, true));
        declaration.put("version", text(alert, "version", null, true));

        switch (category) {
            case "single_event" -> compileSingleEvent(detection, declaration);
            case "window" -> compileWindow(detection, declaration);
            case "cep" -> compileCep(detection, declaration);
            case "baseline" -> compileBaseline(detection, declaration);
            default -> throw new IllegalArgumentException("unsupported detection category: " + category);
        }
        return json(declaration);
    }

    /** Compile a v2 plan using the current compiler version. */
    public String compile(String planJson) {
        return compile(planJson, DetectionPlanCompiler.VERSION);
    }

    /** Alias for callers that describe the output as a RuleDecl artifact. */
    public String toRuleDeclJson(String planJson, String compilerVersion) {
        return compile(planJson, compilerVersion);
    }

    private void compileSingleEvent(JsonNode detection, Map<String, Object> declaration) {
        fields(detection, "detection", "type", "condition", "suppression");
        declaration.put("condition", condition(detection.get("condition"), "detection.condition"));
        declaration.put("alertSuppressionMinutes", suppression(
                object(detection, "suppression"), null));
    }

    private void compileWindow(JsonNode detection, Map<String, Object> declaration) {
        fields(detection, "detection", "type", "key_field", "condition", "window",
                "threshold", "suppression");
        String keyField = text(detection, "key_field", null, true);
        JsonNode window = object(detection, "window");
        String windowType = text(window, "type", null, true);
        if ("tumbling".equals(windowType)) {
            fields(window, "detection.window", "time_basis", "type", "size_minutes");
        } else if ("sliding".equals(windowType)) {
            fields(window, "detection.window", "time_basis", "type", "size_minutes",
                    "slide_minutes");
        } else {
            throw new IllegalArgumentException("unsupported detection window type: " + windowType);
        }
        requireText(window, "time_basis", "event_time", "detection.window.time_basis");
        declaration.put("keyField", keyField);
        declaration.put("condition", condition(detection.get("condition"), "detection.condition"));
        declaration.put("windowMinutes", integer(window, "size_minutes", 0, true));
        if ("sliding".equals(windowType)) {
            declaration.put("slidingMinutes", integer(window, "slide_minutes", 0, true));
        }
        declaration.put("alertSuppressionMinutes", suppression(
                object(detection, "suppression"), keyField));
        declaration.put("threshold", integer(detection, "threshold", 0, true));
    }

    private int suppression(JsonNode source, String expectedPrimaryField) {
        fields(source, "detection.suppression", "duration_minutes", "time_basis",
                "primary_entity_field", "fallback_entity_fields", "fallback_entity", "emission");
        requireText(source, "time_basis", "processing_time", "detection.suppression.time_basis");
        requireText(source, "fallback_entity", "unknown", "detection.suppression.fallback_entity");
        requireText(source, "emission", "first_and_final_count", "detection.suppression.emission");
        JsonNode primary = source.get("primary_entity_field");
        if (expectedPrimaryField == null) {
            if (primary != null && !primary.isNull()) {
                throw new IllegalArgumentException("single-event suppression primary entity must be null");
            }
        } else if (primary == null || !expectedPrimaryField.equals(primary.textValue())) {
            throw new IllegalArgumentException("window suppression primary entity must match key_field");
        }
        List<String> fallback = strings(source, "fallback_entity_fields");
        if (!fallback.equals(List.of("source.ip", "user.name"))) {
            throw new IllegalArgumentException("unsupported suppression fallback entity fields");
        }
        int duration = integer(source, "duration_minutes", 0, true);
        if (duration <= 0) {
            throw new IllegalArgumentException("suppression duration_minutes must be positive");
        }
        return duration;
    }

    private void compileCep(JsonNode detection, Map<String, Object> declaration) {
        fields(detection, "detection", "type", "key_field", "cep");
        declaration.put("keyField", text(detection, "key_field", null, true));
        JsonNode source = object(detection, "cep");
        fields(source, "detection.cep", "time_basis", "within_minutes", "pattern", "output");
        requireText(source, "time_basis", "event_time", "detection.cep.time_basis");
        Map<String, Object> cep = new LinkedHashMap<>();
        JsonNode within = source.get("within_minutes");
        if (within != null && !within.isNull()) {
            cep.put("withinMinutes", integer(within, "detection.cep.within_minutes"));
        }
        JsonNode pattern = source.get("pattern");
        if (pattern == null || !pattern.isArray() || pattern.isEmpty()) {
            throw new IllegalArgumentException("detection.cep.pattern must be a non-empty array");
        }
        List<Map<String, Object>> steps = new ArrayList<>();
        for (int i = 0; i < pattern.size(); i++) {
            JsonNode step = pattern.get(i);
            if (!step.isObject()) {
                throw new IllegalArgumentException("detection.cep.pattern[" + i + "] must be an object");
            }
            JsonNode min = step.get("times_min");
            JsonNode max = step.get("times_max");
            if (min == null && max == null) {
                fields(step, "detection.cep.pattern[" + i + "]", "name", "type", "condition");
            } else {
                fields(step, "detection.cep.pattern[" + i + "]", "name", "type", "condition",
                        "times_min", "times_max");
            }
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("name", text(step, "name", null, true));
            output.put("type", text(step, "type", null, true));
            output.put("condition", condition(step.get("condition"),
                    "detection.cep.pattern[" + i + "].condition"));
            if (min != null && !min.isNull()) output.put("timesMin",
                    integer(min, "detection.cep.pattern[" + i + "].times_min"));
            if (max != null && !max.isNull()) output.put("timesMax",
                    integer(max, "detection.cep.pattern[" + i + "].times_max"));
            steps.add(output);
        }
        cep.put("pattern", steps);
        JsonNode output = object(source, "output");
        fields(output, "detection.cep.output", "type", "failure_step", "success_step");
        requireText(output, "type", "bruteforce_success", "detection.cep.output.type");
        String failureStep = text(output, "failure_step", null, true);
        String successStep = text(output, "success_step", null, true);
        Set<String> names = steps.stream().map(step -> String.valueOf(step.get("name")))
                .collect(java.util.stream.Collectors.toSet());
        if (!names.contains(failureStep) || !names.contains(successStep)) {
            throw new IllegalArgumentException("CEP output steps must exist in the pattern");
        }
        cep.put("failureStep", failureStep);
        cep.put("successStep", successStep);
        declaration.put("cep", cep);
    }

    private void compileBaseline(JsonNode detection, Map<String, Object> declaration) {
        fields(detection, "detection", "type", "condition", "baseline");
        declaration.put("condition", condition(detection.get("condition"), "detection.condition"));
        JsonNode source = object(detection, "baseline");
        fields(source, "detection.baseline", "key_field", "time_basis", "window_hours",
                "baseline_hours", "min_baseline_hours", "algorithm");
        requireText(source, "time_basis", "event_time", "detection.baseline.time_basis");
        Map<String, Object> baseline = new LinkedHashMap<>();
        baseline.put("keyField", text(source, "key_field", null, true));
        baseline.put("windowHours", integer(source, "window_hours", 0, true));
        baseline.put("baselineHours", integer(source, "baseline_hours", 0, true));
        baseline.put("minBaselineHours", integer(source, "min_baseline_hours", 0, true));
        JsonNode algorithm = object(source, "algorithm");
        fields(algorithm, "detection.baseline.algorithm", "type", "sigma_multiplier",
                "comparison", "require_positive_threshold");
        requireText(algorithm, "type", "mean_sigma", "detection.baseline.algorithm.type");
        requireText(algorithm, "comparison", "greater_than",
                "detection.baseline.algorithm.comparison");
        JsonNode positive = algorithm.get("require_positive_threshold");
        if (positive == null || !positive.isBoolean() || !positive.booleanValue()) {
            throw new IllegalArgumentException("baseline algorithm requires a positive threshold");
        }
        JsonNode sigma = algorithm.get("sigma_multiplier");
        if (sigma == null || !sigma.isNumber() || !Double.isFinite(sigma.doubleValue())
                || sigma.doubleValue() <= 0) {
            throw new IllegalArgumentException("baseline sigma_multiplier must be finite and positive");
        }
        baseline.put("sigmaMultiplier", sigma.doubleValue());
        declaration.put("baseline", baseline);
    }

    private Map<String, Object> condition(JsonNode node, String path) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException(path + " must be an object");
        }
        String operator = text(node, "operator", null, true);
        Map<String, Object> result = new LinkedHashMap<>();
        switch (operator) {
            case "eq" -> {
                fields(node, path, "operator", "field", "value");
                result.put("type", "field_equals");
                result.put("field", text(node, "field", null, true));
                result.put("value", scalar(node.get("value"), path + ".value"));
            }
            case "in" -> {
                fields(node, path, "operator", "field", "values");
                result.put("type", "field_in");
                result.put("field", text(node, "field", null, true));
                JsonNode values = node.get("values");
                if (values == null || !values.isArray() || values.isEmpty()) {
                    throw new IllegalArgumentException(path + ".values must be a non-empty array");
                }
                List<Object> output = new ArrayList<>();
                for (int i = 0; i < values.size(); i++) {
                    output.add(scalar(values.get(i), path + ".values[" + i + "]"));
                }
                result.put("values", output);
            }
            case "all", "any", "not" -> {
                fields(node, path, "operator", "conditions");
                result.put("type", operator);
                JsonNode children = node.get("conditions");
                if (children == null || !children.isArray() || children.isEmpty()
                        || "not".equals(operator) && children.size() != 1) {
                    throw new IllegalArgumentException(path + ".conditions has an invalid size");
                }
                List<Object> output = new ArrayList<>();
                for (int i = 0; i < children.size(); i++) {
                    output.add(condition(children.get(i), path + ".conditions[" + i + "]"));
                }
                result.put("conditions", output);
            }
            default -> throw new IllegalArgumentException("unsupported normalized condition: " + operator);
        }
        return result;
    }

    private Object scalar(JsonNode value, String path) {
        if (value == null || value.isNull() || !value.isValueNode()) {
            throw new IllegalArgumentException(path + " must be a scalar");
        }
        if (value.isTextual()) return value.textValue();
        if (value.isBoolean()) return value.booleanValue();
        if (value.isIntegralNumber()) return value.longValue();
        if (value.isFloatingPointNumber()) return value.doubleValue();
        throw new IllegalArgumentException(path + " has an unsupported value type");
    }

    private List<String> strings(JsonNode parent, String field) {
        JsonNode values = parent.get(field);
        if (values == null || values.isNull()) return List.of();
        if (!values.isArray()) throw new IllegalArgumentException("alert." + field + " must be an array");
        List<String> result = new ArrayList<>();
        for (JsonNode value : values) {
            if (!value.isTextual()) throw new IllegalArgumentException("alert." + field + " must contain strings");
            result.add(value.textValue());
        }
        return result;
    }

    private int integer(JsonNode parent, String field, int defaultValue, boolean required) {
        JsonNode value = parent;
        if (parent.isObject()) value = parent.get(field);
        if (value == null || value.isNull()) {
            if (required) throw new IllegalArgumentException(field + " must be an integer");
            return defaultValue;
        }
        return integer(value, field);
    }

    private int integer(JsonNode value, String path) {
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new IllegalArgumentException(path + " must be an integer");
        }
        return value.intValue();
    }

    private void fields(JsonNode node, String path, String... allowedFields) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException(path + " must be an object");
        }
        Set<String> allowed = Set.of(allowedFields);
        node.fieldNames().forEachRemaining(field -> {
            if (!allowed.contains(field)) {
                throw new IllegalArgumentException(path + " contains unsupported field: " + field);
            }
        });
        for (String field : allowedFields) {
            if (!node.has(field)) {
                throw new IllegalArgumentException(path + " is missing field: " + field);
            }
        }
    }

    private void requireText(JsonNode parent, String field, String expected, String path) {
        String actual = text(parent, field, null, true);
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException("unsupported " + path + ": " + actual);
        }
    }

    private JsonNode object(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isObject()) {
            throw new IllegalArgumentException(field + " must be an object");
        }
        return value;
    }

    private String nullableText(JsonNode parent, String field, String defaultValue) {
        JsonNode value = parent.get(field);
        if (value == null || value.isNull()) {
            return defaultValue;
        }
        if (!value.isTextual()) {
            throw new IllegalArgumentException(field + " must be a string");
        }
        return value.textValue();
    }

    private String text(JsonNode parent, String field, String defaultValue, boolean required) {
        JsonNode value = parent.get(field);
        if (value == null || value.isNull()) {
            if (required) throw new IllegalArgumentException(field + " must be a non-blank string");
            return defaultValue;
        }
        if (!value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalArgumentException(field + " must be a non-blank string");
        }
        return value.textValue();
    }

    private JsonNode parse(String planJson) {
        if (planJson == null || planJson.isBlank()) {
            throw new IllegalArgumentException("detection plan JSON must not be blank");
        }
        try {
            JsonNode root = mapper.readTree(planJson);
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("detection plan must be a JSON object");
            }
            return root;
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("invalid detection plan JSON", e);
        }
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Flink RuleDecl serialization failed", e);
        }
    }
}
