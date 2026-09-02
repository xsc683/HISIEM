package com.xscsiem.hsiem_platform.rules;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Explicit JDBC boundary for immutable detection artifacts and desired deployments. */
@Repository
public class ManagedDetectionRepository {

    private final JdbcTemplate jdbc;

    public ManagedDetectionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public RuleRevisionRow findRevision(String ruleKey, String contentHash) {
        List<RuleRevisionRow> rows =
                jdbc.query(
                        """
                SELECT revision_id, rule_key, revision, definition_json, content_hash, source_commit,
                       created_by, created_at
                FROM rule_revision WHERE rule_key = ? AND content_hash = ?
                """,
                        this::revision,
                        ruleKey,
                        contentHash);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public RuleRevisionRow findRevision(UUID revisionId, String ruleKey) {
        List<RuleRevisionRow> rows =
                jdbc.query(
                        """
                SELECT revision_id, rule_key, revision, definition_json, content_hash, source_commit,
                       created_by, created_at
                FROM rule_revision WHERE revision_id = ? AND rule_key = ?
                """,
                        this::revision,
                        revisionId,
                        ruleKey);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public DetectionPlanRow findPlan(UUID revisionId, String compilerVersion) {
        List<DetectionPlanRow> rows =
                jdbc.query(
                        """
                SELECT plan_id, compiler_version, plan_json, plan_hash, created_at
                FROM detection_plan
                WHERE revision_id = ? AND compiler_version = ?
                ORDER BY created_at DESC, plan_id DESC
                """,
                        this::plan,
                        revisionId,
                        compilerVersion);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public int updateCatalog(String ruleKey, String name, String description, String category) {
        return jdbc.update(
                """
                UPDATE detection_rule SET name = ?, description = ?, category = ?, updated_at = CURRENT_TIMESTAMP
                WHERE rule_key = ?
                """,
                name,
                description,
                category,
                ruleKey);
    }

    public void insertCatalog(String ruleKey, String name, String description, String category) {
        jdbc.update(
                """
                INSERT INTO detection_rule (rule_key, name, description, category, updated_at)
                VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)
                """,
                ruleKey,
                name,
                description,
                category);
    }

    public int latestRevisionNumber(String ruleKey) {
        Integer latest =
                jdbc.queryForObject(
                        "SELECT COALESCE(MAX(revision), 0) FROM rule_revision WHERE rule_key = ?",
                        Integer.class,
                        ruleKey);
        return latest == null ? 0 : latest;
    }

    public void insertRevision(RuleRevisionRow revision) {
        jdbc.update(
                """
                INSERT INTO rule_revision
                    (revision_id, rule_key, revision, definition_json, content_hash, source_commit, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                revision.revisionId(),
                revision.ruleKey(),
                revision.revision(),
                revision.definitionJson(),
                revision.contentHash(),
                revision.sourceCommit(),
                revision.createdBy());
    }

    public void insertPlan(DetectionPlanRow plan, UUID revisionId) {
        jdbc.update(
                """
                INSERT INTO detection_plan (plan_id, revision_id, compiler_version, plan_json, plan_hash)
                VALUES (?, ?, ?, ?, ?)
                """,
                plan.planId(),
                revisionId,
                plan.compilerVersion(),
                plan.planJson(),
                plan.planHash());
    }

    public RuleDeploymentRow findDeployment(String tenantId, String ruleKey) {
        List<RuleDeploymentRow> rows =
                jdbc.query(
                        """
                SELECT deployment_id, tenant_id, rule_key, desired_revision_id, desired_state,
                       generation, observed_generation, target_cluster, status, last_error,
                       created_at, updated_at
                FROM rule_deployment WHERE tenant_id = ? AND rule_key = ?
                """,
                        this::deployment,
                        tenantId,
                        ruleKey);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public int updateDesiredState(
            UUID revisionId,
            String desiredState,
            String targetCluster,
            String tenantId,
            String ruleKey) {
        return jdbc.update(
                """
                UPDATE rule_deployment SET desired_revision_id = ?, desired_state = ?,
                    generation = generation + 1, target_cluster = ?, status = 'PENDING',
                    last_error = NULL, updated_at = CURRENT_TIMESTAMP
                WHERE tenant_id = ? AND rule_key = ?
                """,
                revisionId,
                desiredState,
                targetCluster,
                tenantId,
                ruleKey);
    }

    public void insertDeployment(
            UUID deploymentId,
            String tenantId,
            String ruleKey,
            UUID revisionId,
            String desiredState,
            String targetCluster) {
        jdbc.update(
                """
                INSERT INTO rule_deployment
                    (deployment_id, tenant_id, rule_key, desired_revision_id, desired_state,
                     generation, observed_generation, target_cluster, status)
                VALUES (?, ?, ?, ?, ?, 1, 0, ?, 'PENDING')
                """,
                deploymentId,
                tenantId,
                ruleKey,
                revisionId,
                desiredState,
                targetCluster);
    }

    public UUID findDeploymentId(String tenantId, String ruleKey) {
        return jdbc.queryForObject(
                """
                SELECT deployment_id FROM rule_deployment WHERE tenant_id = ? AND rule_key = ?
                """,
                UUID.class,
                tenantId,
                ruleKey);
    }

    public void insertHistory(RuleDeploymentRow deployment, String actor) {
        if (deployment == null) return;
        jdbc.update(
                """
                INSERT INTO rule_deployment_history
                    (deployment_id, tenant_id, rule_key, desired_revision_id, desired_state,
                     generation, status, actor)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                deployment.deploymentId(),
                deployment.tenantId(),
                deployment.ruleKey(),
                deployment.desiredRevisionId(),
                deployment.desiredState(),
                deployment.generation(),
                deployment.status(),
                actor == null || actor.isBlank() ? "system" : actor);
    }

    private RuleRevisionRow revision(ResultSet rs, int rowNum) throws SQLException {
        return new RuleRevisionRow(
                rs.getObject("revision_id", UUID.class),
                rs.getString("rule_key"),
                rs.getInt("revision"),
                rs.getString("definition_json"),
                rs.getString("content_hash"),
                rs.getString("source_commit"),
                rs.getString("created_by"),
                instant(rs, "created_at"));
    }

    private DetectionPlanRow plan(ResultSet rs, int rowNum) throws SQLException {
        return new DetectionPlanRow(
                rs.getObject("plan_id", UUID.class),
                rs.getString("compiler_version"),
                rs.getString("plan_json"),
                rs.getString("plan_hash"),
                instant(rs, "created_at"));
    }

    private RuleDeploymentRow deployment(ResultSet rs, int rowNum) throws SQLException {
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

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        java.sql.Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    public record RuleRevisionRow(
            UUID revisionId,
            String ruleKey,
            int revision,
            String definitionJson,
            String contentHash,
            String sourceCommit,
            String createdBy,
            Instant createdAt) {}

    public record DetectionPlanRow(
            UUID planId,
            String compilerVersion,
            String planJson,
            String planHash,
            Instant createdAt) {}

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
}
