package com.xscsiem.hsiem_platform.rules;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Compiles the bounded YAML rule grammar into a canonical DetectionPlan IR. */
public final class DetectionPlanCompiler {

    public static final String VERSION = "hisiem-detection-plan-2";
    public static final String SCHEMA_VERSION = "2";
    private static final String INPUT_SOURCE = "siem-events";
    private static final String AUTHENTICATION_FAILURE = "authentication_failure";
    private static final Pattern RULE_KEY = Pattern.compile("[a-z0-9][a-z0-9-]{2,95}");
    private static final Pattern FIELD_NAME = Pattern.compile("[A-Za-z@][A-Za-z0-9_.@-]{0,127}");
    private static final Pattern ALERT_TYPE = Pattern.compile("[a-z0-9][a-z0-9_]{1,95}");
    private static final Set<String> CATEGORIES = Set.of("single_event", "window", "cep", "baseline");
    private static final Set<String> SEVERITIES = Set.of("low", "medium", "high", "critical");

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    /**
     * The revision is deliberately accepted for the existing service boundary, but is not part of
     * the plan identity. Revision identity is carried by the database row and plan hash.
     */
    public CompiledPlan compile(Map<String, Object> rule, int revision) {
        return compile(rule);
    }

    public CompiledPlan compile(Map<String, Object> rule) {
        if (rule == null) {
            throw new IllegalArgumentException("rule must be an object");
        }
        String ruleKey = requiredText(rule.get("id"), "id");
        if (!RULE_KEY.matcher(ruleKey).matches()) {
            throw new IllegalArgumentException("id is not a bounded rule key");
        }
        String category = requiredText(rule.get("category"), "category");
        if (!CATEGORIES.contains(category)) {
            throw new IllegalArgumentException("unsupported detection category: " + category);
        }

        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("schema_version", SCHEMA_VERSION);
        plan.put("compiler_version", VERSION);
        plan.put("rule_key", ruleKey);
        plan.put("input", Map.of("source", INPUT_SOURCE));
        plan.put("detection", detection(rule, category));
        plan.put("alert", alert(rule));

        String planJson = json(plan);
        return new CompiledPlan(planJson, sha256(planJson));
    }

    private Map<String, Object> alert(Map<String, Object> rule) {
        Map<String, Object> alert = new LinkedHashMap<>();
        alert.put("name", boundedText(rule.get("name"), "name", 160));
        alert.put("type", requiredPatternText(rule.get("type"), "type", ALERT_TYPE));
        alert.put("severity", requiredSeverity(rule.get("severity")));
        alert.put("description", description(rule.get("description")));
        alert.put("risk_score", integer(rule.get("riskScore"), "riskScore", 0, 0, 100));
        alert.put("tags", stringList(rule.get("tags"), "tags", 50));
        alert.put("status", status(rule.get("status")));
        alert.put("version", optionalText(rule.get("version"), "1.0"));
        return alert;
    }

