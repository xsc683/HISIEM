package com.xscsiem.hsiem_platform.rules.runtime;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Immutable description of the rules that a detection job is expected (or observed) to run.
 * The member order is canonicalized at construction time so callers cannot accidentally make
 * an equivalent manifest hash differently.
 */
public record RuntimeManifest(
        String schemaVersion,
        String tenantId,
        String targetCluster,
        String jobGroupKey,
        long generation,
        String jobId,
        String jobKey,
        List<Member> members) {

    public static final String SCHEMA_VERSION = "1";
    public static final String CURRENT_SCHEMA_VERSION = SCHEMA_VERSION;

    public RuntimeManifest {
        schemaVersion = required(schemaVersion, "schemaVersion");
        tenantId = required(tenantId, "tenantId");
        targetCluster = required(targetCluster, "targetCluster");
        jobGroupKey = required(jobGroupKey, "jobGroupKey");
        if (generation < 0) {
            throw new IllegalArgumentException("generation must be non-negative");
        }
        jobId = optional(jobId, "jobId");
        jobKey = optional(jobKey, "jobKey");
        if (members == null) {
            throw new IllegalArgumentException("members must not be null");
        }
        List<Member> canonical = new ArrayList<>(members);
        Set<String> keys = new HashSet<>();
        for (Member member : canonical) {
            if (member == null) {
                throw new IllegalArgumentException("members must not contain null");
            }
            if (!keys.add(member.ruleKey())) {
                throw new IllegalArgumentException("duplicate ruleKey in manifest: " + member.ruleKey());
            }
        }
        canonical.sort(Comparator.comparing(Member::ruleKey));
        members = List.copyOf(canonical);
    }

    public RuntimeManifest(String schemaVersion, String tenantId, String targetCluster,
                           String jobGroupKey, long generation, List<Member> members) {
        this(schemaVersion, tenantId, targetCluster, jobGroupKey, generation, null, null, members);
    }

    /** Numeric version convenience overload for callers that model schema versions as integers. */
    public RuntimeManifest(int schemaVersion, String tenantId, String targetCluster,
                           String jobGroupKey, long generation, List<Member> members) {
        this(String.valueOf(schemaVersion), tenantId, targetCluster, jobGroupKey, generation,
                null, null, members);
    }

    public RuntimeManifest(String schemaVersion, String tenantId, String targetCluster,
                           String jobGroupKey, long generation, String jobId, String jobKey,
                           Member... members) {
        this(schemaVersion, tenantId, targetCluster, jobGroupKey, generation, jobId, jobKey,
                List.of(members));
    }

    /** Alias useful to clients that call the list rules rather than members. */
    public List<Member> rules() {
        return members;
    }

    public boolean containsRule(String ruleKey) {
        return members.stream().anyMatch(member -> member.ruleKey().equals(ruleKey));
    }

    public Member member(String ruleKey) {
        return members.stream().filter(member -> member.ruleKey().equals(ruleKey)).findFirst().orElse(null);
    }

    /**
     * A deterministic, length-delimited representation independent of JSON implementation details.
     * RuntimeManifestCodec is the wire representation and hash authority.
     */
    public String canonicalRepresentation() {
        StringBuilder result = new StringBuilder();
        append(result, schemaVersion);
        append(result, tenantId);
        append(result, targetCluster);
        append(result, jobGroupKey);
        result.append(generation).append('|');
        for (Member member : members) {
            append(result, member.ruleKey());
            result.append(member.revision()).append('|');
            append(result, member.planHash());
        }
        return result.toString();
    }

    private static void append(StringBuilder target, String value) {
        if (value == null) {
            target.append(-1).append(':').append('|');
        } else {
            target.append(value.length()).append(':').append(value).append('|');
        }
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static String optional(String value, String field) {
        if (value == null) {
            return null;
        }
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank when supplied");
        }
        return value;
    }

    public record Member(String ruleKey, long revision, String planHash) {
        public Member {
            ruleKey = required(ruleKey, "ruleKey");
            if (revision < 0) {
                throw new IllegalArgumentException("revision must be non-negative");
            }
            planHash = required(planHash, "planHash");
        }
    }
}
