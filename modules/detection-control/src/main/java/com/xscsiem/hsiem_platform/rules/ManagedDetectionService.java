package com.xscsiem.hsiem_platform.rules;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.xscsiem.hsiem_platform.onboarding.NotFoundException;
import com.xscsiem.hsiem_platform.rules.runtime.DetectionRuntimeService;
import com.xscsiem.hsiem_platform.tenant.TenantContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** RuleRevision, DetectionPlan and RuleDeployment desired-state boundary. */
@Service
public class ManagedDetectionService {

    private final JdbcTemplate jdbc;
    private final RuleService rules;
    private final ObjectMapper mapper = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    private final DetectionPlanCompiler compiler = new DetectionPlanCompiler();
    private final DetectionRuntimeService runtime;
    private final String sourceCommit;

    @Autowired
    public ManagedDetectionService(JdbcTemplate jdbc, RuleService rules,
                                   @Value("${app.detection.source-commit:working-tree}") String sourceCommit,
                                   DetectionRuntimeService runtime) {
        this.jdbc = jdbc;
        this.rules = rules;
        this.runtime = runtime;
        this.sourceCommit = sourceCommit;
    }

    /** Pure JDBC/unit-test constructor retained for callers outside the Spring composition root. */
    public ManagedDetectionService(JdbcTemplate jdbc, RuleService rules, String sourceCommit) {
        this(jdbc, rules, sourceCommit, new DetectionRuntimeService(jdbc));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> inspect(String ruleKey, String actor) {
        Map<String, Object> rule = rules.get(ruleKey);
        Revision revision = findCurrentRevision(rule);
        Plan plan = revision == null ? null : findPlan(revision);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("rule", rule);
        response.put("revision", revision == null ? null : revision.asMap());
        response.put("plan", plan == null ? null : plan.asMap());
        response.put("deployment", findDeployment(TenantContext.id(), ruleKey));
        Map<String, Object> runtimeView = runtime.inspect(TenantContext.id(), ruleKey);
        response.put("assignment", runtimeView.get("assignment"));
        response.put("jobGroup", runtimeView.get("jobGroup"));
        response.put("runtimeStatus", runtimeView.get("runtimeStatus"));
        response.put("desiredVsObserved", runtimeView.get("desiredVsObserved"));
        return response;
    }

    @Transactional
    public Map<String, Object> deploy(String tenantId, String ruleKey, Map<String, Object> body, String actor) {
        return setDesiredState(tenantId, ruleKey, body, "RUNNING", actor);
    }

    /**
     * Persists a single YAML snapshot as desired state in one transaction, then reconciles all
     * touched rules once.  The physical Flink controller is intentionally not involved.
     */
    @Transactional
    public List<Map<String, Object>> deployAll(String tenantId,
                                                List<Map<String, Object>> ruleSnapshot,
                                                String actor) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        if (ruleSnapshot == null || ruleSnapshot.isEmpty()) {
            return List.of();
        }
        List<String> ruleKeys = new ArrayList<>();
        List<Map<String, Object>> summaries = new ArrayList<>();
        for (Map<String, Object> rule : ruleSnapshot) {
            if (rule == null || rule.get("id") == null) {
                throw new IllegalArgumentException("rule snapshot contains a rule without id");
            }
            String ruleKey = String.valueOf(rule.get("id"));
            String desiredState = Boolean.TRUE.equals(rule.get("enabled")) ? "RUNNING" : "STOPPED";
            Map<String, Object> deployment = setDesiredStateForRule(tenantId, ruleKey, rule,
                    Map.of(), desiredState, actor, false);
            ruleKeys.add(ruleKey);
            summaries.add(pendingSummary(deployment));
        }
        runtime.reconcileDesiredStates(tenantId, ruleKeys);
        return List.copyOf(summaries);
    }

    @Transactional
    public Map<String, Object> stop(String tenantId, String ruleKey, String actor) {
        return setDesiredState(tenantId, ruleKey, Map.of(), "STOPPED", actor);
    }

    @Transactional
    public Map<String, Object> rollback(String tenantId, String ruleKey, Map<String, Object> body, String actor) {
        Object revisionId = body == null ? null : body.get("revisionId");
        if (revisionId == null) {
            throw new IllegalArgumentException("revisionId is required for rollback");
        }
        return setDesiredState(tenantId, ruleKey, body, "RUNNING", actor);
    }

