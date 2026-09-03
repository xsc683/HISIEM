package com.xscsiem.hsiem_platform.soar.persistence;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * SOAR runtime persistence (soar_playbook / soar_execution / soar_node_execution /
 * soar_approval_task / soar_parallel_group / soar_parallel_branch / soar_loop_state /
 * soar_action_receipt). SQL in {@code mybatis/soar/SoarMapper.xml}. Every statement is a verbatim
 * transcription of the retired {@code SoarStore} JdbcTemplate SQL, preserving the lease/fencing
 * (FOR UPDATE, optimistic version) and transition-guard semantics 1:1.
 */
@Mapper
public interface SoarMapper {

    // ---- soar_playbook -------------------------------------------------------

    List<SoarRow.PlaybookRow> selectPlaybooks(@Param("tenantId") String tenantId);

    SoarRow.PlaybookRow selectPlaybook(@Param("tenantId") String tenantId, @Param("id") String id);

    List<SoarRow.PlaybookRow> selectMatchingPlaybooks(
            @Param("tenantId") String tenantId, @Param("objectType") String objectType);

    int countLivePlaybook(@Param("tenantId") String tenantId, @Param("id") String id);

    int insertPlaybook(
            @Param("id") String id,
            @Param("tenantId") String tenantId,
            @Param("name") String name,
            @Param("description") String description,
            @Param("entryType") String entryType,
            @Param("eventTypesJson") String eventTypesJson,
            @Param("graphJson") String graphJson,
            @Param("createdBy") String createdBy,
            @Param("actor") String actor,
            @Param("createdAt") Instant createdAt);

    int updatePlaybook(
            @Param("name") String name,
            @Param("description") String description,
            @Param("entryType") String entryType,
            @Param("eventTypesJson") String eventTypesJson,
            @Param("graphJson") String graphJson,
            @Param("actor") String actor,
            @Param("tenantId") String tenantId,
            @Param("id") String id,
            @Param("expectedRevision") long expectedRevision);

    int publishPlaybook(
            @Param("actor") String actor,
            @Param("tenantId") String tenantId,
            @Param("id") String id,
            @Param("expectedRevision") long expectedRevision);

    int updatePlaybookEnabled(
            @Param("status") String status,
            @Param("enabled") boolean enabled,
            @Param("actor") String actor,
            @Param("tenantId") String tenantId,
            @Param("id") String id);

    int countActiveExecutions(
            @Param("tenantId") String tenantId, @Param("playbookId") String playbookId);

    int deletePlaybook(
            @Param("actor") String actor,
            @Param("tenantId") String tenantId,
            @Param("id") String id);

    // ---- soar_execution reads ----------------------------------------------

    SoarRow.ExecutionRow selectExecution(@Param("id") String id);

    SoarRow.ExecutionRow selectExecutionByTenant(
            @Param("tenantId") String tenantId, @Param("id") String id);

    SoarRow.ExecutionRow selectExecutionByTrigger(
            @Param("tenantId") String tenantId,
            @Param("playbookId") String playbookId,
            @Param("messageId") String messageId);

    List<SoarRow.ExecutionRow> selectRootExecutions(
            @Param("tenantId") String tenantId, @Param("limit") int limit);

    List<SoarRow.ExecutionRow> selectRootExecutionsByStatus(
            @Param("tenantId") String tenantId,
            @Param("status") String status,
            @Param("limit") int limit);

    List<String> selectClaimCandidates(@Param("limit") int limit);

    int claimExecution(
            @Param("owner") String owner,
            @Param("leaseUntil") Instant leaseUntil,
            @Param("id") String id);

    int renewLease(
            @Param("leaseUntil") Instant leaseUntil,
            @Param("id") String id,
            @Param("owner") String owner,
            @Param("fencingToken") long fencingToken);

    List<String> selectLeaseHolders(
            @Param("id") String id,
            @Param("owner") String owner,
            @Param("fencingToken") long fencingToken,
            @Param("lock") boolean lock);

    int markWaitingFarFuture(
            @Param("nextRunAt") Instant nextRunAt,
            @Param("id") String id,
            @Param("owner") String owner,
            @Param("fencingToken") long fencingToken);

    int advancePending(
            @Param("nextNodeId") String nextNodeId,
            @Param("id") String id,
            @Param("owner") String owner,
            @Param("fencingToken") long fencingToken);

    int markSuccess(
            @Param("id") String id,
            @Param("owner") String owner,
            @Param("fencingToken") long fencingToken,
            @Param("cancelGuard") boolean cancelGuard);

    int markFailed(
            @Param("error") String error,
            @Param("id") String id,
            @Param("owner") String owner,
            @Param("fencingToken") long fencingToken);

    int scheduleRetry(
            @Param("error") String error,
            @Param("nextRunAt") Instant nextRunAt,
            @Param("id") String id,
            @Param("owner") String owner,
            @Param("fencingToken") long fencingToken);

    int markWaitingUntil(
            @Param("nextRunAt") Instant nextRunAt,
            @Param("id") String id,
            @Param("owner") String owner,
            @Param("fencingToken") long fencingToken);

