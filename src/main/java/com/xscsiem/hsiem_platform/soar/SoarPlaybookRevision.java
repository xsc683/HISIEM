package com.xscsiem.hsiem_platform.soar;

import java.time.Instant;
import java.util.Map;

/** Playbook 的不可变版本定义及其发布治理状态。 */
public record SoarPlaybookRevision(
        String tenantId,
        String playbookId,
        int revision,
        String semanticVersion,
        String state,
        SoarPlaybook definition,
        Map<String, Object> layout,
        int rolloutPercentage,
        String reviewNote,
        String createdBy,
        String reviewedBy,
        String publishedBy,
        Instant createdAt,
        Instant reviewedAt,
        Instant publishedAt,
        Instant updatedAt,
        long lockVersion) {
}
