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
        String currentNode,
        List<String> frontier,
        SoarPlaybook playbookSnapshot,
        Map<String, Object> context,
        String triggerType,
        String dedupKey,
        String approvalStepId,
        String approvalMessage,
        String approvedBy,
        String error,
        Instant nextRunAt,
        String leaseOwner,
        Instant leaseExpiresAt,
        boolean cancelRequested,
        boolean pauseRequested,
        int nodesExecuted,
        Instant createdAt,
        Instant updatedAt,
        Instant finishedAt,
        long version,
        List<SoarStepExecution> steps) {

    /** V8 兼容构造器，旧测试和旧快照按线性 currentStep 初始化。 */
    public SoarExecution(String id, String playbookId, String playbookVersion,
                         String resourceType, String resourceId, String status,
                         String actor, int currentStep, SoarPlaybook playbookSnapshot,
                         Map<String, Object> context, String approvalStepId,
                         String approvalMessage, String approvedBy, String error,
                         Instant createdAt, Instant updatedAt, Instant finishedAt,
                         long version, List<SoarStepExecution> steps) {
        this(id, playbookId, playbookVersion, resourceType, resourceId, status, actor,
                currentStep, null, List.of(), playbookSnapshot, context, "manual", null,
                approvalStepId, approvalMessage, approvedBy, error, createdAt,
                null, null, false, false, currentStep, createdAt, updatedAt,
                finishedAt, version, steps);
    }

    public SoarExecution withSteps(List<SoarStepExecution> value) {
        return new SoarExecution(id, playbookId, playbookVersion, resourceType, resourceId,
                status, actor, currentStep, currentNode, frontier, playbookSnapshot, context,
                triggerType, dedupKey, approvalStepId, approvalMessage, approvedBy, error,
                nextRunAt, leaseOwner, leaseExpiresAt, cancelRequested, pauseRequested,
                nodesExecuted, createdAt, updatedAt, finishedAt, version, value);
    }
}