    private Map<String, Object> detection(Map<String, Object> rule, String category) {
        Map<String, Object> detection = new LinkedHashMap<>();
        detection.put("type", category);
        switch (category) {
            case "single_event" -> {
                detection.put("condition", condition(rule.get("condition"), "condition", 0));
                detection.put("suppression", suppression(60L, null));
            }
            case "window" -> {
                String keyField = field(rule.get("keyField"), "keyField");
                long windowMinutes = longValue(rule.get("windowMinutes"), "windowMinutes", 1, 1440);
                Object slidingRaw = rule.get("slidingMinutes");
                Long slidingMinutes = slidingRaw == null ? null
                        : longValue(slidingRaw, "slidingMinutes", 1, windowMinutes);
                Map<String, Object> window = new LinkedHashMap<>();
                window.put("time_basis", "event_time");
                window.put("type", slidingMinutes == null ? "tumbling" : "sliding");
                window.put("size_minutes", windowMinutes);
                if (slidingMinutes != null) {
                    window.put("slide_minutes", slidingMinutes);
                }
                detection.put("key_field", keyField);
                detection.put("condition", condition(rule.get("condition"), "condition", 0));
                detection.put("window", window);
                detection.put("threshold", requiredInteger(rule.get("threshold"),
                        "threshold", 2, 1_000_000));
                long suppressionMinutes = rule.get("alertSuppressionMinutes") == null
                        ? windowMinutes
                        : longValue(rule.get("alertSuppressionMinutes"),
                        "alertSuppressionMinutes", 1, 10_080);
                detection.put("suppression", suppression(suppressionMinutes, keyField));
            }
            case "cep" -> {
                detection.put("key_field", field(rule.get("keyField"), "keyField"));
                detection.put("cep", cep(rule.get("cep")));
            }
            case "baseline" -> {
                detection.put("condition", authenticationFailureCondition());
                detection.put("baseline", baseline(rule.get("baseline")));
            }
            default -> throw new IllegalArgumentException("unsupported detection category: " + category);
        }
        return detection;
    }

    private static Map<String, Object> suppression(long durationMinutes, String primaryEntityField) {
        Map<String, Object> suppression = new LinkedHashMap<>();
        suppression.put("duration_minutes", durationMinutes);
        suppression.put("time_basis", "processing_time");
        suppression.put("primary_entity_field", primaryEntityField);
        suppression.put("fallback_entity_fields", List.of("source.ip", "user.name"));
        suppression.put("fallback_entity", "unknown");
        suppression.put("emission", "first_and_final_count");
        return suppression;
    }

    private Map<String, Object> cep(Object raw) {
        Map<?, ?> source = object(raw, "cep");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("time_basis", "event_time");
        Object withinRaw = source.get("withinMinutes");
        // A missing within value is meaningful to the current CEP implementation (no within
        // clause), so retain that bounded grammar option instead of inventing a timeout.
        result.put("within_minutes", withinRaw == null
                ? null : longValue(withinRaw, "cep.withinMinutes", 1, 10_080));
        Object patternRaw = source.get("pattern");
        if (!(patternRaw instanceof List<?> steps) || steps.isEmpty() || steps.size() > 32) {
            throw new IllegalArgumentException("cep.pattern must contain 1-32 steps");
        }
        List<Map<String, Object>> pattern = new ArrayList<>();
        Set<String> stepNames = new HashSet<>();
        for (int i = 0; i < steps.size(); i++) {
            Map<?, ?> step = object(steps.get(i), "cep.pattern[" + i + "]");
            String name = requiredText(step.get("name"), "cep.pattern[" + i + "].name");
            if (name.length() > 80 || !stepNames.add(name)) {
                throw new IllegalArgumentException("CEP step names must be unique and at most 80 characters");
            }
            String stepType = requiredText(step.get("type"), "cep.pattern[" + i + "].type");
            if (i == 0 && !"begin".equals(stepType)) {
                throw new IllegalArgumentException("the first CEP step must be begin");
            }
            if (i > 0 && !Set.of("next", "followedBy").contains(stepType)) {
                throw new IllegalArgumentException("invalid CEP step type: " + stepType);
            }
            Map<String, Object> normalized = new LinkedHashMap<>();
            normalized.put("name", name);
            normalized.put("type", stepType);
            normalized.put("condition", condition(step.get("condition"),
                    "cep.pattern[" + i + "].condition", 0));
            Object minRaw = step.get("timesMin");
            Object maxRaw = step.get("timesMax");
            if (i > 0 && (minRaw != null || maxRaw != null)) {
                throw new IllegalArgumentException("CEP repetition is supported only on begin");
            }
            if (minRaw != null || maxRaw != null) {
                Integer max = maxRaw == null ? null
                        : integer(maxRaw, "cep.pattern[" + i + "].timesMax", 1, 1, 1_000_000);
                Integer min = minRaw == null
                        ? max
                        : integer(minRaw, "cep.pattern[" + i + "].timesMin", 1, 1, 1_000_000);
                if (max != null && min != null && min > max) {
                    throw new IllegalArgumentException("CEP timesMin must not exceed timesMax");
                }
                normalized.put("times_min", min);
                normalized.put("times_max", max);
            }
            pattern.add(normalized);
        }
        if (!stepNames.contains("failures") || !stepNames.contains("success")) {
            throw new IllegalArgumentException("CEP pattern must define failures and success output steps");
        }
        result.put("pattern", pattern);
        result.put("output", Map.of(
                "type", "bruteforce_success",
                "failure_step", "failures",
                "success_step", "success"));
        return result;
    }

