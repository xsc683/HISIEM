package com.xscsiem.hsiem_platform.detection.runtime;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Persistence boundary for the immutable assignment rows used to materialize artifacts. */
@Repository
public final class DetectionArtifactRepository {

    private final JdbcTemplate jdbc;

    public DetectionArtifactRepository(JdbcTemplate jdbc) {
        this.jdbc = java.util.Objects.requireNonNull(jdbc, "jdbc must not be null");
    }

    public List<DetectionArtifactRuleRow> findRules(String tenantId, String groupKey) {
        return jdbc.query(
                """
                SELECT a.rule_key, a.revision, a.plan_id, a.plan_hash, a.generation,
                       p.compiler_version, p.plan_json
                FROM rule_job_assignment a
                JOIN detection_plan p
                  ON p.plan_id = a.plan_id AND p.plan_hash = a.plan_hash
                JOIN rule_revision r
                  ON r.revision_id = p.revision_id
                 AND r.rule_key = a.rule_key AND r.revision = a.revision
                WHERE a.tenant_id = ? AND a.group_key = ?
                ORDER BY a.rule_key
                """,
                DetectionArtifactRepository::map,
                tenantId,
                groupKey);
    }

    private static DetectionArtifactRuleRow map(ResultSet rs, int rowNum) throws SQLException {
        return new DetectionArtifactRuleRow(
                rs.getString("rule_key"),
                rs.getLong("revision"),
                rs.getObject("plan_id", UUID.class),
                rs.getString("plan_hash"),
                rs.getLong("generation"),
                rs.getString("compiler_version"),
                rs.getString("plan_json"));
    }

    public record DetectionArtifactRuleRow(
            String ruleKey,
            long revision,
            UUID planId,
            String planHash,
            long generation,
            String compilerVersion,
            String planJson) {}
}
