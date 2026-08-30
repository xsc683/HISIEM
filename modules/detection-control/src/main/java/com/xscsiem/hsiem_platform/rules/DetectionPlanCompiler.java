package com.xscsiem.hsiem_platform.rules;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Compiles the bounded YAML rule grammar into a typed, auditable DetectionPlan IR. */
public final class DetectionPlanCompiler {

    public static final String VERSION = "hisiem-detection-plan-1";

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    public CompiledPlan compile(Map<String, Object> rule, int revision) {
        String ruleKey = String.valueOf(rule.get("id"));
        String category = String.valueOf(rule.get("category"));
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("rule_key", ruleKey);
        plan.put("revision", revision);
        plan.put("compiler_version", VERSION);
        plan.put("inputs", List.of(Map.of(
                "source", "siem-events",
                "filter", condition(rule.get("condition")))));

        List<Map<String, Object>> operators = new ArrayList<>();
        switch (category) {
            case "single_event" -> {
                operators.add(operator(ruleKey, revision, "single_event_match", Map.of()));
                operators.add(operator(ruleKey, revision, "alert", alertParameters(rule)));
            }
            case "window" -> {
                operators.add(operator(ruleKey, revision, "group", Map.of("field", rule.get("keyField"))));
                Map<String, Object> windowParameters = new LinkedHashMap<>();
                windowParameters.put("minutes", rule.get("windowMinutes"));
                if (rule.get("slidingMinutes") != null) {
                    windowParameters.put("sliding_minutes", rule.get("slidingMinutes"));
                }
                operators.add(operator(ruleKey, revision, "window", windowParameters));
                operators.add(operator(ruleKey, revision, "count", Map.of("threshold", rule.get("threshold"))));
                operators.add(operator(ruleKey, revision, "alert", alertParameters(rule)));
            }
            case "cep" -> operators.add(operator(ruleKey, revision, "cep", Map.of("definition", rule.get("cep"))));
            case "baseline" -> operators.add(operator(ruleKey, revision, "baseline", Map.of("definition", rule.get("baseline"))));
            default -> throw new IllegalArgumentException("unsupported detection category: " + category);
        }
        plan.put("operators", operators);
        plan.put("grammar", "input-filter-typed-operator-alert");
        String planJson = json(plan);
        return new CompiledPlan(planJson, sha256(planJson));
    }

    private Map<String, Object> operator(String ruleKey, int revision, String type,
                                         Map<String, Object> parameters) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("uid", "rule:" + ruleKey + ":rev" + revision + ":" + type);
        result.put("type", type);
        result.put("parameters", parameters);
        return result;
    }

    private Map<String, Object> alertParameters(Map<String, Object> rule) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", rule.get("type"));
        parameters.put("severity", rule.get("severity"));
        parameters.put("risk_score", rule.get("riskScore"));
        parameters.put("description", rule.getOrDefault("description", ""));
        return parameters;
    }

    @SuppressWarnings("unchecked")
    private Object condition(Object raw) {
        if (!(raw instanceof Map<?, ?> input)) {
            throw new IllegalArgumentException("rule condition must be an object");
        }
        String type = String.valueOf(input.get("type"));
        Map<String, Object> result = new LinkedHashMap<>();
        switch (type) {
            case "field_equals" -> {
                result.put("operator", "eq");
                result.put("field", input.get("field"));
                result.put("value", input.get("value"));
            }
            case "field_in" -> {
                result.put("operator", "in");
                result.put("field", input.get("field"));
                result.put("values", input.get("values"));
            }
            case "all", "any", "not" -> {
                result.put("operator", type);
                List<Object> children = new ArrayList<>();
                for (Object child : (List<Object>) input.get("conditions")) {
                    children.add(condition(child));
                }
                result.put("conditions", children);
            }
            default -> throw new IllegalArgumentException("unsupported rule condition: " + type);
        }
        return result;
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("DetectionPlan serialization failed", e);
        }
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    public record CompiledPlan(String json, String hash) { }
}
