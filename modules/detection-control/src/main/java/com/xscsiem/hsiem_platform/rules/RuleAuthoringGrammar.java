package com.xscsiem.hsiem_platform.rules;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Shared bounded authoring grammar used by catalog and visual-editor entry points. */
public final class RuleAuthoringGrammar {
    public enum ValidationProfile {
        CATALOG,
        VISUAL_EDITOR
    }

    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9-]{2,95}");
    private static final Pattern FIELD = Pattern.compile("[A-Za-z@][A-Za-z0-9_.@-]{0,127}");
    private static final Pattern TYPE = Pattern.compile("[a-z0-9][a-z0-9_]{1,95}");
    private static final Set<String> CATEGORIES =
            Set.of("single_event", "window", "cep", "baseline");
    private static final Set<String> EDITOR_CATEGORIES = Set.of("single_event", "window");
    private static final Set<String> SEVERITIES = Set.of("low", "medium", "high", "critical");
    private static final Set<String> STATUSES = Set.of("experimental", "stable", "deprecated");

    public NormalizedRule normalize(Map<String, Object> source, ValidationProfile profile) {
        if (source == null) throw new IllegalArgumentException("rule must be an object");
        Map<String, Object> rule = new LinkedHashMap<>(source);
        String id = text(rule.get("id"), "id");
        if (!ID.matcher(id).matches())
            throw new IllegalArgumentException("id is not a bounded rule key");
        String category = text(rule.get("category"), "category");
        if (!CATEGORIES.contains(category))
            throw new IllegalArgumentException("unsupported detection category: " + category);
        if (profile == ValidationProfile.VISUAL_EDITOR && !EDITOR_CATEGORIES.contains(category)) {
            throw new IllegalArgumentException(
                    "visual editor supports only single_event and window rules");
        }
        String name = text(rule.get("name"), "name");
        if (name.length() > 160) throw new IllegalArgumentException("name is too long");
        String type = text(rule.get("type"), "type");
        if (!TYPE.matcher(type).matches())
            throw new IllegalArgumentException("type has an invalid format");
        String severity = text(rule.get("severity"), "severity").toLowerCase();
        if (!SEVERITIES.contains(severity))
            throw new IllegalArgumentException("severity must be low/medium/high/critical");
        String status =
                rule.get("status") == null ? "experimental" : text(rule.get("status"), "status");
        if (!STATUSES.contains(status))
            throw new IllegalArgumentException("status must be experimental/stable/deprecated");
        if (rule.get("riskScore") != null)
            rule.put("riskScore", integer(rule.get("riskScore"), "riskScore", 0, 100));
        String description =
                rule.get("description") == null
                        ? ""
                        : String.valueOf(rule.get("description")).trim();
        if (description.length() > 2_000)
            throw new IllegalArgumentException("description is too long");
        rule.put("description", description);
        rule.putIfAbsent("riskScore", profile == ValidationProfile.VISUAL_EDITOR ? 50 : 0);
        rule.putIfAbsent("tags", List.of());
        rule.put("status", status);
        rule.put(
                "version",
                rule.get("version") == null ? "1.0" : text(rule.get("version"), "version"));
        rule.putIfAbsent("enabled", true);
        if (!(rule.get("enabled") instanceof Boolean))
            throw new IllegalArgumentException("enabled must be boolean");
        rule.putIfAbsent("references", List.of());
        strings(rule.get("tags"), "tags", 50);
        strings(rule.get("references"), "references", 50);

        if ("single_event".equals(category) || "window".equals(category)) {
            validateCondition(rule.get("condition"), "condition", 0);
        }
        switch (category) {
            case "window" -> validateWindow(rule);
            case "cep" -> validateCep(rule);
            case "baseline" -> validateBaseline(rule);
            default -> {}
        }
        rule.put("id", id);
        rule.put("category", category);
        rule.put("name", name);
        rule.put("type", type);
        rule.put("severity", severity);
        if (profile == ValidationProfile.VISUAL_EDITOR) {
            rule.remove("cep");
            rule.remove("baseline");
            if ("window".equals(category)) {
                if (rule.get("slidingMinutes") == null) rule.remove("slidingMinutes");
                if (rule.get("alertSuppressionMinutes") == null)
                    rule.remove("alertSuppressionMinutes");
            }
            if (!"window".equals(category)) {
                rule.remove("keyField");
                rule.remove("windowMinutes");
                rule.remove("slidingMinutes");
                rule.remove("alertSuppressionMinutes");
                rule.remove("threshold");
            }
        }
        return new NormalizedRule(rule, profile);
    }

    /** Immutable normalized view consumed by persistence and plan compilation. */
    public record NormalizedRule(Map<String, Object> values, ValidationProfile profile) {
        public NormalizedRule {
            values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
        }

        public String id() {
            return text(values.get("id"), "id");
        }

        public String category() {
            return text(values.get("category"), "category");
        }

        public String name() {
            return text(values.get("name"), "name");
        }

        public String type() {
            return text(values.get("type"), "type");
        }

        public String severity() {
            return text(values.get("severity"), "severity");
        }

        public String description() {
            return values.get("description") == null
                    ? ""
                    : String.valueOf(values.get("description"));
        }

        public int riskScore() {
            return values.get("riskScore") == null
                    ? 0
                    : ((Number) values.get("riskScore")).intValue();
        }

        public List<String> tags() {
            return stringsView(values.get("tags"));
        }

        public String status() {
            return values.get("status") == null
                    ? "experimental"
                    : text(values.get("status"), "status");
        }

        public String version() {
            return values.get("version") == null ? "1.0" : text(values.get("version"), "version");
        }

        public Map<?, ?> condition() {
            return objectView(values.get("condition"));
        }

        public String keyField() {
            return values.get("keyField") == null ? null : String.valueOf(values.get("keyField"));
        }

        public long windowMinutes() {
            return ((Number) values.get("windowMinutes")).longValue();
        }

        public Long slidingMinutes() {
            return values.get("slidingMinutes") == null
                    ? null
                    : ((Number) values.get("slidingMinutes")).longValue();
        }

        public int threshold() {
            return ((Number) values.get("threshold")).intValue();
        }

        public long alertSuppressionMinutes() {
            return values.get("alertSuppressionMinutes") == null
                    ? windowMinutes()
                    : ((Number) values.get("alertSuppressionMinutes")).longValue();
        }

        public Map<?, ?> cep() {
            return objectView(values.get("cep"));
        }

        public Map<?, ?> baseline() {
            return objectView(values.get("baseline"));
        }

        private static Map<?, ?> objectView(Object value) {
            return value instanceof Map<?, ?> map ? map : null;
        }

        private static String text(Object value, String path) {
            return value instanceof String text ? text : "";
        }

        private static List<String> stringsView(Object value) {
            if (!(value instanceof List<?> list)) return List.of();
            return list.stream().map(String::valueOf).toList();
        }
    }

    private void validateWindow(Map<String, Object> rule) {
        field(rule.get("keyField"), "keyField");
        long window = integer(rule.get("windowMinutes"), "windowMinutes", 1, 1440);
        rule.put("windowMinutes", window);
        if (rule.get("slidingMinutes") != null)
            rule.put(
                    "slidingMinutes",
                    (long) integer(rule.get("slidingMinutes"), "slidingMinutes", 1, (int) window));
        rule.put("threshold", integer(rule.get("threshold"), "threshold", 2, 1_000_000));
        if (rule.get("alertSuppressionMinutes") != null)
            rule.put(
                    "alertSuppressionMinutes",
                    (long)
                            integer(
                                    rule.get("alertSuppressionMinutes"),
                                    "alertSuppressionMinutes",
                                    1,
                                    10_080));
    }

    private void validateCep(Map<String, Object> rule) {
        field(rule.get("keyField"), "keyField");
        Map<?, ?> cep = object(rule.get("cep"), "cep");
        if (cep.get("withinMinutes") != null)
            integer(cep.get("withinMinutes"), "cep.withinMinutes", 1, 10_080);
        Object raw = cep.get("pattern");
        if (!(raw instanceof List<?> steps) || steps.isEmpty() || steps.size() > 32)
            throw new IllegalArgumentException("cep.pattern must contain 1-32 steps");
        java.util.HashSet<String> names = new java.util.HashSet<>();
        List<Map<String, Object>> normalizedSteps = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            Map<?, ?> step = object(steps.get(i), "cep.pattern[" + i + "]");
            String name = text(step.get("name"), "cep.pattern[" + i + "].name");
            if (name.length() > 80 || !names.add(name))
                throw new IllegalArgumentException(
                        "CEP step names must be unique and at most 80 characters");
            String type = text(step.get("type"), "cep.pattern[" + i + "].type");
            if (i == 0 && !"begin".equals(type))
                throw new IllegalArgumentException("the first CEP step must be begin");
            if (i > 0 && !Set.of("next", "followedBy").contains(type))
                throw new IllegalArgumentException("invalid CEP step type: " + type);
            validateCondition(step.get("condition"), "cep.pattern[" + i + "].condition", 0);
            if (i > 0 && (step.get("timesMin") != null || step.get("timesMax") != null))
                throw new IllegalArgumentException("CEP repetition is supported only on begin");
            Integer min =
                    step.get("timesMin") == null
                            ? null
                            : integer(
                                    step.get("timesMin"),
                                    "cep.pattern[" + i + "].timesMin",
                                    1,
                                    1_000_000);
            Integer max =
                    step.get("timesMax") == null
                            ? null
                            : integer(
                                    step.get("timesMax"),
                                    "cep.pattern[" + i + "].timesMax",
                                    1,
                                    1_000_000);
            if (min != null && max != null && min > max)
                throw new IllegalArgumentException("CEP timesMin must not exceed timesMax");
            Map<String, Object> normalizedStep = new LinkedHashMap<>();
            step.forEach((key, value) -> normalizedStep.put(String.valueOf(key), value));
            if (min != null) normalizedStep.put("timesMin", min);
            if (max != null) normalizedStep.put("timesMax", max);
            normalizedSteps.add(normalizedStep);
        }
        if (!names.contains("failures") || !names.contains("success"))
            throw new IllegalArgumentException(
                    "CEP pattern must define failures and success output steps");
        if (cep.get("output") != null) {
            Map<?, ?> output = object(cep.get("output"), "cep.output");
            if (!"bruteforce_success".equals(text(output.get("type"), "cep.output.type"))
                    || !names.contains(text(output.get("failureStep"), "cep.output.failureStep"))
                    || !names.contains(text(output.get("successStep"), "cep.output.successStep"))) {
                throw new IllegalArgumentException("unsupported CEP output contract");
            }
        }
        Map<String, Object> normalizedCep = new LinkedHashMap<>();
        cep.forEach((key, value) -> normalizedCep.put(String.valueOf(key), value));
        if (cep.get("withinMinutes") != null)
            normalizedCep.put(
                    "withinMinutes",
                    (long) integer(cep.get("withinMinutes"), "cep.withinMinutes", 1, 10_080));
        normalizedCep.put("pattern", normalizedSteps);
        rule.put("cep", normalizedCep);
    }

    private void validateBaseline(Map<String, Object> rule) {
        Map<?, ?> baseline = object(rule.get("baseline"), "baseline");
        field(baseline.get("keyField"), "baseline.keyField");
        Map<String, Object> normalized = new LinkedHashMap<>();
        baseline.forEach((key, value) -> normalized.put(String.valueOf(key), value));
        if (baseline.get("windowHours") != null)
            normalized.put(
                    "windowHours",
                    (long) integer(baseline.get("windowHours"), "baseline.windowHours", 1, 168));
        int hours = integer(baseline.get("baselineHours"), "baseline.baselineHours", 1, 8_760);
        normalized.put("baselineHours", hours);
        normalized.put(
                "minBaselineHours",
                integer(baseline.get("minBaselineHours"), "baseline.minBaselineHours", 1, hours));
        if (baseline.get("algorithm") != null) {
            Map<?, ?> algorithm = object(baseline.get("algorithm"), "baseline.algorithm");
            if (!"mean_sigma".equals(text(algorithm.get("type"), "baseline.algorithm.type"))
                    || !"greater_than"
                            .equals(
                                    text(
                                            algorithm.get("comparison"),
                                            "baseline.algorithm.comparison"))
                    || !Boolean.TRUE.equals(algorithm.get("requirePositiveThreshold"))) {
                throw new IllegalArgumentException("unsupported baseline algorithm contract");
            }
            Object sigma = algorithm.get("sigmaMultiplier");
            if (!(sigma instanceof Number number)
                    || !Double.isFinite(number.doubleValue())
                    || number.doubleValue() <= 0) {
                throw new IllegalArgumentException(
                        "baseline sigmaMultiplier must be finite and positive");
            }
        }
        rule.put("baseline", normalized);
    }

    private void validateCondition(Object raw, String path, int depth) {
        Map<?, ?> condition = object(raw, path);
        if (depth > 8) throw new IllegalArgumentException(path + " is nested too deeply");
        String type = text(condition.get("type"), path + ".type");
        switch (type) {
            case "field_equals" -> {
                field(condition.get("field"), path + ".field");
                scalar(condition.get("value"), path + ".value");
            }
            case "field_in" -> {
                field(condition.get("field"), path + ".field");
                List<?> values = list(condition.get("values"), path + ".values");
                if (values.isEmpty() || values.size() > 100)
                    throw new IllegalArgumentException(path + ".values must contain 1-100 values");
                values.forEach(value -> scalar(value, path + ".value"));
            }
            case "all", "any" -> children(condition.get("conditions"), path, depth, false);
            case "not" -> children(condition.get("conditions"), path, depth, true);
            default -> throw new IllegalArgumentException("unsupported rule condition: " + type);
        }
    }

    private void children(Object raw, String path, int depth, boolean one) {
        List<?> values = list(raw, path + ".conditions");
        if (values.isEmpty() || values.size() > 20 || one && values.size() != 1)
            throw new IllegalArgumentException(path + ".conditions has an invalid size");
        for (int i = 0; i < values.size(); i++)
            validateCondition(values.get(i), path + ".conditions[" + i + "]", depth + 1);
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

    private static String text(Object value, String path) {
        if (!(value instanceof String text) || text.isBlank())
            throw new IllegalArgumentException(path + " must be a non-blank string");
        return text.trim();
    }

    private static String field(Object value, String path) {
        String field = text(value, path);
        if (!FIELD.matcher(field).matches())
            throw new IllegalArgumentException(path + " is not a valid ECS field");
        return field;
    }

    private static void scalar(Object value, String path) {
        if (value == null
                || value instanceof Map<?, ?>
                || value instanceof List<?>
                || !(value instanceof String
                        || value instanceof Number
                        || value instanceof Boolean))
            throw new IllegalArgumentException(path + " must be a scalar");
        if (value instanceof String text && text.length() > 500)
            throw new IllegalArgumentException(path + " is too long");
    }

    private static int integer(Object value, String path, int min, int max) {
        if (value == null) throw new IllegalArgumentException(path + " is required");
        if (!(value instanceof Number n) || n.doubleValue() != Math.rint(n.doubleValue())) {
            try {
                return checked(Long.parseLong(String.valueOf(value)), path, min, max);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(path + " must be an integer", e);
            }
        }
        return checked(n.longValue(), path, min, max);
    }

    private static int checked(long value, String path, int min, int max) {
        if (value < min || value > max)
            throw new IllegalArgumentException(path + " must be in " + min + "-" + max);
        return (int) value;
    }

    private static void strings(Object raw, String path, int max) {
        if (raw == null) return;
        List<?> values = list(raw, path);
        if (values.size() > max)
            throw new IllegalArgumentException(path + " must contain at most " + max + " items");
        java.util.HashSet<String> unique = new java.util.HashSet<>();
        values.forEach(
                value -> {
                    String text = text(value, path);
                    if (text.length() > 500 || !unique.add(text))
                        throw new IllegalArgumentException(
                                path + " contains duplicates or oversized values");
                });
    }
}
