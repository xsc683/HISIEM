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

    private final ManagedDetectionRepositoryPort repository;
    private final RuleService rules;
    private final ObjectMapper mapper =
            new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    private final DetectionPlanCompiler compiler = new DetectionPlanCompiler();
    private final DetectionRuntimeService runtime;
    private final String sourceCommit;

    @Autowired
    public ManagedDetectionService(
            ManagedDetectionRepositoryPort repository,
            RuleService rules,
            @Value("${app.detection.source-commit:working-tree}") String sourceCommit,
            DetectionRuntimeService runtime) {
        this.repository = repository;
        this.rules = rules;
        this.runtime = runtime;
        this.sourceCommit = sourceCommit;
    }

    /** Compatibility constructor for direct JDBC-based test and migration callers. */
    public ManagedDetectionService(
            ManagedDetectionRepository repository,
            RuleService rules,
            String sourceCommit,
            DetectionRuntimeService runtime) {
        this((ManagedDetectionRepositoryPort) repository, rules, sourceCommit, runtime);
    }

    @Transactional(readOnly = true)
    public ManagedDetectionInspection inspect(String ruleKey, String actor) {
        Map<String, Object> rule = rules.get(ruleKey);
        RuleRevision revision = findCurrentRevision(rule);
        DetectionPlanArtifact plan = revision == null ? null : findPlan(revision);
        RuleDeployment deployment = findDeployment(TenantContext.id(), ruleKey);
        Map<String, Object> runtimeView = runtime.inspect(TenantContext.id(), ruleKey);
        return new ManagedDetectionInspection(rule, revision, plan, deployment, runtimeView);
    }

    @Transactional
    public RuleDeployment deploy(
            String tenantId, String ruleKey, DeploymentCommand command, String actor) {
        return setDesiredState(tenantId, ruleKey, command, DesiredState.RUNNING, actor);
    }

    /**
     * Persists a single YAML snapshot as desired state in one transaction, then reconciles all
     * touched rules once. The physical Flink controller is intentionally not involved.
     */
    @Transactional
    public List<DeploymentSummary> deployAll(
            String tenantId, List<Map<String, Object>> ruleSnapshot, String actor) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        if (ruleSnapshot == null || ruleSnapshot.isEmpty()) {
            return List.of();
        }
        List<String> ruleKeys = new ArrayList<>();
        List<DeploymentSummary> summaries = new ArrayList<>();
        for (Map<String, Object> rule : ruleSnapshot) {
            if (rule == null || rule.get("id") == null) {
                throw new IllegalArgumentException("rule snapshot contains a rule without id");
            }
            String ruleKey = String.valueOf(rule.get("id"));
            DesiredState desiredState =
                    Boolean.TRUE.equals(rule.get("enabled"))
                            ? DesiredState.RUNNING
                            : DesiredState.STOPPED;
            RuleDeployment deployment =
                    setDesiredStateForRule(
                            tenantId,
                            ruleKey,
                            rule,
                            DeploymentCommand.empty(),
                            desiredState,
                            actor,
                            false);
            ruleKeys.add(ruleKey);
            summaries.add(pendingSummary(deployment));
        }
        runtime.reconcileDesiredStates(tenantId, ruleKeys);
        return List.copyOf(summaries);
    }

    @Transactional
    public RuleDeployment stop(String tenantId, String ruleKey, String actor) {
        return setDesiredState(
                tenantId, ruleKey, DeploymentCommand.empty(), DesiredState.STOPPED, actor);
    }

    @Transactional
    public RuleDeployment rollback(
            String tenantId, String ruleKey, DeploymentCommand command, String actor) {
        if (command == null || command.revisionId() == null) {
            throw new IllegalArgumentException("revisionId is required for rollback");
        }
        return setDesiredState(tenantId, ruleKey, command, DesiredState.RUNNING, actor);
    }

    private RuleDeployment setDesiredState(
            String tenantId,
            String ruleKey,
            DeploymentCommand command,
            DesiredState desiredState,
            String actor) {
        Map<String, Object> rule = rules.get(ruleKey);
        return setDesiredStateForRule(
                tenantId,
                ruleKey,
                rule,
                command == null ? DeploymentCommand.empty() : command,
                desiredState,
                actor,
                true);
    }

    private RuleDeployment setDesiredStateForRule(
            String tenantId,
            String ruleKey,
            Map<String, Object> rule,
            DeploymentCommand command,
            DesiredState desiredState,
            String actor,
            boolean reconcile) {
        RuleRevision revision = revisionFor(rule, command.revisionId(), actor);
        DetectionPlanArtifact plan = ensurePlan(rule, revision);
        UUID deploymentId = UUID.randomUUID();
        RuleDeployment previous = findDeployment(tenantId, ruleKey);
        String requestedCluster = command.targetCluster();
        String targetCluster =
                requestedCluster == null || requestedCluster.isBlank()
                        ? previous == null || previous.targetCluster() == null
                                ? "default"
                                : previous.targetCluster()
                        : requestedCluster;
        if (sameDesiredState(previous, revision, plan, desiredState, targetCluster)) {
            if (reconcile) {
                // Even an idempotent mutation reconciles so missing assignments/status can be
                // repaired.
                runtime.reconcileDesiredState(
                        tenantId, ruleKey, targetCluster, desiredState.name());
            }
            return previous;
        }
        int updated =
                repository.updateDesiredState(
                        new ManagedDetectionRepositoryPort.DesiredStateCommand(
                                revision.revisionId(),
                                desiredState,
                                targetCluster,
                                tenantId,
                                ruleKey));
        if (updated == 0) {
            repository.insertDeployment(
                    new ManagedDetectionRepositoryPort.NewDeployment(
                            deploymentId,
                            tenantId,
                            ruleKey,
                            revision.revisionId(),
                            desiredState,
                            targetCluster));
        }
        RuleDeployment deployment = findDeployment(tenantId, ruleKey);
        insertHistory(deployment, actor);
        if (reconcile) {
            // This call joins the surrounding transaction; no physical Flink operation is
            // performed.
            runtime.reconcileDesiredState(tenantId, ruleKey, targetCluster, desiredState.name());
        }
        return deployment;
    }

    private DeploymentSummary pendingSummary(RuleDeployment deployment) {
        return new DeploymentSummary(
                deployment.ruleKey(),
                deployment.deploymentId(),
                deployment.desiredState(),
                deployment.targetCluster(),
                deployment.generation(),
                deployment.status());
    }

    private RuleRevision findCurrentRevision(Map<String, Object> rule) {
        String ruleKey = String.valueOf(rule.get("id"));
        String definition = json(rule);
        String hash = sha256(definition);
        RuleRevision row = repository.findRevision(ruleKey, hash);
        return row;
    }

    private DetectionPlanArtifact findPlan(RuleRevision revision) {
        return repository.findPlan(revision.revisionId(), DetectionPlanCompiler.VERSION);
    }

    private boolean sameDesiredState(
            RuleDeployment previous,
            RuleRevision requestedRevision,
            DetectionPlanArtifact requestedPlan,
            DesiredState desiredState,
            String targetCluster) {
        if (previous == null
                || desiredState != previous.desiredState()
                || !targetCluster.equals(previous.targetCluster())) {
            return false;
        }
        UUID previousRevisionId = previous.desiredRevisionId();
        // Revision provenance is part of logical desired state even when the compiled physical
        // plan hash is unchanged.  An explicit deploy of a newer equivalent revision must be
        // recorded, while the runtime layer can keep the existing physical generation.
        if (!requestedRevision.revisionId().equals(previousRevisionId)) {
            return false;
        }
        DetectionPlanArtifact previousPlan =
                previousRevisionId == null ? null : findPlan(previousRevisionId);
        return previousPlan != null && previousPlan.planHash().equals(requestedPlan.planHash());
    }

    private DetectionPlanArtifact findPlan(UUID revisionId) {
        return repository.findPlan(revisionId, DetectionPlanCompiler.VERSION);
    }

    private RuleRevision revisionFor(Map<String, Object> rule, UUID requestedId, String actor) {
        if (requestedId == null) return ensureRevision(rule, actor);
        RuleRevision row = repository.findRevision(requestedId, String.valueOf(rule.get("id")));
        if (row == null)
            throw new NotFoundException("revision not found or does not belong to rule");
        return row;
    }

    private RuleRevision ensureRevision(Map<String, Object> rule, String actor) {
        String ruleKey = String.valueOf(rule.get("id"));
        String definition = json(rule);
        String hash = sha256(definition);
        RuleRevision existing = repository.findRevision(ruleKey, hash);
        if (existing != null) return existing;
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
        RuleRevision revision =
                new RuleRevision(
                        UUID.randomUUID(),
                        ruleKey,
                        repository.latestRevisionNumber(ruleKey) + 1,
                        definition,
                        hash,
                        sourceCommit,
                        actor == null || actor.isBlank() ? "system" : actor,
                        Instant.now());
        try {
            repository.insertRevision(revision);
        } catch (DuplicateKeyException e) {
            return ensureRevision(rule, actor);
        }
        return repository.findRevision(ruleKey, hash);
    }

    private DetectionPlanArtifact ensurePlan(Map<String, Object> rule, RuleRevision revision) {
        DetectionPlanArtifact existing =
                repository.findPlan(revision.revisionId(), DetectionPlanCompiler.VERSION);
        // A revision is immutable: an existing artifact is authoritative and must not be recompiled
        // or rehashed merely because a caller inspects or deploys the rule again.
        if (existing != null) return existing;

        Map<String, Object> immutableRule;
        try {
            immutableRule = mapper.readValue(revision.definitionJson(), Map.class);
            if (immutableRule == null) {
                throw new IllegalStateException("definition JSON root must be an object");
            }
        } catch (Exception failure) {
            throw new IllegalStateException(
                    "invalid immutable RuleRevision definition for revision "
                            + revision.revisionId(),
                    failure);
        }
        DetectionPlanCompiler.CompiledPlan compiled =
                compiler.compile(immutableRule, revision.revision());
        UUID planId = UUID.randomUUID();
        try {
            repository.insertPlan(
                    new DetectionPlanArtifact(
                            planId,
                            revision.revisionId(),
                            DetectionPlanCompiler.VERSION,
                            compiled.json(),
                            compiled.hash(),
                            Instant.now()));
        } catch (DuplicateKeyException ignored) {
            // Another request created the immutable artifact; return that canonical row below.
        }
        DetectionPlanArtifact stored =
                repository.findPlan(revision.revisionId(), DetectionPlanCompiler.VERSION);
        if (stored == null)
            throw new IllegalStateException("detection plan artifact was not created");
        return stored;
    }

    private RuleDeployment findDeployment(String tenantId, String ruleKey) {
        return repository.findDeployment(tenantId, ruleKey);
    }

    private void insertHistory(RuleDeployment deployment, String actor) {
        if (deployment == null) return;
        repository.insertHistory(deployment, actor);
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
}
