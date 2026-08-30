package com.xscsiem.hsiem_platform.detection.runtime;

import java.util.Objects;

/** Immutable, transport-neutral target passed to a runtime port. */
public record DetectionRuntimeTarget(
        String tenantId,
        String groupKey,
        String targetCluster,
        long desiredGeneration,
        String expectedManifestJson,
        String expectedManifestHash) {

    public DetectionRuntimeTarget {
        require(tenantId, "tenantId");
        require(groupKey, "groupKey");
        require(targetCluster, "targetCluster");
        if (desiredGeneration < 0) {
            throw new IllegalArgumentException("desiredGeneration must be non-negative");
        }
        require(expectedManifestJson, "expectedManifestJson");
        require(expectedManifestHash, "expectedManifestHash");
    }

    public DetectionRuntimeTarget(DetectionGroupLease lease) {
        this(lease.tenantId(), lease.groupKey(), lease.targetCluster(), lease.desiredGeneration(),
                lease.expectedJson(), lease.expectedHash());
    }

    private static String require(String value, String field) {
        if (Objects.requireNonNullElse(value, "").isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
