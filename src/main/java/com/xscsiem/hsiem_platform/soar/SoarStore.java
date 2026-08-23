package com.xscsiem.hsiem_platform.soar;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xscsiem.hsiem_platform.onboarding.ConflictException;
import com.xscsiem.hsiem_platform.onboarding.NotFoundException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class SoarStore {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };
    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() { };
    private static final String PLAYBOOK_COLUMNS = "id, tenant_id, name, description, status, enabled, "
            + "entry_type, event_types_json, graph_json, revision, created_by, updated_by, "
            + "created_at, updated_at, published_at";
    private static final String EXECUTION_COLUMNS = "id, tenant_id, playbook_id, playbook_name, "
            + "playbook_revision, graph_snapshot, object_type, object_id, event_type, trigger_message_id, "
            + "trigger_envelope, payload_snapshot, status, current_node_id, next_run_at, error, actor, cancel_requested, "
            + "lease_owner, lease_expires_at, version, "
            + "created_at, updated_at, started_at, finished_at";

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final RowMapper<SoarPlaybook> playbookMapper = this::mapPlaybook;
    private final RowMapper<SoarExecution> executionMapper = this::mapExecution;
    private final RowMapper<SoarApproval> approvalMapper = this::mapApproval;

    public SoarStore(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public List<SoarPlaybook> listPlaybooks(String tenantId) {
        return jdbc.query("SELECT " + PLAYBOOK_COLUMNS + " FROM soar_playbook "
                        + "WHERE tenant_id = ? AND deleted_at IS NULL ORDER BY updated_at DESC",
                playbookMapper, tenantId);
    }

    public SoarPlaybook getPlaybook(String tenantId, String id) {
        List<SoarPlaybook> rows = jdbc.query("SELECT " + PLAYBOOK_COLUMNS + " FROM soar_playbook "
                        + "WHERE tenant_id = ? AND id = ? AND deleted_at IS NULL",
                playbookMapper, tenantId, id);
        if (rows.isEmpty()) throw new NotFoundException("Playbook 不存在: " + id);
        return rows.getFirst();
    }

    public List<SoarPlaybook> matchingPlaybooks(String tenantId, String objectType, String eventType) {
        return jdbc.query("SELECT " + PLAYBOOK_COLUMNS + " FROM soar_playbook "
                        + "WHERE tenant_id = ? AND entry_type = ? AND status = 'published' "
                        + "AND enabled = TRUE AND deleted_at IS NULL",
                playbookMapper, tenantId, objectType).stream()
                .filter(item -> item.eventTypes().contains(eventType))
                .toList();
    }

    @Transactional
    public SoarPlaybook createPlaybook(String tenantId, String name, String description,
                                       String entryType, List<String> eventTypes,
                                       PlaybookGraph graph, String actor) {
        String id = "pb-" + UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("INSERT INTO soar_playbook (id, tenant_id, name, description, status, enabled, "
                        + "entry_type, event_types_json, graph_json, revision, created_by, updated_by, "
                        + "created_at, updated_at) VALUES (?, ?, ?, ?, 'draft', FALSE, ?, ?, ?, 1, ?, ?, ?, ?)",
                id, tenantId, name, description == null ? "" : description, entryType,
                json(eventTypes), json(graph), actor, actor, Timestamp.from(now), Timestamp.from(now));
        return getPlaybook(tenantId, id);
    }

    @Transactional
    public SoarPlaybook updatePlaybook(String tenantId, String id, String name, String description,
                                       String entryType, List<String> eventTypes, PlaybookGraph graph,
                                       long expectedRevision, String actor) {
        int updated = jdbc.update("UPDATE soar_playbook SET name = ?, description = ?, entry_type = ?, "
                        + "event_types_json = ?, graph_json = ?, status = 'draft', enabled = FALSE, "
                        + "revision = revision + 1, updated_by = ?, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE tenant_id = ? AND id = ? AND revision = ? AND deleted_at IS NULL",
                name, description == null ? "" : description, entryType, json(eventTypes), json(graph), actor,
                tenantId, id, expectedRevision);
        if (updated == 0) distinguishMissingOrConflict(tenantId, id);
        return getPlaybook(tenantId, id);
    }

    @Transactional
    public SoarPlaybook publishPlaybook(String tenantId, String id, long expectedRevision, String actor) {
        int updated = jdbc.update("UPDATE soar_playbook SET status = 'published', enabled = TRUE, "
                        + "revision = revision + 1, updated_by = ?, updated_at = CURRENT_TIMESTAMP, "
                        + "published_at = CURRENT_TIMESTAMP WHERE tenant_id = ? AND id = ? "
                        + "AND revision = ? AND deleted_at IS NULL",
                actor, tenantId, id, expectedRevision);
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
        jdbc.update("UPDATE soar_playbook SET status = ?, enabled = ?, revision = revision + 1, "
                        + "updated_by = ?, updated_at = CURRENT_TIMESTAMP WHERE tenant_id = ? AND id = ?",
                status, enabled, actor, tenantId, id);
        return getPlaybook(tenantId, id);
    }

    @Transactional
    public void deletePlaybook(String tenantId, String id, String actor) {
        getPlaybook(tenantId, id);
        Integer active = jdbc.queryForObject("SELECT COUNT(*) FROM soar_execution WHERE tenant_id = ? "
                        + "AND playbook_id = ? AND status IN ('pending','running','waiting','waiting_human')",
                Integer.class, tenantId, id);
        if (active != null && active > 0) {
            throw new ConflictException("Playbook 存在活动执行，需先取消执行实例");
        }
        jdbc.update("UPDATE soar_playbook SET status = 'disabled', enabled = FALSE, deleted_at = CURRENT_TIMESTAMP, "
                        + "updated_by = ?, updated_at = CURRENT_TIMESTAMP WHERE tenant_id = ? AND id = ?",
                actor, tenantId, id);
    }

    @Transactional
    public boolean createExecution(SoarPlaybook playbook, SoarTriggerEnvelope trigger) {
        String id = "exec-" + UUID.randomUUID();
        String startNode = playbook.graph().nodes().stream()
                .filter(node -> "start".equals(node.type())).findFirst().orElseThrow().id();
        try {
            jdbc.update("INSERT INTO soar_execution (id, tenant_id, playbook_id, playbook_name, "
                            + "playbook_revision, graph_snapshot, object_type, object_id, event_type, "
                            + "trigger_message_id, trigger_envelope, payload_snapshot, status, current_node_id, actor) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'pending', ?, ?)",
                    id, playbook.tenantId(), playbook.id(), playbook.name(), playbook.revision(),
                    json(playbook.graph()), playbook.entryType(), trigger.objectId(), trigger.eventType(),
                    trigger.messageId(), json(trigger), json(trigger.payload()), startNode,
                    "kafka:" + trigger.messageId());
            return true;
        } catch (DuplicateKeyException ignored) {
            return false;
        }
    }

    public List<SoarExecution> listExecutions(String tenantId, String status, int size) {
        int limit = Math.max(1, Math.min(size, 200));
        if (status == null || status.isBlank()) {
            return jdbc.query("SELECT " + EXECUTION_COLUMNS + " FROM soar_execution "
                            + "WHERE tenant_id = ? ORDER BY updated_at DESC FETCH FIRST ? ROWS ONLY",
                    executionMapper, tenantId, limit);
        }
        return jdbc.query("SELECT " + EXECUTION_COLUMNS + " FROM soar_execution "
                        + "WHERE tenant_id = ? AND status = ? ORDER BY updated_at DESC FETCH FIRST ? ROWS ONLY",
                executionMapper, tenantId, status, limit);
    }

    public SoarExecution getExecution(String tenantId, String id) {
        List<SoarExecution> rows = jdbc.query("SELECT " + EXECUTION_COLUMNS + " FROM soar_execution "
                        + "WHERE tenant_id = ? AND id = ?", executionMapper, tenantId, id);
        if (rows.isEmpty()) throw new NotFoundException("SOAR 执行不存在: " + id);
        SoarExecution execution = rows.getFirst();
        return withNodeRuns(execution, listNodeRuns(id));
    }

    public SoarExecution getExecution(String id) {
        List<SoarExecution> rows = jdbc.query("SELECT " + EXECUTION_COLUMNS
                + " FROM soar_execution WHERE id = ?", executionMapper, id);
        if (rows.isEmpty()) throw new NotFoundException("SOAR 执行不存在: " + id);
        return rows.getFirst();
    }

    @Transactional
    public List<SoarExecution> claimDue(String owner, Duration lease, int batchSize) {
        int limit = Math.max(1, Math.min(batchSize, 100));
        List<String> candidates = jdbc.queryForList("SELECT id FROM soar_execution WHERE "
                        + "status IN ('pending','running','waiting') AND next_run_at <= CURRENT_TIMESTAMP "
                        + "AND (lease_expires_at IS NULL OR lease_expires_at < CURRENT_TIMESTAMP) "
                        + "ORDER BY next_run_at, created_at FETCH FIRST ? ROWS ONLY", String.class, limit * 3);
        List<SoarExecution> claimed = new ArrayList<>();
        Timestamp leaseUntil = Timestamp.from(Instant.now().plus(lease));
        for (String id : candidates) {
            int won = jdbc.update("UPDATE soar_execution SET lease_owner = ?, lease_expires_at = ?, "
                            + "status = 'running', started_at = COALESCE(started_at, CURRENT_TIMESTAMP), "
                            + "updated_at = CURRENT_TIMESTAMP, version = version + 1 WHERE id = ? "
                            + "AND status IN ('pending','running','waiting') "
                            + "AND (lease_expires_at IS NULL OR lease_expires_at < CURRENT_TIMESTAMP)",
                    owner, leaseUntil, id);
            if (won == 1) claimed.add(getExecution(id));
            if (claimed.size() >= limit) break;
        }
        return claimed;
    }

    /** Extends only the exact lease generation returned by claimDue. */
    public boolean renewLease(SoarExecution execution, Duration lease) {
        if (execution.leaseOwner() == null || execution.leaseOwner().isBlank()) return false;
        int renewed = jdbc.update("UPDATE soar_execution SET lease_expires_at = ?, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE id = ? AND status = 'running' AND cancel_requested = FALSE "
                        + "AND lease_owner = ? AND version = ? AND lease_expires_at >= CURRENT_TIMESTAMP",
                Timestamp.from(Instant.now().plus(lease)), execution.id(),
                execution.leaseOwner(), execution.fencingToken());
        return renewed == 1;
    }

    /** Rejects work that no longer owns the current, unexpired lease generation. */
    public void requireLease(SoarExecution execution) {
        if (!hasLease(execution, false)) throw new SoarLeaseLostException(execution.id());
    }

    public Map<String, Map<String, Object>> nodeOutputs(String executionId) {
        Map<String, Map<String, Object>> result = new java.util.LinkedHashMap<>();
        jdbc.query("SELECT node_id, output_json FROM soar_node_execution WHERE execution_id = ? "
                        + "AND status = 'success' AND output_json IS NOT NULL ORDER BY sequence_no", rs -> {
                    result.put(rs.getString("node_id"), map(rs.getString("output_json")));
                }, executionId);
        return result;
    }

    public SoarExecution.NodeRun resumableNodeRun(String executionId, String nodeId) {
        List<SoarExecution.NodeRun> rows = jdbc.query("SELECT * FROM soar_node_execution "
                        + "WHERE execution_id = ? AND node_id = ? AND status = 'waiting' "
                        + "ORDER BY sequence_no DESC FETCH FIRST 1 ROWS ONLY",
                this::mapNodeRun, executionId, nodeId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    @Transactional
    public StartAttempt startNode(SoarExecution execution, PlaybookGraph.Node node,
                                  Map<String, Object> input, int maxAttempts) {
        requireLeaseForUpdate(execution);
        String executionId = execution.id();
        List<SoarExecution.NodeRun> rows = jdbc.query("SELECT * FROM soar_node_execution "
                        + "WHERE execution_id = ? AND node_id = ? ORDER BY sequence_no DESC "
                        + "FETCH FIRST 1 ROWS ONLY", this::mapNodeRun, executionId, node.id());
        SoarExecution.NodeRun latest = rows.isEmpty() ? null : rows.getFirst();
        boolean retry = latest != null && List.of("running", "retrying").contains(latest.status());
        if (retry && "running".equals(latest.status())) {
            jdbc.update("UPDATE soar_node_execution SET status = 'retrying', "
                            + "error = COALESCE(error, '工作租约过期，重新执行该节点'), "
                            + "finished_at = COALESCE(finished_at, CURRENT_TIMESTAMP) WHERE id = ?",
                    latest.id());
            latest = jdbc.queryForObject("SELECT * FROM soar_node_execution WHERE id = ?",
                    this::mapNodeRun, latest.id());
        }
        if (retry && latest.attempt() >= maxAttempts) return new StartAttempt(latest, true);

        Long nextSequence = jdbc.queryForObject("SELECT COALESCE(MAX(sequence_no), 0) + 1 "
                + "FROM soar_node_execution WHERE execution_id = ?", Long.class, executionId);
        int visit = retry ? latest.visitNo() : latest == null ? 1 : latest.visitNo() + 1;
        int attempt = retry ? latest.attempt() + 1 : 1;
        String idempotencyKey = retry ? latest.idempotencyKey()
                : "soar:" + executionId + ":" + node.id() + ":" + visit;
        String runId = "run-" + UUID.randomUUID();
        jdbc.update("INSERT INTO soar_node_execution (id, execution_id, node_id, node_name, node_type, "
                        + "status, sequence_no, visit_no, attempt, token_id, idempotency_key, input_json) "
                        + "VALUES (?, ?, ?, ?, ?, 'running', ?, ?, ?, 'root', ?, ?)",
                runId, executionId, node.id(), node.name(), node.type(), nextSequence,
                visit, attempt, idempotencyKey, json(input));
        return new StartAttempt(jdbc.queryForObject("SELECT * FROM soar_node_execution WHERE id = ?",
                this::mapNodeRun, runId), false);
    }

    @Transactional
    public void updateNodeInput(SoarExecution execution, String nodeRunId, Map<String, Object> input) {
        requireLeaseForUpdate(execution);
        int updated = jdbc.update("UPDATE soar_node_execution SET input_json = ? "
                        + "WHERE id = ? AND execution_id = ? AND status = 'running'",
                json(input), nodeRunId, execution.id());
        if (updated != 1) throw new IllegalStateException("SOAR 节点执行状态已经变化: " + nodeRunId);
    }

    @Transactional
    public void advance(SoarExecution execution, String nodeRunId, Map<String, Object> output, String nextNodeId) {
        int moved = jdbc.update("UPDATE soar_execution SET status = 'pending', current_node_id = ?, next_run_at = CURRENT_TIMESTAMP, "
                        + "lease_owner = NULL, lease_expires_at = NULL, updated_at = CURRENT_TIMESTAMP, version = version + 1 "
                        + "WHERE id = ? AND status = 'running' AND cancel_requested = FALSE "
                        + "AND lease_owner = ? AND version = ? AND lease_expires_at >= CURRENT_TIMESTAMP",
                nextNodeId, execution.id(), execution.leaseOwner(), execution.fencingToken());
        requireTransition(moved, execution);
        finishNode(nodeRunId, "success", output, null);
    }

    @Transactional
    public void succeed(SoarExecution execution, String nodeRunId, Map<String, Object> output) {
        int moved = jdbc.update("UPDATE soar_execution SET status = 'success', lease_owner = NULL, lease_expires_at = NULL, "
                        + "updated_at = CURRENT_TIMESTAMP, finished_at = CURRENT_TIMESTAMP, version = version + 1 "
                        + "WHERE id = ? AND status = 'running' AND cancel_requested = FALSE "
                        + "AND lease_owner = ? AND version = ? AND lease_expires_at >= CURRENT_TIMESTAMP",
                execution.id(), execution.leaseOwner(), execution.fencingToken());
        requireTransition(moved, execution);
        finishNode(nodeRunId, "success", output, null);
    }

    @Transactional
    public void fail(SoarExecution execution, String nodeRunId, String error) {
        int moved = jdbc.update("UPDATE soar_execution SET status = 'failed', error = ?, lease_owner = NULL, "
                        + "lease_expires_at = NULL, updated_at = CURRENT_TIMESTAMP, finished_at = CURRENT_TIMESTAMP, "
                        + "version = version + 1 WHERE id = ? AND status = 'running' "
                        + "AND cancel_requested = FALSE AND lease_owner = ? AND version = ? "
                        + "AND lease_expires_at >= CURRENT_TIMESTAMP",
                error, execution.id(), execution.leaseOwner(), execution.fencingToken());
        requireTransition(moved, execution);
        if (nodeRunId != null) finishNode(nodeRunId, "failed", Map.of(), error);
    }

    @Transactional
    public void scheduleRetry(SoarExecution execution, String nodeRunId, String error, Instant nextAttemptAt) {
        int moved = jdbc.update("UPDATE soar_execution SET status = 'pending', error = ?, next_run_at = ?, "
                        + "lease_owner = NULL, lease_expires_at = NULL, updated_at = CURRENT_TIMESTAMP, "
                        + "version = version + 1 WHERE id = ? AND status = 'running' "
                        + "AND cancel_requested = FALSE AND lease_owner = ? AND version = ? "
                        + "AND lease_expires_at >= CURRENT_TIMESTAMP",
                error, Timestamp.from(nextAttemptAt), execution.id(), execution.leaseOwner(), execution.fencingToken());
        requireTransition(moved, execution);
        finishNode(nodeRunId, "retrying", Map.of(), error);
    }

    @Transactional
    public void waitUntil(SoarExecution execution, String nodeRunId, Instant until) {
        int moved = jdbc.update("UPDATE soar_execution SET status = 'waiting', next_run_at = ?, lease_owner = NULL, "
                        + "lease_expires_at = NULL, updated_at = CURRENT_TIMESTAMP, version = version + 1 "
                        + "WHERE id = ? AND status = 'running' AND cancel_requested = FALSE "
                        + "AND lease_owner = ? AND version = ? AND lease_expires_at >= CURRENT_TIMESTAMP",
                Timestamp.from(until), execution.id(), execution.leaseOwner(), execution.fencingToken());
        requireTransition(moved, execution);
        int updated = jdbc.update("UPDATE soar_node_execution SET status = 'waiting' "
                        + "WHERE id = ? AND execution_id = ? AND status = 'running'",
                nodeRunId, execution.id());
        if (updated != 1) throw new IllegalStateException("SOAR 节点执行状态已经变化: " + nodeRunId);
    }

    @Transactional
    public SoarApproval createApproval(SoarExecution execution, PlaybookGraph.Node node,
                                       SoarExecution.NodeRun nodeRun, String prompt) {
        String id = "approval-" + UUID.randomUUID();
        int moved = jdbc.update("UPDATE soar_execution SET status = 'waiting_human', lease_owner = NULL, "
                        + "lease_expires_at = NULL, updated_at = CURRENT_TIMESTAMP, version = version + 1 WHERE id = ? "
                        + "AND status = 'running' AND cancel_requested = FALSE AND lease_owner = ? AND version = ? "
                        + "AND lease_expires_at >= CURRENT_TIMESTAMP",
                execution.id(), execution.leaseOwner(), execution.fencingToken());
        requireTransition(moved, execution);
        jdbc.update("INSERT INTO soar_approval_task (id, tenant_id, execution_id, node_run_id, node_id, "
                        + "playbook_id, playbook_name, object_type, object_id, prompt) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, execution.tenantId(), execution.id(), nodeRun.id(), node.id(), execution.playbookId(),
                execution.playbookName(), execution.objectType(), execution.objectId(), prompt);
        int updated = jdbc.update("UPDATE soar_node_execution SET status = 'waiting_human' "
                        + "WHERE id = ? AND execution_id = ? AND status = 'running'",
                nodeRun.id(), execution.id());
        if (updated != 1) throw new IllegalStateException("SOAR 节点执行状态已经变化: " + nodeRun.id());
        return getApproval(execution.tenantId(), id);
    }

    public record StartAttempt(SoarExecution.NodeRun run, boolean exhausted) { }

    public List<SoarApproval> listApprovals(String tenantId, String status, int size) {
        int limit = Math.max(1, Math.min(size, 200));
        if (status == null || status.isBlank()) {
            return jdbc.query("SELECT * FROM soar_approval_task WHERE tenant_id = ? "
                            + "ORDER BY created_at DESC FETCH FIRST ? ROWS ONLY", approvalMapper, tenantId, limit);
        }
        return jdbc.query("SELECT * FROM soar_approval_task WHERE tenant_id = ? AND status = ? "
                        + "ORDER BY created_at DESC FETCH FIRST ? ROWS ONLY", approvalMapper, tenantId, status, limit);
    }

    public SoarApproval getApproval(String tenantId, String id) {
        List<SoarApproval> rows = jdbc.query("SELECT * FROM soar_approval_task WHERE tenant_id = ? AND id = ?",
                approvalMapper, tenantId, id);
        if (rows.isEmpty()) throw new NotFoundException("审批不存在: " + id);
        return rows.getFirst();
    }

    @Transactional
    public SoarApproval decideApproval(String tenantId, String id, String decision,
                                       String actor, String note, String nextNodeId) {
        if (!List.of("approved", "rejected").contains(decision)) {
            throw new IllegalArgumentException("审批决定仅支持 approved 或 rejected");
        }
        SoarApproval approval = getApproval(tenantId, id);
        if (!"pending".equals(approval.status())) throw new ConflictException("该审批已处理");
        int updated = jdbc.update("UPDATE soar_approval_task SET status = ?, decided_by = ?, decision_note = ?, "
                        + "decided_at = CURRENT_TIMESTAMP WHERE tenant_id = ? AND id = ? AND status = 'pending'",
                decision, actor, note, tenantId, id);
        if (updated == 0) throw new ConflictException("该审批已被其他分析师处理");
        finishNode(approval.nodeRunId(), "success",
                Map.of("decision", decision, "actor", actor, "note", note == null ? "" : note), null);
        int moved = jdbc.update("UPDATE soar_execution SET status = 'pending', current_node_id = ?, next_run_at = CURRENT_TIMESTAMP, "
                        + "lease_owner = NULL, lease_expires_at = NULL, updated_at = CURRENT_TIMESTAMP, "
                        + "version = version + 1 WHERE id = ? AND status = 'waiting_human'",
                nextNodeId, approval.executionId());
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
        jdbc.update("UPDATE soar_execution SET cancel_requested = TRUE, status = 'cancelled', "
                        + "lease_owner = NULL, lease_expires_at = NULL, updated_at = CURRENT_TIMESTAMP, "
                        + "finished_at = CURRENT_TIMESTAMP, version = version + 1 WHERE tenant_id = ? AND id = ?",
                tenantId, id);
        jdbc.update("UPDATE soar_node_execution SET status = 'cancelled', finished_at = CURRENT_TIMESTAMP "
                        + "WHERE execution_id = ? AND status IN ('running','waiting','waiting_human','retrying')", id);
        jdbc.update("UPDATE soar_approval_task SET status = 'cancelled', decided_at = CURRENT_TIMESTAMP "
                        + "WHERE execution_id = ? AND status = 'pending'", id);
    }

    private void finishNode(String nodeRunId, String status,
                            Map<String, Object> output, String error) {
        int updated = jdbc.update("UPDATE soar_node_execution SET status = ?, output_json = ?, error = ?, "
                        + "finished_at = CURRENT_TIMESTAMP WHERE id = ? "
                        + "AND status IN ('running','waiting','waiting_human','retrying')",
                status, json(output), error, nodeRunId);
        if (updated != 1) throw new IllegalStateException("SOAR 节点执行状态已经变化: " + nodeRunId);
    }

    private void requireLeaseForUpdate(SoarExecution execution) {
        if (!hasLease(execution, true)) throw new SoarLeaseLostException(execution.id());
    }

    private boolean hasLease(SoarExecution execution, boolean lock) {
        if (execution.leaseOwner() == null || execution.leaseOwner().isBlank()) return false;
        String suffix = lock ? " FOR UPDATE" : "";
        List<String> rows = jdbc.queryForList("SELECT id FROM soar_execution WHERE id = ? "
                        + "AND status = 'running' AND cancel_requested = FALSE AND lease_owner = ? "
                        + "AND version = ? AND lease_expires_at >= CURRENT_TIMESTAMP" + suffix,
                String.class, execution.id(), execution.leaseOwner(), execution.fencingToken());
        return rows.size() == 1;
    }

    private void requireTransition(int moved, SoarExecution execution) {
        if (moved != 1) throw new SoarLeaseLostException(execution.id());
    }

    private List<SoarExecution.NodeRun> listNodeRuns(String executionId) {
        return jdbc.query("SELECT * FROM soar_node_execution WHERE execution_id = ? ORDER BY sequence_no",
                this::mapNodeRun, executionId);
    }

    private SoarExecution withNodeRuns(SoarExecution value, List<SoarExecution.NodeRun> runs) {
        return new SoarExecution(value.id(), value.tenantId(), value.playbookId(), value.playbookName(),
                value.playbookRevision(), value.graphSnapshot(), value.objectType(), value.objectId(), value.eventType(),
                value.triggerMessageId(), value.triggerEnvelope(), value.payloadSnapshot(), value.status(),
                value.currentNodeId(), value.nextRunAt(), value.error(), value.actor(), value.cancelRequested(),
                value.leaseOwner(), value.leaseExpiresAt(), value.fencingToken(),
                value.createdAt(), value.updatedAt(), value.startedAt(), value.finishedAt(), runs);
    }

    private SoarPlaybook mapPlaybook(ResultSet rs, int rowNum) throws SQLException {
        return new SoarPlaybook(rs.getString("id"), rs.getString("tenant_id"), rs.getString("name"),
                rs.getString("description"), rs.getString("status"), rs.getBoolean("enabled"),
                rs.getString("entry_type"), strings(rs.getString("event_types_json")),
                read(rs.getString("graph_json"), PlaybookGraph.class), rs.getLong("revision"),
                rs.getString("created_by"), rs.getString("updated_by"), instant(rs, "created_at"),
                instant(rs, "updated_at"), instant(rs, "published_at"));
    }

    private SoarExecution mapExecution(ResultSet rs, int rowNum) throws SQLException {
        Map<String, Object> payload = map(rs.getString("payload_snapshot"));
        SoarTriggerEnvelope trigger = triggerEnvelope(rs, payload);
        return new SoarExecution(rs.getString("id"), rs.getString("tenant_id"), rs.getString("playbook_id"),
                rs.getString("playbook_name"), rs.getLong("playbook_revision"),
                read(rs.getString("graph_snapshot"), PlaybookGraph.class), rs.getString("object_type"),
                rs.getString("object_id"), rs.getString("event_type"), rs.getString("trigger_message_id"),
                trigger, payload, rs.getString("status"), rs.getString("current_node_id"), instant(rs, "next_run_at"),
                rs.getString("error"), rs.getString("actor"),
                rs.getBoolean("cancel_requested"), rs.getString("lease_owner"), instant(rs, "lease_expires_at"),
                rs.getLong("version"), instant(rs, "created_at"), instant(rs, "updated_at"),
                instant(rs, "started_at"), instant(rs, "finished_at"), List.of());
    }

    private SoarApproval mapApproval(ResultSet rs, int rowNum) throws SQLException {
        return new SoarApproval(rs.getString("id"), rs.getString("tenant_id"), rs.getString("execution_id"),
                rs.getString("node_run_id"), rs.getString("node_id"), rs.getString("playbook_id"),
                rs.getString("playbook_name"),
                rs.getString("object_type"), rs.getString("object_id"), rs.getString("prompt"),
                rs.getString("status"), rs.getString("decided_by"), rs.getString("decision_note"),
                instant(rs, "created_at"), instant(rs, "decided_at"));
    }

    private SoarExecution.NodeRun mapNodeRun(ResultSet rs, int rowNum) throws SQLException {
        return new SoarExecution.NodeRun(rs.getString("id"), rs.getString("execution_id"),
                rs.getString("node_id"), rs.getString("node_name"), rs.getString("node_type"),
                rs.getString("status"), rs.getLong("sequence_no"), rs.getInt("visit_no"),
                rs.getInt("attempt"), rs.getString("token_id"), rs.getString("idempotency_key"),
                mapNullable(rs.getString("input_json")), mapNullable(rs.getString("output_json")),
                rs.getString("error"), instant(rs, "started_at"), instant(rs, "finished_at"));
    }

    private SoarTriggerEnvelope triggerEnvelope(ResultSet rs, Map<String, Object> payload) throws SQLException {
        String value = rs.getString("trigger_envelope");
        if (value != null && !value.isBlank()) return read(value, SoarTriggerEnvelope.class);
        return new SoarTriggerEnvelope(rs.getString("trigger_message_id"), rs.getString("event_type"),
                instant(rs, "created_at"), "legacy", rs.getString("tenant_id"), rs.getString("object_type"),
                rs.getString("object_id"), payload, null);
    }

    private void distinguishMissingOrConflict(String tenantId, String id) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM soar_playbook WHERE tenant_id = ? "
                + "AND id = ? AND deleted_at IS NULL", Integer.class, tenantId, id);
        if (count == null || count == 0) throw new NotFoundException("Playbook 不存在: " + id);
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

    private Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
