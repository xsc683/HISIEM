package com.xscsiem.hsiem_platform.soar;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 一次 SOAR 执行及其持久化状态。 */
public record SoarExecution(
        String id,
        String playbookId,
        String playbookVersion,
        String resourceType,
        String resourceId,
        String status,
        String actor,
        int currentStep,
        SoarPlaybook playbookSnapshot,
        Map<String, Object> context,
        String approvalStepId,
        String approvalMessage,
        String approvedBy,
        String error,
        Instant createdAt,
        Instant updatedAt,
        Instant finishedAt,
        long version,
        List<SoarStepExecution> steps) {

    public SoarExecution withSteps(List<SoarStepExecution> value) {
        return new SoarExecution(id, playbookId, playbookVersion, resourceType, resourceId,
                status, actor, currentStep, playbookSnapshot, context, approvalStepId,
                approvalMessage, approvedBy, error, createdAt, updatedAt, finishedAt, version, value);
    }
}