    private Map<String, Object> setDesiredState(String tenantId, String ruleKey,
                                                Map<String, Object> body, String desiredState, String actor) {
        Map<String, Object> rule = rules.get(ruleKey);
        return setDesiredStateForRule(tenantId, ruleKey, rule, body, desiredState, actor, true);
    }

    private Map<String, Object> setDesiredStateForRule(String tenantId, String ruleKey,
                                                        Map<String, Object> rule,
                                                        Map<String, Object> body,
                                                        String desiredState, String actor,
                                                        boolean reconcile) {
        Map<String, Object> request = body == null ? Map.of() : body;
        Revision revision = revisionFor(rule, request.get("revisionId"), actor);
        Plan plan = ensurePlan(rule, revision);
        UUID deploymentId = UUID.randomUUID();
        Map<String, Object> previous = findDeployment(tenantId, ruleKey);
        Object requestedCluster = request.get("targetCluster");
        String targetCluster = requestedCluster == null || String.valueOf(requestedCluster).isBlank()
                ? previous == null || previous.get("target_cluster") == null
                    ? "default" : String.valueOf(previous.get("target_cluster"))
                : String.valueOf(requestedCluster);
        if (sameDesiredState(previous, plan, desiredState, targetCluster)) {
            if (reconcile) {
                // Even an idempotent mutation reconciles so missing assignments/status can be repaired.
                runtime.reconcileDesiredState(tenantId, ruleKey, targetCluster, desiredState);
            }
            return previous;
        }
        int updated = jdbc.update("""
                UPDATE rule_deployment SET desired_revision_id = ?, desired_state = ?,
                    generation = generation + 1, target_cluster = ?, status = 'PENDING',
                    last_error = NULL, updated_at = CURRENT_TIMESTAMP
                WHERE tenant_id = ? AND rule_key = ?
                """, revision.id(), desiredState, targetCluster, tenantId, ruleKey);
        if (updated == 0) {
            jdbc.update("""
                    INSERT INTO rule_deployment
                        (deployment_id, tenant_id, rule_key, desired_revision_id, desired_state,
                         generation, observed_generation, target_cluster, status)
                    VALUES (?, ?, ?, ?, ?, 1, 0, ?, 'PENDING')
                    """, deploymentId, tenantId, ruleKey, revision.id(), desiredState, targetCluster);
        } else {
            deploymentId = jdbc.queryForObject("""
                    SELECT deployment_id FROM rule_deployment WHERE tenant_id = ? AND rule_key = ?
                    """, UUID.class, tenantId, ruleKey);
        }
        Map<String, Object> deployment = findDeployment(tenantId, ruleKey);
        insertHistory(deployment, actor);
        if (reconcile) {
            // This call joins the surrounding transaction; no physical Flink operation is performed.
            runtime.reconcileDesiredState(tenantId, ruleKey, targetCluster, desiredState);
        }
        return deployment;
    }