    private Map<String, Object> baseline(Object raw) {
        Map<?, ?> source = object(raw, "baseline");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("key_field", field(source.get("keyField"), "baseline.keyField"));
        result.put("time_basis", "event_time");
        result.put("window_hours", source.get("windowHours") == null ? 1L
                : longValue(source.get("windowHours"), "baseline.windowHours", 1, 168));
        int baselineHours = requiredInteger(source.get("baselineHours"),
                "baseline.baselineHours", 1, 8_760);
        int minBaselineHours = requiredInteger(source.get("minBaselineHours"),
                "baseline.minBaselineHours", 1, baselineHours);
        result.put("baseline_hours", baselineHours);
        result.put("min_baseline_hours", minBaselineHours);
        result.put("algorithm", Map.of(
                "type", "mean_sigma",
                "sigma_multiplier", 3.0d,
                "comparison", "greater_than",
                "require_positive_threshold", true));
        return result;
    }

    private static Map<String, Object> authenticationFailureCondition() {
        return Map.of("operator", "eq", "field", "event.action", "value", AUTHENTICATION_FAILURE);
    }

    private Map<String, Object> condition(Object raw, String path, int depth) {
        Map<?, ?> input = object(raw, path);
        if (depth > 8) {
            throw new IllegalArgumentException(path + " is nested too deeply");
        }
        String type = requiredText(input.get("type"), path + ".type");
        Map<String, Object> result = new LinkedHashMap<>();
        switch (type) {
            case "field_equals" -> {
                result.put("operator", "eq");
                result.put("field", field(input.get("field"), path + ".field"));
                if (!input.containsKey("value") || input.get("value") == null) {
                    throw new IllegalArgumentException(path + ".value must not be null");
                }
                result.put("value", scalar(input.get("value"), path + ".value"));
            }
            case "field_in" -> {
                result.put("operator", "in");
                result.put("field", field(input.get("field"), path + ".field"));
                Object valuesRaw = input.get("values");
                if (!(valuesRaw instanceof List<?> values) || values.isEmpty() || values.size() > 100) {
                    throw new IllegalArgumentException(path + ".values must contain 1-100 values");
                }
                List<Object> valuesOut = new ArrayList<>();
                for (int i = 0; i < values.size(); i++) {
                    valuesOut.add(scalar(values.get(i), path + ".values[" + i + "]"));
                }
                result.put("values", valuesOut);
            }
            case "all", "any" -> {
                result.put("operator", type);
                result.put("conditions", childConditions(input.get("conditions"), path, depth, false));
            }
            case "not" -> {
                result.put("operator", type);
                result.put("conditions", childConditions(input.get("conditions"), path, depth, true));
            }
            default -> throw new IllegalArgumentException("unsupported rule condition: " + type);
        }
        return result;
    }

    private List<Object> childConditions(Object raw, String path, int depth, boolean exactlyOne) {
        if (!(raw instanceof List<?> children) || children.isEmpty()
                || (exactlyOne && children.size() != 1) || children.size() > 20) {
            throw new IllegalArgumentException(path + ".conditions has an invalid size");
        }
        List<Object> result = new ArrayList<>();
        for (int i = 0; i < children.size(); i++) {
            result.add(condition(children.get(i), path + ".conditions[" + i + "]", depth + 1));
        }
        return result;
    }

