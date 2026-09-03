package com.xscsiem.hsiem_platform.soar;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xscsiem.hsiem_platform.onboarding.ConflictException;
import com.xscsiem.hsiem_platform.onboarding.NotFoundException;
import com.xscsiem.hsiem_platform.soar.persistence.SoarMapper;
import com.xscsiem.hsiem_platform.soar.persistence.SoarRow;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * SOAR durable store. Every statement lives in {@link SoarMapper} (MyBatis XML); this class only
 * translates typed rows to domain models and preserves the lease/fencing and transition-guard
 * semantics of the retired JdbcTemplate implementation. The mapper participates in the same
 * Spring-managed transaction as the caller (SqlSessionTemplate), so {@code FOR UPDATE} and
 * optimistic-version guards keep their meaning.
 */
@Repository
public class SoarStore {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};
    private static final TypeReference<List<Object>> OBJECT_LIST = new TypeReference<>() {};
    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() {};

    private final SoarMapper mapper;
    private final ObjectMapper objectMapper;

    public SoarStore(SoarMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    public List<SoarPlaybook> listPlaybooks(String tenantId) {
        return mapper.selectPlaybooks(tenantId).stream().map(this::mapPlaybook).toList();
    }

    public SoarPlaybook getPlaybook(String tenantId, String id) {
        SoarRow.PlaybookRow row = mapper.selectPlaybook(tenantId, id);
        if (row == null) throw new NotFoundException("Playbook 不存在: " + id);
        return mapPlaybook(row);
    }

    public List<SoarPlaybook> matchingPlaybooks(
            String tenantId, String objectType, String eventType) {
        return mapper.selectMatchingPlaybooks(tenantId, objectType).stream()
                .map(this::mapPlaybook)
                .filter(item -> item.eventTypes().contains(eventType))
                .toList();
    }

    @Transactional
    public SoarPlaybook createPlaybook(
            String tenantId,
            String name,
            String description,
            String entryType,
            List<String> eventTypes,
            PlaybookGraph graph,
            String actor) {
        String id = "pb-" + UUID.randomUUID();
        Instant now = Instant.now();
        mapper.insertPlaybook(
                id,
                tenantId,
                name,
                description == null ? "" : description,
                entryType,
                json(eventTypes),
                json(graph),
                actor,
                actor,
                now);
        return getPlaybook(tenantId, id);
    }

    @Transactional
    public SoarPlaybook updatePlaybook(
            String tenantId,
            String id,
            String name,
            String description,
            String entryType,
            List<String> eventTypes,
            PlaybookGraph graph,
            long expectedRevision,
            String actor) {
        int updated =
                mapper.updatePlaybook(
                        name,
                        description == null ? "" : description,
                        entryType,
                        json(eventTypes),
                        json(graph),
                        actor,
                        tenantId,
                        id,
                        expectedRevision);
        if (updated == 0) distinguishMissingOrConflict(tenantId, id);
        return getPlaybook(tenantId, id);
    }

    @Transactional
    public SoarPlaybook publishPlaybook(
            String tenantId, String id, long expectedRevision, String actor) {
        int updated = mapper.publishPlaybook(actor, tenantId, id, expectedRevision);
        if (updated == 0) distinguishMissingOrConflict(tenantId, id);
        return getPlaybook(tenantId, id);
    }

    @Transactional
    public SoarPlaybook setEnabled(String tenantId, String id, boolean enabled, String actor) {
        SoarPlaybook current = getPlaybook(tenantId, id);
        if (enabled && "draft".equals(current.status())) {
            throw new ConflictException("草稿必须先发布，不能直接启用");
        }
        String status = enabled ? "published" : "disabled";
        mapper.updatePlaybookEnabled(status, enabled, actor, tenantId, id);
        return getPlaybook(tenantId, id);
    }

    @Transactional
    public void deletePlaybook(String tenantId, String id, String actor) {
        getPlaybook(tenantId, id);
        int active = mapper.countActiveExecutions(tenantId, id);
        if (active > 0) {
            throw new ConflictException("Playbook 存在活动执行，需先取消执行实例");
        }
        mapper.deletePlaybook(actor, tenantId, id);
    }

    @Transactional
    public boolean createExecution(SoarPlaybook playbook, SoarTriggerEnvelope trigger) {
        return createExecution(playbook, trigger, "kafka:" + trigger.messageId(), "KAFKA");
    }

    @Transactional
    public boolean createExecution(
            SoarPlaybook playbook, SoarTriggerEnvelope trigger, String actor) {
        return createExecution(playbook, trigger, actor, "KAFKA");
    }

    @Transactional
    public boolean createExecution(
            SoarPlaybook playbook, SoarTriggerEnvelope trigger, String actor, String triggerType) {
        String id = "exec-" + UUID.randomUUID();
        String startNode =
                playbook.graph().nodes().stream()
                        .filter(node -> "start".equals(node.type()))
                        .findFirst()
                        .orElseThrow()
                        .id();
        try {
            mapper.insertExecution(
                    id,
                    playbook.tenantId(),
                    playbook.id(),
                    playbook.name(),
                    playbook.revision(),
                    json(playbook.graph()),
                    playbook.entryType(),
                    trigger.objectId(),
                    trigger.eventType(),
                    triggerType,
                    trigger.messageId(),
                    json(trigger),
                    json(trigger.payload()),
                    startNode,
                    actor == null || actor.isBlank() ? "system" : actor);
            return true;
        } catch (DuplicateKeyException ignored) {
            return false;
        }
    }

    /** Materialises each fan-out branch as a durable child execution. */
    @Transactional
    public void fanOut(
            SoarExecution parent,
            SoarExecution.NodeRun parentRun,
            Map<String, Object> output,
            List<BranchTarget> targets,
            String joinNodeId) {
        requireLeaseForUpdate(parent);
        if (targets == null || targets.size() < 2) throw new IllegalArgumentException("并行分支不能为空");
        String groupId = "join-" + UUID.randomUUID();
        finishNode(parentRun.id(), "success", output, null);
        mapper.insertParallelGroup(
                groupId,
                parent.tenantId(),
                parent.id(),
                parentRun.id(),
                joinNodeId,
                targets.size());
        int index = 0;
        for (BranchTarget target : targets) {
            String branchId = "branch-" + UUID.randomUUID();
            String childId = "exec-" + UUID.randomUUID();
            String messageId = parent.triggerMessageId() + ":" + groupId + ":" + index++;
            SoarTriggerEnvelope trigger =
                    new SoarTriggerEnvelope(
                            messageId,
                            parent.eventType(),
                            parent.triggerEnvelope().occurredAt(),
                            "parallel:" + parent.id(),
                            parent.tenantId(),
                            parent.objectType(),
                            parent.objectId(),
                            parent.payloadSnapshot(),
                            null);
            mapper.insertParallelChild(
                    childId,
                    parent.tenantId(),
                    parent.playbookId(),
                    parent.playbookName(),
                    parent.playbookRevision(),
                    json(parent.graphSnapshot()),
                    parent.objectType(),
                    parent.objectId(),
                    parent.eventType(),
                    messageId,
                    json(trigger),
                    json(parent.payloadSnapshot()),
                    target.nodeId(),
                    "parallel:" + parent.id(),
                    parent.id());
            mapper.insertParallelBranch(
                    branchId, groupId, childId, target.branch(), target.nodeId());
        }
        int moved =
                mapper.markWaitingFarFuture(
                        Instant.now().plus(Duration.ofDays(365)),
                        parent.id(),
                        parent.leaseOwner(),
                        parent.fencingToken());
        requireTransition(moved, parent);
    }

    public ParallelBranch parallelBranch(String executionId, String nodeId) {
        List<SoarRow.ParallelBranchJoinRow> rows = mapper.selectParallelBranch(executionId, nodeId);
        if (rows.isEmpty()) return null;
        SoarRow.ParallelBranchJoinRow row = rows.getFirst();
        return new ParallelBranch(
                row.id(),
                row.groupId(),
                row.parentExecutionId(),
                row.joinNodeId(),
                row.branchLabel(),
                row.targetNodeId());
    }

    /** Marks a branch arrived and releases the parent only on the final arrival. */
    @Transactional
    public void arriveParallel(SoarExecution child, ParallelBranch branch, String nextNodeId) {
        requireLeaseForUpdate(child);
        int branchUpdated = mapper.markParallelBranchArrived(branch.id());
        if (branchUpdated != 1) return;
        int childMoved =
                mapper.markSuccess(child.id(), child.leaseOwner(), child.fencingToken(), false);
        requireTransition(childMoved, child);
        int nextCount = mapper.selectArrivedCount(branch.groupId()) + 1;
        mapper.updateArrivedCount(nextCount, branch.groupId());
        if (nextCount < mapper.selectExpectedCount(branch.groupId())) return;

        Map<String, Object> aggregate = new LinkedHashMap<>();
        List<SoarRow.BranchExecutionRow> branchExecutions =
                mapper.selectBranchExecutions(branch.groupId());
        for (SoarRow.BranchExecutionRow item : branchExecutions) {
            aggregate.put(item.branchLabel(), nodeOutputs(item.executionId()));
        }
        String parentId = branch.parentExecutionId();
        List<String> parentRows = mapper.selectWaitingParentId(parentId);
        if (parentRows.isEmpty()) return;
        String parentRunId = mapper.selectGroupParentNodeRunId(branch.groupId());
        mapper.finishNodeRunNoGuard("success", json(aggregate), null, parentRunId);
        mapper.releaseGroup(json(aggregate), branch.groupId());
        mapper.releaseWaitingExecution(nextNodeId, parentId);
    }

    public record BranchTarget(String branch, String nodeId) {}

    public record ParallelBranch(
            String id,
            String groupId,
            String parentExecutionId,
            String joinNodeId,
            String branchLabel,
            String targetNodeId) {}

    private record BranchExecution(String branchLabel, String executionId) {}

    @Transactional
    public void startLoop(
            SoarExecution parent,
            SoarExecution.NodeRun parentRun,
            String bodyStartNodeId,
            String bodyEndNodeId,
            List<Object> items,
            int maxIterations) {
        requireLeaseForUpdate(parent);
        if (items == null || items.isEmpty()) throw new IllegalArgumentException("循环 items 不能为空");
        if (items.size() > maxIterations || maxIterations > 1000) {
            throw new IllegalArgumentException("循环迭代次数超过安全上限");
        }
        String loopId = "loop-" + UUID.randomUUID();
        String childId = "exec-" + UUID.randomUUID();
        SoarTriggerEnvelope trigger =
                new SoarTriggerEnvelope(
                        parent.triggerMessageId() + ":" + loopId,
                        parent.eventType(),
                        parent.triggerEnvelope().occurredAt(),
                        "loop:" + parent.id(),
                        parent.tenantId(),
                        parent.objectType(),
                        parent.objectId(),
                        loopPayload(parent.payloadSnapshot(), 0, items.getFirst()),
                        null);
        mapper.insertLoopChild(
                childId,
                parent.tenantId(),
                parent.playbookId(),
                parent.playbookName(),
                parent.playbookRevision(),
                json(parent.graphSnapshot()),
                parent.objectType(),
                parent.objectId(),
                parent.eventType(),
                trigger.messageId(),
                json(trigger),
                json(trigger.payload()),
                bodyStartNodeId,
                "loop:" + parent.id());
        finishNode(
                parentRun.id(),
                "success",
                Map.of("iterations", items.size(), "maxIterations", maxIterations),
                null);
        mapper.insertLoopState(
                loopId,
                parent.tenantId(),
                parent.id(),
                parentRun.id(),
                childId,
                bodyStartNodeId,
                bodyEndNodeId,
                json(items),
                maxIterations);
        int moved =
                mapper.markWaitingFarFuture(
                        Instant.now().plus(Duration.ofDays(365)),
                        parent.id(),
                        parent.leaseOwner(),
                        parent.fencingToken());
        requireTransition(moved, parent);
    }

    public LoopState loopState(String childExecutionId, String nodeId) {
        List<SoarRow.LoopStateRow> rows = mapper.selectLoopState(childExecutionId, nodeId);
        if (rows.isEmpty()) return null;
        SoarRow.LoopStateRow row = rows.getFirst();
        return new LoopState(
                row.id(),
                row.parentExecutionId(),
                row.parentNodeRunId(),
                row.childExecutionId(),
                row.bodyStartNodeId(),
                row.bodyEndNodeId(),
                read(row.itemsJson(), OBJECT_LIST),
                row.iterationIndex(),
                row.maxIterations(),
                row.status());
    }

    @Transactional
    public void advanceLoop(SoarExecution child, LoopState loop, String nextNodeId) {
        requireLeaseForUpdate(child);
        int nextIndex = loop.iterationIndex() + 1;
        if (nextIndex >= loop.items().size()) {
            int childMoved =
                    mapper.markSuccess(child.id(), child.leaseOwner(), child.fencingToken(), false);
            requireTransition(childMoved, child);
            String completion = json(Map.of("iterations", loop.items().size()));
            mapper.completeLoop(nextIndex, completion, loop.id());
            mapper.finishNodeRunNoGuard("success", completion, null, loop.parentNodeRunId());
            mapper.releaseWaitingExecution(nextNodeId, loop.parentExecutionId());
            return;
        }
        if (nextIndex >= loop.maxIterations()) {
            failLoop(child, loop, "循环超过 maxIterations 安全上限");
            return;
        }
        mapper.advanceLoopIndex(nextIndex, loop.id());
        Map<String, Object> payload =
                loopPayload(child.payloadSnapshot(), nextIndex, loop.items().get(nextIndex));
        int childMoved =
                mapper.updateChildPayload(
                        loop.bodyStartNodeId(),
                        json(payload),
                        child.id(),
                        child.leaseOwner(),
                        child.fencingToken());
        requireTransition(childMoved, child);
    }

    private void failLoop(SoarExecution child, LoopState loop, String message) {
        mapper.failExecution(message, child.id());
        mapper.markLoopFailed(json(Map.of("error", message)), loop.id());
        mapper.markFailedWaiting(message, loop.parentExecutionId());
    }

    private Map<String, Object> loopPayload(Map<String, Object> base, int index, Object item) {
        Map<String, Object> payload = new LinkedHashMap<>(base == null ? Map.of() : base);
        payload.put("loop", Map.of("index", index, "item", item));
        return payload;
    }

    public record LoopState(
            String id,
            String parentExecutionId,
            String parentNodeRunId,
            String childExecutionId,
            String bodyStartNodeId,
            String bodyEndNodeId,
            List<Object> items,
            int iterationIndex,
            int maxIterations,
            String status) {}

    /** Returns the execution for a playbook/request pair, including node history. */
    public SoarExecution findExecutionByTrigger(
            String tenantId, String playbookId, String messageId) {
        SoarRow.ExecutionRow row = mapper.selectExecutionByTrigger(tenantId, playbookId, messageId);
        if (row == null) throw new NotFoundException("SOAR 执行不存在: " + messageId);
        SoarExecution execution = mapExecution(row);
        return withNodeRuns(execution, listNodeRuns(execution.id()));
    }

    public List<SoarExecution> listExecutions(String tenantId, String status, int size) {
        int limit = Math.max(1, Math.min(size, 200));
        List<SoarRow.ExecutionRow> rows =
                (status == null || status.isBlank())
                        ? mapper.selectRootExecutions(tenantId, limit)
                        : mapper.selectRootExecutionsByStatus(tenantId, status, limit);
        return rows.stream().map(this::mapExecution).toList();
    }

    public SoarExecution getExecution(String tenantId, String id) {
        SoarRow.ExecutionRow row = mapper.selectExecutionByTenant(tenantId, id);
        if (row == null) throw new NotFoundException("SOAR 执行不存在: " + id);
        SoarExecution execution = mapExecution(row);
        return withNodeRuns(execution, listNodeRuns(id));
    }

    public SoarExecution getExecution(String id) {
        SoarRow.ExecutionRow row = mapper.selectExecution(id);
        if (row == null) throw new NotFoundException("SOAR 执行不存在: " + id);
        return mapExecution(row);
    }

    @Transactional
    public List<SoarExecution> claimDue(String owner, Duration lease, int batchSize) {
        int limit = Math.max(1, Math.min(batchSize, 100));
        List<String> candidates = mapper.selectClaimCandidates(limit * 3);
        List<SoarExecution> claimed = new ArrayList<>();
        Instant leaseUntil = Instant.now().plus(lease);
        for (String id : candidates) {
            int won = mapper.claimExecution(owner, leaseUntil, id);
            if (won == 1) claimed.add(getExecution(id));
            if (claimed.size() >= limit) break;
        }
        return claimed;
    }

    /** Extends only the exact lease generation returned by claimDue. */
    public boolean renewLease(SoarExecution execution, Duration lease) {
        if (execution.leaseOwner() == null || execution.leaseOwner().isBlank()) return false;
        int renewed =
                mapper.renewLease(
                        Instant.now().plus(lease),
                        execution.id(),
                        execution.leaseOwner(),
                        execution.fencingToken());
        return renewed == 1;
    }

    /** Rejects work that no longer owns the current, unexpired lease generation. */
    public void requireLease(SoarExecution execution) {
        if (!hasLease(execution, false)) throw new SoarLeaseLostException(execution.id());
    }

    public Map<String, Map<String, Object>> nodeOutputs(String executionId) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (SoarRow.NodeOutputRow row : mapper.selectNodeOutputs(executionId)) {
            result.put(row.nodeId(), map(row.outputJson()));
        }
        return result;
    }

    public SoarExecution.NodeRun resumableNodeRun(String executionId, String nodeId) {
        SoarRow.NodeRunRow row = mapper.selectResumableNodeRun(executionId, nodeId);
        return row == null ? null : mapNodeRun(row);
    }

    @Transactional
    public StartAttempt startNode(
            SoarExecution execution,
            PlaybookGraph.Node node,
            Map<String, Object> input,
            int maxAttempts) {
        requireLeaseForUpdate(execution);
        String executionId = execution.id();
        SoarRow.NodeRunRow latestRow = mapper.selectLatestNodeRun(executionId, node.id());
        SoarExecution.NodeRun latest = latestRow == null ? null : mapNodeRun(latestRow);
        boolean retry = latest != null && List.of("running", "retrying").contains(latest.status());
        if (retry && "running".equals(latest.status())) {
            mapper.markNodeRunRetrying(latest.id());
            latest = mapNodeRun(mapper.selectNodeRun(latest.id()));
        }
        if (retry && latest.attempt() >= maxAttempts) return new StartAttempt(latest, true);

        long nextSequence = mapper.selectNextSequence(executionId);
        int visit = retry ? latest.visitNo() : latest == null ? 1 : latest.visitNo() + 1;
        int attempt = retry ? latest.attempt() + 1 : 1;
        String idempotencyKey =
                retry
                        ? latest.idempotencyKey()
                        : "soar:" + executionId + ":" + node.id() + ":" + visit;
        String runId = "run-" + UUID.randomUUID();
        mapper.insertNodeRun(
                runId,
                executionId,
                node.id(),
                node.name(),
                node.type(),
                nextSequence,
                visit,
                attempt,
                idempotencyKey,
                json(input));
        return new StartAttempt(mapNodeRun(mapper.selectNodeRun(runId)), false);
    }

    @Transactional
    public void updateNodeInput(
            SoarExecution execution, String nodeRunId, Map<String, Object> input) {
        requireLeaseForUpdate(execution);
        int updated = mapper.updateNodeRunInput(json(input), nodeRunId, execution.id());
        if (updated != 1) throw new IllegalStateException("SOAR 节点执行状态已经变化: " + nodeRunId);
    }

    @Transactional
    public void advance(
            SoarExecution execution,
            String nodeRunId,
            Map<String, Object> output,
            String nextNodeId) {
        int moved =
                mapper.advancePending(
                        nextNodeId,
                        execution.id(),
                        execution.leaseOwner(),
                        execution.fencingToken());
        requireTransition(moved, execution);
        finishNode(nodeRunId, "success", output, null);
    }

    @Transactional
    public void succeed(SoarExecution execution, String nodeRunId, Map<String, Object> output) {
        int moved =
                mapper.markSuccess(
                        execution.id(), execution.leaseOwner(), execution.fencingToken(), true);
        requireTransition(moved, execution);
        finishNode(nodeRunId, "success", output, null);
    }

    @Transactional
    public void fail(SoarExecution execution, String nodeRunId, String error) {
        int moved =
                mapper.markFailed(
                        error, execution.id(), execution.leaseOwner(), execution.fencingToken());
        requireTransition(moved, execution);
        if (nodeRunId != null) finishNode(nodeRunId, "failed", Map.of(), error);
        propagateInternalFailure(execution.id(), error);
    }

    @Transactional
    public void scheduleRetry(
            SoarExecution execution, String nodeRunId, String error, Instant nextAttemptAt) {
        int moved =
                mapper.scheduleRetry(
                        error,
                        nextAttemptAt,
                        execution.id(),
                        execution.leaseOwner(),
                        execution.fencingToken());
        requireTransition(moved, execution);
        finishNode(nodeRunId, "retrying", Map.of(), error);
    }

    @Transactional
    public void waitUntil(SoarExecution execution, String nodeRunId, Instant until) {
        int moved =
                mapper.markWaitingUntil(
                        until, execution.id(), execution.leaseOwner(), execution.fencingToken());
        requireTransition(moved, execution);
        int updated = mapper.setNodeRunWaiting(nodeRunId, execution.id());
        if (updated != 1) throw new IllegalStateException("SOAR 节点执行状态已经变化: " + nodeRunId);
    }

    @Transactional
    public SoarApproval createApproval(
            SoarExecution execution,
            PlaybookGraph.Node node,
            SoarExecution.NodeRun nodeRun,
            String prompt) {
        String id = "approval-" + UUID.randomUUID();
        int moved =
                mapper.markWaitingHuman(
                        execution.id(), execution.leaseOwner(), execution.fencingToken());
        requireTransition(moved, execution);
        mapper.insertApproval(
                id,
                execution.tenantId(),
                execution.id(),
                nodeRun.id(),
                node.id(),
                execution.playbookId(),
                execution.playbookName(),
                execution.objectType(),
                execution.objectId(),
                prompt);
        int updated = mapper.setNodeRunWaitingHuman(nodeRun.id(), execution.id());
        if (updated != 1) throw new IllegalStateException("SOAR 节点执行状态已经变化: " + nodeRun.id());
        return getApproval(execution.tenantId(), id);
    }

    public record StartAttempt(SoarExecution.NodeRun run, boolean exhausted) {}

    public List<SoarApproval> listApprovals(String tenantId, String status, int size) {
        int limit = Math.max(1, Math.min(size, 200));
        List<SoarRow.ApprovalRow> rows =
                (status == null || status.isBlank())
                        ? mapper.selectApprovals(tenantId, limit)
                        : mapper.selectApprovalsByStatus(tenantId, status, limit);
        return rows.stream().map(this::mapApproval).toList();
    }

    public SoarApproval getApproval(String tenantId, String id) {
        SoarRow.ApprovalRow row = mapper.selectApproval(tenantId, id);
        if (row == null) throw new NotFoundException("审批不存在: " + id);
        return mapApproval(row);
    }

    @Transactional
    public SoarApproval decideApproval(
            String tenantId,
            String id,
            String decision,
            String actor,
            String note,
            String nextNodeId) {
        if (!List.of("approved", "rejected").contains(decision)) {
            throw new IllegalArgumentException("审批决定仅支持 approved 或 rejected");
        }
        SoarApproval approval = getApproval(tenantId, id);
        if (!"pending".equals(approval.status())) throw new ConflictException("该审批已处理");
        int updated = mapper.decideApproval(decision, actor, note, tenantId, id);
        if (updated == 0) throw new ConflictException("该审批已被其他分析师处理");
        finishNode(
                approval.nodeRunId(),
                "success",
                Map.of("decision", decision, "actor", actor, "note", note == null ? "" : note),
                null);
        int moved = mapper.resumeWaitingHuman(nextNodeId, approval.executionId());
        if (moved == 0) {
            throw new ConflictException("执行已取消或状态已经变化，审批结果未生效");
        }
        return getApproval(tenantId, id);
    }

    @Transactional
    public void requestCancel(String tenantId, String id) {
        SoarExecution current = getExecution(tenantId, id);
        if (List.of("success", "failed", "cancelled").contains(current.status())) {
            throw new ConflictException("终态执行不能取消");
        }
        if ("INTERNAL".equals(current.triggerType())) {
            throw new ConflictException("内部并行/循环执行不能单独取消，请取消根执行实例");
        }
        cancelExecutionTree(id);
    }

    /** Cancels an internal subtree without touching a shared parent/join target. */
    private void cancelExecutionTree(String rootId) {
        Set<String> tree = new LinkedHashSet<>();
        List<String> frontier = List.of(rootId);
        while (!frontier.isEmpty()) {
            List<String> next = new ArrayList<>();
            for (String parent : frontier) {
                for (String child : mapper.selectActiveChildren(parent)) {
                    if (tree.add(child)) next.add(child);
                }
            }
            frontier = next;
        }
        tree.add(rootId);
        for (String executionId : tree) {
            mapper.cancelAll(executionId);
            mapper.cancelNodeRuns(executionId);
            mapper.cancelPendingApprovals(executionId);
            mapper.markBranchCancelledAll(executionId);
            mapper.markGroupCancelledWaiting(executionId);
            mapper.markLoopCancelled(executionId);
        }
    }

    /** A failed internal branch/body must never leave its durable parent waiting forever. */
    private void propagateInternalFailure(String childExecutionId, String cause) {
        String parentId = failParallelParent(childExecutionId, cause);
        if (parentId == null) parentId = failLoopParent(childExecutionId, cause);
        if (parentId != null) propagateInternalFailure(parentId, cause);
    }

    private String failParallelParent(String childExecutionId, String cause) {
        SoarRow.ParallelFailureParentRow parent =
                mapper.selectParallelBranchForUpdate(childExecutionId);
        if (parent == null) return null;
        String error = "并行分支 " + parent.branchLabel() + " 失败: " + cause;
        for (String sibling : mapper.selectSiblingBranches(parent.groupId(), childExecutionId)) {
            cancelExecutionTree(sibling);
        }
        mapper.cancelBranchesOfGroup(parent.groupId());
        mapper.markGroupCancelledById(parent.groupId());
        mapper.failParentNodeRun(error, parent.parentNodeRunId());
        int moved = mapper.markFailedWaiting(error, parent.parentExecutionId());
        return moved == 1 ? parent.parentExecutionId() : null;
    }

    private String failLoopParent(String childExecutionId, String cause) {
        SoarRow.LoopFailureParentRow parent = mapper.selectLoopFailureParent(childExecutionId);
        if (parent == null) return null;
        String error = "循环体失败: " + cause;
        mapper.markLoopFailed(json(Map.of("error", error)), parent.id());
        mapper.failParentNodeRun(error, parent.parentNodeRunId());
        int moved = mapper.markFailedWaiting(error, parent.parentExecutionId());
        return moved == 1 ? parent.parentExecutionId() : null;
    }

    private void finishNode(
            String nodeRunId, String status, Map<String, Object> output, String error) {
        int updated = mapper.finishNodeRun(status, json(output), error, nodeRunId);
        if (updated != 1) throw new IllegalStateException("SOAR 节点执行状态已经变化: " + nodeRunId);
    }

    private void requireLeaseForUpdate(SoarExecution execution) {
        if (!hasLease(execution, true)) throw new SoarLeaseLostException(execution.id());
    }

    private boolean hasLease(SoarExecution execution, boolean lock) {
        if (execution.leaseOwner() == null || execution.leaseOwner().isBlank()) return false;
        List<String> rows =
                mapper.selectLeaseHolders(
                        execution.id(), execution.leaseOwner(), execution.fencingToken(), lock);
        return rows.size() == 1;
    }

    private void requireTransition(int moved, SoarExecution execution) {
        if (moved != 1) throw new SoarLeaseLostException(execution.id());
    }

    private List<SoarExecution.NodeRun> listNodeRuns(String executionId) {
        return mapper.selectNodeRuns(executionId).stream().map(this::mapNodeRun).toList();
    }

    private SoarExecution withNodeRuns(SoarExecution value, List<SoarExecution.NodeRun> runs) {
        return new SoarExecution(
                value.id(),
                value.tenantId(),
                value.playbookId(),
                value.playbookName(),
                value.playbookRevision(),
                value.graphSnapshot(),
                value.objectType(),
                value.objectId(),
                value.eventType(),
                value.triggerType(),
                value.triggerMessageId(),
                value.triggerEnvelope(),
                value.payloadSnapshot(),
                value.status(),
                value.currentNodeId(),
                value.nextRunAt(),
                value.error(),
                value.actor(),
                value.cancelRequested(),
                value.leaseOwner(),
                value.leaseExpiresAt(),
                value.fencingToken(),
                value.createdAt(),
                value.updatedAt(),
                value.startedAt(),
                value.finishedAt(),
                runs);
    }

    private SoarPlaybook mapPlaybook(SoarRow.PlaybookRow row) {
        return new SoarPlaybook(
                row.id(),
                row.tenantId(),
                row.name(),
                row.description(),
                row.status(),
                row.enabled(),
                row.entryType(),
                strings(row.eventTypesJson()),
                read(row.graphJson(), PlaybookGraph.class),
                row.revision(),
                row.createdBy(),
                row.updatedBy(),
                row.createdAt(),
                row.updatedAt(),
                row.publishedAt());
    }

    private SoarExecution mapExecution(SoarRow.ExecutionRow row) {
        Map<String, Object> payload = map(row.payloadSnapshot());
        SoarTriggerEnvelope trigger = triggerEnvelope(row, payload);
        return new SoarExecution(
                row.id(),
                row.tenantId(),
                row.playbookId(),
                row.playbookName(),
                row.playbookRevision(),
                read(row.graphSnapshot(), PlaybookGraph.class),
                row.objectType(),
                row.objectId(),
                row.eventType(),
                row.triggerType(),
                row.triggerMessageId(),
                trigger,
                payload,
                row.status(),
                row.currentNodeId(),
                row.nextRunAt(),
                row.error(),
                row.actor(),
                row.cancelRequested(),
                row.leaseOwner(),
                row.leaseExpiresAt(),
                row.version(),
                row.createdAt(),
                row.updatedAt(),
                row.startedAt(),
                row.finishedAt(),
                List.of());
    }

    private SoarApproval mapApproval(SoarRow.ApprovalRow row) {
        return new SoarApproval(
                row.id(),
                row.tenantId(),
                row.executionId(),
                row.nodeRunId(),
                row.nodeId(),
                row.playbookId(),
                row.playbookName(),
                row.objectType(),
                row.objectId(),
                row.prompt(),
                row.status(),
                row.decidedBy(),
                row.decisionNote(),
                row.createdAt(),
                row.decidedAt());
    }

    private SoarExecution.NodeRun mapNodeRun(SoarRow.NodeRunRow row) {
        return new SoarExecution.NodeRun(
                row.id(),
                row.executionId(),
                row.nodeId(),
                row.nodeName(),
                row.nodeType(),
                row.status(),
                row.sequenceNo(),
                row.visitNo(),
                row.attempt(),
                row.tokenId(),
                row.idempotencyKey(),
                mapNullable(row.inputJson()),
                mapNullable(row.outputJson()),
                row.error(),
                row.startedAt(),
                row.finishedAt());
    }

    private SoarTriggerEnvelope triggerEnvelope(
            SoarRow.ExecutionRow row, Map<String, Object> payload) {
        String value = row.triggerEnvelope();
        if (value != null && !value.isBlank()) return read(value, SoarTriggerEnvelope.class);
        return new SoarTriggerEnvelope(
                row.triggerMessageId(),
                row.eventType(),
                row.createdAt(),
                "legacy",
                row.tenantId(),
                row.objectType(),
                row.objectId(),
                payload,
                null);
    }

    private void distinguishMissingOrConflict(String tenantId, String id) {
        int count = mapper.countLivePlaybook(tenantId, id);
        if (count == 0) throw new NotFoundException("Playbook 不存在: " + id);
        throw new ConflictException("Playbook 已被其他用户修改，请刷新后重试");
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("SOAR 数据无法序列化", e);
        }
    }

    private <T> T read(String value, Class<T> type) {
        try {
            return objectMapper.readValue(value, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("数据库中的 SOAR 数据格式错误", e);
        }
    }

    private <T> T read(String value, TypeReference<T> type) {
        try {
            return objectMapper.readValue(value, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("数据库中的 SOAR 数据格式错误", e);
        }
    }

    private List<String> strings(String value) {
        try {
            return objectMapper.readValue(value, STRING_LIST);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("数据库中的事件类型格式错误", e);
        }
    }

    private Map<String, Object> map(String value) {
        try {
            return objectMapper.readValue(value, OBJECT_MAP);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("数据库中的 SOAR JSON 格式错误", e);
        }
    }

    private Map<String, Object> mapNullable(String value) {
        return value == null ? null : map(value);
    }
}
