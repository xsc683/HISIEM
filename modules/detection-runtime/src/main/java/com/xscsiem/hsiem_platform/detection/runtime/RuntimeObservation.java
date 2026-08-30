package com.xscsiem.hsiem_platform.detection.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Adapter-independent point-in-time observation.  It contains no database or Docker types so a
 * process adapter can be replaced without changing controller state-machine code.
 */
public record RuntimeObservation(
        String runtimeState,
        String tenantId,
        String targetCluster,
        String groupKey,
        long generation,
        String jobId,
        String jobKey,
        List<Member> members,
        String errorCode,
        String errorMessage) {

    public RuntimeObservation {
        require(runtimeState, "runtimeState");
        require(tenantId, "tenantId");
        require(targetCluster, "targetCluster");
        require(groupKey, "groupKey");
        if (generation < 0) {
            throw new IllegalArgumentException("generation must be non-negative");
        }
        members = members == null ? List.of() : List.copyOf(new ArrayList<>(members));
        if (members.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("members must not contain null");
        }
    }

    public RuntimeObservation(String runtimeState, DetectionRuntimeTarget target,
                              String jobId, String jobKey, long generation,
                              List<Member> members, String errorCode, String errorMessage) {
        this(runtimeState, target.tenantId(), target.targetCluster(), target.groupKey(), generation,
                jobId, jobKey, members, errorCode, errorMessage);
    }

    public RuntimeObservation(String runtimeState, DetectionRuntimeTarget target,
                              String jobId, String jobKey, List<Member> members) {
        this(runtimeState, target, jobId, jobKey, target.desiredGeneration(), members, null, null);
    }

    public static RuntimeObservation unknown(DetectionRuntimeTarget target) {
        return new RuntimeObservation("UNKNOWN", target, null, null, 0L, List.of(),
                "RUNTIME_UNKNOWN", "runtime adapter returned no usable observation");
    }

    public static RuntimeObservation running(DetectionRuntimeTarget target, String jobId,
                                             String jobKey, List<Member> members) {
        return new RuntimeObservation("RUNNING", target, jobId, jobKey,
                target.desiredGeneration(), members, null, null);
    }

    public static RuntimeObservation stopped(DetectionRuntimeTarget target, String jobId,
                                             String jobKey) {
        return new RuntimeObservation("STOPPED", target, jobId, jobKey,
                target.desiredGeneration(), List.of(), null, null);
    }

    public String state() {
        return runtimeState;
    }

    public record Member(String ruleKey, long revision, String planHash) {
        public Member {
            require(ruleKey, "ruleKey");
            if (revision < 0) {
                throw new IllegalArgumentException("revision must be non-negative");
            }
            require(planHash, "planHash");
        }
    }

    private static String require(String value, String field) {
        if (Objects.requireNonNullElse(value, "").isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
