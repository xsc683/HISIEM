package com.xscsiem.hsiem_platform.rules;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The sole codec for the persisted v2 DetectionPlan JSON contract. */
public final class DetectionPlanCodec {
    private final ObjectMapper mapper =
            new ObjectMapper()
                    .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
                    .configure(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, true);

    public String encode(DetectionPlan plan) {
        try {
            return mapper.writeValueAsString(toMap(plan));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("DetectionPlan serialization failed", e);
        }
    }

    public DetectionPlan decode(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("detection plan JSON must not be blank");
        }
        try {
            return fromMap(mapper.readValue(json, Map.class));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("invalid detection plan JSON", e);
        }
    }

    /** Converts an already validated dynamic authoring projection into the typed IR. */
    public DetectionPlan fromMap(Map<String, ?> source) {
        if (source == null) throw new IllegalArgumentException("detection plan must be an object");
        Map<?, ?> root = source;
        Map<?, ?> input = object(root.get("input"), "input");
        Map<?, ?> detection = object(root.get("detection"), "detection");
        Map<?, ?> alert = object(root.get("alert"), "alert");
        return new DetectionPlan(
                text(root, "schema_version"),
                text(root, "compiler_version"),
                text(root, "rule_key"),
                new DetectionPlan.Input(text(input, "source")),
                detection(detection),
                alert(alert));
    }

    private DetectionPlan.Alert alert(Map<?, ?> source) {
        return new DetectionPlan.Alert(
                text(source, "name"),
                text(source, "type"),
                text(source, "severity"),
                nullableText(source, "description"),
                integer(source, "risk_score"),
                strings(source.get("tags"), "alert.tags"),
                text(source, "status"),
                text(source, "version"));
    }

    private DetectionPlan.DetectionSpec detection(Map<?, ?> source) {
        String type = text(source, "type");
        return switch (type) {
            case "single_event" ->
                    new DetectionPlan.SingleEventDetection(
                            condition(source.get("condition")),
                            suppression(source.get("suppression")));
            case "window" ->
                    new DetectionPlan.WindowDetection(
                            text(source, "key_field"),
                            condition(source.get("condition")),
                            window(source.get("window")),
                            integer(source, "threshold"),
                            suppression(source.get("suppression")));
            case "cep" ->
                    new DetectionPlan.CepDetection(
                            text(source, "key_field"), cep(source.get("cep")));
            case "baseline" ->
                    new DetectionPlan.BaselineDetection(
                            condition(source.get("condition")), baseline(source.get("baseline")));
            default ->
                    throw new IllegalArgumentException("unsupported detection category: " + type);
        };
    }

    private DetectionPlan.Suppression suppression(Object raw) {
        Map<?, ?> source = object(raw, "suppression");
        return new DetectionPlan.Suppression(
                longValue(source, "duration_minutes"),
                text(source, "time_basis"),
                nullableText(source, "primary_entity_field"),
                strings(source.get("fallback_entity_fields"), "fallback_entity_fields"),
                text(source, "fallback_entity"),
                text(source, "emission"));
    }

    private DetectionPlan.Window window(Object raw) {
        Map<?, ?> source = object(raw, "window");
        return new DetectionPlan.Window(
                text(source, "time_basis"),
                text(source, "type"),
                longValue(source, "size_minutes"),
                nullableLong(source, "slide_minutes"));
    }

    private DetectionPlan.Cep cep(Object raw) {
        Map<?, ?> source = object(raw, "cep");
        List<?> rawPattern = list(source.get("pattern"), "cep.pattern");
        List<DetectionPlan.CepStep> pattern = new ArrayList<>();
        for (Object item : rawPattern) {
            Map<?, ?> step = object(item, "cep.pattern step");
            pattern.add(
                    new DetectionPlan.CepStep(
                            text(step, "name"),
                            text(step, "type"),
                            condition(step.get("condition")),
                            nullableInteger(step, "times_min"),
                            nullableInteger(step, "times_max")));
        }
        Map<?, ?> output = object(source.get("output"), "cep.output");
        return new DetectionPlan.Cep(
                text(source, "time_basis"),
                nullableLong(source, "within_minutes"),
                pattern,
                new DetectionPlan.CepOutput(
                        text(output, "type"),
                        text(output, "failure_step"),
                        text(output, "success_step")));
    }

    private DetectionPlan.Baseline baseline(Object raw) {
        Map<?, ?> source = object(raw, "baseline");
        Map<?, ?> algorithm = object(source.get("algorithm"), "baseline.algorithm");
        return new DetectionPlan.Baseline(
                text(source, "key_field"),
                text(source, "time_basis"),
                longValue(source, "window_hours"),
                integer(source, "baseline_hours"),
                integer(source, "min_baseline_hours"),
                new DetectionPlan.BaselineAlgorithm(
                        text(algorithm, "type"), doubleValue(algorithm, "sigma_multiplier"),
                        text(algorithm, "comparison"),
                                booleanValue(algorithm, "require_positive_threshold")));
    }

    private DetectionPlan.Condition condition(Object raw) {
        Map<?, ?> source = object(raw, "condition");
        return switch (text(source, "operator")) {
            case "eq" -> new DetectionPlan.Eq(text(source, "field"), scalar(source.get("value")));
            case "in" -> new DetectionPlan.In(text(source, "field"), scalars(source.get("values")));
            case "all" -> new DetectionPlan.All(conditions(source.get("conditions")));
            case "any" -> new DetectionPlan.Any(conditions(source.get("conditions")));
            case "not" -> {
                List<DetectionPlan.Condition> conditions = conditions(source.get("conditions"));
                if (conditions.size() != 1)
                    throw new IllegalArgumentException("not requires one condition");
                yield new DetectionPlan.Not(conditions.getFirst());
            }
            default ->
                    throw new IllegalArgumentException(
                            "unsupported condition operator: " + source.get("operator"));
        };
    }

    private List<DetectionPlan.Condition> conditions(Object raw) {
        List<?> values = list(raw, "condition.conditions");
        return values.stream().map(this::condition).toList();
    }

    private Map<String, Object> toMap(DetectionPlan plan) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schema_version", plan.schemaVersion());
        root.put("compiler_version", plan.compilerVersion());
        root.put("rule_key", plan.ruleKey());
        root.put("input", Map.of("source", plan.input().source()));
        root.put("detection", detectionMap(plan.detection()));
        root.put("alert", alertMap(plan.alert()));
        return root;
    }

    private Map<String, Object> alertMap(DetectionPlan.Alert alert) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", alert.name());
        out.put("type", alert.type());
        out.put("severity", alert.severity());
        out.put("description", alert.description());
        out.put("risk_score", alert.riskScore());
        out.put("tags", alert.tags());
        out.put("status", alert.status());
        out.put("version", alert.version());
        return out;
    }

    private Map<String, Object> detectionMap(DetectionPlan.DetectionSpec spec) {
        Map<String, Object> out = new LinkedHashMap<>();
        switch (spec) {
            case DetectionPlan.SingleEventDetection single -> {
                out.put("type", "single_event");
                out.put("condition", conditionMap(single.condition()));
                out.put("suppression", suppressionMap(single.suppression()));
            }
            case DetectionPlan.WindowDetection window -> {
                out.put("type", "window");
                out.put("key_field", window.keyField());
                out.put("condition", conditionMap(window.condition()));
                out.put("window", windowMap(window.window()));
                out.put("threshold", window.threshold());
                out.put("suppression", suppressionMap(window.suppression()));
            }
            case DetectionPlan.CepDetection cep -> {
                out.put("type", "cep");
                out.put("key_field", cep.keyField());
                out.put("cep", cepMap(cep.cep()));
            }
            case DetectionPlan.BaselineDetection baseline -> {
                out.put("type", "baseline");
                out.put("condition", conditionMap(baseline.condition()));
                out.put("baseline", baselineMap(baseline.baseline()));
            }
        }
        return out;
    }

    private Map<String, Object> suppressionMap(DetectionPlan.Suppression value) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("duration_minutes", value.durationMinutes());
        out.put("time_basis", value.timeBasis());
        out.put("primary_entity_field", value.primaryEntityField());
        out.put("fallback_entity_fields", value.fallbackEntityFields());
        out.put("fallback_entity", value.fallbackEntity());
        out.put("emission", value.emission());
        return out;
    }

    private Map<String, Object> windowMap(DetectionPlan.Window value) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("time_basis", value.timeBasis());
        out.put("type", value.type());
        out.put("size_minutes", value.sizeMinutes());
        if (value.slideMinutes() != null) out.put("slide_minutes", value.slideMinutes());
        return out;
    }

    private Map<String, Object> cepMap(DetectionPlan.Cep value) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("time_basis", value.timeBasis());
        out.put("within_minutes", value.withinMinutes());
        List<Map<String, Object>> steps = new ArrayList<>();
        for (DetectionPlan.CepStep step : value.pattern()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", step.name());
            item.put("type", step.type());
            item.put("condition", conditionMap(step.condition()));
            if (step.timesMin() != null) item.put("times_min", step.timesMin());
            if (step.timesMax() != null) item.put("times_max", step.timesMax());
            steps.add(item);
        }
        out.put("pattern", steps);
        DetectionPlan.CepOutput output = value.output();
        out.put(
                "output",
                Map.of(
                        "type",
                        output.type(),
                        "failure_step",
                        output.failureStep(),
                        "success_step",
                        output.successStep()));
        return out;
    }

    private Map<String, Object> baselineMap(DetectionPlan.Baseline value) {
        DetectionPlan.BaselineAlgorithm algorithm = value.algorithm();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("key_field", value.keyField());
        out.put("time_basis", value.timeBasis());
        out.put("window_hours", value.windowHours());
        out.put("baseline_hours", value.baselineHours());
        out.put("min_baseline_hours", value.minBaselineHours());
        out.put(
                "algorithm",
                Map.of(
                        "type",
                        algorithm.type(),
                        "sigma_multiplier",
                        algorithm.sigmaMultiplier(),
                        "comparison",
                        algorithm.comparison(),
                        "require_positive_threshold",
                        algorithm.requirePositiveThreshold()));
        return out;
    }

    private Map<String, Object> conditionMap(DetectionPlan.Condition value) {
        Map<String, Object> out = new LinkedHashMap<>();
        switch (value) {
            case DetectionPlan.Eq eq -> {
                out.put("operator", "eq");
                out.put("field", eq.field());
                out.put("value", eq.value());
            }
            case DetectionPlan.In in -> {
                out.put("operator", "in");
                out.put("field", in.field());
                out.put("values", in.values());
            }
            case DetectionPlan.All all -> {
                out.put("operator", "all");
                out.put("conditions", all.conditions().stream().map(this::conditionMap).toList());
            }
            case DetectionPlan.Any any -> {
                out.put("operator", "any");
                out.put("conditions", any.conditions().stream().map(this::conditionMap).toList());
            }
            case DetectionPlan.Not not -> {
                out.put("operator", "not");
                out.put("conditions", List.of(conditionMap(not.condition())));
            }
        }
        return out;
    }

    private static Map<?, ?> object(Object value, String path) {
        if (!(value instanceof Map<?, ?> map))
            throw new IllegalArgumentException(path + " must be an object");
        return map;
    }

    private static List<?> list(Object value, String path) {
        if (!(value instanceof List<?> list))
            throw new IllegalArgumentException(path + " must be an array");
        return list;
    }

    private static String text(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof String string) || string.isBlank())
            throw new IllegalArgumentException(key + " must be a string");
        return string;
    }

    private static String nullableText(Map<?, ?> map, String key) {
        Object value = map.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static long longValue(Map<?, ?> map, String key) {
        return number(map.get(key), key).longValue();
    }

    private static Long nullableLong(Map<?, ?> map, String key) {
        Object value = map.get(key);
        return value == null ? null : number(value, key).longValue();
    }

    private static int integer(Map<?, ?> map, String key) {
        return number(map.get(key), key).intValue();
    }

    private static Integer nullableInteger(Map<?, ?> map, String key) {
        Object value = map.get(key);
        return value == null ? null : number(value, key).intValue();
    }

    private static double doubleValue(Map<?, ?> map, String key) {
        return ((Number) map.get(key)).doubleValue();
    }

    private static boolean booleanValue(Map<?, ?> map, String key) {
        return Boolean.TRUE.equals(map.get(key));
    }

    private static Number number(Object value, String path) {
        if (!(value instanceof Number n))
            throw new IllegalArgumentException(path + " must be numeric");
        return n;
    }

    private static List<String> strings(Object raw, String path) {
        if (!(raw instanceof List<?> values))
            throw new IllegalArgumentException(path + " must be an array");
        return values.stream()
                .map(
                        value -> {
                            if (!(value instanceof String s))
                                throw new IllegalArgumentException(path + " must contain strings");
                            return s;
                        })
                .toList();
    }

    private static List<Object> scalars(Object raw) {
        if (!(raw instanceof List<?> values))
            throw new IllegalArgumentException("condition.values must be an array");
        return values.stream()
                .<Object>map(
                        value -> {
                            if (value == null
                                    || value instanceof Map<?, ?>
                                    || value instanceof List<?>)
                                throw new IllegalArgumentException(
                                        "condition value must be scalar");
                            return value;
                        })
                .toList();
    }

    private static Object scalar(Object value) {
        if (value == null || value instanceof Map<?, ?> || value instanceof List<?>)
            throw new IllegalArgumentException("condition.value must be scalar");
        return value;
    }
}
