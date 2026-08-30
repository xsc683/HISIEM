package com.xscsiem.hsiem_platform.rules.runtime;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable typed difference between a desired and an observed manifest.
 * Missing and unexpected members are deliberately separate from outdated members so a
 * controller can make add/remove/update decisions without parsing strings.
 */
public final class RuntimeDiff {

    private final List<RuntimeManifest.Member> missing;
    private final List<Outdated> outdated;
    private final List<RuntimeManifest.Member> unexpected;
    private final long expectedGeneration;
    private final Long observedGeneration;
    private final boolean generationMismatch;

    public RuntimeDiff(List<RuntimeManifest.Member> missing,
                       List<Outdated> outdated,
                       List<RuntimeManifest.Member> unexpected) {
        this(missing, outdated, unexpected, 0L, null, false);
    }

    public RuntimeDiff(List<RuntimeManifest.Member> missing,
                       List<Outdated> outdated,
                       List<RuntimeManifest.Member> unexpected,
                       long expectedGeneration,
                       Long observedGeneration) {
        this(missing, outdated, unexpected, expectedGeneration, observedGeneration,
                observedGeneration == null || expectedGeneration != observedGeneration);
    }

    private RuntimeDiff(List<RuntimeManifest.Member> missing,
                        List<Outdated> outdated,
                        List<RuntimeManifest.Member> unexpected,
                        long expectedGeneration,
                        Long observedGeneration,
                        boolean generationMismatch) {
        this.missing = canonicalMembers(missing, "missing");
        this.outdated = canonicalOutdated(outdated);
        this.unexpected = canonicalMembers(unexpected, "unexpected");
        this.expectedGeneration = expectedGeneration;
        this.observedGeneration = observedGeneration;
        this.generationMismatch = generationMismatch;
    }

    public static RuntimeDiff compare(RuntimeManifest expected, RuntimeManifest observed) {
        Objects.requireNonNull(expected, "expected manifest must not be null");
        Objects.requireNonNull(observed, "observed manifest must not be null");
        if (!expected.tenantId().equals(observed.tenantId())
                || !expected.targetCluster().equals(observed.targetCluster())
                || !expected.jobGroupKey().equals(observed.jobGroupKey())) {
            throw new IllegalArgumentException(
                    "expected and observed manifests must have the same tenant, cluster, and group scope");
        }
        Map<String, RuntimeManifest.Member> expectedByKey = byKey(expected.members());
        Map<String, RuntimeManifest.Member> observedByKey = byKey(observed.members());
        List<RuntimeManifest.Member> missing = new ArrayList<>();
        List<RuntimeManifest.Member> unexpected = new ArrayList<>();
        List<Outdated> outdated = new ArrayList<>();

        for (Map.Entry<String, RuntimeManifest.Member> entry : expectedByKey.entrySet()) {
            RuntimeManifest.Member actual = observedByKey.get(entry.getKey());
            if (actual == null) {
                missing.add(entry.getValue());
                continue;
            }
            RuntimeManifest.Member wanted = entry.getValue();
            boolean revisionMismatch = wanted.revision() != actual.revision();
            boolean planHashMismatch = !wanted.planHash().equals(actual.planHash());
            boolean generationMismatch = expected.generation() != observed.generation();
            if (revisionMismatch || planHashMismatch || generationMismatch) {
                outdated.add(new Outdated(entry.getKey(), wanted, actual,
                        revisionMismatch, generationMismatch, planHashMismatch));
            }
        }
        for (Map.Entry<String, RuntimeManifest.Member> entry : observedByKey.entrySet()) {
            if (!expectedByKey.containsKey(entry.getKey())) {
                unexpected.add(entry.getValue());
            }
        }
        return new RuntimeDiff(missing, outdated, unexpected, expected.generation(),
                observed.generation());
    }

    public static RuntimeDiff between(RuntimeManifest expected, RuntimeManifest observed) {
        return compare(expected, observed);
    }

    public static RuntimeDiff diff(RuntimeManifest expected, RuntimeManifest observed) {
        return compare(expected, observed);
    }

    public List<RuntimeManifest.Member> missing() {
        return missing;
    }

    public List<Outdated> outdated() {
        return outdated;
    }

    public List<RuntimeManifest.Member> unexpected() {
        return unexpected;
    }

    public long expectedGeneration() {
        return expectedGeneration;
    }

    public Long observedGeneration() {
        return observedGeneration;
    }

    public boolean generationMismatch() {
        return generationMismatch;
    }

    public boolean isEmpty() {
        return missing.isEmpty() && outdated.isEmpty() && unexpected.isEmpty()
                && !generationMismatch;
    }

    public boolean inSync() {
        return isEmpty();
    }

    public List<String> missingRuleKeys() {
        return missing.stream().map(RuntimeManifest.Member::ruleKey).toList();
    }

    public List<String> outdatedRuleKeys() {
        return outdated.stream().map(Outdated::ruleKey).toList();
    }

    public List<String> unexpectedRuleKeys() {
        return unexpected.stream().map(RuntimeManifest.Member::ruleKey).toList();
    }

    private static Map<String, RuntimeManifest.Member> byKey(List<RuntimeManifest.Member> members) {
        Map<String, RuntimeManifest.Member> result = new LinkedHashMap<>();
        for (RuntimeManifest.Member member : members) {
            result.put(member.ruleKey(), member);
        }
        return result;
    }

    private static List<RuntimeManifest.Member> canonicalMembers(List<RuntimeManifest.Member> values,
                                                                  String field) {
        Objects.requireNonNull(values, field + " must not be null");
        List<RuntimeManifest.Member> result = new ArrayList<>(values);
        if (result.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(field + " must not contain null");
        }
        result.sort(Comparator.comparing(RuntimeManifest.Member::ruleKey));
        return List.copyOf(result);
    }

    private static List<Outdated> canonicalOutdated(List<Outdated> values) {
        Objects.requireNonNull(values, "outdated must not be null");
        List<Outdated> result = new ArrayList<>(values);
        if (result.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("outdated must not contain null");
        }
        result.sort(Comparator.comparing(Outdated::ruleKey));
        return List.copyOf(result);
    }

    /** One same-key member whose revision, plan hash, or manifest generation differs. */
    public record Outdated(String ruleKey,
                           RuntimeManifest.Member expected,
                           RuntimeManifest.Member observed,
                           boolean revisionMismatch,
                           boolean generationMismatch,
                           boolean planHashMismatch) {
        public Outdated {
            if (ruleKey == null || ruleKey.isBlank()) {
                throw new IllegalArgumentException("outdated ruleKey must not be blank");
            }
            if (expected == null && observed == null) {
                throw new IllegalArgumentException("outdated must have expected or observed member");
            }
        }

        public Long expectedRevision() {
            return expected == null ? null : expected.revision();
        }

        public Long observedRevision() {
            return observed == null ? null : observed.revision();
        }

        public String expectedPlanHash() {
            return expected == null ? null : expected.planHash();
        }

        public String observedPlanHash() {
            return observed == null ? null : observed.planHash();
        }

        public boolean revisionChanged() {
            return revisionMismatch;
        }

        public boolean planHashChanged() {
            return planHashMismatch;
        }
    }
}