    int markWaitingHuman(
            @Param("id") String id,
            @Param("owner") String owner,
            @Param("fencingToken") long fencingToken);

    int resumeWaitingHuman(@Param("nextNodeId") String nextNodeId, @Param("id") String id);

    /** waiting -> pending release of a durable parent (join complete / loop complete). */
    int releaseWaitingExecution(@Param("nextNodeId") String nextNodeId, @Param("id") String id);

    int cancelAll(@Param("id") String id);

    /** Cancels a waiting parallel/loop parent after its child fails. */
    int markFailedWaiting(@Param("error") String error, @Param("id") String id);

    /** Unconditional terminal fail (loop body child). */
    int failExecution(@Param("error") String error, @Param("id") String id);

    List<String> selectActiveChildren(@Param("parent") String parent);

    int updateChildPayload(
            @Param("currentNodeId") String currentNodeId,
            @Param("payloadSnapshot") String payloadSnapshot,
            @Param("id") String id,
            @Param("owner") String owner,
            @Param("fencingToken") long fencingToken);

    // ---- soar_execution writes ----------------------------------------------

    int insertExecution(
            @Param("id") String id,
            @Param("tenantId") String tenantId,
            @Param("playbookId") String playbookId,
            @Param("playbookName") String playbookName,
            @Param("playbookRevision") long playbookRevision,
            @Param("graphSnapshot") String graphSnapshot,
            @Param("objectType") String objectType,
            @Param("objectId") String objectId,
            @Param("eventType") String eventType,
            @Param("triggerType") String triggerType,
            @Param("triggerMessageId") String triggerMessageId,
            @Param("triggerEnvelope") String triggerEnvelope,
            @Param("payloadSnapshot") String payloadSnapshot,
            @Param("currentNodeId") String currentNodeId,
            @Param("actor") String actor);

    int insertParallelChild(
            @Param("id") String id,
            @Param("tenantId") String tenantId,
            @Param("playbookId") String playbookId,
            @Param("playbookName") String playbookName,
            @Param("playbookRevision") long playbookRevision,
            @Param("graphSnapshot") String graphSnapshot,
            @Param("objectType") String objectType,
            @Param("objectId") String objectId,
            @Param("eventType") String eventType,
            @Param("triggerMessageId") String triggerMessageId,
            @Param("triggerEnvelope") String triggerEnvelope,
            @Param("payloadSnapshot") String payloadSnapshot,
            @Param("currentNodeId") String currentNodeId,
            @Param("actor") String actor,
            @Param("parallelParentId") String parallelParentId);

    int insertLoopChild(
            @Param("id") String id,
            @Param("tenantId") String tenantId,
            @Param("playbookId") String playbookId,
            @Param("playbookName") String playbookName,
            @Param("playbookRevision") long playbookRevision,
            @Param("graphSnapshot") String graphSnapshot,
            @Param("objectType") String objectType,
            @Param("objectId") String objectId,
            @Param("eventType") String eventType,
            @Param("triggerMessageId") String triggerMessageId,
            @Param("triggerEnvelope") String triggerEnvelope,
            @Param("payloadSnapshot") String payloadSnapshot,
            @Param("currentNodeId") String currentNodeId,
            @Param("actor") String actor);

    // ---- soar_node_execution ------------------------------------------------

    List<SoarRow.NodeRunRow> selectNodeRuns(@Param("executionId") String executionId);

    SoarRow.NodeRunRow selectResumableNodeRun(
            @Param("executionId") String executionId, @Param("nodeId") String nodeId);

    SoarRow.NodeRunRow selectLatestNodeRun(
            @Param("executionId") String executionId, @Param("nodeId") String nodeId);

    SoarRow.NodeRunRow selectNodeRun(@Param("id") String id);

    Long selectNextSequence(@Param("executionId") String executionId);

    int insertNodeRun(
            @Param("id") String id,
            @Param("executionId") String executionId,
            @Param("nodeId") String nodeId,
            @Param("nodeName") String nodeName,
            @Param("nodeType") String nodeType,
            @Param("sequenceNo") long sequenceNo,
            @Param("visitNo") int visitNo,
            @Param("attempt") int attempt,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("inputJson") String inputJson);

    int markNodeRunRetrying(@Param("id") String id);

    int updateNodeRunInput(
            @Param("inputJson") String inputJson,
            @Param("id") String id,
            @Param("executionId") String executionId);

    /** Guarded node-run finish shared by advance/succeed/fail/decide. */
    int finishNodeRun(
            @Param("status") String status,
            @Param("outputJson") String outputJson,
            @Param("error") String error,
            @Param("id") String id);

    /** Unguarded node-run finish used to write a durable parent's aggregate output. */
    int finishNodeRunNoGuard(
            @Param("status") String status,
            @Param("outputJson") String outputJson,
            @Param("error") String error,
            @Param("id") String id);

    int setNodeRunWaiting(
            @Param("nodeRunId") String nodeRunId, @Param("executionId") String executionId);

    int setNodeRunWaitingHuman(
            @Param("nodeRunId") String nodeRunId, @Param("executionId") String executionId);

