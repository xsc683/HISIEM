package com.xscsiem.hsiem_platform.rules;

import java.time.Instant;
import java.util.UUID;

/** Immutable compiled DetectionPlan artifact. */
public record DetectionPlanArtifact(
        UUID planId,
        UUID revisionId,
        String compilerVersion,
        String planJson,
        String planHash,
        Instant createdAt) {}
