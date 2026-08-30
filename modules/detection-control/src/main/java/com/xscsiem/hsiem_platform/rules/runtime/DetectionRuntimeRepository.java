package com.xscsiem.hsiem_platform.rules.runtime;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Types;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** JDBC persistence boundary for desired assignments and observed runtime state. */
@Repository
public class DetectionRuntimeRepository {

    private final JdbcTemplate jdbc;

    public DetectionRuntimeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Set<String> findAssignmentGroups(String tenantId, String ruleKey) {
        return jdbc.queryForList("""
                SELECT group_key FROM rule_job_assignment
                WHERE tenant_id = ? AND rule_key = ?
                """, String.class, tenantId, ruleKey).stream().collect(Collectors.toSet());
    }

    public List<Map<String, Object>> findGroups(String tenantId) {
        return jdbc.queryForList("""
                SELECT tenant_id, group_key, target_cluster, source_family, category, bucket,
                       desired_generation, expected_manifest_json, expected_manifest_hash, status,
                       job_id, job_key, last_error, created_at, updated_at
                FROM detection_job_group
                WHERE tenant_id = ?
                ORDER BY group_key
                """, tenantId);
    }

    public Map<String, Object> findGroup(String tenantId, String groupKey) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT tenant_id, group_key, target_cluster, source_family, category, bucket,
                       desired_generation, expected_manifest_json, expected_manifest_hash, status,
                       job_id, job_key, last_error, created_at, updated_at
                FROM detection_job_group
                WHERE tenant_id = ? AND group_key = ?
                """, tenantId, groupKey);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public Map<String, Object> findGroup(String tenantId, String groupKey, String targetCluster) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT tenant_id, group_key, target_cluster, source_family, category, bucket,
                       desired_generation, expected_manifest_json, expected_manifest_hash, status,
                       job_id, job_key, last_error, created_at, updated_at
                FROM detection_job_group
                WHERE tenant_id = ? AND group_key = ? AND target_cluster = ?
                """, tenantId, groupKey, targetCluster);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public List<Map<String, Object>> findDesiredRunning(String tenantId, String compilerVersion) {
        return jdbc.queryForList("""
                SELECT d.deployment_id, d.tenant_id, d.rule_key, d.desired_revision_id,
                       d.generation AS deployment_generation, d.target_cluster,
                       r.revision, p.plan_id, p.plan_hash, p.plan_json, dr.category
                FROM rule_deployment d
                JOIN rule_revision r ON r.revision_id = d.desired_revision_id
                JOIN detection_plan p ON p.revision_id = r.revision_id
                    AND p.compiler_version = ?
                JOIN detection_rule dr ON dr.rule_key = d.rule_key
                WHERE d.tenant_id = ? AND d.desired_state = 'RUNNING'
                ORDER BY d.rule_key
                """, compilerVersion, tenantId);
    }

    public Map<String, Object> findDeployment(String tenantId, String ruleKey) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT deployment_id, tenant_id, rule_key, desired_revision_id, desired_state,
                       generation, observed_generation, target_cluster, status, last_error,
                       created_at, updated_at
                FROM rule_deployment
                WHERE tenant_id = ? AND rule_key = ?
                """, tenantId, ruleKey);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public Map<String, Object> findAssignment(String tenantId, String ruleKey) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT tenant_id, rule_key, deployment_id, revision, plan_hash, group_key,
                       generation, created_at, updated_at
                FROM rule_job_assignment
                WHERE tenant_id = ? AND rule_key = ?
                """, tenantId, ruleKey);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public Map<String, Object> findRuntimeStatus(String tenantId, String ruleKey) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT tenant_id, rule_key, deployment_id, group_key, target_cluster, job_id, job_key,
                       observed_revision, observed_generation, observed_plan_hash, runtime_state,
                       last_seen, last_event, last_match, last_alert,
                       last_seen_at, last_event_at, last_match_at, last_alert_at,
                       error_code, error_message,
                       created_at, updated_at
                FROM rule_runtime_status
                WHERE tenant_id = ? AND rule_key = ?
                """, tenantId, ruleKey);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public List<Map<String, Object>> findStatusesInScope(String tenantId, String groupKey,
                                                          String targetCluster) {
        return jdbc.queryForList("""
                SELECT s.tenant_id, s.rule_key, s.deployment_id, s.group_key, s.target_cluster,
                       s.job_id, s.job_key, s.observed_revision, s.observed_generation,
                       s.observed_plan_hash, s.runtime_state, s.last_seen, s.last_event,
                       s.last_match, s.last_alert,
                       s.last_seen_at, s.last_event_at, s.last_match_at, s.last_alert_at,
                       s.error_code, s.error_message,
                       d.desired_state, d.desired_revision_id, d.generation AS desired_generation,
                       d.status AS deployment_status
                FROM rule_runtime_status s
                LEFT JOIN rule_deployment d
                  ON d.tenant_id = s.tenant_id AND d.rule_key = s.rule_key
                WHERE s.tenant_id = ? AND s.group_key = ? AND s.target_cluster = ?
                ORDER BY s.rule_key
                """, tenantId, groupKey, targetCluster);
    }

    public Map<String, Object> findLatestObservedManifest(String tenantId, String groupKey,
                                                            String targetCluster) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT manifest_id, tenant_id, group_key, target_cluster, job_id, job_key,
                       generation, manifest_json, manifest_hash, observed_at
                FROM detection_runtime_manifest
                WHERE tenant_id = ? AND group_key = ? AND target_cluster = ?
                ORDER BY observed_at DESC, manifest_id DESC
                FETCH FIRST 1 ROW ONLY
                """, tenantId, groupKey, targetCluster);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public void deleteAssignments(String tenantId) {
        jdbc.update("DELETE FROM rule_job_assignment WHERE tenant_id = ?", tenantId);
    }

    public void deleteAssignment(String tenantId, String ruleKey) {
        jdbc.update("DELETE FROM rule_job_assignment WHERE tenant_id = ? AND rule_key = ?",
                tenantId, ruleKey);
    }

    public void upsertAssignment(String tenantId, String ruleKey, UUID deploymentId, long revision,
                                 UUID planId, String planHash, String groupKey, long generation) {
        int updated = jdbc.update("""
                UPDATE rule_job_assignment
                SET deployment_id = ?, revision = ?, plan_id = ?, plan_hash = ?, group_key = ?,
                    generation = ?, updated_at = CURRENT_TIMESTAMP
                WHERE tenant_id = ? AND rule_key = ?
                """, deploymentId, revision, planId, planHash, groupKey, generation,
                tenantId, ruleKey);
        if (updated == 0) {
            try {
                insertAssignment(tenantId, ruleKey, deploymentId, revision, planId, planHash,
                        groupKey, generation);
            } catch (DuplicateKeyException ignored) {
                jdbc.update("""
                        UPDATE rule_job_assignment
                        SET deployment_id = ?, revision = ?, plan_id = ?, plan_hash = ?, group_key = ?,
                            generation = ?, updated_at = CURRENT_TIMESTAMP
                        WHERE tenant_id = ? AND rule_key = ?
                        """, deploymentId, revision, planId, planHash, groupKey, generation,
                        tenantId, ruleKey);
            }
        }
    }

    public void insertAssignment(String tenantId, String ruleKey, UUID deploymentId, long revision,
                                 UUID planId, String planHash, String groupKey, long generation) {
        jdbc.update("""
                INSERT INTO rule_job_assignment
                    (tenant_id, rule_key, deployment_id, revision, plan_id, plan_hash, group_key, generation)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, tenantId, ruleKey, deploymentId, revision, planId, planHash, groupKey, generation);
    }

    /**
     * A group generation describes the complete immutable assignment set, not just the rule whose
     * desired revision changed.  Move every existing assignment in the group before upserting the
     * changed rule so artifact materialization can enforce one generation for all members.
     */
    public void updateAssignmentGenerations(String tenantId, String groupKey, long generation) {
        jdbc.update("""
                UPDATE rule_job_assignment
                SET generation = ?, updated_at = CURRENT_TIMESTAMP
                WHERE tenant_id = ? AND group_key = ?
                """, generation, tenantId, groupKey);
    }

    public void upsertGroup(String tenantId, String groupKey, String targetCluster,
                            String sourceFamily, String category, int bucket, long generation,
                            String expectedJson, String expectedHash) {
        int updated = jdbc.update("""
                UPDATE detection_job_group
                SET target_cluster = ?, source_family = ?, category = ?, bucket = ?,
                    desired_generation = ?, expected_manifest_json = ?, expected_manifest_hash = ?,
                    status = 'PENDING', job_id = NULL, job_key = NULL, last_error = NULL,
                    reconcile_state = 'PENDING', reconcile_available_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                WHERE tenant_id = ? AND group_key = ?
                """, targetCluster, sourceFamily, category, bucket, generation,
                expectedJson, expectedHash, tenantId, groupKey);
        if (updated == 0) {
            try {
                jdbc.update("""
                        INSERT INTO detection_job_group
                            (tenant_id, group_key, target_cluster, source_family, category, bucket,
                             desired_generation, expected_manifest_json, expected_manifest_hash, status)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING')
                        """, tenantId, groupKey, targetCluster, sourceFamily, category, bucket,
                        generation, expectedJson, expectedHash);
            } catch (DuplicateKeyException ignored) {
                // A concurrent reconciler won the insert; its row is updated on the next pass.
                jdbc.update("""
                        UPDATE detection_job_group
                        SET target_cluster = ?, source_family = ?, category = ?, bucket = ?,
                            desired_generation = ?, expected_manifest_json = ?, expected_manifest_hash = ?,
                            status = 'PENDING', job_id = NULL, job_key = NULL, last_error = NULL,
                            reconcile_state = 'PENDING', reconcile_available_at = CURRENT_TIMESTAMP,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE tenant_id = ? AND group_key = ?
                        """, targetCluster, sourceFamily, category, bucket, generation,
                        expectedJson, expectedHash, tenantId, groupKey);
            }
        }
    }

    public void upsertPendingStatus(String tenantId, String ruleKey, UUID deploymentId,
                                    String groupKey, String targetCluster) {
        int updated = jdbc.update("""
                UPDATE rule_runtime_status
                SET deployment_id = ?, group_key = ?, target_cluster = ?, runtime_state = 'PENDING',
                    error_code = NULL, error_message = NULL, updated_at = CURRENT_TIMESTAMP
                WHERE tenant_id = ? AND rule_key = ?
                """, deploymentId, groupKey, targetCluster, tenantId, ruleKey);
        if (updated == 0) {
            try {
                jdbc.update("""
                        INSERT INTO rule_runtime_status
                            (tenant_id, rule_key, deployment_id, group_key, target_cluster,
                             runtime_state)
                        VALUES (?, ?, ?, ?, ?, 'PENDING')
                        """, tenantId, ruleKey, deploymentId, groupKey, targetCluster);
            } catch (DuplicateKeyException ignored) {
                jdbc.update("""
                        UPDATE rule_runtime_status
                        SET deployment_id = ?, group_key = ?, target_cluster = ?,
                            runtime_state = 'PENDING', error_code = NULL, error_message = NULL,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE tenant_id = ? AND rule_key = ?
                        """, deploymentId, groupKey, targetCluster, tenantId, ruleKey);
            }
        }
    }

    public void insertObservedManifest(String tenantId, String groupKey, String targetCluster,
                                       String jobId, String jobKey, long generation,
                                       String json, String hash) {
        jdbc.update("""
                INSERT INTO detection_runtime_manifest
                    (tenant_id, group_key, target_cluster, job_id, job_key, generation,
                     manifest_json, manifest_hash)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, tenantId, groupKey, targetCluster, jobId, jobKey, generation, json, hash);
    }

    public void updateGroupObserved(String tenantId, String groupKey, String targetCluster,
                                    RuleRuntimeState state, String jobId, String jobKey,
                                    String errorMessage) {
        jdbc.update("""
                UPDATE detection_job_group
                SET status = ?, job_id = ?, job_key = ?, last_error = ?, updated_at = CURRENT_TIMESTAMP
                WHERE tenant_id = ? AND group_key = ? AND target_cluster = ?
                """, state.name(), jobId, jobKey, errorMessage, tenantId, groupKey, targetCluster);
    }

    public void updateRuntimeStatusObserved(String tenantId, String ruleKey, String groupKey,
                                            String targetCluster, String jobId, String jobKey,
                                            Long revision, Long generation, String planHash,
                                            RuleRuntimeState state, String errorCode,
                                            String errorMessage) {
        jdbc.update("""
                UPDATE rule_runtime_status
                SET job_id = ?, job_key = ?, observed_revision = ?, observed_generation = ?,
                    observed_plan_hash = ?, runtime_state = ?, last_seen = CURRENT_TIMESTAMP,
                    last_seen_at = CURRENT_TIMESTAMP,
                    error_code = ?, error_message = ?, updated_at = CURRENT_TIMESTAMP
                WHERE tenant_id = ? AND rule_key = ? AND group_key = ? AND target_cluster = ?
                """, new Object[]{jobId, jobKey, revision, generation, planHash, state.name(),
                        errorCode, errorMessage, tenantId, ruleKey, groupKey, targetCluster},
                new int[]{Types.VARCHAR, Types.VARCHAR, Types.BIGINT, Types.BIGINT, Types.VARCHAR,
                        Types.VARCHAR, Types.VARCHAR, Types.VARCHAR, Types.VARCHAR, Types.VARCHAR,
                        Types.VARCHAR, Types.VARCHAR});
    }

    public void updateDeploymentRuntimeState(String tenantId, String ruleKey,
                                             RuleRuntimeState state, Long observedGeneration,
                                             String errorMessage) {
        jdbc.update("""
                UPDATE rule_deployment
                SET status = ?, observed_generation = COALESCE(?, observed_generation),
                    last_error = ?, updated_at = CURRENT_TIMESTAMP
                WHERE tenant_id = ? AND rule_key = ?
                """, state.name(), observedGeneration, errorMessage, tenantId, ruleKey);
    }
}
