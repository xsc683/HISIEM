package com.xscsiem.hsiem_platform.rules.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Desired/observed boundary for managed detection jobs.  It persists intent and observations;
 * it deliberately does not start, stop, or otherwise call a physical Flink controller.
 */
@Service
public class DetectionRuntimeService {

    private static final String DEFAULT_SOURCE_FAMILY = "siem-events";
    private static final String DEFAULT_CLUSTER = "default";

    private final DetectionRuntimeRepository repository;
    private final int groupBuckets;
    private final RuntimeManifestCodec codec = new RuntimeManifestCodec();
    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    public DetectionRuntimeService(JdbcTemplate jdbc,
                                   @Value("${app.detection.group-buckets:1}") int groupBuckets) {
        this(new DetectionRuntimeRepository(jdbc), groupBuckets);
    }

    public DetectionRuntimeService(JdbcTemplate jdbc) {
        this(jdbc, 1);
    }

    public DetectionRuntimeService(DetectionRuntimeRepository repository, int groupBuckets) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        if (groupBuckets <= 0) {
            throw new IllegalArgumentException("app.detection.group-buckets must be greater than zero");
        }
        this.groupBuckets = groupBuckets;
    }

    /**
     * Reconciles one desired-state mutation without rewriting unrelated assignments or groups.
     */
    @Transactional
    public Map<String, Object> reconcileDesiredState(String tenantId, String ruleKey,
                                                      String targetCluster, String desiredState) {
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
     * Applies a batch of already-persisted desired deployments in one transaction.  The caller
     * writes all desired rows first; this method only rebuilds groups touched by those rules.
     */
    @Transactional
    public List<Map<String, Object>> reconcileDesiredStates(String tenantId,
                                                              Collection<String> ruleKeys) {
        tenant(tenantId);
        if (ruleKeys == null || ruleKeys.isEmpty()) {
            return List.of();
        }
        List<String> orderedRuleKeys = ruleKeys.stream()
                .map(key -> required(key, "ruleKey"))
                .distinct().sorted().toList();
        Set<String> affectedGroups = new HashSet<>();
        Map<String, Map<String, Object>> oldStatuses = new HashMap<>();
        for (String ruleKey : orderedRuleKeys) {
            affectedGroups.addAll(repository.findAssignmentGroups(tenantId, ruleKey));
            Map<String, Object> oldStatus = repository.findRuntimeStatus(tenantId, ruleKey);
            oldStatuses.put(ruleKey, oldStatus);
            if (oldStatus != null && text(oldStatus, "group_key") != null) {
                affectedGroups.add(text(oldStatus, "group_key"));
            }
        }

        List<DesiredRule> desiredRules = repository.findDesiredRunning(
                tenantId, com.xscsiem.hsiem_platform.rules.DetectionPlanCompiler.VERSION)
                .stream().map(this::desiredRule).toList();
        Map<String, DesiredRule> desiredByRule = desiredRules.stream().collect(
                java.util.stream.Collectors.toMap(DesiredRule::ruleKey, rule -> rule,
                        (left, right) -> right, LinkedHashMap::new));
        for (DesiredRule desired : desiredRules) {
            if (orderedRuleKeys.contains(desired.ruleKey())) {
                affectedGroups.add(desired.groupKey());
            }
        }

        Map<String, Map<String, Object>> existingGroups = new LinkedHashMap<>();
        for (String groupKey : affectedGroups.stream().sorted().toList()) {
            Map<String, Object> group = repository.findGroup(tenantId, groupKey);
            if (group != null) {
                existingGroups.put(groupKey, group);
            }
        }
        Map<String, List<DesiredRule>> grouped = new HashMap<>();
        for (DesiredRule desired : desiredRules) {
            if (affectedGroups.contains(desired.groupKey())) {
                grouped.computeIfAbsent(desired.groupKey(), ignored -> new ArrayList<>()).add(desired);
            }
        }

        Map<String, Long> generations = new HashMap<>();
        for (String groupKey : affectedGroups.stream().sorted().toList()) {
            Map<String, Object> existing = existingGroups.get(groupKey);
            List<DesiredRule> members = grouped.getOrDefault(groupKey, List.of());
            if (existing == null && members.isEmpty()) {
                continue;
            }
            GroupMetadata metadata = metadata(groupKey, existing, members, DEFAULT_CLUSTER);
            List<RuntimeManifest.Member> manifestMembers = members.stream()
                    .sorted(Comparator.comparing(DesiredRule::ruleKey))
                    .map(rule -> new RuntimeManifest.Member(rule.ruleKey(), rule.revision(), rule.planHash()))
                    .toList();
            long generation = nextGeneration(existing, true, manifestMembers);
            RuntimeManifest expected = new RuntimeManifest(RuntimeManifest.SCHEMA_VERSION, tenantId,
                    metadata.targetCluster(), groupKey, generation, manifestMembers);
            repository.upsertGroup(tenantId, groupKey, metadata.targetCluster(), metadata.sourceFamily(),
                    metadata.category(), metadata.bucket(), generation, codec.encode(expected), codec.specHash(expected));
            generations.put(groupKey, generation);
        }

        // Only the rules in this batch are changed.  Assignments for every other rule/group remain
        // untouched, including assignments in the same tenant but a different group.
        for (String ruleKey : orderedRuleKeys) {
            repository.deleteAssignment(tenantId, ruleKey);
            DesiredRule desired = desiredByRule.get(ruleKey);
            if (desired != null) {
                Long generation = generations.get(desired.groupKey());
                if (generation == null) {
                    throw new IllegalStateException("missing group generation for " + desired.groupKey());
                }
                repository.upsertAssignment(tenantId, desired.ruleKey(), desired.deploymentId(),
                        desired.revision(), desired.planId(), desired.planHash(), desired.groupKey(), generation);
            }
        }

        // A changed group invalidates prior observations for all current members of that group.
        for (DesiredRule desired : desiredRules) {
            if (!affectedGroups.contains(desired.groupKey())) {
                continue;
            }
            Map<String, Object> memberDeployment = repository.findDeployment(tenantId, desired.ruleKey());
            if (memberDeployment != null) {
                repository.upsertPendingStatus(tenantId, desired.ruleKey(),
                        uuid(memberDeployment, "deployment_id"), desired.groupKey(),
                        text(memberDeployment, "target_cluster"));
                repository.updateDeploymentRuntimeState(tenantId, desired.ruleKey(),
                        RuleRuntimeState.PENDING, null, null);
            }
        }

        // STOPPED rules retain their status row and old group scope, but have no assignment.
        for (String ruleKey : orderedRuleKeys) {
            if (desiredByRule.containsKey(ruleKey)) {
                continue;
            }
            Map<String, Object> deployment = repository.findDeployment(tenantId, ruleKey);
            if (deployment == null) {
                continue;
            }
            Map<String, Object> oldStatus = oldStatuses.get(ruleKey);
            String statusGroup = oldStatus == null ? null : text(oldStatus, "group_key");
            repository.upsertPendingStatus(tenantId, ruleKey, uuid(deployment, "deployment_id"),
                    statusGroup, text(deployment, "target_cluster"));
        }

        return orderedRuleKeys.stream().map(ruleKey -> inspect(tenantId, ruleKey)).toList();
    }

    public List<Map<String, Object>> reconcileDesiredStateBatch(String tenantId,
                                                                  Collection<String> ruleKeys) {
        return reconcileDesiredStates(tenantId, ruleKeys);
    }

    public List<Map<String, Object>> synchronizeDesiredStates(String tenantId,
                                                                Collection<String> ruleKeys) {
        return reconcileDesiredStates(tenantId, ruleKeys);
    }

    public Map<String, Object> synchronizeDesiredState(String tenantId, String ruleKey,
                                                        String targetCluster, String desiredState) {
        return reconcileDesiredState(tenantId, ruleKey, targetCluster, desiredState);
    }

    public Map<String, Object> applyDesiredState(String tenantId, String ruleKey,
                                                  String targetCluster, String desiredState) {
        return reconcileDesiredState(tenantId, ruleKey, targetCluster, desiredState);
    }

    /** Returns tenant-scoped desired assignment, group, status, and expected/observed details. */
    @Transactional(readOnly = true)
    public Map<String, Object> inspect(String tenantId, String ruleKey) {
        tenant(tenantId);
        required(ruleKey, "ruleKey");
        Map<String, Object> deployment = repository.findDeployment(tenantId, ruleKey);
        Map<String, Object> assignment = repository.findAssignment(tenantId, ruleKey);
        Map<String, Object> status = repository.findRuntimeStatus(tenantId, ruleKey);
        String groupKey = assignment == null ? text(status, "group_key") : text(assignment, "group_key");
        Map<String, Object> group = groupKey == null ? null : repository.findGroup(tenantId, groupKey);
        Map<String, Object> latestObserved = group == null ? null
                : repository.findLatestObservedManifest(tenantId, groupKey, text(group, "target_cluster"));

        Map<String, Object> desired = new LinkedHashMap<>();
        if (deployment != null) {
            desired.put("state", deployment.get("desired_state"));
            desired.put("generation", deployment.get("generation"));
            desired.put("targetCluster", deployment.get("target_cluster"));
        }
        if (group != null) {
            desired.put("jobGroupKey", group.get("group_key"));
            desired.put("desiredGeneration", group.get("desired_generation"));
            desired.put("expectedManifestJson", group.get("expected_manifest_json"));
            desired.put("expectedManifestHash", group.get("expected_manifest_hash"));
        }
        Map<String, Object> observed = new LinkedHashMap<>();
        if (latestObserved != null) {
            observed.put("jobId", latestObserved.get("job_id"));
            observed.put("jobKey", latestObserved.get("job_key"));
            observed.put("generation", latestObserved.get("generation"));
            observed.put("manifestJson", latestObserved.get("manifest_json"));
            observed.put("manifestHash", latestObserved.get("manifest_hash"));
            observed.put("observedAt", latestObserved.get("observed_at"));
        }
        Map<String, Object> desiredVsObserved = new LinkedHashMap<>();
        desiredVsObserved.put("desired", desired);
        desiredVsObserved.put("observed", observed);
        String desiredState = deployment == null ? null : text(deployment, "desired_state");
        String runtimeState = status == null ? null : text(status, "runtime_state");
        boolean inSync = ("RUNNING".equalsIgnoreCase(desiredState)
                && RuleRuntimeState.RUNNING.name().equals(runtimeState))
                || ("STOPPED".equalsIgnoreCase(desiredState)
                && RuleRuntimeState.DISABLED.name().equals(runtimeState));
        desiredVsObserved.put("inSync", inSync);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("assignment", assignment);
        response.put("jobGroup", group);
        response.put("runtimeStatus", status);
        response.put("desiredVsObserved", desiredVsObserved);
        return response;
    }

    public RuntimeDiff observe(RuntimeManifest observedManifest, RuntimeJobState runtimeJobState) {
        return observe(observedManifest, runtimeJobState, null, null);
    }

    public RuntimeDiff observe(RuntimeManifest observedManifest, RuntimeJobState runtimeJobState,
                               String errorMessage) {
        return observe(observedManifest, runtimeJobState, null, errorMessage);
    }

    public RuntimeDiff observe(RuntimeManifest observedManifest, String runtimeJobState,
                               String errorMessage) {
        return observe(observedManifest, RuntimeJobState.from(runtimeJobState), null, errorMessage);
    }

    public RuntimeDiff observe(RuntimeManifest observedManifest, String runtimeJobState,
                               String errorCode, String errorMessage) {
        return observe(observedManifest, RuntimeJobState.from(runtimeJobState), errorCode, errorMessage);
    }

    /**
     * Records one observation and reconciles only the exact tenant/group/cluster scope carried by
     * that manifest.  No observation can update another tenant, cluster, or job group.
     */
    @Transactional
    public RuntimeDiff observe(RuntimeManifest observedManifest, RuntimeJobState runtimeJobState,
                               String errorCode, String errorMessage) {
        codec.validateSchemaVersion(observedManifest);
        Objects.requireNonNull(runtimeJobState, "runtimeJobState must not be null");
        String tenantId = tenant(observedManifest.tenantId());
        String error = firstNonBlank(errorMessage, errorCode);
        String json = codec.encode(observedManifest);
        String hash = codec.specHash(observedManifest);
        repository.insertObservedManifest(tenantId, observedManifest.jobGroupKey(),
                observedManifest.targetCluster(), observedManifest.jobId(), observedManifest.jobKey(),
                observedManifest.generation(), json, hash);

        Map<String, Object> group = repository.findGroup(tenantId, observedManifest.jobGroupKey(),
                observedManifest.targetCluster());
        RuntimeManifest expected;
        if (group == null) {
            // Unknown scope is persisted for audit but cannot mutate any desired/status row.
            expected = new RuntimeManifest(observedManifest.schemaVersion(), tenantId,
                    observedManifest.targetCluster(), observedManifest.jobGroupKey(),
                    observedManifest.generation(), List.of());
        } else {
            expected = codec.decode(text(group, "expected_manifest_json"));
        }
        RuntimeDiff diff = RuntimeDiff.compare(expected, observedManifest);
        boolean failed = runtimeJobState == RuntimeJobState.FAILED
                || errorCode != null && !errorCode.isBlank()
                || errorMessage != null && !errorMessage.isBlank();
        RuleRuntimeState groupState = groupState(expected, diff, runtimeJobState, failed);
        if (group != null) {
            repository.updateGroupObserved(tenantId, observedManifest.jobGroupKey(),
                    observedManifest.targetCluster(), groupState, observedManifest.jobId(),
                    observedManifest.jobKey(), error);
        }

        Map<String, RuntimeManifest.Member> observedMembers = observedManifest.members().stream()
                .collect(java.util.stream.Collectors.toMap(RuntimeManifest.Member::ruleKey,
                        member -> member, (left, right) -> left, LinkedHashMap::new));
        List<Map<String, Object>> statuses = group == null ? List.of()
                : repository.findStatusesInScope(tenantId, observedManifest.jobGroupKey(),
                observedManifest.targetCluster());
        Map<String, Map<String, Object>> statusByRule = new HashMap<>();
        for (Map<String, Object> status : statuses) {
            statusByRule.put(text(status, "rule_key"), status);
        }

        for (RuntimeManifest.Member expectedMember : expected.members()) {
            Map<String, Object> deployment = repository.findDeployment(tenantId, expectedMember.ruleKey());
            if (deployment == null || !observedManifest.targetCluster().equals(text(deployment, "target_cluster"))) {
                continue;
            }
            Map<String, Object> status = statusByRule.get(expectedMember.ruleKey());
            if (status == null) {
                // This only repairs rows created before the runtime status foundation; it remains
                // tenant and exact cluster scoped.
                repository.upsertPendingStatus(tenantId, expectedMember.ruleKey(),
                        uuid(deployment, "deployment_id"), observedManifest.jobGroupKey(),
                        observedManifest.targetCluster());
                status = repository.findRuntimeStatus(tenantId, expectedMember.ruleKey());
                statusByRule.put(expectedMember.ruleKey(), status);
            }
            RuntimeManifest.Member actual = observedMembers.get(expectedMember.ruleKey());
            RuleRuntimeState state = memberState(expected, expectedMember, actual, diff,
                    text(deployment, "desired_state"), runtimeJobState, failed);
            repository.updateRuntimeStatusObserved(tenantId, expectedMember.ruleKey(),
                    observedManifest.jobGroupKey(), observedManifest.targetCluster(),
                    observedManifest.jobId(), observedManifest.jobKey(),
                    actual == null ? null : actual.revision(), observedManifest.generation(),
                    actual == null ? null : actual.planHash(), state,
                    failed ? errorCode : null, failed ? errorMessage : null);
            repository.updateDeploymentRuntimeState(tenantId, expectedMember.ruleKey(), state,
                    observedManifest.generation(), failed ? error : null);
        }

        // A stopped rule has no expected member by design.  Its old group/status row is retained
        // and only this exact observed scope may transition it to DISABLED.
        for (Map<String, Object> status : statuses) {
            String ruleKey = text(status, "rule_key");
            if (expected.containsRule(ruleKey)) {
                continue;
            }
            Map<String, Object> deployment = repository.findDeployment(tenantId, ruleKey);
            if (deployment == null || !observedManifest.targetCluster().equals(text(deployment, "target_cluster"))) {
                continue;
            }
            String desiredState = text(deployment, "desired_state");
            RuntimeManifest.Member actual = observedMembers.get(ruleKey);
            RuleRuntimeState state = memberState(expected, null, actual, diff, desiredState,
                    runtimeJobState, failed);
            repository.updateRuntimeStatusObserved(tenantId, ruleKey, observedManifest.jobGroupKey(),
                    observedManifest.targetCluster(), observedManifest.jobId(), observedManifest.jobKey(),
                    actual == null ? null : actual.revision(), observedManifest.generation(),
                    actual == null ? null : actual.planHash(), state,
                    failed ? errorCode : null, failed ? errorMessage : null);
            repository.updateDeploymentRuntimeState(tenantId, ruleKey, state,
                    observedManifest.generation(), failed ? error : null);
        }

        // If a known rule appears unexpectedly but has no status row in this scope, do not create
        // one: its assignment belongs to another group and must not be stolen by this observation.
        return diff;
    }

    public String groupKey(String tenantId, String targetCluster, String sourceFamily,
                           String category, String ruleKey) {
        tenant(tenantId);
        targetCluster = required(targetCluster, "targetCluster");
        sourceFamily = sourceFamily == null || sourceFamily.isBlank()
                ? DEFAULT_SOURCE_FAMILY : sourceFamily;
        category = required(category, "category");
        ruleKey = required(ruleKey, "ruleKey");
        int bucket = bucket(tenantId, sourceFamily, category, ruleKey);
        return groupKey(tenantId, targetCluster, sourceFamily, category, bucket);
    }

    public int bucket(String tenantId, String sourceFamily, String category, String ruleKey) {
        String value = hashPart(tenant(tenantId)) + hashPart(required(sourceFamily, "sourceFamily"))
                + hashPart(required(category, "category")) + hashPart(required(ruleKey, "ruleKey"));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            long unsigned = ((long) (digest[0] & 0xff) << 24)
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

    private RuleRuntimeState groupState(RuntimeManifest expected, RuntimeDiff diff,
                                        RuntimeJobState jobState, boolean failed) {
        if (failed) return RuleRuntimeState.FAILED;
        return switch (jobState) {
            case UNKNOWN -> RuleRuntimeState.UNKNOWN;
            case PENDING -> RuleRuntimeState.PENDING;
            case DEPLOYING -> RuleRuntimeState.DEPLOYING;
            case STOPPED -> expected.members().isEmpty() ? RuleRuntimeState.DISABLED
                    : RuleRuntimeState.DEGRADED;
            case RUNNING -> diff.isEmpty() ? RuleRuntimeState.RUNNING : RuleRuntimeState.DEGRADED;
            case FAILED -> RuleRuntimeState.FAILED;
        };
    }

    private RuleRuntimeState memberState(RuntimeManifest expected,
                                         RuntimeManifest.Member expectedMember,
                                         RuntimeManifest.Member actual,
                                         RuntimeDiff diff, String desiredState,
                                         RuntimeJobState jobState, boolean failed) {
        if (failed || jobState == RuntimeJobState.FAILED) return RuleRuntimeState.FAILED;
        if (jobState == RuntimeJobState.UNKNOWN) return RuleRuntimeState.UNKNOWN;
        if (jobState == RuntimeJobState.PENDING) return RuleRuntimeState.PENDING;
        if (jobState == RuntimeJobState.DEPLOYING) return RuleRuntimeState.DEPLOYING;
        if ("STOPPED".equalsIgnoreCase(desiredState) && actual == null
                && jobState == RuntimeJobState.STOPPED) {
            return RuleRuntimeState.DISABLED;
        }
        if (expectedMember != null && actual != null && jobState == RuntimeJobState.RUNNING
                && !diff.generationMismatch()
                && expectedMember.revision() == actual.revision()
                && expectedMember.planHash().equals(actual.planHash())) {
            return RuleRuntimeState.RUNNING;
        }
        return RuleRuntimeState.OUT_OF_SYNC;
    }

    private DesiredRule desiredRule(Map<String, Object> row) {
        String tenantId = text(row, "tenant_id");
        String ruleKey = text(row, "rule_key");
        String sourceFamily = sourceFamily(text(row, "plan_json"));
        String category = required(text(row, "category"), "category");
        String targetCluster = required(text(row, "target_cluster"), "target_cluster");
        int bucket = bucket(tenantId, sourceFamily, category, ruleKey);
        return new DesiredRule(ruleKey, uuid(row, "deployment_id"), number(row, "revision"),
                uuid(row, "plan_id"), text(row, "plan_hash"), text(row, "plan_json"), targetCluster, sourceFamily,
                category, bucket, groupKey(tenantId, targetCluster, sourceFamily, category, bucket));
    }

    private GroupMetadata metadata(String groupKey, Map<String, Object> existing,
                                   List<DesiredRule> members, String fallbackCluster) {
        if (!members.isEmpty()) {
            DesiredRule first = members.getFirst();
            return new GroupMetadata(first.targetCluster(), first.sourceFamily(), first.category(), first.bucket());
        }
        if (existing != null) {
            return new GroupMetadata(required(text(existing, "target_cluster"), "target_cluster"),
                    required(text(existing, "source_family"), "source_family"),
                    required(text(existing, "category"), "category"),
                    intNumber(existing, "bucket"));
        }
        throw new IllegalStateException("cannot derive metadata for group " + groupKey
                + " on cluster " + fallbackCluster);
    }

    private long nextGeneration(Map<String, Object> existing, boolean affected,
                                List<RuntimeManifest.Member> desiredMembers) {
        if (existing == null) return 1L;
        long oldGeneration = number(existing, "desired_generation");
        boolean changed = true;
        try {
            RuntimeManifest old = codec.decode(text(existing, "expected_manifest_json"));
            changed = !old.members().equals(desiredMembers);
        } catch (RuntimeException ignored) {
            // An invalid legacy manifest must be replaced and receives a new generation.
        }
        return affected || changed ? oldGeneration + 1 : oldGeneration;
    }

    private String sourceFamily(String planJson) {
        if (planJson == null || planJson.isBlank()) return DEFAULT_SOURCE_FAMILY;
        try {
            JsonNode root = mapper.readTree(planJson);
            JsonNode source = root.path("inputs").path(0).path("source");
            return source.isTextual() && !source.textValue().isBlank()
                    ? source.textValue() : DEFAULT_SOURCE_FAMILY;
        } catch (Exception ignored) {
            return DEFAULT_SOURCE_FAMILY;
        }
    }

    private static String groupKey(String tenantId, String targetCluster, String sourceFamily,
                                   String category, int bucket) {
        return "tenant=" + tenantId + "|cluster=" + targetCluster + "|source=" + sourceFamily
                + "|category=" + category + "|bucket=" + bucket;
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

    private static String text(Map<String, Object> row, String key) {
        if (row == null) return null;
        Object value = row.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static long number(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (!(value instanceof Number number)) {
            try {
                return Long.parseLong(String.valueOf(value));
            } catch (RuntimeException e) {
                throw new IllegalStateException("expected numeric " + key + ": " + value, e);
            }
        }
        return number.longValue();
    }

    private static int intNumber(Map<String, Object> row, String key) {
        long value = number(row, key);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IllegalStateException("numeric " + key + " is outside integer range");
        }
        return (int) value;
    }

    private static UUID uuid(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value instanceof UUID uuid) return uuid;
        try {
            return UUID.fromString(String.valueOf(value));
        } catch (RuntimeException e) {
            throw new IllegalStateException("expected UUID " + key + ": " + value, e);
        }
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second != null && !second.isBlank() ? second : null;
    }

    private record DesiredRule(String ruleKey, UUID deploymentId, long revision, UUID planId,
                               String planHash, String planJson, String targetCluster, String sourceFamily,
                               String category, int bucket, String groupKey) { }

    private record GroupMetadata(String targetCluster, String sourceFamily, String category, int bucket) { }
}
