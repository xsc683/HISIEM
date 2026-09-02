package com.xscsiem.hsiem_platform.rules.runtime;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** JDBC persistence boundary for desired assignments and observed runtime state. */
@Repository
public class DetectionRuntimeRepository {

    private final JdbcTemplate jdbc;

    public DetectionRuntimeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Set<String> findAssignmentGroups(String tenantId, String ruleKey) {
        return jdbc
                .queryForList(
                        """
                SELECT group_key FROM rule_job_assignment
                WHERE tenant_id = ? AND rule_key = ?
                """,
                        String.class,
                        tenantId,
                        ruleKey)
                .stream()
                .collect(Collectors.toSet());
    }

    public List<DetectionJobGroupRow> findGroupRows(String tenantId) {
        return jdbc.query(
                """
                SELECT tenant_id, group_key, target_cluster, source_family, category, bucket,
                       desired_generation, expected_manifest_json, expected_manifest_hash, status,
                       job_id, job_key, last_error, created_at, updated_at
                FROM detection_job_group WHERE tenant_id = ? ORDER BY group_key
                """,
                this::groupRow,
                tenantId);
    }

    public DetectionJobGroupRow findGroupRow(String tenantId, String groupKey) {
        return findGroupRow(tenantId, groupKey, null);
    }

    public DetectionJobGroupRow findGroupRow(
            String tenantId, String groupKey, String targetCluster) {
        String sql =
                targetCluster == null
                        ? """
                SELECT tenant_id, group_key, target_cluster, source_family, category, bucket,
                       desired_generation, expected_manifest_json, expected_manifest_hash, status,
                       job_id, job_key, last_error, created_at, updated_at
                FROM detection_job_group WHERE tenant_id = ? AND group_key = ?
                """
                        : """
                SELECT tenant_id, group_key, target_cluster, source_family, category, bucket,
                       desired_generation, expected_manifest_json, expected_manifest_hash, status,
                       job_id, job_key, last_error, created_at, updated_at
                FROM detection_job_group WHERE tenant_id = ? AND group_key = ? AND target_cluster = ?
                """;
        List<DetectionJobGroupRow> rows =
                targetCluster == null
                        ? jdbc.query(sql, this::groupRow, tenantId, groupKey)
                        : jdbc.query(sql, this::groupRow, tenantId, groupKey, targetCluster);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public List<DesiredDetectionRow> findDesiredRunningRows(
            String tenantId, String compilerVersion) {
        return jdbc.query(
                """
                SELECT d.deployment_id, d.tenant_id, d.rule_key, d.desired_revision_id,
                       d.generation AS deployment_generation, d.target_cluster,
                       r.revision, p.plan_id, p.plan_hash, p.plan_json
                FROM rule_deployment d
                JOIN rule_revision r ON r.revision_id = d.desired_revision_id
                JOIN detection_plan p ON p.revision_id = r.revision_id AND p.compiler_version = ?
                WHERE d.tenant_id = ? AND d.desired_state = 'RUNNING'
                ORDER BY d.rule_key
                """,
                this::desiredRow,
                compilerVersion,
                tenantId);
    }

    public RuleDeploymentRow findDeploymentRow(String tenantId, String ruleKey) {
        List<RuleDeploymentRow> rows =
                jdbc.query(
                        """
                SELECT deployment_id, tenant_id, rule_key, desired_revision_id, desired_state,
                       generation, observed_generation, target_cluster, status, last_error,
                       created_at, updated_at
                FROM rule_deployment WHERE tenant_id = ? AND rule_key = ?
                """,
                        this::deploymentRow,
                        tenantId,
                        ruleKey);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public RuntimeAssignmentRow findAssignmentRow(String tenantId, String ruleKey) {
        List<RuntimeAssignmentRow> rows =
                jdbc.query(
                        """
                SELECT tenant_id, rule_key, deployment_id, revision, plan_id, plan_hash, group_key,
                       generation, created_at, updated_at
                FROM rule_job_assignment WHERE tenant_id = ? AND rule_key = ?
                """,
                        this::assignmentRow,
                        tenantId,
                        ruleKey);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public RuleRuntimeStatusRow findRuntimeStatusRow(String tenantId, String ruleKey) {
        List<RuleRuntimeStatusRow> rows =
                jdbc.query(
                        """
                SELECT tenant_id, rule_key, deployment_id, group_key, target_cluster, job_id, job_key,
                       observed_revision, observed_generation, observed_plan_hash, runtime_state,
                       last_seen, last_event, last_match, last_alert, last_seen_at, last_event_at,
                       last_match_at, last_alert_at, error_code, error_message, created_at, updated_at
                FROM rule_runtime_status WHERE tenant_id = ? AND rule_key = ?
                """,
                        this::statusRow,
                        tenantId,
                        ruleKey);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public List<RuntimeStatusScopeRow> findStatusesInScopeRows(
            String tenantId, String groupKey, String targetCluster) {
        return jdbc.query(
                """
                SELECT s.tenant_id, s.rule_key, s.deployment_id, s.group_key, s.target_cluster,
                       s.job_id, s.job_key, s.observed_revision, s.observed_generation,
                       s.observed_plan_hash, s.runtime_state, s.last_seen, s.last_event,
                       s.last_match, s.last_alert, s.last_seen_at, s.last_event_at,
                       s.last_match_at, s.last_alert_at, s.error_code, s.error_message,
                       d.desired_state, d.desired_revision_id, d.generation AS desired_generation,
                       d.status AS deployment_status
                FROM rule_runtime_status s LEFT JOIN rule_deployment d
                  ON d.tenant_id = s.tenant_id AND d.rule_key = s.rule_key
                WHERE s.tenant_id = ? AND s.group_key = ? AND s.target_cluster = ?
                ORDER BY s.rule_key
                """,
                this::scopeRow,
                tenantId,
                groupKey,
                targetCluster);
    }

    public RuntimeManifestRow findLatestObservedManifestRow(
            String tenantId, String groupKey, String targetCluster) {
        List<RuntimeManifestRow> rows =
                jdbc.query(
                        """
                SELECT manifest_id, tenant_id, group_key, target_cluster, job_id, job_key,
                       generation, manifest_json, manifest_hash, observed_at
                FROM detection_runtime_manifest
                WHERE tenant_id = ? AND group_key = ? AND target_cluster = ?
                ORDER BY observed_at DESC, manifest_id DESC FETCH FIRST 1 ROW ONLY
                """,
                        this::manifestRow,
                        tenantId,
                        groupKey,
                        targetCluster);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public List<DetectionJobGroupRow> findGroups(String tenantId) {
        return findGroupRows(tenantId);
    }

    public DetectionJobGroupRow findGroup(String tenantId, String groupKey) {
        return findGroupRow(tenantId, groupKey);
    }

    public DetectionJobGroupRow findGroup(String tenantId, String groupKey, String targetCluster) {
        return findGroupRow(tenantId, groupKey, targetCluster);
    }

    public List<DesiredDetectionRow> findDesiredRunning(String tenantId, String compilerVersion) {
        return findDesiredRunningRows(tenantId, compilerVersion);
    }

    public RuleDeploymentRow findDeployment(String tenantId, String ruleKey) {
        return findDeploymentRow(tenantId, ruleKey);
    }

    public RuntimeAssignmentRow findAssignment(String tenantId, String ruleKey) {
        return findAssignmentRow(tenantId, ruleKey);
    }

    public RuleRuntimeStatusRow findRuntimeStatus(String tenantId, String ruleKey) {
        return findRuntimeStatusRow(tenantId, ruleKey);
    }

    public List<RuntimeStatusScopeRow> findStatusesInScope(
            String tenantId, String groupKey, String targetCluster) {
        return findStatusesInScopeRows(tenantId, groupKey, targetCluster);
    }

    public RuntimeManifestRow findLatestObservedManifest(
            String tenantId, String groupKey, String targetCluster) {
        return findLatestObservedManifestRow(tenantId, groupKey, targetCluster);
    }

    /** Locks the group and validates the complete controller ownership epoch. */
    public boolean lockCurrentObservation(ObservationFence fence) {
        return !jdbc.queryForList(
                        """
                SELECT 1 FROM detection_job_group
                WHERE tenant_id = ? AND group_key = ? AND target_cluster = ?
                  AND controller_lease_owner = ?
                  AND controller_fencing_token = ?
                  AND desired_generation = ?
                  AND controller_lease_until > CURRENT_TIMESTAMP
                FOR UPDATE
                """,
                        fence.tenantId(),
                        fence.groupKey(),
                        fence.targetCluster(),
                        fence.owner(),
                        fence.fencingToken(),
                        fence.desiredGeneration())
                .isEmpty();
    }

    public void deleteAssignments(String tenantId) {
        jdbc.update("DELETE FROM rule_job_assignment WHERE tenant_id = ?", tenantId);
    }

    public void deleteAssignment(String tenantId, String ruleKey) {
        jdbc.update(
                "DELETE FROM rule_job_assignment WHERE tenant_id = ? AND rule_key = ?",
                tenantId,
                ruleKey);
    }

    public void upsertAssignment(
            String tenantId,
            String ruleKey,
            UUID deploymentId,
            long revision,
            UUID planId,
            String planHash,
            String groupKey,
            long generation) {
        int updated =
                jdbc.update(
                        """
                UPDATE rule_job_assignment
                SET deployment_id = ?, revision = ?, plan_id = ?, plan_hash = ?, group_key = ?,
                    generation = ?, updated_at = CURRENT_TIMESTAMP
                WHERE tenant_id = ? AND rule_key = ?
                """,
                        deploymentId,
                        revision,
                        planId,
                        planHash,
                        groupKey,
                        generation,
                        tenantId,
                        ruleKey);
        if (updated == 0) {
            try {
                insertAssignment(
                        tenantId,
                        ruleKey,
                        deploymentId,
                        revision,
                        planId,
                        planHash,
                        groupKey,
                        generation);
            } catch (DuplicateKeyException ignored) {
                jdbc.update(
                        """
                        UPDATE rule_job_assignment
                        SET deployment_id = ?, revision = ?, plan_id = ?, plan_hash = ?, group_key = ?,
                            generation = ?, updated_at = CURRENT_TIMESTAMP
                        WHERE tenant_id = ? AND rule_key = ?
                        """,
                        deploymentId,
                        revision,
                        planId,
                        planHash,
                        groupKey,
                        generation,
                        tenantId,
                        ruleKey);
            }
        }
    }

    public void insertAssignment(
            String tenantId,
            String ruleKey,
            UUID deploymentId,
            long revision,
            UUID planId,
            String planHash,
            String groupKey,
            long generation) {
        jdbc.update(
                """
                INSERT INTO rule_job_assignment
                    (tenant_id, rule_key, deployment_id, revision, plan_id, plan_hash, group_key, generation)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                tenantId,
                ruleKey,
                deploymentId,
                revision,
                planId,
                planHash,
                groupKey,
                generation);
    }

    /**
     * A group generation describes the complete immutable assignment set, not just the rule whose
     * desired revision changed. Move every existing assignment in the group before upserting the
     * changed rule so artifact materialization can enforce one generation for all members.
     */
    public void updateAssignmentGenerations(String tenantId, String groupKey, long generation) {
        jdbc.update(
                """
                UPDATE rule_job_assignment
                SET generation = ?, updated_at = CURRENT_TIMESTAMP
                WHERE tenant_id = ? AND group_key = ?
                """,
                generation,
                tenantId,
                groupKey);
    }

    public void upsertGroup(
            String tenantId,
            String groupKey,
            String targetCluster,
            String sourceFamily,
            String category,
            int bucket,
            long generation,
            String expectedJson,
            String expectedHash) {
        int updated =
                jdbc.update(
                        """
                UPDATE detection_job_group
                SET target_cluster = ?, source_family = ?, category = ?, bucket = ?,
                    desired_generation = ?, expected_manifest_json = ?, expected_manifest_hash = ?,
                    status = 'PENDING', job_id = NULL, job_key = NULL, last_error = NULL,
                    reconcile_state = 'PENDING', reconcile_available_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                WHERE tenant_id = ? AND group_key = ?
                """,
                        targetCluster,
                        sourceFamily,
                        category,
                        bucket,
                        generation,
                        expectedJson,
                        expectedHash,
                        tenantId,
                        groupKey);
        if (updated == 0) {
            try {
                jdbc.update(
                        """
                        INSERT INTO detection_job_group
                            (tenant_id, group_key, target_cluster, source_family, category, bucket,
                             desired_generation, expected_manifest_json, expected_manifest_hash, status)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING')
                        """,
                        tenantId,
                        groupKey,
                        targetCluster,
                        sourceFamily,
                        category,
                        bucket,
                        generation,
                        expectedJson,
                        expectedHash);
            } catch (DuplicateKeyException ignored) {
                // A concurrent reconciler won the insert; its row is updated on the next pass.
                jdbc.update(
                        """
                        UPDATE detection_job_group
                        SET target_cluster = ?, source_family = ?, category = ?, bucket = ?,
                            desired_generation = ?, expected_manifest_json = ?, expected_manifest_hash = ?,
                            status = 'PENDING', job_id = NULL, job_key = NULL, last_error = NULL,
                            reconcile_state = 'PENDING', reconcile_available_at = CURRENT_TIMESTAMP,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE tenant_id = ? AND group_key = ?
                        """,
                        targetCluster,
                        sourceFamily,
                        category,
                        bucket,
                        generation,
                        expectedJson,
                        expectedHash,
                        tenantId,
                        groupKey);
            }
        }
    }

    public void upsertPendingStatus(
            String tenantId,
            String ruleKey,
            UUID deploymentId,
            String groupKey,
            String targetCluster) {
        int updated =
                jdbc.update(
                        """
                UPDATE rule_runtime_status
                SET deployment_id = ?, group_key = ?, target_cluster = ?, runtime_state = 'PENDING',
                    error_code = NULL, error_message = NULL, updated_at = CURRENT_TIMESTAMP
                WHERE tenant_id = ? AND rule_key = ?
                """,
                        deploymentId,
                        groupKey,
                        targetCluster,
                        tenantId,
                        ruleKey);
        if (updated == 0) {
            try {
                jdbc.update(
                        """
                        INSERT INTO rule_runtime_status
                            (tenant_id, rule_key, deployment_id, group_key, target_cluster,
                             runtime_state)
                        VALUES (?, ?, ?, ?, ?, 'PENDING')
                        """,
                        tenantId,
                        ruleKey,
                        deploymentId,
                        groupKey,
                        targetCluster);
            } catch (DuplicateKeyException ignored) {
                jdbc.update(
                        """
                        UPDATE rule_runtime_status
                        SET deployment_id = ?, group_key = ?, target_cluster = ?,
                            runtime_state = 'PENDING', error_code = NULL, error_message = NULL,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE tenant_id = ? AND rule_key = ?
                        """,
                        deploymentId,
                        groupKey,
                        targetCluster,
                        tenantId,
                        ruleKey);
            }
        }
    }

    public void insertObservedManifest(
            String tenantId,
            String groupKey,
            String targetCluster,
            String jobId,
            String jobKey,
            long generation,
            String json,
            String hash) {
        jdbc.update(
                """
                INSERT INTO detection_runtime_manifest
                    (tenant_id, group_key, target_cluster, job_id, job_key, generation,
                     manifest_json, manifest_hash)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                tenantId,
                groupKey,
                targetCluster,
                jobId,
                jobKey,
                generation,
                json,
                hash);
    }

    public int insertObservedManifestFenced(
            ObservationFence fence, String jobId, String jobKey, String json, String hash) {
        return jdbc.update(
                """
                INSERT INTO detection_runtime_manifest
                    (tenant_id, group_key, target_cluster, job_id, job_key, generation,
                     manifest_json, manifest_hash)
                SELECT ?, ?, ?, ?, ?, ?, ?, ?
                WHERE EXISTS (SELECT 1 FROM detection_job_group
                              WHERE tenant_id = ? AND group_key = ? AND target_cluster = ?
                                AND controller_lease_owner = ? AND controller_fencing_token = ?
                                AND desired_generation = ? AND controller_lease_until > CURRENT_TIMESTAMP)
                """,
                fence.tenantId(),
                fence.groupKey(),
                fence.targetCluster(),
                jobId,
                jobKey,
                fence.desiredGeneration(),
                json,
                hash,
                fence.tenantId(),
                fence.groupKey(),
                fence.targetCluster(),
                fence.owner(),
                fence.fencingToken(),
                fence.desiredGeneration());
    }

    public void updateGroupObserved(
            String tenantId,
            String groupKey,
            String targetCluster,
            RuleRuntimeState state,
            String jobId,
            String jobKey,
            String errorMessage) {
        jdbc.update(
                """
                UPDATE detection_job_group
                SET status = ?, job_id = ?, job_key = ?, last_error = ?, updated_at = CURRENT_TIMESTAMP
                WHERE tenant_id = ? AND group_key = ? AND target_cluster = ?
                """,
                state.name(),
                jobId,
                jobKey,
                errorMessage,
                tenantId,
                groupKey,
                targetCluster);
    }

    /** Final group mutation is itself fenced; the row lock prevents a concurrent claim race. */
    public int updateGroupObservedFenced(
            ObservationFence fence,
            RuleRuntimeState state,
            String jobId,
            String jobKey,
            String errorMessage) {
        return jdbc.update(
                """
                UPDATE detection_job_group
                SET status = ?, job_id = ?, job_key = ?, last_error = ?, updated_at = CURRENT_TIMESTAMP
                WHERE tenant_id = ? AND group_key = ? AND target_cluster = ?
                  AND controller_lease_owner = ? AND controller_fencing_token = ?
                  AND desired_generation = ? AND controller_lease_until > CURRENT_TIMESTAMP
                """,
                state.name(),
                jobId,
                jobKey,
                errorMessage,
                fence.tenantId(),
                fence.groupKey(),
                fence.targetCluster(),
                fence.owner(),
                fence.fencingToken(),
                fence.desiredGeneration());
    }

    public void updateRuntimeStatusObserved(
            String tenantId,
            String ruleKey,
            String groupKey,
            String targetCluster,
            String jobId,
            String jobKey,
            Long revision,
            Long generation,
            String planHash,
            RuleRuntimeState state,
            String errorCode,
            String errorMessage) {
        jdbc.update(
                """
                UPDATE rule_runtime_status
                SET job_id = ?, job_key = ?, observed_revision = ?, observed_generation = ?,
                    observed_plan_hash = ?, runtime_state = ?, last_seen = CURRENT_TIMESTAMP,
                    last_seen_at = CURRENT_TIMESTAMP,
                    error_code = ?, error_message = ?, updated_at = CURRENT_TIMESTAMP
                WHERE tenant_id = ? AND rule_key = ? AND group_key = ? AND target_cluster = ?
                """,
                new Object[] {
                    jobId,
                    jobKey,
                    revision,
                    generation,
                    planHash,
                    state.name(),
                    errorCode,
                    errorMessage,
                    tenantId,
                    ruleKey,
                    groupKey,
                    targetCluster
                },
                new int[] {
                    Types.VARCHAR,
                    Types.VARCHAR,
                    Types.BIGINT,
                    Types.BIGINT,
                    Types.VARCHAR,
                    Types.VARCHAR,
                    Types.VARCHAR,
                    Types.VARCHAR,
                    Types.VARCHAR,
                    Types.VARCHAR,
                    Types.VARCHAR,
                    Types.VARCHAR
                });
    }

    public int updateRuntimeStatusObservedFenced(
            ObservationFence fence,
            String ruleKey,
            String jobId,
            String jobKey,
            Long revision,
            Long generation,
            String planHash,
            RuleRuntimeState state,
            String errorCode,
            String errorMessage) {
        return jdbc.update(
                """
                UPDATE rule_runtime_status
                SET job_id = ?, job_key = ?, observed_revision = ?, observed_generation = ?,
                    observed_plan_hash = ?, runtime_state = ?, last_seen = CURRENT_TIMESTAMP,
                    last_seen_at = CURRENT_TIMESTAMP, error_code = ?, error_message = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE tenant_id = ? AND rule_key = ? AND group_key = ? AND target_cluster = ?
                  AND EXISTS (SELECT 1 FROM detection_job_group
                              WHERE tenant_id = ? AND group_key = ? AND target_cluster = ?
                                AND controller_lease_owner = ? AND controller_fencing_token = ?
                                AND desired_generation = ? AND controller_lease_until > CURRENT_TIMESTAMP)
                """,
                new Object[] {
                    jobId,
                    jobKey,
                    revision,
                    generation,
                    planHash,
                    state.name(),
                    errorCode,
                    errorMessage,
                    fence.tenantId(),
                    ruleKey,
                    fence.groupKey(),
                    fence.targetCluster(),
                    fence.tenantId(),
                    fence.groupKey(),
                    fence.targetCluster(),
                    fence.owner(),
                    fence.fencingToken(),
                    fence.desiredGeneration()
                },
                new int[] {
                    Types.VARCHAR,
                    Types.VARCHAR,
                    Types.BIGINT,
                    Types.BIGINT,
                    Types.VARCHAR,
                    Types.VARCHAR,
                    Types.VARCHAR,
                    Types.VARCHAR,
                    Types.VARCHAR,
                    Types.VARCHAR,
                    Types.VARCHAR,
                    Types.VARCHAR,
                    Types.VARCHAR,
                    Types.VARCHAR,
                    Types.VARCHAR,
                    Types.VARCHAR,
                    Types.BIGINT,
                    Types.BIGINT
                });
    }

    public void updateDeploymentRuntimeState(
            String tenantId,
            String ruleKey,
            RuleRuntimeState state,
            Long observedGeneration,
            String errorMessage) {
        jdbc.update(
                """
                UPDATE rule_deployment
                SET status = ?, observed_generation = COALESCE(?, observed_generation),
                    last_error = ?, updated_at = CURRENT_TIMESTAMP
                WHERE tenant_id = ? AND rule_key = ?
                """,
                state.name(),
                observedGeneration,
                errorMessage,
                tenantId,
                ruleKey);
    }

    public int updateDeploymentRuntimeStateFenced(
            ObservationFence fence,
            String ruleKey,
            RuleRuntimeState state,
            Long observedGeneration,
            String errorMessage) {
        return jdbc.update(
                """
                UPDATE rule_deployment
                SET status = ?, observed_generation = COALESCE(?, observed_generation),
                    last_error = ?, updated_at = CURRENT_TIMESTAMP
                WHERE tenant_id = ? AND rule_key = ?
                  AND EXISTS (SELECT 1 FROM detection_job_group
                              WHERE tenant_id = ? AND group_key = ? AND target_cluster = ?
                                AND controller_lease_owner = ? AND controller_fencing_token = ?
                                AND desired_generation = ? AND controller_lease_until > CURRENT_TIMESTAMP)
                """,
                new Object[] {
                    state.name(),
                    observedGeneration,
                    errorMessage,
                    fence.tenantId(),
                    ruleKey,
                    fence.tenantId(),
                    fence.groupKey(),
                    fence.targetCluster(),
                    fence.owner(),
                    fence.fencingToken(),
                    fence.desiredGeneration()
                },
                new int[] {
                    Types.VARCHAR,
                    Types.BIGINT,
                    Types.VARCHAR,
                    Types.VARCHAR,
                    Types.VARCHAR,
                    Types.VARCHAR,
                    Types.VARCHAR,
                    Types.VARCHAR,
                    Types.VARCHAR,
                    Types.BIGINT,
                    Types.BIGINT
                });
    }

    private DetectionJobGroupRow groupRow(ResultSet rs, int rowNum) throws SQLException {
        return new DetectionJobGroupRow(
                rs.getString("tenant_id"),
                rs.getString("group_key"),
                rs.getString("target_cluster"),
                rs.getString("source_family"),
                rs.getString("category"),
                rs.getInt("bucket"),
                rs.getLong("desired_generation"),
                rs.getString("expected_manifest_json"),
                rs.getString("expected_manifest_hash"),
                rs.getString("status"),
                rs.getString("job_id"),
                rs.getString("job_key"),
                rs.getString("last_error"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }

    private DesiredDetectionRow desiredRow(ResultSet rs, int rowNum) throws SQLException {
        return new DesiredDetectionRow(
                rs.getObject("deployment_id", UUID.class),
                rs.getString("tenant_id"),
                rs.getString("rule_key"),
                rs.getObject("desired_revision_id", UUID.class),
                rs.getLong("deployment_generation"),
                rs.getString("target_cluster"),
                rs.getLong("revision"),
                rs.getObject("plan_id", UUID.class),
                rs.getString("plan_hash"),
                rs.getString("plan_json"));
    }

    private RuleDeploymentRow deploymentRow(ResultSet rs, int rowNum) throws SQLException {
        return new RuleDeploymentRow(
                rs.getObject("deployment_id", UUID.class),
                rs.getString("tenant_id"),
                rs.getString("rule_key"),
                rs.getObject("desired_revision_id", UUID.class),
                rs.getString("desired_state"),
                rs.getLong("generation"),
                rs.getLong("observed_generation"),
                rs.getString("target_cluster"),
                rs.getString("status"),
                rs.getString("last_error"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }

    private RuntimeAssignmentRow assignmentRow(ResultSet rs, int rowNum) throws SQLException {
        return new RuntimeAssignmentRow(
                rs.getString("tenant_id"),
                rs.getString("rule_key"),
                rs.getObject("deployment_id", UUID.class),
                rs.getLong("revision"),
                rs.getObject("plan_id", UUID.class),
                rs.getString("plan_hash"),
                rs.getString("group_key"),
                rs.getLong("generation"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }

    private RuleRuntimeStatusRow statusRow(ResultSet rs, int rowNum) throws SQLException {
        return new RuleRuntimeStatusRow(
                rs.getString("tenant_id"),
                rs.getString("rule_key"),
                rs.getObject("deployment_id", UUID.class),
                rs.getString("group_key"),
                rs.getString("target_cluster"),
                rs.getString("job_id"),
                rs.getString("job_key"),
                nullableLong(rs, "observed_revision"),
                nullableLong(rs, "observed_generation"),
                rs.getString("observed_plan_hash"),
                rs.getString("runtime_state"),
                rs.getString("error_code"),
                rs.getString("error_message"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }

    private RuntimeStatusScopeRow scopeRow(ResultSet rs, int rowNum) throws SQLException {
        return new RuntimeStatusScopeRow(
                rs.getString("tenant_id"),
                rs.getString("rule_key"),
                rs.getObject("deployment_id", UUID.class),
                rs.getString("group_key"),
                rs.getString("target_cluster"),
                rs.getString("job_id"),
                rs.getString("job_key"),
                nullableLong(rs, "observed_revision"),
                nullableLong(rs, "observed_generation"),
                rs.getString("observed_plan_hash"),
                rs.getString("runtime_state"),
                rs.getString("error_code"),
                rs.getString("error_message"),
                rs.getString("desired_state"),
                rs.getObject("desired_revision_id", UUID.class),
                nullableLong(rs, "desired_generation"),
                rs.getString("deployment_status"));
    }

    private RuntimeManifestRow manifestRow(ResultSet rs, int rowNum) throws SQLException {
        return new RuntimeManifestRow(
                rs.getLong("manifest_id"),
                rs.getString("tenant_id"),
                rs.getString("group_key"),
                rs.getString("target_cluster"),
                rs.getString("job_id"),
                rs.getString("job_key"),
                rs.getLong("generation"),
                rs.getString("manifest_json"),
                rs.getString("manifest_hash"),
                instant(rs, "observed_at"));
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        java.sql.Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    public record DetectionJobGroupRow(
            String tenantId,
            String groupKey,
            String targetCluster,
            String sourceFamily,
            String category,
            int bucket,
            long desiredGeneration,
            String expectedManifestJson,
            String expectedManifestHash,
            String status,
            String jobId,
            String jobKey,
            String lastError,
            Instant createdAt,
            Instant updatedAt) {}

    public record DesiredDetectionRow(
            UUID deploymentId,
            String tenantId,
            String ruleKey,
            UUID desiredRevisionId,
            long deploymentGeneration,
            String targetCluster,
            long revision,
            UUID planId,
            String planHash,
            String planJson) {}

    public record RuleDeploymentRow(
            UUID deploymentId,
            String tenantId,
            String ruleKey,
            UUID desiredRevisionId,
            String desiredState,
            long generation,
            long observedGeneration,
            String targetCluster,
            String status,
            String lastError,
            Instant createdAt,
            Instant updatedAt) {}

    public record RuntimeAssignmentRow(
            String tenantId,
            String ruleKey,
            UUID deploymentId,
            long revision,
            UUID planId,
            String planHash,
            String groupKey,
            long generation,
            Instant createdAt,
            Instant updatedAt) {}

    public record RuleRuntimeStatusRow(
            String tenantId,
            String ruleKey,
            UUID deploymentId,
            String groupKey,
            String targetCluster,
            String jobId,
            String jobKey,
            Long observedRevision,
            Long observedGeneration,
            String observedPlanHash,
            String runtimeState,
            String errorCode,
            String errorMessage,
            Instant createdAt,
            Instant updatedAt) {}

    public record RuntimeStatusScopeRow(
            String tenantId,
            String ruleKey,
            UUID deploymentId,
            String groupKey,
            String targetCluster,
            String jobId,
            String jobKey,
            Long observedRevision,
            Long observedGeneration,
            String observedPlanHash,
            String runtimeState,
            String errorCode,
            String errorMessage,
            String desiredState,
            UUID desiredRevisionId,
            Long desiredGeneration,
            String deploymentStatus) {}

    public record RuntimeManifestRow(
            long manifestId,
            String tenantId,
            String groupKey,
            String targetCluster,
            String jobId,
            String jobKey,
            long generation,
            String manifestJson,
            String manifestHash,
            Instant observedAt) {}
}