    int cancelNodeRuns(@Param("executionId") String executionId);

    int failParentNodeRun(@Param("error") String error, @Param("id") String id);

    List<SoarRow.NodeOutputRow> selectNodeOutputs(@Param("executionId") String executionId);

    // ---- soar_approval_task ------------------------------------------------

    List<SoarRow.ApprovalRow> selectApprovals(
            @Param("tenantId") String tenantId, @Param("limit") int limit);

    List<SoarRow.ApprovalRow> selectApprovalsByStatus(
            @Param("tenantId") String tenantId,
            @Param("status") String status,
            @Param("limit") int limit);

    SoarRow.ApprovalRow selectApproval(@Param("tenantId") String tenantId, @Param("id") String id);

    int insertApproval(
            @Param("id") String id,
            @Param("tenantId") String tenantId,
            @Param("executionId") String executionId,
            @Param("nodeRunId") String nodeRunId,
            @Param("nodeId") String nodeId,
            @Param("playbookId") String playbookId,
            @Param("playbookName") String playbookName,
            @Param("objectType") String objectType,
            @Param("objectId") String objectId,
            @Param("prompt") String prompt);

    int decideApproval(
            @Param("decision") String decision,
            @Param("actor") String actor,
            @Param("note") String note,
            @Param("tenantId") String tenantId,
            @Param("id") String id);

    int cancelPendingApprovals(@Param("executionId") String executionId);

    // ---- soar_parallel_group / soar_parallel_branch -------------------------

    int insertParallelGroup(
            @Param("id") String id,
            @Param("tenantId") String tenantId,
            @Param("parentExecutionId") String parentExecutionId,
            @Param("parentNodeRunId") String parentNodeRunId,
            @Param("joinNodeId") String joinNodeId,
            @Param("expectedCount") int expectedCount);

    int insertParallelBranch(
            @Param("id") String id,
            @Param("groupId") String groupId,
            @Param("executionId") String executionId,
            @Param("branchLabel") String branchLabel,
            @Param("targetNodeId") String targetNodeId);

    int markParallelBranchArrived(@Param("id") String id);

    int markBranchCancelledAll(@Param("executionId") String executionId);

    int cancelBranchesOfGroup(@Param("groupId") String groupId);

    int markGroupCancelledWaiting(@Param("executionId") String executionId);

    int markGroupCancelledById(@Param("id") String id);

    int releaseGroup(@Param("outputJson") String outputJson, @Param("groupId") String groupId);

    int selectArrivedCount(@Param("groupId") String groupId);

    int updateArrivedCount(
            @Param("arrivedCount") int arrivedCount, @Param("groupId") String groupId);

    int selectExpectedCount(@Param("groupId") String groupId);

    String selectGroupParentNodeRunId(@Param("groupId") String groupId);

    List<SoarRow.ParallelBranchJoinRow> selectParallelBranch(
            @Param("executionId") String executionId, @Param("nodeId") String nodeId);

    SoarRow.ParallelFailureParentRow selectParallelBranchForUpdate(
            @Param("childExecutionId") String childExecutionId);

    List<SoarRow.BranchExecutionRow> selectBranchExecutions(@Param("groupId") String groupId);

    List<String> selectSiblingBranches(
            @Param("groupId") String groupId, @Param("childExecutionId") String childExecutionId);

    List<String> selectWaitingParentId(@Param("parentId") String parentId);

    // ---- soar_loop_state ----------------------------------------------------

    List<SoarRow.LoopStateRow> selectLoopState(
            @Param("childExecutionId") String childExecutionId, @Param("nodeId") String nodeId);

    int insertLoopState(
            @Param("id") String id,
            @Param("tenantId") String tenantId,
            @Param("parentExecutionId") String parentExecutionId,
            @Param("parentNodeRunId") String parentNodeRunId,
            @Param("childExecutionId") String childExecutionId,
            @Param("bodyStartNodeId") String bodyStartNodeId,
            @Param("bodyEndNodeId") String bodyEndNodeId,
            @Param("itemsJson") String itemsJson,
            @Param("maxIterations") int maxIterations);

    int advanceLoopIndex(@Param("iterationIndex") int iterationIndex, @Param("id") String id);

    int completeLoop(
            @Param("iterationIndex") int iterationIndex,
            @Param("outputJson") String outputJson,
            @Param("id") String id);

    int markLoopFailed(@Param("outputJson") String outputJson, @Param("id") String id);

    int markLoopCancelled(@Param("executionId") String executionId);

    SoarRow.LoopFailureParentRow selectLoopFailureParent(
            @Param("childExecutionId") String childExecutionId);

    // ---- soar_action_receipt ----------------------------------------------

    String selectReceipt(@Param("idempotencyKey") String idempotencyKey);

    int insertReceipt(
            @Param("idempotencyKey") String idempotencyKey,
            @Param("tenantId") String tenantId,
            @Param("executionId") String executionId,
            @Param("nodeId") String nodeId,
            @Param("actionId") String actionId,
            @Param("resultJson") String resultJson);
}
