package com.xscsiem.hsiem_platform.soar;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** PostgreSQL 中的 SOAR 执行、审批和步骤日志实现。 */
@Repository
@DependsOn("flyway")
public class JdbcSoarExecutionStore implements SoarExecutionStore {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public JdbcSoarExecutionStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void create(SoarExecution execution) {
        jdbc.update("""
                INSERT INTO soar_executions(id, playbook_id, playbook_version, resource_type, resource_id,
                    status, actor, current_step, playbook_snapshot_json, context_json, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'queued', ?, 0, ?, ?, ?, ?)
                """, execution.id(), execution.playbookId(), execution.playbookVersion(),
                execution.resourceType(), execution.resourceId(), execution.actor(),
                json(execution.playbookSnapshot()), json(execution.context()),
                timestamp(execution.createdAt()), timestamp(execution.updatedAt()));
    }

    @Override
    public SoarExecution find(String id) {
        List<SoarExecution> rows = jdbc.query("SELECT * FROM soar_executions WHERE id = ?",
                (rs, rowNum) -> execution(rs), id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public List<SoarExecution> list(int size) {
        int limit = Math.min(Math.max(size, 1), 200);
        return jdbc.query("SELECT * FROM soar_executions ORDER BY updated_at DESC LIMIT ?",
                (rs, rowNum) -> execution(rs), limit);
    }

    @Override
    public boolean claimQueued(String id) {
        return jdbc.update("""
                UPDATE soar_executions SET status = 'running', updated_at = CURRENT_TIMESTAMP, version = version + 1
                WHERE id = ? AND status = 'queued'
                """, id) == 1;
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
                SELECT * FROM soar_step_executions WHERE execution_id = ? ORDER BY step_index
                """, (rs, rowNum) -> step(rs), executionId);
    }

    @Override
    public void startStep(String executionId, int index, SoarPlaybook.Step step,
                          Map<String, Object> input) {
        int changed = jdbc.update("""
                UPDATE soar_step_executions SET status = 'running', input_json = ?, output_json = NULL,
                    error = NULL, started_at = CURRENT_TIMESTAMP, finished_at = NULL
                WHERE execution_id = ? AND step_id = ? AND status = 'failed'
                """, json(input), executionId, step.id());
        if (changed == 0) {
            jdbc.update("""
                    INSERT INTO soar_step_executions(execution_id, step_id, step_index, step_name, action,
                        status, input_json, started_at)
                    VALUES (?, ?, ?, ?, ?, 'running', ?, CURRENT_TIMESTAMP)
                    """, executionId, step.id(), index, step.name(), step.action(), json(input));
        }
    }

    @Override
    public void finishStep(String executionId, String stepId, String status,
                           Map<String, Object> output, String error) {
        jdbc.update("""
                UPDATE soar_step_executions SET status = ?, output_json = ?, error = ?,
                    finished_at = CURRENT_TIMESTAMP WHERE execution_id = ? AND step_id = ? AND status = 'running'
                """, status, output == null ? null : json(output), error, executionId, stepId);
    }

    @Override
    public void advance(String executionId, int nextStep) {
        jdbc.update("""
                UPDATE soar_executions SET current_step = ?, updated_at = CURRENT_TIMESTAMP, version = version + 1
                WHERE id = ? AND status = 'running'
                """, nextStep, executionId);
    }

    @Override
    @Transactional
    public void waitForApproval(String executionId, int stepIndex, String stepId, String message) {
        jdbc.update("""
                UPDATE soar_step_executions SET status = 'waiting_approval' WHERE execution_id = ? AND step_id = ?
                """, executionId, stepId);
        jdbc.update("""
                UPDATE soar_executions SET status = 'waiting_approval', current_step = ?,
                    approval_step_id = ?, approval_message = ?, updated_at = CURRENT_TIMESTAMP, version = version + 1
                WHERE id = ? AND status = 'running'
                """, stepIndex, stepId, message, executionId);
    }

    @Override
    @Transactional
    public boolean resolveApproval(String executionId, String stepId, boolean approved, String actor) {
        String stepStatus = approved ? "succeeded" : "rejected";
        int stepChanged = jdbc.update("""
                UPDATE soar_step_executions SET status = ?, output_json = ?, finished_at = CURRENT_TIMESTAMP
                WHERE execution_id = ? AND step_id = ? AND status = 'waiting_approval'
                """, stepStatus, json(Map.of("decision", approved ? "approved" : "rejected", "actor", actor)),
                executionId, stepId);
        if (stepChanged == 0) return false;
        String executionStatus = approved ? "queued" : "rejected";
        int currentIncrement = approved ? 1 : 0;
        jdbc.update("""
                UPDATE soar_executions SET status = ?, current_step = current_step + ?, approved_by = ?,
                    approval_step_id = NULL, approval_message = NULL, updated_at = CURRENT_TIMESTAMP,
                    finished_at = CASE WHEN ? = 'rejected' THEN CURRENT_TIMESTAMP ELSE NULL END,
                    version = version + 1
                WHERE id = ? AND status = 'waiting_approval'
                """, executionStatus, currentIncrement, actor, executionStatus, executionId);
        return true;
    }

    @Override
    public void finishExecution(String executionId, String status, String error) {
        jdbc.update("""
                UPDATE soar_executions SET status = ?, error = ?, updated_at = CURRENT_TIMESTAMP,
                    finished_at = CURRENT_TIMESTAMP, version = version + 1 WHERE id = ? AND status = 'running'
                """, status, error, executionId);
    }

    @Override
    public boolean prepareRetry(String executionId) {
        return jdbc.update("""
                UPDATE soar_executions SET status = 'queued', error = NULL, finished_at = NULL,
                    updated_at = CURRENT_TIMESTAMP, version = version + 1
                WHERE id = ? AND status = 'failed'
                """, executionId) == 1;
    }

    @Override
    @Transactional
    public int recoverStale(Instant cutoff) {
        jdbc.update("""
                UPDATE soar_step_executions SET status = 'failed', error = '服务重启或执行超时，可人工重试',
                    finished_at = CURRENT_TIMESTAMP
                WHERE status = 'running' AND execution_id IN (
                    SELECT id FROM soar_executions WHERE status IN ('queued', 'running') AND updated_at < ?)
                """, timestamp(cutoff));
        return jdbc.update("""
                UPDATE soar_executions SET status = 'failed', error = '服务重启或执行超时，可人工重试',
                    updated_at = CURRENT_TIMESTAMP, finished_at = CURRENT_TIMESTAMP, version = version + 1
                WHERE status IN ('queued', 'running') AND updated_at < ?
                """, timestamp(cutoff));
    }

    private SoarExecution execution(ResultSet rs) throws SQLException {
        return new SoarExecution(
                rs.getString("id"), rs.getString("playbook_id"), rs.getString("playbook_version"),
                rs.getString("resource_type"), rs.getString("resource_id"), rs.getString("status"),
                rs.getString("actor"), rs.getInt("current_step"),
                read(rs.getString("playbook_snapshot_json"), SoarPlaybook.class),
                readMap(rs.getString("context_json")), rs.getString("approval_step_id"),
                rs.getString("approval_message"), rs.getString("approved_by"), rs.getString("error"),
                instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("updated_at")),
                instant(rs.getTimestamp("finished_at")), rs.getLong("version"), List.of());
    }

    private SoarStepExecution step(ResultSet rs) throws SQLException {
        return new SoarStepExecution(
                rs.getString("execution_id"), rs.getString("step_id"), rs.getInt("step_index"),
                rs.getString("step_name"), rs.getString("action"), rs.getString("status"),
                readMap(rs.getString("input_json")), readMap(rs.getString("output_json")),
                rs.getString("error"), instant(rs.getTimestamp("started_at")),
                instant(rs.getTimestamp("finished_at")));
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
}
