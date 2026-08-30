package com.xscsiem.hsiem_platform.detection.runtime;

import java.util.Objects;

/**
 * Fencing-aware snapshot of one claimed detection group.  The lease intentionally carries the
 * desired generation and manifest captured by the claim; callers must never re-read those values
 * and then perform an action without checking {@code isCurrent}.
 */
public record DetectionGroupLease(
        String tenantId,
        String groupKey,
        String targetCluster,
        long desiredGeneration,
        String expectedJson,
        String expectedHash,
        String owner,
        long fencingToken,
        int attempt) {

    public DetectionGroupLease {
        require(tenantId, "tenantId");
        require(groupKey, "groupKey");
        require(targetCluster, "targetCluster");
        if (desiredGeneration < 0) {
            throw new IllegalArgumentException("desiredGeneration must be non-negative");
        }
        require(expectedJson, "expectedJson");
        require(expectedHash, "expectedHash");
        require(owner, "owner");
        if (fencingToken < 0) {
            throw new IllegalArgumentException("fencingToken must be non-negative");
        }
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be positive");
        }
    }

    /** Alias matching the database column name. */
    public long controllerFencingToken() {
        return fencingToken;
    }

    /** Alias matching the database column name. */
    public int reconcileAttempt() {
        return attempt;
    }

    private static String require(String value, String field) {
        if (Objects.requireNonNullElse(value, "").isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
