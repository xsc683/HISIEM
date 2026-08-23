package com.xscsiem.hsiem_platform.soar;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.context.annotation.DependsOn;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** PostgreSQL 中的 SOAR 图执行、Worker 租约、审批和时间线。 */
@Repository
@DependsOn("flyway")
public class JdbcSoarExecutionStore implements SoarExecutionStore {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {};
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public JdbcSoarExecutionStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean create(SoarExecution execution) {
        try {
            jdbc.update("""
                    INSERT INTO soar_executions(id, playbook_id, playbook_version, resource_type, resource_id,
                        status, actor, current_step, current_node, frontier_json, playbook_snapshot_json,
                        context_json, trigger_type, dedup_key, next_run_at, created_at, updated_at,
                        tenant_id, parent_execution_id, parent_node_id)
                    VALUES (?, ?, ?, ?, ?, 'queued', ?, 0, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, execution.id(), execution.playbookId(), execution.playbookVersion(),
                    execution.resourceType(), execution.resourceId(), execution.actor(),
                    execution.currentNode(), json(execution.frontier()), json(execution.playbookSnapshot()),
                    json(execution.context()), execution.triggerType(), execution.dedupKey(),
                    timestamp(execution.nextRunAt()), timestamp(execution.createdAt()),
                    timestamp(execution.updatedAt()), execution.tenantId(),
                    execution.parentExecutionId(), execution.parentNodeId());
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    @Override
    public SoarExecution find(String id) {
        List<SoarExecution> rows = jdbc.query("SELECT * FROM soar_executions WHERE id = ?",
                (rs, rowNum) -> execution(rs), id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public List<SoarExecution> list(String tenantId, int size) {
        int limit = Math.min(Math.max(size, 1), 200);
        return jdbc.query("SELECT * FROM soar_executions WHERE tenant_id = ? ORDER BY updated_at DESC LIMIT ?",
                (rs, rowNum) -> execution(rs), tenantId, limit);
    }

    @Override
    @Transactional
    public SoarExecution claimNext(String owner, Instant now, Instant leaseUntil) {
        List<String> candidates = jdbc.queryForList("""
                SELECT id FROM soar_executions
                WHERE status = 'queued' AND next_run_at <= ?
                  AND (lease_expires_at IS NULL OR lease_expires_at < ?)
                ORDER BY next_run_at, created_at LIMIT 20
                """, String.class, timestamp(now), timestamp(now));
        for (String id : candidates) {
            int changed = jdbc.update("""
                    UPDATE soar_executions SET status = 'running', lease_owner = ?, lease_expires_at = ?,
                        updated_at = ?, version = version + 1
                    WHERE id = ? AND status = 'queued' AND next_run_at <= ?
                      AND (lease_expires_at IS NULL OR lease_expires_at < ?)
                    """, owner, timestamp(leaseUntil), timestamp(now), id, timestamp(now), timestamp(now));
            if (changed == 1) return find(id);
        }
        return null;
    }

    @Override
    public boolean heartbeat(String executionId, String owner, Instant leaseUntil) {
        return jdbc.update("""
                UPDATE soar_executions SET lease_expires_at = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status = 'running' AND lease_owner = ?
                """, timestamp(leaseUntil), executionId, owner) == 1;
    }

    @Override
    public SoarStepExecution findStep(String executionId, String stepId) {
        List<SoarStepExecution> rows = jdbc.query("""
                SELECT * FROM soar_step_executions WHERE execution_id = ? AND step_id = ?
                """, (rs, rowNum) -> step(rs), executionId, stepId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public List<SoarStepExecution> listSteps(String executionId) {
        return jdbc.query("""
                SELECT * FROM soar_step_executions WHERE execution_id = ? ORDER BY step_index, scheduled_at
                """, (rs, rowNum) -> step(rs), executionId);
    }

    @Override
    public int startNode(String executionId, int index, SoarPlaybook.Node node,
                         int maxAttempts, Map<String, Object> input) {
        Instant now = Instant.now();
        int changed = jdbc.update("""
                UPDATE soar_step_executions SET status = 'running', attempt = attempt + 1,
                    max_attempts = ?, input_json = ?, output_json = NULL, error = NULL,
                    scheduled_at = ?, started_at = ?, finished_at = NULL, duration_ms = NULL
                WHERE execution_id = ? AND step_id = ? AND status IN ('failed', 'retrying')
                """, maxAttempts, json(input), timestamp(now), timestamp(now), executionId, node.id());
        if (changed == 0) {
            jdbc.update("""
                    INSERT INTO soar_step_executions(execution_id, step_id, step_index, step_name, action,
                        node_type, status, attempt, max_attempts, input_json, scheduled_at, started_at)
                    VALUES (?, ?, ?, ?, ?, ?, 'running', 1, ?, ?, ?, ?)
                    """, executionId, node.id(), index, node.name(),
                    node.action() == null ? node.type() : node.action(), node.type(), maxAttempts,
                    json(input), timestamp(now), timestamp(now));
        }
        SoarStepExecution result = findStep(executionId, node.id());
        return result == null ? 1 : result.attempt();
    }

    @Override
    public void finishNode(String executionId, String stepId, String status,
                           Map<String, Object> output, String error) {
        SoarStepExecution current = findStep(executionId, stepId);
        Instant now = Instant.now();
        Long duration = current == null || current.startedAt() == null ? null
                : Math.max(0, Duration.between(current.startedAt(), now).toMillis());
        jdbc.update("""
                UPDATE soar_step_executions SET status = ?, output_json = ?, error = ?,
                    finished_at = ?, duration_ms = ?
                WHERE execution_id = ? AND step_id = ? AND status = 'running'
                """, status, output == null ? null : json(output), error, timestamp(now), duration,
                executionId, stepId);
    }

    @Override
    public void finishWaitingNode(String executionId, String stepId, String status,
                                  Map<String, Object> output, String error) {
        Instant now = Instant.now();
        requireUpdate(jdbc.update("""
                UPDATE soar_step_executions SET status = ?, output_json = ?, error = ?,
                    finished_at = ?, duration_ms = 0
                WHERE execution_id = ? AND step_id = ? AND status = 'waiting_child'
                """, status, output == null ? null : json(output), error, timestamp(now),
                executionId, stepId), "子 Playbook 等待节点结果更新失败");
    }

    @Override
    public void waitForChild(String executionId, String stepId, Map<String, Object> output) {
        requireUpdate(jdbc.update("""
                UPDATE soar_step_executions SET status = 'waiting_child', output_json = ?,
                    finished_at = NULL, duration_ms = NULL
                WHERE execution_id = ? AND step_id = ? AND status = 'running'
                """, json(output), executionId, stepId), "子 Playbook 节点进入等待失败");
    }

    @Override
    public void resetNodes(String executionId, List<String> stepIds) {
        if (stepIds == null || stepIds.isEmpty()) return;
        String placeholders = String.join(",", java.util.Collections.nCopies(stepIds.size(), "?"));
        List<Object> arguments = new java.util.ArrayList<>();
        arguments.add(executionId);
        arguments.addAll(stepIds);
        jdbc.update("DELETE FROM soar_step_executions WHERE execution_id = ? AND step_id IN ("
                + placeholders + ")", arguments.toArray());
    }

    @Override
    public void saveProgress(String executionId, String owner, List<String> frontier,
                             String currentNode, Map<String, Object> context, int nodesExecuted) {
        requireUpdate(jdbc.update("""
                UPDATE soar_executions SET frontier_json = ?, current_node = ?, context_json = ?,
                    current_step = ?, nodes_executed = ?, updated_at = CURRENT_TIMESTAMP,
                    version = version + 1
                WHERE id = ? AND status = 'running' AND lease_owner = ?
                """, json(frontier), currentNode, json(context), nodesExecuted, nodesExecuted,
                executionId, owner), "保存 SOAR 执行进度失败");
    }

    @Override
    public void release(String executionId, String owner, List<String> frontier, String currentNode,
                        Map<String, Object> context, int nodesExecuted, Instant nextRunAt, String error) {
        requireUpdate(jdbc.update("""
                UPDATE soar_executions SET status = 'queued', frontier_json = ?, current_node = ?,
                    context_json = ?, current_step = ?, nodes_executed = ?, next_run_at = ?, error = ?,
                    lease_owner = NULL, lease_expires_at = NULL, updated_at = CURRENT_TIMESTAMP,
                    version = version + 1
                WHERE id = ? AND status = 'running' AND lease_owner = ?
                """, json(frontier), currentNode, json(context), nodesExecuted, nodesExecuted,
                timestamp(nextRunAt), error, executionId, owner), "释放 SOAR Worker 租约失败");
    }

    @Override
    @Transactional
    public void waitForApproval(String executionId, String owner, String stepId, String message,
                                List<String> frontier, Map<String, Object> context, int nodesExecuted) {
        requireUpdate(jdbc.update("""
                UPDATE soar_step_executions SET status = 'waiting_approval'
                WHERE execution_id = ? AND step_id = ? AND status = 'running'
                """, executionId, stepId), "审批节点状态更新失败");
        requireUpdate(jdbc.update("""
                UPDATE soar_executions SET status = 'waiting_approval', frontier_json = ?,
                    current_node = ?, context_json = ?, current_step = ?, nodes_executed = ?,
                    approval_step_id = ?, approval_message = ?, lease_owner = NULL,
                    lease_expires_at = NULL, updated_at = CURRENT_TIMESTAMP, version = version + 1
                WHERE id = ? AND status = 'running' AND lease_owner = ?
                """, json(frontier), stepId, json(context), nodesExecuted, nodesExecuted,
                stepId, message, executionId, owner), "执行进入审批等待失败");
    }

    @Override
    @Transactional
    public boolean resolveApproval(String executionId, String stepId, boolean approved, String actor,
                                   List<String> frontier, Map<String, Object> context,
                                   boolean continueExecution) {
        String stepStatus = approved ? "succeeded" : "rejected";
        Instant now = Instant.now();
        String status = continueExecution ? "queued" : approved ? "succeeded" : "rejected";
        int changed = jdbc.update("""
                UPDATE soar_executions SET status = ?, frontier_json = ?, current_node = ?,
                    context_json = ?, approved_by = ?, approval_step_id = NULL,
                    approval_message = NULL, next_run_at = ?, updated_at = ?,
                    finished_at = CASE WHEN ? THEN NULL ELSE ? END, version = version + 1
                WHERE id = ? AND status = 'waiting_approval' AND approval_step_id = ?
                """, status, json(frontier), frontier.isEmpty() ? null : frontier.get(0), json(context),
                actor, timestamp(now), timestamp(now), continueExecution, timestamp(now), executionId, stepId);
        if (changed == 0) return false;
        requireUpdate(jdbc.update("""
                UPDATE soar_step_executions SET status = ?, output_json = ?, finished_at = ?,
                    duration_ms = CASE WHEN started_at IS NULL THEN NULL ELSE 0 END
                WHERE execution_id = ? AND step_id = ? AND status = 'waiting_approval'
                """, stepStatus, json(Map.of("decision", approved ? "approved" : "rejected",
                "actor", actor)), timestamp(now), executionId, stepId), "审批节点结果更新失败");
        return true;
    }

    @Override
    public void finishExecution(String executionId, String owner, String status, String error,
                                Map<String, Object> context, int nodesExecuted) {
        String ownerClause = owner == null ? "" : " AND lease_owner = ?";
        String sql = """
                UPDATE soar_executions SET status = ?, error = ?, context_json = ?, current_step = ?,
                    nodes_executed = ?, current_node = NULL, frontier_json = '[]',
                    lease_owner = NULL, lease_expires_at = NULL, updated_at = CURRENT_TIMESTAMP,
                    finished_at = CURRENT_TIMESTAMP, version = version + 1
                WHERE id = ? AND status IN ('running', 'queued')
                """ + ownerClause;
        int changed = owner == null
                ? jdbc.update(sql, status, error, json(context), nodesExecuted, nodesExecuted, executionId)
                : jdbc.update(sql, status, error, json(context), nodesExecuted, nodesExecuted,
                        executionId, owner);
        requireUpdate(changed, "SOAR 执行完成状态更新失败");
    }

    @Override
    @Transactional
    public boolean prepareRetry(String executionId) {
        List<String> failedNodes = jdbc.queryForList("""
                SELECT step_id FROM soar_step_executions
                WHERE execution_id = ? AND status = 'failed' ORDER BY step_index, scheduled_at
                """, String.class, executionId);
        if (failedNodes.isEmpty()) return false;
        int changed = jdbc.update("""
                UPDATE soar_executions SET status = 'queued', error = NULL, finished_at = NULL,
                    frontier_json = ?, current_node = ?, next_run_at = CURRENT_TIMESTAMP,
                    cancel_requested = FALSE, pause_requested = FALSE,
                    updated_at = CURRENT_TIMESTAMP, version = version + 1
                WHERE id = ? AND status = 'failed'
                """, json(failedNodes), failedNodes.get(0), executionId);
        if (changed == 0) return false;
        jdbc.update("""
                UPDATE soar_step_executions SET status = 'retrying', attempt = 0,
                    error = '用户请求重新执行失败节点', finished_at = NULL, duration_ms = NULL
                WHERE execution_id = ? AND status = 'failed'
                """, executionId);
        return true;
    }

    @Override
    public boolean requestCancel(String executionId) {
        int terminal = jdbc.update("""
                UPDATE soar_executions SET status = 'cancelled', cancel_requested = TRUE,
                    approval_step_id = NULL, approval_message = NULL, finished_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP, version = version + 1
                WHERE id = ? AND status IN ('queued', 'waiting_approval', 'paused')
                """, executionId);
        if (terminal == 1) {
            jdbc.update("""
                    UPDATE soar_step_executions SET status = 'cancelled', finished_at = CURRENT_TIMESTAMP
                    WHERE execution_id = ? AND status IN ('running', 'waiting_approval', 'waiting_child', 'retrying')
                    """, executionId);
            cancelDescendants(executionId);
            return true;
        }
        boolean requested = jdbc.update("""
                UPDATE soar_executions SET cancel_requested = TRUE, updated_at = CURRENT_TIMESTAMP,
                    version = version + 1 WHERE id = ? AND status = 'running'
                """, executionId) == 1;
        if (requested) cancelDescendants(executionId);
        return requested;
    }

    @Override
    public boolean requestPause(String executionId) {
        int queued = jdbc.update("""
                UPDATE soar_executions SET status = 'paused', pause_requested = TRUE,
                    updated_at = CURRENT_TIMESTAMP, version = version + 1
                WHERE id = ? AND status = 'queued'
                """, executionId);
        if (queued == 1) return true;
        return jdbc.update("""
                UPDATE soar_executions SET pause_requested = TRUE, updated_at = CURRENT_TIMESTAMP,
                    version = version + 1 WHERE id = ? AND status = 'running'
                """, executionId) == 1;
    }

    @Override
    public boolean resume(String executionId) {
        return jdbc.update("""
                UPDATE soar_executions SET status = 'queued', pause_requested = FALSE,
                    next_run_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP,
                    version = version + 1 WHERE id = ? AND status = 'paused'
                """, executionId) == 1;
    }

    @Override
    @Transactional
    public int recoverExpiredLeases(Instant now) {
        jdbc.update("""
                UPDATE soar_step_executions SET status = 'retrying', error = 'Worker 租约过期，节点将恢复执行'
                WHERE status = 'running' AND execution_id IN (
                    SELECT id FROM soar_executions WHERE status = 'running' AND lease_expires_at < ?)
                """, timestamp(now));
        return jdbc.update("""
                UPDATE soar_executions SET status = 'queued', lease_owner = NULL, lease_expires_at = NULL,
                    next_run_at = ?, error = 'Worker 租约过期，执行已重新入队',
                    updated_at = ?, version = version + 1
                WHERE status = 'running' AND lease_expires_at < ?
                """, timestamp(now), timestamp(now), timestamp(now));
    }

    @Override
    public void appendEvent(String executionId, String eventType, String nodeId,
                            String actor, Map<String, Object> details) {
        jdbc.update("""
                INSERT INTO soar_execution_events(execution_id, event_type, node_id, actor, details_json)
                VALUES (?, ?, ?, ?, ?)
                """, executionId, eventType, nodeId, actor == null ? "soar-worker" : actor,
                json(details == null ? Map.of() : details));
    }

    @Override
    public List<SoarExecutionEvent> listEvents(String executionId) {
        return jdbc.query("""
                SELECT * FROM soar_execution_events WHERE execution_id = ? ORDER BY sequence
                """, (rs, rowNum) -> new SoarExecutionEvent(rs.getLong("sequence"),
                rs.getString("execution_id"), rs.getString("event_type"), rs.getString("node_id"),
                rs.getString("actor"), readMap(rs.getString("details_json")),
                instant(rs.getTimestamp("created_at"))), executionId);
    }

    private void cancelDescendants(String parentId) {
        List<String> pending = new java.util.ArrayList<>(List.of(parentId));
        for (int depth = 0; depth < 5 && !pending.isEmpty(); depth++) {
            List<String> next = new java.util.ArrayList<>();
            for (String current : pending) {
                List<String> children = jdbc.queryForList("""
                        SELECT id FROM soar_executions WHERE parent_execution_id = ?
                        """, String.class, current);
                for (String child : children) {
                    jdbc.update("""
                            UPDATE soar_executions SET status = CASE WHEN status = 'running' THEN status ELSE 'cancelled' END,
                                cancel_requested = TRUE, finished_at = CASE WHEN status = 'running' THEN finished_at ELSE CURRENT_TIMESTAMP END,
                                updated_at = CURRENT_TIMESTAMP, version = version + 1
                            WHERE id = ? AND status IN ('queued', 'running', 'waiting_approval', 'paused')
                            """, child);
                    jdbc.update("""
                            UPDATE soar_step_executions SET status = 'cancelled', finished_at = CURRENT_TIMESTAMP
                            WHERE execution_id = ? AND status IN
                              ('running', 'waiting_approval', 'waiting_child', 'retrying')
                            """, child);
                    next.add(child);
                }
            }
            pending = next;
        }
    }

    private SoarExecution execution(ResultSet rs) throws SQLException {
        return new SoarExecution(
                rs.getString("id"), rs.getString("playbook_id"), rs.getString("playbook_version"),
                rs.getString("resource_type"), rs.getString("resource_id"), rs.getString("status"),
                rs.getString("actor"), rs.getInt("current_step"), rs.getString("current_node"),
                readStrings(rs.getString("frontier_json")),
                read(rs.getString("playbook_snapshot_json"), SoarPlaybook.class),
                readMap(rs.getString("context_json")), rs.getString("trigger_type"),
                rs.getString("dedup_key"), rs.getString("approval_step_id"),
                rs.getString("approval_message"), rs.getString("approved_by"), rs.getString("error"),
                instant(rs.getTimestamp("next_run_at")), rs.getString("lease_owner"),
                instant(rs.getTimestamp("lease_expires_at")), rs.getBoolean("cancel_requested"),
                rs.getBoolean("pause_requested"), rs.getInt("nodes_executed"),
                instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("updated_at")),
                instant(rs.getTimestamp("finished_at")), rs.getLong("version"),
                rs.getString("tenant_id"), rs.getString("parent_execution_id"),
                rs.getString("parent_node_id"), List.of());
    }

    private SoarStepExecution step(ResultSet rs) throws SQLException {
        long duration = rs.getLong("duration_ms");
        return new SoarStepExecution(
                rs.getString("execution_id"), rs.getString("step_id"), rs.getInt("step_index"),
                rs.getString("step_name"), rs.getString("action"), rs.getString("node_type"),
                rs.getString("status"), rs.getInt("attempt"), rs.getInt("max_attempts"),
                readMap(rs.getString("input_json")), readMap(rs.getString("output_json")),
                rs.getString("error"), instant(rs.getTimestamp("scheduled_at")),
                instant(rs.getTimestamp("started_at")), instant(rs.getTimestamp("finished_at")),
                rs.wasNull() ? null : duration);
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("SOAR JSON 序列化失败", e);
        }
    }

    private Map<String, Object> readMap(String value) {
        if (value == null || value.isBlank()) return Map.of();
        try {
            return mapper.readValue(value, MAP);
        } catch (Exception e) {
            throw new IllegalStateException("SOAR JSON 反序列化失败", e);
        }
    }

    private List<String> readStrings(String value) {
        if (value == null || value.isBlank()) return List.of();
        try {
            return mapper.readValue(value, STRINGS);
        } catch (Exception e) {
            throw new IllegalStateException("SOAR frontier 反序列化失败", e);
        }
    }

    private <T> T read(String value, Class<T> type) {
        try {
            return mapper.readValue(value, type);
        } catch (Exception e) {
            throw new IllegalStateException("SOAR 快照反序列化失败", e);
        }
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static void requireUpdate(int changed, String message) {
        if (changed != 1) throw new IllegalStateException(message);
    }
}
