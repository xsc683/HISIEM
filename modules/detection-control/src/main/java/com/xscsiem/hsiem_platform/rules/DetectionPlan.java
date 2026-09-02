package com.xscsiem.hsiem_platform.rules;

import java.util.List;

/** Typed, immutable intermediate representation of the v2 detection plan. */
public record DetectionPlan(
        String schemaVersion,
        String compilerVersion,
        String ruleKey,
        Input input,
        DetectionSpec detection,
        Alert alert) {
    public record Input(String source) {}

    public record Alert(
            String name,
            String type,
            String severity,
            String description,
            int riskScore,
            List<String> tags,
            String status,
            String version) {
        public Alert {
            tags = List.copyOf(tags == null ? List.of() : tags);
        }
    }

    public sealed interface DetectionSpec
            permits SingleEventDetection, WindowDetection, CepDetection, BaselineDetection {}

    public record SingleEventDetection(Condition condition, Suppression suppression)
            implements DetectionSpec {}

    public record WindowDetection(
            String keyField,
            Condition condition,
            Window window,
            int threshold,
            Suppression suppression)
            implements DetectionSpec {}

    public record CepDetection(String keyField, Cep cep) implements DetectionSpec {}

    public record BaselineDetection(Condition condition, Baseline baseline)
            implements DetectionSpec {}

    public record Suppression(
            long durationMinutes,
            String timeBasis,
            String primaryEntityField,
            List<String> fallbackEntityFields,
            String fallbackEntity,
            String emission) {
        public Suppression {
            fallbackEntityFields =
                    List.copyOf(fallbackEntityFields == null ? List.of() : fallbackEntityFields);
        }
    }

    public record Window(String timeBasis, String type, long sizeMinutes, Long slideMinutes) {}

    public record Cep(
            String timeBasis, Long withinMinutes, List<CepStep> pattern, CepOutput output) {
        public Cep {
            pattern = List.copyOf(pattern == null ? List.of() : pattern);
        }
    }

    public record CepStep(
            String name, String type, Condition condition, Integer timesMin, Integer timesMax) {}

    public record CepOutput(String type, String failureStep, String successStep) {}

    public record Baseline(
            String keyField,
            String timeBasis,
            long windowHours,
            int baselineHours,
            int minBaselineHours,
            BaselineAlgorithm algorithm) {}

    public record BaselineAlgorithm(
            String type,
            double sigmaMultiplier,
            String comparison,
            boolean requirePositiveThreshold) {}

    public sealed interface Condition permits Eq, In, All, Any, Not {}

    public record Eq(String field, Object value) implements Condition {}

    public record In(String field, List<Object> values) implements Condition {
        public In {
            values = List.copyOf(values == null ? List.of() : values);
        }
    }

    public record All(List<Condition> conditions) implements Condition {
        public All {
            conditions = List.copyOf(conditions == null ? List.of() : conditions);
        }
    }

    public record Any(List<Condition> conditions) implements Condition {
        public Any {
            conditions = List.copyOf(conditions == null ? List.of() : conditions);
        }
    }

    public record Not(Condition condition) implements Condition {}
}