    private Map<String, Object> pendingSummary(Map<String, Object> deployment) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("ruleKey", deployment.get("rule_key"));
        summary.put("deploymentId", deployment.get("deployment_id"));
        summary.put("desiredState", deployment.get("desired_state"));
        summary.put("targetCluster", deployment.get("target_cluster"));
        summary.put("generation", deployment.get("generation"));
        summary.put("status", deployment.get("status"));
        return summary;
    }

    private Revision findCurrentRevision(Map<String, Object> rule) {
        String ruleKey = String.valueOf(rule.get("id"));
        String definition = json(rule);
        String hash = sha256(definition);
        List<Revision> revisions = jdbc.query("""
                SELECT revision_id, revision, definition_json, content_hash, source_commit, created_by, created_at
                FROM rule_revision WHERE rule_key = ? AND content_hash = ?
                """, this::revision, ruleKey, hash);
        return revisions.isEmpty() ? null : revisions.getFirst();
    }

    private Plan findPlan(Revision revision) {
        List<Plan> plans = jdbc.query("""
                SELECT plan_id, compiler_version, plan_json, plan_hash, created_at
                FROM detection_plan
                WHERE revision_id = ? AND compiler_version = ?
                ORDER BY created_at DESC, plan_id DESC
                """, (rs, rowNum) -> new Plan(rs.getObject("plan_id", UUID.class),
                rs.getString("compiler_version"), rs.getString("plan_json"),
                rs.getString("plan_hash"), rs.getTimestamp("created_at").toInstant()),
                revision.id(), DetectionPlanCompiler.VERSION);
        return plans.isEmpty() ? null : plans.getFirst();
    }

    private boolean sameDesiredState(Map<String, Object> previous, Plan requestedPlan,
                                     String desiredState, String targetCluster) {
        if (previous == null
                || !desiredState.equals(previous.get("desired_state"))
                || !targetCluster.equals(previous.get("target_cluster"))) {
            return false;
        }
        UUID previousRevisionId = uuid(previous.get("desired_revision_id"));
        Plan previousPlan = previousRevisionId == null ? null : findPlan(previousRevisionId);
        return previousPlan != null && previousPlan.hash().equals(requestedPlan.hash());
    }

    private Plan findPlan(UUID revisionId) {
        List<Plan> plans = jdbc.query("""
                SELECT plan_id, compiler_version, plan_json, plan_hash, created_at
                FROM detection_plan
                WHERE revision_id = ? AND compiler_version = ?
                ORDER BY created_at DESC, plan_id DESC
                """, (rs, rowNum) -> new Plan(rs.getObject("plan_id", UUID.class),
                rs.getString("compiler_version"), rs.getString("plan_json"),
                rs.getString("plan_hash"), rs.getTimestamp("created_at").toInstant()),
                revisionId, DetectionPlanCompiler.VERSION);
        return plans.isEmpty() ? null : plans.getFirst();
    }

    private static UUID uuid(Object value) {
        if (value instanceof UUID uuid) return uuid;
        try {
            return UUID.fromString(String.valueOf(value));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private Revision revisionFor(Map<String, Object> rule, Object requestedId, String actor) {
        if (requestedId == null) return ensureRevision(rule, actor);
        UUID id;
        try {
            id = UUID.fromString(String.valueOf(requestedId));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("revisionId must be a UUID", e);
        }
        List<Revision> revisions = jdbc.query("""
                SELECT revision_id, revision, definition_json, content_hash, source_commit, created_by, created_at
                FROM rule_revision WHERE revision_id = ? AND rule_key = ?
                """, (rs, rowNum) -> new Revision(
                rs.getObject("revision_id", UUID.class), rs.getInt("revision"),
                rs.getString("definition_json"), rs.getString("content_hash"),
                rs.getString("source_commit"), rs.getString("created_by"),
                rs.getTimestamp("created_at").toInstant()), id, rule.get("id"));
        if (revisions.isEmpty()) throw new NotFoundException("规则版本不存在或不属于该规则");
        return revisions.getFirst();
    }

    private Revision ensureRevision(Map<String, Object> rule, String actor) {
        String ruleKey = String.valueOf(rule.get("id"));
        String definition = json(rule);
        String hash = sha256(definition);
        List<Revision> existing = jdbc.query("""
                SELECT revision_id, revision, definition_json, content_hash, source_commit, created_by, created_at
                FROM rule_revision WHERE rule_key = ? AND content_hash = ?
                """, this::revision, ruleKey, hash);
        if (!existing.isEmpty()) return existing.getFirst();
        int ruleUpdated = jdbc.update("""
                UPDATE detection_rule SET name = ?, description = ?, category = ?, updated_at = CURRENT_TIMESTAMP
                WHERE rule_key = ?
                """, String.valueOf(rule.get("name")),
                String.valueOf(rule.getOrDefault("description", "")), String.valueOf(rule.get("category")), ruleKey);
        if (ruleUpdated == 0) {
            try {
                jdbc.update("""
                        INSERT INTO detection_rule (rule_key, name, description, category, updated_at)
                        VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)
                        """, ruleKey, String.valueOf(rule.get("name")),
                        String.valueOf(rule.getOrDefault("description", "")), String.valueOf(rule.get("category")));
            } catch (DuplicateKeyException ignored) {
                // Another request created the catalog row concurrently; the revision remains the source of truth.
            }
        }
        Integer latest = jdbc.queryForObject(
                "SELECT COALESCE(MAX(revision), 0) FROM rule_revision WHERE rule_key = ?",
                Integer.class, ruleKey);
        Revision revision = new Revision(UUID.randomUUID(), (latest == null ? 0 : latest) + 1,
                definition, hash, sourceCommit, actor == null || actor.isBlank() ? "system" : actor, Instant.now());
        try {
            jdbc.update("""
                    INSERT INTO rule_revision
                        (revision_id, rule_key, revision, definition_json, content_hash, source_commit, created_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, revision.id(), ruleKey, revision.number(), revision.definition(), revision.hash(),
                    revision.sourceCommit(), revision.createdBy());
        } catch (DuplicateKeyException e) {
            return ensureRevision(rule, actor);
        }
        return jdbc.query("""
                SELECT revision_id, revision, definition_json, content_hash, source_commit, created_by, created_at
                FROM rule_revision WHERE rule_key = ? AND content_hash = ?
                """, this::revision, ruleKey, hash).getFirst();
    }

    private Plan ensurePlan(Map<String, Object> rule, Revision revision) {
        List<Plan> existing = jdbc.query("""
                SELECT plan_id, compiler_version, plan_json, plan_hash, created_at
                FROM detection_plan
                WHERE revision_id = ? AND compiler_version = ?
                ORDER BY created_at DESC, plan_id DESC
                """, (rs, rowNum) -> new Plan(rs.getObject("plan_id", UUID.class),
                rs.getString("compiler_version"), rs.getString("plan_json"),
                rs.getString("plan_hash"), rs.getTimestamp("created_at").toInstant()),
                revision.id(), DetectionPlanCompiler.VERSION);
        // A revision is immutable: an existing artifact is authoritative and must not be recompiled
        // or rehashed merely because a caller inspects or deploys the rule again.
        if (!existing.isEmpty()) return existing.getFirst();

        Map<String, Object> immutableRule = rule;
        try {
            immutableRule = mapper.readValue(revision.definition(), Map.class);
        } catch (Exception ignored) {
            // Existing rows from an older installation can still be compiled from the catalog rule.
        }
        DetectionPlanCompiler.CompiledPlan compiled = compiler.compile(immutableRule, revision.number());
        UUID planId = UUID.randomUUID();
        try {
            jdbc.update("""
                    INSERT INTO detection_plan (plan_id, revision_id, compiler_version, plan_json, plan_hash)
                    VALUES (?, ?, ?, ?, ?)
                    """, planId, revision.id(), DetectionPlanCompiler.VERSION,
                    compiled.json(), compiled.hash());
        } catch (DuplicateKeyException ignored) {
            // Another request created the immutable artifact; return that canonical row below.
        }
        return jdbc.query("""
                SELECT plan_id, compiler_version, plan_json, plan_hash, created_at
                FROM detection_plan
                WHERE revision_id = ? AND compiler_version = ?
                ORDER BY created_at DESC, plan_id DESC
                """, (rs, rowNum) -> new Plan(rs.getObject("plan_id", UUID.class),
                rs.getString("compiler_version"), rs.getString("plan_json"),
                rs.getString("plan_hash"), rs.getTimestamp("created_at").toInstant()),
                revision.id(), DetectionPlanCompiler.VERSION).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("detection plan artifact was not created"));
    }

    private Map<String, Object> findDeployment(String tenantId, String ruleKey) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT deployment_id, tenant_id, rule_key, desired_revision_id, desired_state,
                       generation, observed_generation, target_cluster, status, last_error,
                       created_at, updated_at
                FROM rule_deployment WHERE tenant_id = ? AND rule_key = ?
                """, tenantId, ruleKey);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private void insertHistory(Map<String, Object> deployment, String actor) {
        if (deployment == null) return;
        jdbc.update("""
                INSERT INTO rule_deployment_history
                    (deployment_id, tenant_id, rule_key, desired_revision_id, desired_state,
                     generation, status, actor)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, deployment.get("deployment_id"), deployment.get("tenant_id"), deployment.get("rule_key"),
                deployment.get("desired_revision_id"), deployment.get("desired_state"),
                deployment.get("generation"), deployment.get("status"),
                actor == null || actor.isBlank() ? "system" : actor);
    }

    private Revision revision(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new Revision(rs.getObject("revision_id", UUID.class), rs.getInt("revision"),
                rs.getString("definition_json"), rs.getString("content_hash"),
                rs.getString("source_commit"), rs.getString("created_by"),
                rs.getTimestamp("created_at").toInstant());
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("规则定义序列化失败", e);
        }
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("规则 hash 计算失败", e);
        }
    }

    private record Revision(UUID id, int number, String definition, String hash,
                            String sourceCommit, String createdBy, Instant createdAt) {
        Map<String, Object> asMap() {
            return Map.of("revisionId", id, "revision", number, "contentHash", hash,
                    "sourceCommit", sourceCommit, "createdBy", createdBy, "createdAt", createdAt);
        }
    }

    private record Plan(UUID id, String compilerVersion, String json, String hash, Instant createdAt) {
        Map<String, Object> asMap() {
            return Map.of("planId", id, "compilerVersion", compilerVersion,
                    "planHash", hash, "plan", json, "createdAt", createdAt);
        }
    }
}
