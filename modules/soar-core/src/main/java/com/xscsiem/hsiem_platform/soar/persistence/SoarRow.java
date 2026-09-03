package com.xscsiem.hsiem_platform.soar.persistence;

import java.time.Instant;

/**
 * Typed persistence rows for the SOAR runtime tables. Mapper XML maps columns into these immutable
 * records via constructor {@code resultMap}; {@code SoarStore} converts them to domain objects at
 * the typed seam.
 */
public final class SoarRow {

    private SoarRow() {}

    /** soar_playbook */
    public record PlaybookRow(
            String id,
            String tenantId,
            String name,
            String description,
            String status,
            boolean enabled,
            String entryType,
            String eventTypesJson,
            String graphJson,
            long revision,
            String createdBy,
            String updatedBy,
            Instant createdAt,
            Instant updatedAt,
            Instant publishedAt) {}

    /** soar_execution, excluding the JSON text columns that require decoding on read. */
    public record ExecutionRow(
            String id,
            String tenantId,
            String playbookId,
            String playbookName,
            long playbookRevision,
            String graphSnapshot,
            String objectType,
            String objectId,
            String eventType,
            String triggerType,
            String triggerMessageId,
            String triggerEnvelope,
            String payloadSnapshot,
            String status,
            String currentNodeId,
            Instant nextRunAt,
            String error,
            String actor,
            boolean cancelRequested,
            String leaseOwner,
            Instant leaseExpiresAt,
            long version,
            Instant createdAt,
            Instant updatedAt,
            Instant startedAt,
            Instant finishedAt) {}

    /** ordered {node_id, output_json} pair used to rebuild node output maps by sequence. */
    public record NodeOutputRow(String nodeId, String outputJson) {}

    /** soar_node_execution */
    public record NodeRunRow(
            String id,
            String executionId,
            String nodeId,
            String nodeName,
            String nodeType,
            String status,
            long sequenceNo,
            int visitNo,
            int attempt,
            String tokenId,
            String idempotencyKey,
            String inputJson,
            String outputJson,
            String error,
            Instant startedAt,
            Instant finishedAt) {}

    /** soar_approval_task */
    public record ApprovalRow(
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
            Instant decidedAt) {}

    /** soar_parallel_group */
    public record ParallelGroupRow(
            String id,
            String tenantId,
            String parentExecutionId,
            String parentNodeRunId,
            String joinNodeId,
            int expectedCount,
            int arrivedCount,
            String status,
            String outputJson,
            Instant createdAt,
            Instant releasedAt) {}

    /** soar_parallel_branch */
    public record ParallelBranchRow(
            String id,
            String groupId,
            String executionId,
            String branchLabel,
            String targetNodeId,
            String status,
            Instant arrivedAt) {}

    /** soar_loop_state */
    public record LoopStateRow(
            String id,
            String tenantId,
            String parentExecutionId,
            String parentNodeRunId,
            String childExecutionId,
            String bodyStartNodeId,
            String bodyEndNodeId,
            String itemsJson,
            int iterationIndex,
            int maxIterations,
            String status,
            String outputJson,
            Instant createdAt,
            Instant finishedAt) {}

    /**
     * soar_parallel_group aggregate with branch target facts, produced by the {@code
     * selectParallelBranch} join.
     */
    public record ParallelBranchJoinRow(
            String id,
            String groupId,
            String parentExecutionId,
            String joinNodeId,
            String branchLabel,
            String targetNodeId) {}

    /** branch execution pair used to aggregate join outputs. */
    public record BranchExecutionRow(String branchLabel, String executionId) {}

    /**
     * soar_parallel_branch JOIN soar_parallel_group row locked when a failed child's failure must
     * be propagated to its waiting parallel parent.
     */
    public record ParallelFailureParentRow(
            String groupId, String parentExecutionId, String parentNodeRunId, String branchLabel) {}

    /** soar_loop_state row locked when a failed child must fail its waiting loop parent. */
    public record LoopFailureParentRow(
            String id, String parentExecutionId, String parentNodeRunId) {}
}
