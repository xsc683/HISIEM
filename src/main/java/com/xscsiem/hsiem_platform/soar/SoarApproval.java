package com.xscsiem.hsiem_platform.soar;

import java.time.Instant;

public record SoarApproval(
        String id,
        String tenantId,
        String executionId,
        String nodeRunId,
        String nodeId,
        String playbookId,
        String playbookName,
        String objectType,
        String objectId,
        String prompt,
        String status,
        String decidedBy,
        String decisionNote,
        Instant createdAt,
        Instant decidedAt) {
}
