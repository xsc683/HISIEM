package com.xscsiem.hsiem_platform.rules.runtime;

import java.util.Objects;

/** Immutable controller ownership epoch required for observed-state writes. */
public record ObservationFence(
        String tenantId,
        String groupKey,
        String targetCluster,
        String owner,
        long fencingToken,
        long desiredGeneration) {

    public ObservationFence {
        require(tenantId, "tenantId");
        require(groupKey, "groupKey");
        require(targetCluster, "targetCluster");
        require(owner, "owner");
        if (fencingToken < 0)
            throw new IllegalArgumentException("fencingToken must be non-negative");
        if (desiredGeneration < 0)
            throw new IllegalArgumentException("desiredGeneration must be non-negative");
    }

    private static void require(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
    }
}
