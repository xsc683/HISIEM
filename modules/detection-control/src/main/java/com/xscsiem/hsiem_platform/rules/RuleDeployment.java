package com.xscsiem.hsiem_platform.rules;

import java.time.Instant;
import java.util.UUID;

/** Typed desired deployment row exposed to the application domain. */
public record RuleDeployment(
        UUID deploymentId,
        String tenantId,
        String ruleKey,
        UUID desiredRevisionId,
        DesiredState desiredState,
        long generation,
        long observedGeneration,
        String targetCluster,
        String status,
        String lastError,
        Instant createdAt,
        Instant updatedAt) {}