    private static Map<?, ?> object(Object value, String path) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(path + " must be an object");
        }
        return map;
    }

    private static Object scalar(Object value, String path) {
        if (value == null || value instanceof Map<?, ?> || value instanceof List<?>) {
            throw new IllegalArgumentException(path + " must be a scalar");
        }
        if (value instanceof String text && text.length() > 500) {
            throw new IllegalArgumentException(path + " is too long");
        }
        if (!(value instanceof String || value instanceof Number || value instanceof Boolean)) {
            throw new IllegalArgumentException(path + " has an unsupported value type");
        }
        return value;
    }

    private static String field(Object value, String name) {
        String field = requiredText(value, name);
        if (!FIELD_NAME.matcher(field).matches()) {
            throw new IllegalArgumentException(name + " is not a valid ECS field");
        }
        return field;
    }

    private static String requiredPatternText(Object value, String name, Pattern pattern) {
        String text = requiredText(value, name);
        if (!pattern.matcher(text).matches()) {
            throw new IllegalArgumentException(name + " has an invalid format");
        }
        return text;
    }

    private static String requiredSeverity(Object value) {
        String severity = requiredText(value, "severity").toLowerCase();
        if (!SEVERITIES.contains(severity)) {
            throw new IllegalArgumentException("severity must be low/medium/high/critical");
        }
        return severity;
    }

    private static String status(Object value) {
        String status = optionalText(value, "experimental");
        if (!Set.of("experimental", "stable", "deprecated").contains(status)) {
            throw new IllegalArgumentException("status must be experimental/stable/deprecated");
        }
        return status;
    }

    private static String description(Object value) {
        String description = value == null ? "" : String.valueOf(value).trim();
        if (description.length() > 2_000) {
            throw new IllegalArgumentException("description is too long");
        }
        return description;
    }

    private static List<String> stringList(Object raw, String name, int max) {
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> values) || values.size() > max) {
            throw new IllegalArgumentException(name + " must contain at most " + max + " items");
        }
        Set<String> unique = new HashSet<>();
        List<String> result = new ArrayList<>();
        for (Object value : values) {
            String item = requiredText(value, name);
            if (item.length() > 500 || !unique.add(item)) {
                throw new IllegalArgumentException(name + " contains duplicates or oversized values");
            }
            result.add(item);
        }
        return result;
    }

    private static String optionalText(Object value, String defaultValue) {
        return value == null ? defaultValue : requiredText(value, "text");
    }

    private static String boundedText(Object value, String name, int maxLength) {
        String text = requiredText(value, name);
        if (text.length() > maxLength) {
            throw new IllegalArgumentException(name + " is too long");
        }
        return text;
    }

    private static String requiredText(Object value, String name) {
        if (!(value instanceof String text) || text.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must be a non-blank string");
        }
        return text.trim();
    }

    private static int requiredInteger(Object value, String name, int min, int max) {
        if (value == null) {
            throw new IllegalArgumentException(name + " is required");
        }
        return integer(value, name, min, min, max);
    }

    private static int integer(Object value, String name, int defaultValue, int min, int max) {
        if (value == null) return defaultValue;
        long number = number(value, name);
        if (number < min || number > max) {
            throw new IllegalArgumentException(name + " must be in " + min + "-" + max);
        }
        return (int) number;
    }

    private static long longValue(Object value, String name, long min, long max) {
        long number = number(value, name);
        if (number < min || number > max) {
            throw new IllegalArgumentException(name + " must be in " + min + "-" + max);
        }
        return number;
    }

    private static long number(Object value, String name) {
        if (!(value instanceof Number number)) {
            try {
                return Long.parseLong(String.valueOf(value));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(name + " must be an integer", e);
            }
        }
        if (number instanceof Float || number instanceof Double
                || number.doubleValue() != Math.rint(number.doubleValue())) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
        try {
            return Long.parseLong(number.toString());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be an integer", e);
        }
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
