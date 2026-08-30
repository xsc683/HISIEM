package com.xscsiem.hsiem_platform.detection.runtime;

import java.nio.file.Path;

/** Immutable local/container locations and identity of one materialized detection artifact. */
public record DetectionArtifact(
        Path localPath,
        String containerPath,
        String jobKey,
        long generation,
        String manifestHash) {

    public DetectionArtifact {
        if (localPath == null || containerPath == null || containerPath.isBlank()) {
            throw new IllegalArgumentException("artifact paths must not be blank");
        }
        DetectionJobNameCodec.validateJobKey(jobKey);
        if (generation < 0) throw new IllegalArgumentException("generation must be non-negative");
        DetectionJobNameCodec.validateHash(manifestHash);
    }
}
