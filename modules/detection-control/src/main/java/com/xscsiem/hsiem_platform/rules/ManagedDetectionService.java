package com.xscsiem.hsiem_platform.rules;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.xscsiem.hsiem_platform.onboarding.NotFoundException;
import com.xscsiem.hsiem_platform.rules.runtime.DetectionRuntimeService;
import com.xscsiem.hsiem_platform.tenant.TenantContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** RuleRevision, DetectionPlan and RuleDeployment desired-state boundary. */
@Service
public class ManagedDetectionService {

    private final ManagedDetectionRepository repository;
    private final RuleService rules;
    private final ObjectMapper mapper =
            new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    private final DetectionPlanCompiler compiler = new DetectionPlanCompiler();
    private final DetectionRuntimeService runtime;
    private final String sourceCommit;

    @Autowired
    public ManagedDetectionService(
            ManagedDetectionRepository repository,
            RuleService rules,
            @Value("${app.detection.source-commit:working-tree}") String sourceCommit,
            DetectionRuntimeService runtime) {
        this.repository = repository;
        this.rules = rules;
        this.runtime = runtime;
        this.sourceCommit = sourceCommit;
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
    public Map<String, Object> deploy(
            String tenantId, String ruleKey, Map<String, Object> body, String actor) {
        return setDesiredState(tenantId, ruleKey, body, "RUNNING", actor);
    }

    /**
     * Persists a single YAML snapshot as desired state in one transaction, then reconciles all
     * touched rules once. The physical Flink controller is intentionally not involved.
     */
    @Transactional
    public List<Map<String, Object>> deployAll(
            String tenantId, List<Map<String, Object>> ruleSnapshot, String actor) {
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
            Map<String, Object> deployment =
                    setDesiredStateForRule(
                            tenantId, ruleKey, rule, Map.of(), desiredState, actor, false);
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
    public Map<String, Object> rollback(
            String tenantId, String ruleKey, Map<String, Object> body, String actor) {
        Object revisionId = body == null ? null : body.get("revisionId");
        if (revisionId == null) {
            throw new IllegalArgumentException("revisionId is required for rollback");
        }
        return setDesiredState(tenantId, ruleKey, body, "RUNNING", actor);
    }

    private Map<String, Object> setDesiredState(
            String tenantId,
            String ruleKey,
            Map<String, Object> body,
            String desiredState,
            String actor) {
        Map<String, Object> rule = rules.get(ruleKey);
        return setDesiredStateForRule(tenantId, ruleKey, rule, body, desiredState, actor, true);
    }

    private Map<String, Object> setDesiredStateForRule(
            String tenantId,
            String ruleKey,
            Map<String, Object> rule,
            Map<String, Object> body,
            String desiredState,
            String actor,
            boolean reconcile) {
        Map<String, Object> request = body == null ? Map.of() : body;
        Revision revision = revisionFor(rule, request.get("revisionId"), actor);
        Plan plan = ensurePlan(rule, revision);
        UUID deploymentId = UUID.randomUUID();
        Map<String, Object> previous = findDeployment(tenantId, ruleKey);
        Object requestedCluster = request.get("targetCluster");
        String targetCluster =
                requestedCluster == null || String.valueOf(requestedCluster).isBlank()
                        ? previous == null || previous.get("target_cluster") == null
                                ? "default"
                                : String.valueOf(previous.get("target_cluster"))
                        : String.valueOf(requestedCluster);
        if (sameDesiredState(previous, revision, plan, desiredState, targetCluster)) {
            if (reconcile) {
                // Even an idempotent mutation reconciles so missing assignments/status can be
                // repaired.
                runtime.reconcileDesiredState(tenantId, ruleKey, targetCluster, desiredState);
            }
            return previous;
        }
        int updated =
                repository.updateDesiredState(
                        revision.id(), desiredState, targetCluster, tenantId, ruleKey);
        if (updated == 0) {
            repository.insertDeployment(
                    deploymentId, tenantId, ruleKey, revision.id(), desiredState, targetCluster);
        } else {
            deploymentId = repository.findDeploymentId(tenantId, ruleKey);
        }
        Map<String, Object> deployment = findDeployment(tenantId, ruleKey);
        insertHistory(deployment, actor);
        if (reconcile) {
            // This call joins the surrounding transaction; no physical Flink operation is
            // performed.
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
        ManagedDetectionRepository.RuleRevisionRow row = repository.findRevision(ruleKey, hash);
        return row == null ? null : revision(row);
    }

    private Plan findPlan(Revision revision) {
        ManagedDetectionRepository.DetectionPlanRow row =
                repository.findPlan(revision.id(), DetectionPlanCompiler.VERSION);
        return row == null ? null : plan(row);
    }

    private boolean sameDesiredState(
            Map<String, Object> previous,
            Revision requestedRevision,
            Plan requestedPlan,
            String desiredState,
            String targetCluster) {
        if (previous == null
                || !desiredState.equals(previous.get("desired_state"))
                || !targetCluster.equals(previous.get("target_cluster"))) {
            return false;
        }
        UUID previousRevisionId = uuid(previous.get("desired_revision_id"));
        // Revision provenance is part of logical desired state even when the compiled physical
        // plan hash is unchanged.  An explicit deploy of a newer equivalent revision must be
        // recorded, while the runtime layer can keep the existing physical generation.
        if (!requestedRevision.id().equals(previousRevisionId)) {
            return false;
        }
        Plan previousPlan = previousRevisionId == null ? null : findPlan(previousRevisionId);
        return previousPlan != null && previousPlan.hash().equals(requestedPlan.hash());
    }

    private Plan findPlan(UUID revisionId) {
        ManagedDetectionRepository.DetectionPlanRow row =
                repository.findPlan(revisionId, DetectionPlanCompiler.VERSION);
        return row == null ? null : plan(row);
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
        ManagedDetectionRepository.RuleRevisionRow row =
                repository.findRevision(id, String.valueOf(rule.get("id")));
        if (row == null)
            throw new NotFoundException("revision not found or does not belong to rule");
        return revision(row);
    }

    private Revision ensureRevision(Map<String, Object> rule, String actor) {
        String ruleKey = String.valueOf(rule.get("id"));
        String definition = json(rule);
        String hash = sha256(definition);
        ManagedDetectionRepository.RuleRevisionRow existing =
                repository.findRevision(ruleKey, hash);
        if (existing != null) return revision(existing);
        int ruleUpdated =
                repository.updateCatalog(
                        ruleKey,
                        String.valueOf(rule.get("name")),
                        String.valueOf(rule.getOrDefault("description", "")),
                        String.valueOf(rule.get("category")));
        if (ruleUpdated == 0) {
            try {
                repository.insertCatalog(
                        ruleKey,
                        String.valueOf(rule.get("name")),
                        String.valueOf(rule.getOrDefault("description", "")),
                        String.valueOf(rule.get("category")));
            } catch (DuplicateKeyException ignored) {
                // Another request created the catalog row concurrently; the revision remains the
                // source of truth.
            }
        }
        Revision revision =
                new Revision(
                        UUID.randomUUID(),
                        repository.latestRevisionNumber(ruleKey) + 1,
                        definition,
                        hash,
                        sourceCommit,
                        actor == null || actor.isBlank() ? "system" : actor,
                        Instant.now());
        try {
            repository.insertRevision(
                    new ManagedDetectionRepository.RuleRevisionRow(
                            revision.id(),
                            ruleKey,
                            revision.number(),
                            revision.definition(),
                            revision.hash(),
                            revision.sourceCommit(),
                            revision.createdBy(),
                            revision.createdAt()));
        } catch (DuplicateKeyException e) {
            return ensureRevision(rule, actor);
        }
        return revision(repository.findRevision(ruleKey, hash));
    }

    private Plan ensurePlan(Map<String, Object> rule, Revision revision) {
        ManagedDetectionRepository.DetectionPlanRow existing =
                repository.findPlan(revision.id(), DetectionPlanCompiler.VERSION);
        // A revision is immutable: an existing artifact is authoritative and must not be recompiled
        // or rehashed merely because a caller inspects or deploys the rule again.
        if (existing != null) return plan(existing);

        Map<String, Object> immutableRule;
        try {
            immutableRule = mapper.readValue(revision.definition(), Map.class);
            if (immutableRule == null) {
                throw new IllegalStateException("definition JSON root must be an object");
            }
        } catch (Exception failure) {
            throw new IllegalStateException(
                    "invalid immutable RuleRevision definition for revision " + revision.id(),
                    failure);
        }
        DetectionPlanCompiler.CompiledPlan compiled =
                compiler.compile(immutableRule, revision.number());
        UUID planId = UUID.randomUUID();
        try {
            repository.insertPlan(
                    new ManagedDetectionRepository.DetectionPlanRow(
                            planId,
                            DetectionPlanCompiler.VERSION,
                            compiled.json(),
                            compiled.hash(),
                            Instant.now()),
                    revision.id());
        } catch (DuplicateKeyException ignored) {
            // Another request created the immutable artifact; return that canonical row below.
        }
        ManagedDetectionRepository.DetectionPlanRow stored =
                repository.findPlan(revision.id(), DetectionPlanCompiler.VERSION);
        if (stored == null)
            throw new IllegalStateException("detection plan artifact was not created");
        return plan(stored);
    }

    private Map<String, Object> findDeployment(String tenantId, String ruleKey) {
        ManagedDetectionRepository.RuleDeploymentRow row =
                repository.findDeployment(tenantId, ruleKey);
        if (row == null) return null;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deployment_id", row.deploymentId());
        result.put("tenant_id", row.tenantId());
        result.put("rule_key", row.ruleKey());
        result.put("desired_revision_id", row.desiredRevisionId());
        result.put("desired_state", row.desiredState());
        result.put("generation", row.generation());
        result.put("observed_generation", row.observedGeneration());
        result.put("target_cluster", row.targetCluster());
        result.put("status", row.status());
        result.put("last_error", row.lastError());
        result.put("created_at", row.createdAt());
        result.put("updated_at", row.updatedAt());
        return result;
    }

    private void insertHistory(Map<String, Object> deployment, String actor) {
        if (deployment == null) return;
        repository.insertHistory(
                repository.findDeployment(
                        String.valueOf(deployment.get("tenant_id")),
                        String.valueOf(deployment.get("rule_key"))),
                actor);
    }

    private static Revision revision(ManagedDetectionRepository.RuleRevisionRow row) {
        return new Revision(
                row.revisionId(),
                row.revision(),
                row.definitionJson(),
                row.contentHash(),
                row.sourceCommit(),
                row.createdBy(),
                row.createdAt());
    }

    private static Plan plan(ManagedDetectionRepository.DetectionPlanRow row) {
        return new Plan(
                row.planId(),
                row.compilerVersion(),
                row.planJson(),
                row.planHash(),
                row.createdAt());
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
            return java.util.HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("规则 hash 计算失败", e);
        }
    }

    private record Revision(
            UUID id,
            int number,
            String definition,
            String hash,
            String sourceCommit,
            String createdBy,
            Instant createdAt) {
        Map<String, Object> asMap() {
            return Map.of(
                    "revisionId",
                    id,
                    "revision",
                    number,
                    "contentHash",
                    hash,
                    "sourceCommit",
                    sourceCommit,
                    "createdBy",
                    createdBy,
                    "createdAt",
                    createdAt);
        }
    }

    private record Plan(
            UUID id, String compilerVersion, String json, String hash, Instant createdAt) {
        Map<String, Object> asMap() {
            return Map.of(
                    "planId",
                    id,
                    "compilerVersion",
                    compilerVersion,
                    "planHash",
                    hash,
                    "plan",
                    json,
                    "createdAt",
                    createdAt);
        }
    }
}
