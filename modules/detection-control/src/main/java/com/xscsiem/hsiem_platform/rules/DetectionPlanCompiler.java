package com.xscsiem.hsiem_platform.rules;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Compiles the bounded authoring grammar directly into the typed v2 DetectionPlan IR. */
public final class DetectionPlanCompiler {
    public static final String VERSION = "hisiem-detection-plan-2";
    public static final String SCHEMA_VERSION = "2";
    private static final String INPUT_SOURCE = "siem-events";
    private static final String AUTHENTICATION_FAILURE = "authentication_failure";

    private final RuleAuthoringGrammar grammar = new RuleAuthoringGrammar();
    private final DetectionPlanCodec codec = new DetectionPlanCodec();

    /** Revision is a persistence provenance input and intentionally not plan identity. */
    public CompiledPlan compile(Map<String, Object> rule, int revision) {
        return compile(rule);
    }

    public CompiledPlan compile(Map<String, Object> rule) {
        RuleAuthoringGrammar.NormalizedRule normalized =
                grammar.normalize(rule, RuleAuthoringGrammar.ValidationProfile.CATALOG);
        DetectionPlan plan =
                new DetectionPlan(
                        SCHEMA_VERSION,
                        VERSION,
                        normalized.id(),
                        new DetectionPlan.Input(INPUT_SOURCE),
                        detection(normalized),
                        alert(normalized));
        String json = codec.encode(plan);
        return new CompiledPlan(json, sha256(json), plan);
    }

    private DetectionPlan.Alert alert(RuleAuthoringGrammar.NormalizedRule rule) {
        return new DetectionPlan.Alert(
                rule.name(),
                rule.type(),
                rule.severity(),
                rule.description(),
                rule.riskScore(),
                rule.tags(),
                rule.status(),
                rule.version());
    }

    private DetectionPlan.DetectionSpec detection(RuleAuthoringGrammar.NormalizedRule rule) {
        return switch (rule.category()) {
            case "single_event" ->
                    new DetectionPlan.SingleEventDetection(
                            condition(rule.condition()), suppression(60L, null));
            case "window" ->
                    new DetectionPlan.WindowDetection(
                            rule.keyField(),
                            condition(rule.condition()),
                            new DetectionPlan.Window(
                                    "event_time",
                                    rule.slidingMinutes() == null ? "tumbling" : "sliding",
                                    rule.windowMinutes(),
                                    rule.slidingMinutes()),
                            rule.threshold(),
                            suppression(rule.alertSuppressionMinutes(), rule.keyField()));
            case "cep" -> new DetectionPlan.CepDetection(rule.keyField(), cep(rule.cep()));
            case "baseline" ->
                    new DetectionPlan.BaselineDetection(
                            authenticationFailureCondition(), baseline(rule.baseline()));
            default ->
                    throw new IllegalArgumentException(
                            "unsupported detection category: " + rule.category());
        };
    }

    private static DetectionPlan.Suppression suppression(long duration, String primaryField) {
        return new DetectionPlan.Suppression(
                duration,
                "processing_time",
                primaryField,
                List.of("source.ip", "user.name"),
                "unknown",
                "first_and_final_count");
    }

    private DetectionPlan.Cep cep(Map<?, ?> source) {
        Object within = source.get("withinMinutes");
        List<?> rawSteps = list(source.get("pattern"));
        List<DetectionPlan.CepStep> steps = new ArrayList<>();
        for (Object raw : rawSteps) {
            Map<?, ?> step = object(raw);
            Integer min = step.get("timesMin") == null ? null : integer(step.get("timesMin"));
            Integer max = step.get("timesMax") == null ? null : integer(step.get("timesMax"));
            steps.add(
                    new DetectionPlan.CepStep(
                            text(step, "name"),
                            text(step, "type"),
                            condition(object(step.get("condition"))),
                            min,
                            max));
        }
        return new DetectionPlan.Cep(
                "event_time",
                within == null ? null : longValue(within),
                steps,
                new DetectionPlan.CepOutput("bruteforce_success", "failures", "success"));
    }

    private DetectionPlan.Baseline baseline(Map<?, ?> source) {
        long windowHours =
                source.get("windowHours") == null ? 1L : longValue(source.get("windowHours"));
        int baselineHours = integer(source.get("baselineHours"));
        int minBaselineHours = integer(source.get("minBaselineHours"));
        return new DetectionPlan.Baseline(
                text(source, "keyField"),
                "event_time",
                windowHours,
                baselineHours,
                minBaselineHours,
                new DetectionPlan.BaselineAlgorithm("mean_sigma", 3.0d, "greater_than", true));
    }

    private DetectionPlan.Condition condition(Map<?, ?> source) {
        return switch (text(source, "type")) {
            case "field_equals" ->
                    new DetectionPlan.Eq(text(source, "field"), scalar(source.get("value")));
            case "field_in" ->
                    new DetectionPlan.In(text(source, "field"), scalars(source.get("values")));
            case "all" -> new DetectionPlan.All(conditions(source.get("conditions")));
            case "any" -> new DetectionPlan.Any(conditions(source.get("conditions")));
            case "not" -> new DetectionPlan.Not(conditions(source.get("conditions")).getFirst());
            default ->
                    throw new IllegalArgumentException(
                            "unsupported rule condition: " + source.get("type"));
        };
    }

    private List<DetectionPlan.Condition> conditions(Object raw) {
        return list(raw).stream().map(value -> condition(object(value))).toList();
    }

    private static DetectionPlan.Condition authenticationFailureCondition() {
        return new DetectionPlan.Eq("event.action", AUTHENTICATION_FAILURE);
    }

    private static Map<?, ?> object(Object value) {
        if (!(value instanceof Map<?, ?> map))
            throw new IllegalArgumentException("expected an object");
        return map;
    }

    private static List<?> list(Object value) {
        if (!(value instanceof List<?> list))
            throw new IllegalArgumentException("expected an array");
        return list;
    }

    private static String text(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof String string) || string.isBlank())
            throw new IllegalArgumentException(key + " must be a string");
        return string.trim();
    }

    private static Object scalar(Object value) {
        if (value == null || value instanceof Map<?, ?> || value instanceof List<?>)
            throw new IllegalArgumentException("condition value must be scalar");
        return value;
    }

    private static List<Object> scalars(Object value) {
        return list(value).stream().<Object>map(DetectionPlanCompiler::scalar).toList();
    }

    private static long longValue(Object value) {
        return ((Number) value).longValue();
    }

    private static int integer(Object value) {
        return ((Number) value).intValue();
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    public record CompiledPlan(String json, String hash, DetectionPlan plan) {
        public CompiledPlan(String json, String hash) {
            this(json, hash, null);
        }
    }
}
