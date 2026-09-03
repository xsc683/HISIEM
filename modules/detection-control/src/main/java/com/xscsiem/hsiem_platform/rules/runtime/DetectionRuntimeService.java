package com.xscsiem.hsiem_platform.rules.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Desired/observed boundary for managed detection jobs. It persists intent and observations; it
 * deliberately does not start, stop, or otherwise call a physical Flink controller.
 */
@Service
public class DetectionRuntimeService {

    private static final String DEFAULT_SOURCE_FAMILY = "siem-events";
    private static final String DEFAULT_CLUSTER = "default";

    private final DetectionRuntimeRepositoryPort repository;
    private final int groupBuckets;
    private final RuntimeManifestCodec codec = new RuntimeManifestCodec();
    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    public DetectionRuntimeService(
            DetectionRuntimeRepositoryPort repository,
            @Value("${app.detection.group-buckets:1}") int groupBuckets) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        if (groupBuckets <= 0) {
            throw new IllegalArgumentException(
                    "app.detection.group-buckets must be greater than zero");
        }
        this.groupBuckets = groupBuckets;
    }

    /** Reconciles one desired-state mutation without rewriting unrelated assignments or groups. */
    @Transactional
    public Map<String, Object> reconcileDesiredState(
            String tenantId, String ruleKey, String targetCluster, String desiredState) {
        tenant(tenantId);
        required(ruleKey, "ruleKey");
        if (targetCluster == null || targetCluster.isBlank()) {
            targetCluster = DEFAULT_CLUSTER;
        }
        desiredState = required(desiredState, "desiredState").toUpperCase();
        if (!Set.of("RUNNING", "STOPPED").contains(desiredState)) {
            throw new IllegalArgumentException("desiredState must be RUNNING or STOPPED");
        }
        reconcileDesiredStates(tenantId, List.of(ruleKey));
        return inspect(tenantId, ruleKey);
    }

    /**
     * Applies a batch of already-persisted desired deployments in one transaction. The caller
     * writes all desired rows first; this method only rebuilds groups touched by those rules.
     */
    @Transactional
    public List<Map<String, Object>> reconcileDesiredStates(
            String tenantId, Collection<String> ruleKeys) {
        tenant(tenantId);
        if (ruleKeys == null || ruleKeys.isEmpty()) {
            return List.of();
        }
        List<String> orderedRuleKeys =
                ruleKeys.stream().map(key -> required(key, "ruleKey")).distinct().sorted().toList();
        Set<String> affectedGroups = new HashSet<>();
        Map<String, DetectionRuntimeRepository.RuleRuntimeStatusRow> oldStatuses = new HashMap<>();
        for (String ruleKey : orderedRuleKeys) {
            affectedGroups.addAll(repository.findAssignmentGroups(tenantId, ruleKey));
            DetectionRuntimeRepository.RuleRuntimeStatusRow oldStatus =
                    repository.findRuntimeStatusRow(tenantId, ruleKey);
            oldStatuses.put(ruleKey, oldStatus);
            if (oldStatus != null && oldStatus.groupKey() != null) {
                affectedGroups.add(oldStatus.groupKey());
            }
        }

        List<DesiredRule> desiredRules =
                repository
                        .findDesiredRunningRows(
                                tenantId,
                                com.xscsiem.hsiem_platform.rules.DetectionPlanCompiler.VERSION)
                        .stream()
                        .map(this::desiredRule)
                        .toList();
        Map<String, DesiredRule> desiredByRule =
                desiredRules.stream()
                        .collect(
                                java.util.stream.Collectors.toMap(
                                        DesiredRule::ruleKey,
                                        rule -> rule,
                                        (left, right) -> right,
                                        LinkedHashMap::new));
        for (DesiredRule desired : desiredRules) {
            if (orderedRuleKeys.contains(desired.ruleKey())) {
                affectedGroups.add(desired.groupKey());
            }
        }

        Map<String, DetectionRuntimeRepository.DetectionJobGroupRow> existingGroups =
                new LinkedHashMap<>();
        for (String groupKey : affectedGroups.stream().sorted().toList()) {
            DetectionRuntimeRepository.DetectionJobGroupRow group =
                    repository.findGroupRow(tenantId, groupKey);
            if (group != null) {
                existingGroups.put(groupKey, group);
            }
        }
        Map<String, List<DesiredRule>> grouped = new HashMap<>();
        for (DesiredRule desired : desiredRules) {
            if (affectedGroups.contains(desired.groupKey())) {
                grouped.computeIfAbsent(desired.groupKey(), ignored -> new ArrayList<>())
                        .add(desired);
            }
        }

        Map<String, Long> generations = new HashMap<>();
        Map<String, Boolean> changedGroups = new HashMap<>();
        for (String groupKey : affectedGroups.stream().sorted().toList()) {
            DetectionRuntimeRepository.DetectionJobGroupRow existing = existingGroups.get(groupKey);
            List<DesiredRule> members = grouped.getOrDefault(groupKey, List.of());
            if (existing == null && members.isEmpty()) {
                continue;
            }
            GroupMetadata metadata = metadata(groupKey, existing, members, DEFAULT_CLUSTER);
            List<RuntimeManifest.Member> manifestMembers =
                    members.stream()
                            .sorted(Comparator.comparing(DesiredRule::ruleKey))
                            .map(
                                    rule ->
                                            new RuntimeManifest.Member(
                                                    rule.ruleKey(),
                                                    rule.revision(),
                                                    rule.planHash()))
                            .toList();
            boolean changed = groupSpecChanged(existing, metadata, manifestMembers);
            long generation = nextGeneration(existing, changed);
            RuntimeManifest expected =
                    new RuntimeManifest(
                            RuntimeManifest.SCHEMA_VERSION,
                            tenantId,
                            metadata.targetCluster(),
                            groupKey,
                            generation,
                            manifestMembers);
            if (changed) {
                repository.upsertGroup(
                        tenantId,
                        groupKey,
                        metadata.targetCluster(),
                        metadata.sourceFamily(),
                        metadata.category(),
                        metadata.bucket(),
                        generation,
                        codec.encode(expected),
                        codec.specHash(expected));
                repository.updateAssignmentGenerations(tenantId, groupKey, generation);
            }
            generations.put(groupKey, generation);
            changedGroups.put(groupKey, changed);
        }

        // Keep equivalent assignments untouched, while repairing missing or stale rows in touched
        // groups.  STOPPED rules have no desired assignment and are removed only when one exists.
        for (DesiredRule desired : desiredRules) {
            if (!affectedGroups.contains(desired.groupKey())) {
                continue;
            }
            Long generation = generations.get(desired.groupKey());
            if (generation == null) {
                throw new IllegalStateException(
                        "missing group generation for " + desired.groupKey());
            }
            DetectionRuntimeRepository.RuntimeAssignmentRow assignment =
                    repository.findAssignmentRow(tenantId, desired.ruleKey());
            if (!assignmentEquivalent(assignment, desired, generation)) {
                repository.upsertAssignment(
                        tenantId,
                        desired.ruleKey(),
                        desired.deploymentId(),
                        desired.revision(),
                        desired.planId(),
                        desired.planHash(),
                        desired.groupKey(),
                        generation);
            }
        }
        for (String ruleKey : orderedRuleKeys) {
            if (!desiredByRule.containsKey(ruleKey)
                    && repository.findAssignmentRow(tenantId, ruleKey) != null) {
                repository.deleteAssignment(tenantId, ruleKey);
            }
        }

        // A changed group invalidates prior observations for all current members.  An unchanged
        // group only gets a pending status when its status row is absent or out of scope.
        for (DesiredRule desired : desiredRules) {
            if (!affectedGroups.contains(desired.groupKey())) {
                continue;
            }
            DetectionRuntimeRepository.RuleDeploymentRow memberDeployment =
                    repository.findDeploymentRow(tenantId, desired.ruleKey());
            if (memberDeployment == null) {
                continue;
            }
            DetectionRuntimeRepository.RuleRuntimeStatusRow status =
                    repository.findRuntimeStatusRow(tenantId, desired.ruleKey());
            boolean statusNeedsRepair =
                    status == null
                            || !Objects.equals(desired.groupKey(), status.groupKey())
                            || !Objects.equals(desired.targetCluster(), status.targetCluster());
            if (changedGroups.getOrDefault(desired.groupKey(), false) || statusNeedsRepair) {
                repository.upsertPendingStatus(
                        tenantId,
                        desired.ruleKey(),
                        memberDeployment.deploymentId(),
                        desired.groupKey(),
                        memberDeployment.targetCluster());
                repository.updateDeploymentRuntimeState(
                        tenantId, desired.ruleKey(), RuleRuntimeState.PENDING, null, null);
            }
        }

        // STOPPED rules retain their status row and old group scope, but have no assignment.
        for (String ruleKey : orderedRuleKeys) {
            if (desiredByRule.containsKey(ruleKey)) {
                continue;
            }
            DetectionRuntimeRepository.RuleDeploymentRow deployment =
                    repository.findDeploymentRow(tenantId, ruleKey);
            if (deployment == null) {
                continue;
            }
            DetectionRuntimeRepository.RuleRuntimeStatusRow oldStatus = oldStatuses.get(ruleKey);
            String statusGroup = oldStatus == null ? null : oldStatus.groupKey();
            boolean statusNeedsRepair =
                    oldStatus == null
                            || !Objects.equals(deployment.deploymentId(), oldStatus.deploymentId())
                            || !Objects.equals(
                                    deployment.targetCluster(), oldStatus.targetCluster());
            if (changedGroups.getOrDefault(statusGroup, false) || statusNeedsRepair) {
                repository.upsertPendingStatus(
                        tenantId,
                        ruleKey,
                        deployment.deploymentId(),
                        statusGroup,
                        deployment.targetCluster());
            }
        }

        return orderedRuleKeys.stream().map(ruleKey -> inspect(tenantId, ruleKey)).toList();
    }

    public List<Map<String, Object>> reconcileDesiredStateBatch(
            String tenantId, Collection<String> ruleKeys) {
        return reconcileDesiredStates(tenantId, ruleKeys);
    }

    public List<Map<String, Object>> synchronizeDesiredStates(
            String tenantId, Collection<String> ruleKeys) {
        return reconcileDesiredStates(tenantId, ruleKeys);
    }

    public Map<String, Object> synchronizeDesiredState(
            String tenantId, String ruleKey, String targetCluster, String desiredState) {
        return reconcileDesiredState(tenantId, ruleKey, targetCluster, desiredState);
    }

    public Map<String, Object> applyDesiredState(
            String tenantId, String ruleKey, String targetCluster, String desiredState) {
        return reconcileDesiredState(tenantId, ruleKey, targetCluster, desiredState);
    }

    /** Returns tenant-scoped desired assignment, group, status, and expected/observed details. */
    @Transactional(readOnly = true)
    public Map<String, Object> inspect(String tenantId, String ruleKey) {
        tenant(tenantId);
        required(ruleKey, "ruleKey");
        DetectionRuntimeRepository.RuleDeploymentRow deployment =
                repository.findDeploymentRow(tenantId, ruleKey);
        DetectionRuntimeRepository.RuntimeAssignmentRow assignment =
                repository.findAssignmentRow(tenantId, ruleKey);
        DetectionRuntimeRepository.RuleRuntimeStatusRow status =
                repository.findRuntimeStatusRow(tenantId, ruleKey);
        String groupKey =
                assignment == null
                        ? status == null ? null : status.groupKey()
                        : assignment.groupKey();
        DetectionRuntimeRepository.DetectionJobGroupRow group =
                groupKey == null ? null : repository.findGroupRow(tenantId, groupKey);
        DetectionRuntimeRepository.RuntimeManifestRow latestObserved =
                group == null
                        ? null
                        : repository.findLatestObservedManifestRow(
                                tenantId, groupKey, group.targetCluster());

        Map<String, Object> desired = new LinkedHashMap<>();
        if (deployment != null) {
            desired.put("state", deployment.desiredState());
            desired.put("generation", deployment.generation());
            desired.put("targetCluster", deployment.targetCluster());
        }
        if (group != null) {
            desired.put("jobGroupKey", group.groupKey());
            desired.put("desiredGeneration", group.desiredGeneration());
            desired.put("expectedManifestJson", group.expectedManifestJson());
            desired.put("expectedManifestHash", group.expectedManifestHash());
        }
        Map<String, Object> observed = new LinkedHashMap<>();
        if (latestObserved != null) {
            observed.put("jobId", latestObserved.jobId());
            observed.put("jobKey", latestObserved.jobKey());
            observed.put("generation", latestObserved.generation());
            observed.put("manifestJson", latestObserved.manifestJson());
            observed.put("manifestHash", latestObserved.manifestHash());
            observed.put("observedAt", latestObserved.observedAt());
        }
        Map<String, Object> desiredVsObserved = new LinkedHashMap<>();
        desiredVsObserved.put("desired", desired);
        desiredVsObserved.put("observed", observed);
        String desiredState = deployment == null ? null : deployment.desiredState();
        String runtimeState = status == null ? null : status.runtimeState();
        boolean inSync =
                ("RUNNING".equalsIgnoreCase(desiredState)
                                && RuleRuntimeState.RUNNING.name().equals(runtimeState))
                        || ("STOPPED".equalsIgnoreCase(desiredState)
                                && RuleRuntimeState.DISABLED.name().equals(runtimeState));
        desiredVsObserved.put("inSync", inSync);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("assignment", assignment == null ? null : assignmentMap(assignment));
        response.put("jobGroup", group == null ? null : groupMap(group));
        response.put("runtimeStatus", status == null ? null : statusMap(status));
        response.put("desiredVsObserved", desiredVsObserved);
        return response;
    }

    public RuntimeDiff observe(RuntimeManifest observedManifest, RuntimeJobState runtimeJobState) {
        return observe(observedManifest, runtimeJobState, null, null);
    }

    public RuntimeDiff observe(
            RuntimeManifest observedManifest,
            RuntimeJobState runtimeJobState,
            String errorMessage) {
        return observe(observedManifest, runtimeJobState, null, errorMessage);
    }

    public RuntimeDiff observe(
            RuntimeManifest observedManifest, String runtimeJobState, String errorMessage) {
        return observe(observedManifest, RuntimeJobState.from(runtimeJobState), null, errorMessage);
    }

    public RuntimeDiff observe(
            RuntimeManifest observedManifest,
            String runtimeJobState,
            String errorCode,
            String errorMessage) {
        return observe(
                observedManifest, RuntimeJobState.from(runtimeJobState), errorCode, errorMessage);
    }

    /**
     * Records one observation and reconciles only the exact tenant/group/cluster scope carried by
     * that manifest. No observation can update another tenant, cluster, or job group.
     */
    @Transactional
    public RuntimeDiff observe(
            RuntimeManifest observedManifest,
            RuntimeJobState runtimeJobState,
            String errorCode,
            String errorMessage) {
        return observeInternal(observedManifest, runtimeJobState, errorCode, errorMessage, null);
    }

    /**
     * Records an observation only for the controller ownership epoch that claimed the group. The
     * group is locked and validated before any append or observed-state mutation; a stale
     * controller therefore cannot leave a partial observation behind.
     */
    @Transactional
    public RuntimeDiff observeFenced(
            RuntimeManifest observedManifest,
            RuntimeJobState runtimeJobState,
            String errorCode,
            String errorMessage,
            ObservationFence fence) {
        Objects.requireNonNull(fence, "fence must not be null");
        return observeInternal(observedManifest, runtimeJobState, errorCode, errorMessage, fence);
    }

    private RuntimeDiff observeInternal(
            RuntimeManifest observedManifest,
            RuntimeJobState runtimeJobState,
            String errorCode,
            String errorMessage,
            ObservationFence fence) {
        codec.validateSchemaVersion(observedManifest);
        Objects.requireNonNull(runtimeJobState, "runtimeJobState must not be null");
        String tenantId = tenant(observedManifest.tenantId());
        if (fence != null && !repository.lockCurrentObservation(fence)) {
            throw new StaleObservationException(
                    "controller observation fence is no longer current");
        }
        if (fence != null
                && (!fence.tenantId().equals(observedManifest.tenantId())
                        || !fence.groupKey().equals(observedManifest.jobGroupKey())
                        || !fence.targetCluster().equals(observedManifest.targetCluster())
                        || fence.desiredGeneration() != observedManifest.generation())) {
            throw new StaleObservationException("controller observation is outside fenced scope");
        }
        String error = firstNonBlank(errorMessage, errorCode);
        String json = codec.encode(observedManifest);
        String hash = codec.specHash(observedManifest);

        DetectionRuntimeRepository.DetectionJobGroupRow group =
                repository.findGroupRow(
                        tenantId, observedManifest.jobGroupKey(), observedManifest.targetCluster());
        RuntimeManifest expected;
        if (group == null) {
            // Unknown scope is persisted for audit but cannot mutate any desired/status row.
            expected =
                    new RuntimeManifest(
                            observedManifest.schemaVersion(),
                            tenantId,
                            observedManifest.targetCluster(),
                            observedManifest.jobGroupKey(),
                            observedManifest.generation(),
                            List.of());
        } else {
            expected = codec.decode(group.expectedManifestJson());
        }
        RuntimeDiff diff = RuntimeDiff.compare(expected, observedManifest);
        boolean failed =
                runtimeJobState == RuntimeJobState.FAILED
                        || errorCode != null && !errorCode.isBlank()
                        || errorMessage != null && !errorMessage.isBlank();
        RuleRuntimeState groupState = groupState(expected, diff, runtimeJobState, failed);
        if (group != null) {
            if (fence == null) {
                repository.updateGroupObserved(
                        tenantId,
                        observedManifest.jobGroupKey(),
                        observedManifest.targetCluster(),
                        groupState,
                        observedManifest.jobId(),
                        observedManifest.jobKey(),
                        error);
            } else if (repository.updateGroupObservedFenced(
                            fence,
                            groupState,
                            observedManifest.jobId(),
                            observedManifest.jobKey(),
                            error)
                    != 1) {
                throw new StaleObservationException(
                        "controller observation fence expired before commit");
            }
        }

        if (fence == null) {
            repository.insertObservedManifest(
                    tenantId,
                    observedManifest.jobGroupKey(),
                    observedManifest.targetCluster(),
                    observedManifest.jobId(),
                    observedManifest.jobKey(),
                    observedManifest.generation(),
                    json,
                    hash);
        } else if (repository.insertObservedManifestFenced(
                        fence, observedManifest.jobId(), observedManifest.jobKey(), json, hash)
                != 1) {
            throw new StaleObservationException(
                    "controller observation fence expired before manifest append");
        }

        Map<String, RuntimeManifest.Member> observedMembers =
                observedManifest.members().stream()
                        .collect(
                                java.util.stream.Collectors.toMap(
                                        RuntimeManifest.Member::ruleKey,
                                        member -> member,
                                        (left, right) -> left,
                                        LinkedHashMap::new));
        List<DetectionRuntimeRepository.RuntimeStatusScopeRow> statuses =
                group == null
                        ? List.of()
                        : repository.findStatusesInScopeRows(
                                tenantId,
                                observedManifest.jobGroupKey(),
                                observedManifest.targetCluster());
        Map<String, DetectionRuntimeRepository.RuntimeStatusScopeRow> statusByRule =
                new HashMap<>();
        for (DetectionRuntimeRepository.RuntimeStatusScopeRow status : statuses) {
            statusByRule.put(status.ruleKey(), status);
        }

        for (RuntimeManifest.Member expectedMember : expected.members()) {
            DetectionRuntimeRepository.RuleDeploymentRow deployment =
                    repository.findDeploymentRow(tenantId, expectedMember.ruleKey());
            if (deployment == null
                    || !observedManifest.targetCluster().equals(deployment.targetCluster())) {
                continue;
            }
            DetectionRuntimeRepository.RuntimeStatusScopeRow status =
                    statusByRule.get(expectedMember.ruleKey());
            if (status == null) {
                // This only repairs rows created before the runtime status foundation; it remains
                // tenant and exact cluster scoped.
                if (fence != null && !repository.lockCurrentObservation(fence)) {
                    throw new StaleObservationException(
                            "controller observation fence expired before status repair");
                }
                repository.upsertPendingStatus(
                        tenantId,
                        expectedMember.ruleKey(),
                        deployment.deploymentId(),
                        observedManifest.jobGroupKey(),
                        observedManifest.targetCluster());
                DetectionRuntimeRepository.RuleRuntimeStatusRow repaired =
                        repository.findRuntimeStatusRow(tenantId, expectedMember.ruleKey());
                status =
                        repaired == null
                                ? null
                                : new DetectionRuntimeRepository.RuntimeStatusScopeRow(
                                        repaired.tenantId(),
                                        repaired.ruleKey(),
                                        repaired.deploymentId(),
                                        repaired.groupKey(),
                                        repaired.targetCluster(),
                                        repaired.jobId(),
                                        repaired.jobKey(),
                                        repaired.observedRevision(),
                                        repaired.observedGeneration(),
                                        repaired.observedPlanHash(),
                                        repaired.runtimeState(),
                                        repaired.errorCode(),
                                        repaired.errorMessage(),
                                        deployment.desiredState(),
                                        deployment.desiredRevisionId(),
                                        deployment.generation(),
                                        deployment.status());
                statusByRule.put(expectedMember.ruleKey(), status);
            }
            RuntimeManifest.Member actual = observedMembers.get(expectedMember.ruleKey());
            RuleRuntimeState state =
                    memberState(
                            expected,
                            expectedMember,
                            actual,
                            diff,
                            deployment.desiredState(),
                            runtimeJobState,
                            failed);
            if (fence == null) {
                repository.updateRuntimeStatusObserved(
                        tenantId,
                        expectedMember.ruleKey(),
                        observedManifest.jobGroupKey(),
                        observedManifest.targetCluster(),
                        observedManifest.jobId(),
                        observedManifest.jobKey(),
                        actual == null ? null : actual.revision(),
                        observedManifest.generation(),
                        actual == null ? null : actual.planHash(),
                        state,
                        failed ? errorCode : null,
                        failed ? errorMessage : null);
                repository.updateDeploymentRuntimeState(
                        tenantId,
                        expectedMember.ruleKey(),
                        state,
                        observedManifest.generation(),
                        failed ? error : null);
            } else {
                if (repository.updateRuntimeStatusObservedFenced(
                                fence,
                                expectedMember.ruleKey(),
                                observedManifest.jobId(),
                                observedManifest.jobKey(),
                                actual == null ? null : actual.revision(),
                                observedManifest.generation(),
                                actual == null ? null : actual.planHash(),
                                state,
                                failed ? errorCode : null,
                                failed ? errorMessage : null)
                        != 1) {
                    throw new StaleObservationException(
                            "controller observation fence expired before status commit");
                }
                if (repository.updateDeploymentRuntimeStateFenced(
                                fence,
                                expectedMember.ruleKey(),
                                state,
                                observedManifest.generation(),
                                failed ? error : null)
                        != 1) {
                    throw new StaleObservationException(
                            "controller observation fence expired before deployment commit");
                }
            }
        }

        // A stopped rule has no expected member by design.  Its old group/status row is retained
        // and only this exact observed scope may transition it to DISABLED.
        for (DetectionRuntimeRepository.RuntimeStatusScopeRow status : statuses) {
            String ruleKey = status.ruleKey();
            if (expected.containsRule(ruleKey)) {
                continue;
            }
            DetectionRuntimeRepository.RuleDeploymentRow deployment =
                    repository.findDeploymentRow(tenantId, ruleKey);
            if (deployment == null
                    || !observedManifest.targetCluster().equals(deployment.targetCluster())) {
                continue;
            }
            String desiredState = deployment.desiredState();
            RuntimeManifest.Member actual = observedMembers.get(ruleKey);
            RuleRuntimeState state =
                    memberState(
                            expected, null, actual, diff, desiredState, runtimeJobState, failed);
            if (fence == null) {
                repository.updateRuntimeStatusObserved(
                        tenantId,
                        ruleKey,
                        observedManifest.jobGroupKey(),
                        observedManifest.targetCluster(),
                        observedManifest.jobId(),
                        observedManifest.jobKey(),
                        actual == null ? null : actual.revision(),
                        observedManifest.generation(),
                        actual == null ? null : actual.planHash(),
                        state,
                        failed ? errorCode : null,
                        failed ? errorMessage : null);
                repository.updateDeploymentRuntimeState(
                        tenantId,
                        ruleKey,
                        state,
                        observedManifest.generation(),
                        failed ? error : null);
            } else {
                if (repository.updateRuntimeStatusObservedFenced(
                                fence,
                                ruleKey,
                                observedManifest.jobId(),
                                observedManifest.jobKey(),
                                actual == null ? null : actual.revision(),
                                observedManifest.generation(),
                                actual == null ? null : actual.planHash(),
                                state,
                                failed ? errorCode : null,
                                failed ? errorMessage : null)
                        != 1) {
                    throw new StaleObservationException(
                            "controller observation fence expired before status commit");
                }
                if (repository.updateDeploymentRuntimeStateFenced(
                                fence,
                                ruleKey,
                                state,
                                observedManifest.generation(),
                                failed ? error : null)
                        != 1) {
                    throw new StaleObservationException(
                            "controller observation fence expired before deployment commit");
                }
            }
        }

        // If a known rule appears unexpectedly but has no status row in this scope, do not create
        // one: its assignment belongs to another group and must not be stolen by this observation.
        return diff;
    }

    public static final class StaleObservationException extends RuntimeException {
        public StaleObservationException(String message) {
            super(message);
        }
    }

    public String groupKey(
            String tenantId,
            String targetCluster,
            String sourceFamily,
            String category,
            String ruleKey) {
        tenant(tenantId);
        targetCluster = required(targetCluster, "targetCluster");
        sourceFamily =
                sourceFamily == null || sourceFamily.isBlank()
                        ? DEFAULT_SOURCE_FAMILY
                        : sourceFamily;
        category = required(category, "category");
        ruleKey = required(ruleKey, "ruleKey");
        int bucket = bucket(tenantId, sourceFamily, category, ruleKey);
        return groupKey(tenantId, targetCluster, sourceFamily, category, bucket);
    }

    public int bucket(String tenantId, String sourceFamily, String category, String ruleKey) {
        String value =
                hashPart(tenant(tenantId))
                        + hashPart(required(sourceFamily, "sourceFamily"))
                        + hashPart(required(category, "category"))
                        + hashPart(required(ruleKey, "ruleKey"));
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8));
            long unsigned =
                    ((long) (digest[0] & 0xff) << 24)
                            | ((long) (digest[1] & 0xff) << 16)
                            | ((long) (digest[2] & 0xff) << 8)
                            | (digest[3] & 0xffL);
            return (int) (unsigned % groupBuckets);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static String hashPart(String value) {
        return value.length() + ":" + value + "|";
    }

    private RuleRuntimeState groupState(
            RuntimeManifest expected, RuntimeDiff diff, RuntimeJobState jobState, boolean failed) {
        if (failed) return RuleRuntimeState.FAILED;
        return switch (jobState) {
            case UNKNOWN -> RuleRuntimeState.UNKNOWN;
            case PENDING -> RuleRuntimeState.PENDING;
            case DEPLOYING -> RuleRuntimeState.DEPLOYING;
            case STOPPED ->
                    expected.members().isEmpty()
                            ? RuleRuntimeState.DISABLED
                            : RuleRuntimeState.DEGRADED;
            case RUNNING -> diff.isEmpty() ? RuleRuntimeState.RUNNING : RuleRuntimeState.DEGRADED;
            case FAILED -> RuleRuntimeState.FAILED;
        };
    }

    private RuleRuntimeState memberState(
            RuntimeManifest expected,
            RuntimeManifest.Member expectedMember,
            RuntimeManifest.Member actual,
            RuntimeDiff diff,
            String desiredState,
            RuntimeJobState jobState,
            boolean failed) {
        if (failed || jobState == RuntimeJobState.FAILED) return RuleRuntimeState.FAILED;
        if (jobState == RuntimeJobState.UNKNOWN) return RuleRuntimeState.UNKNOWN;
        if (jobState == RuntimeJobState.PENDING) return RuleRuntimeState.PENDING;
        if (jobState == RuntimeJobState.DEPLOYING) return RuleRuntimeState.DEPLOYING;
        if ("STOPPED".equalsIgnoreCase(desiredState)
                && actual == null
                && jobState == RuntimeJobState.STOPPED) {
            return RuleRuntimeState.DISABLED;
        }
        if (expectedMember != null
                && actual != null
                && jobState == RuntimeJobState.RUNNING
                && !diff.generationMismatch()
                && expectedMember.revision() == actual.revision()
                && expectedMember.planHash().equals(actual.planHash())) {
            return RuleRuntimeState.RUNNING;
        }
        return RuleRuntimeState.OUT_OF_SYNC;
    }

    private DesiredRule desiredRule(DetectionRuntimeRepository.DesiredDetectionRow row) {
        String tenantId = row.tenantId();
        String ruleKey = row.ruleKey();
        String planJson = row.planJson();
        String planHash = row.planHash();
        if (!sha256(planJson).equals(planHash)) {
            throw new IllegalStateException("detection plan hash mismatch for " + ruleKey);
        }
        PlanMetadata plan = planMetadata(planJson);
        String targetCluster = required(row.targetCluster(), "target_cluster");
        int bucket = bucket(tenantId, plan.sourceFamily(), plan.category(), ruleKey);
        return new DesiredRule(
                ruleKey,
                row.deploymentId(),
                row.revision(),
                row.planId(),
                planHash,
                planJson,
                targetCluster,
                plan.sourceFamily(),
                plan.category(),
                bucket,
                groupKey(tenantId, targetCluster, plan.sourceFamily(), plan.category(), bucket));
    }

    private GroupMetadata metadata(
            String groupKey,
            DetectionRuntimeRepository.DetectionJobGroupRow existing,
            List<DesiredRule> members,
            String fallbackCluster) {
        if (!members.isEmpty()) {
            DesiredRule first = members.getFirst();
            return new GroupMetadata(
                    first.targetCluster(), first.sourceFamily(), first.category(), first.bucket());
        }
        if (existing != null) {
            return new GroupMetadata(
                    required(existing.targetCluster(), "target_cluster"),
                    required(existing.sourceFamily(), "source_family"),
                    required(existing.category(), "category"),
                    existing.bucket());
        }
        throw new IllegalStateException(
                "cannot derive metadata for group " + groupKey + " on cluster " + fallbackCluster);
    }

    private boolean groupSpecChanged(
            DetectionRuntimeRepository.DetectionJobGroupRow existing,
            GroupMetadata metadata,
            List<RuntimeManifest.Member> desiredMembers) {
        if (existing == null) {
            return true;
        }
        if (!metadata.targetCluster().equals(existing.targetCluster())
                || !metadata.sourceFamily().equals(existing.sourceFamily())
                || !metadata.category().equals(existing.category())
                || metadata.bucket() != existing.bucket()) {
            return true;
        }
        try {
            RuntimeManifest old = codec.decode(existing.expectedManifestJson());
            return !physicalMembers(old.members()).equals(physicalMembers(desiredMembers));
        } catch (RuntimeException ignored) {
            return true;
        }
    }

    private static List<String> physicalMembers(List<RuntimeManifest.Member> members) {
        return members.stream()
                .map(member -> member.ruleKey() + "|" + member.planHash())
                .sorted()
                .toList();
    }

    private long nextGeneration(
            DetectionRuntimeRepository.DetectionJobGroupRow existing, boolean changed) {
        if (existing == null) return 1L;
        long oldGeneration = existing.desiredGeneration();
        return changed ? oldGeneration + 1 : oldGeneration;
    }

    private boolean assignmentEquivalent(
            DetectionRuntimeRepository.RuntimeAssignmentRow assignment,
            DesiredRule desired,
            long generation) {
        // Revision and plan_id are provenance of the logical deployment.  The assignment is the
        // physical artifact input, whose identity is the immutable plan hash; equivalent plans
        // must not rewrite an assignment or force a new runtime generation.
        return assignment != null
                && desired.deploymentId().equals(assignment.deploymentId())
                && desired.planHash().equals(assignment.planHash())
                && desired.groupKey().equals(assignment.groupKey())
                && generation == assignment.generation();
    }

    private PlanMetadata planMetadata(String planJson) {
        if (planJson == null || planJson.isBlank()) {
            throw new IllegalStateException("detection plan JSON must not be blank");
        }
        try {
            JsonNode root = mapper.readTree(planJson);
            if (root == null
                    || !root.isObject()
                    || !com.xscsiem.hsiem_platform.rules.DetectionPlanCompiler.SCHEMA_VERSION
                            .equals(root.path("schema_version").textValue())
                    || !com.xscsiem.hsiem_platform.rules.DetectionPlanCompiler.VERSION.equals(
                            root.path("compiler_version").textValue())) {
                throw new IllegalStateException("unsupported detection plan contract");
            }
            String source = root.path("input").path("source").textValue();
            String category = root.path("detection").path("type").textValue();
            return new PlanMetadata(
                    required(source, "input.source"), required(category, "detection.type"));
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("invalid detection plan JSON", e);
        }
    }

    private static String sha256(String value) {
        if (value == null) throw new IllegalStateException("detection plan JSON must not be null");
        try {
            return java.util.HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static String groupKey(
            String tenantId,
            String targetCluster,
            String sourceFamily,
            String category,
            int bucket) {
        return "tenant="
                + tenantId
                + "|cluster="
                + targetCluster
                + "|source="
                + sourceFamily
                + "|category="
                + category
                + "|bucket="
                + bucket;
    }

    private static String tenant(String tenantId) {
        return required(tenantId, "tenantId");
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    /** Converts typed persistence rows to the legacy JSON response shape at the API boundary. */
    private static Map<String, Object> assignmentMap(
            DetectionRuntimeRepository.RuntimeAssignmentRow row) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tenant_id", row.tenantId());
        result.put("rule_key", row.ruleKey());
        result.put("deployment_id", row.deploymentId());
        result.put("revision", row.revision());
        result.put("plan_id", row.planId());
        result.put("plan_hash", row.planHash());
        result.put("group_key", row.groupKey());
        result.put("generation", row.generation());
        result.put("created_at", row.createdAt());
        result.put("updated_at", row.updatedAt());
        return result;
    }

    private static Map<String, Object> groupMap(
            DetectionRuntimeRepository.DetectionJobGroupRow row) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tenant_id", row.tenantId());
        result.put("group_key", row.groupKey());
        result.put("target_cluster", row.targetCluster());
        result.put("source_family", row.sourceFamily());
        result.put("category", row.category());
        result.put("bucket", row.bucket());
        result.put("desired_generation", row.desiredGeneration());
        result.put("expected_manifest_json", row.expectedManifestJson());
        result.put("expected_manifest_hash", row.expectedManifestHash());
        result.put("status", row.status());
        result.put("job_id", row.jobId());
        result.put("job_key", row.jobKey());
        result.put("last_error", row.lastError());
        result.put("created_at", row.createdAt());
        result.put("updated_at", row.updatedAt());
        return result;
    }

    private static Map<String, Object> statusMap(
            DetectionRuntimeRepository.RuleRuntimeStatusRow row) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tenant_id", row.tenantId());
        result.put("rule_key", row.ruleKey());
        result.put("deployment_id", row.deploymentId());
        result.put("group_key", row.groupKey());
        result.put("target_cluster", row.targetCluster());
        result.put("job_id", row.jobId());
        result.put("job_key", row.jobKey());
        result.put("observed_revision", row.observedRevision());
        result.put("observed_generation", row.observedGeneration());
        result.put("observed_plan_hash", row.observedPlanHash());
        result.put("runtime_state", row.runtimeState());
        result.put("error_code", row.errorCode());
        result.put("error_message", row.errorMessage());
        result.put("created_at", row.createdAt());
        result.put("updated_at", row.updatedAt());
        return result;
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank()
                ? first
                : second != null && !second.isBlank() ? second : null;
    }

    private record PlanMetadata(String sourceFamily, String category) {}

    private record DesiredRule(
            String ruleKey,
            UUID deploymentId,
            long revision,
            UUID planId,
            String planHash,
            String planJson,
            String targetCluster,
            String sourceFamily,
            String category,
            int bucket,
            String groupKey) {}

    private record GroupMetadata(
            String targetCluster, String sourceFamily, String category, int bucket) {}
}
