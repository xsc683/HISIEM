package com.xscsiem.hsiem_platform.rules;

import java.util.UUID;

/** Typed summary returned by a desired-state bulk mutation. */
public record DeploymentSummary(
        String ruleKey,
        UUID deploymentId,
        DesiredState desiredState,
        String targetCluster,
        long generation,
        String status) {}
