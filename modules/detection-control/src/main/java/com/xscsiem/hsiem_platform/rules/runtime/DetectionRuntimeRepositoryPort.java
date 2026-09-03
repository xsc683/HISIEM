package com.xscsiem.hsiem_platform.rules.runtime;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Domain persistence port for desired detection assignments and observed runtime state. */
public interface DetectionRuntimeRepositoryPort {
    Set<String> findAssignmentGroups(String tenantId, String ruleKey);

    List<DetectionRuntimeRepository.DetectionJobGroupRow> findGroupRows(String tenantId);

    DetectionRuntimeRepository.DetectionJobGroupRow findGroupRow(String tenantId, String groupKey);

    DetectionRuntimeRepository.DetectionJobGroupRow findGroupRow(
            String tenantId, String groupKey, String targetCluster);

    List<DetectionRuntimeRepository.DesiredDetectionRow> findDesiredRunningRows(
            String tenantId, String compilerVersion);

    DetectionRuntimeRepository.RuleDeploymentRow findDeploymentRow(String tenantId, String ruleKey);

    DetectionRuntimeRepository.RuntimeAssignmentRow findAssignmentRow(
            String tenantId, String ruleKey);

    DetectionRuntimeRepository.RuleRuntimeStatusRow findRuntimeStatusRow(
            String tenantId, String ruleKey);

    List<DetectionRuntimeRepository.RuntimeStatusScopeRow> findStatusesInScopeRows(
            String tenantId, String groupKey, String targetCluster);

    DetectionRuntimeRepository.RuntimeManifestRow findLatestObservedManifestRow(
            String tenantId, String groupKey, String targetCluster);

    default List<DetectionRuntimeRepository.DetectionJobGroupRow> findGroups(String tenantId) {
        return findGroupRows(tenantId);
    }

    default DetectionRuntimeRepository.DetectionJobGroupRow findGroup(
            String tenantId, String groupKey) {
        return findGroupRow(tenantId, groupKey);
    }

    default DetectionRuntimeRepository.DetectionJobGroupRow findGroup(
            String tenantId, String groupKey, String targetCluster) {
        return findGroupRow(tenantId, groupKey, targetCluster);
    }

    default List<DetectionRuntimeRepository.DesiredDetectionRow> findDesiredRunning(
            String tenantId, String compilerVersion) {
        return findDesiredRunningRows(tenantId, compilerVersion);
    }

    default DetectionRuntimeRepository.RuleDeploymentRow findDeployment(
            String tenantId, String ruleKey) {
        return findDeploymentRow(tenantId, ruleKey);
    }

    default DetectionRuntimeRepository.RuntimeAssignmentRow findAssignment(
            String tenantId, String ruleKey) {
        return findAssignmentRow(tenantId, ruleKey);
    }

    default DetectionRuntimeRepository.RuleRuntimeStatusRow findRuntimeStatus(
            String tenantId, String ruleKey) {
        return findRuntimeStatusRow(tenantId, ruleKey);
    }

    default List<DetectionRuntimeRepository.RuntimeStatusScopeRow> findStatusesInScope(
            String tenantId, String groupKey, String targetCluster) {
        return findStatusesInScopeRows(tenantId, groupKey, targetCluster);
    }

    default DetectionRuntimeRepository.RuntimeManifestRow findLatestObservedManifest(
            String tenantId, String groupKey, String targetCluster) {
        return findLatestObservedManifestRow(tenantId, groupKey, targetCluster);
    }

    boolean lockCurrentObservation(ObservationFence fence);

    void deleteAssignments(String tenantId);

    void deleteAssignment(String tenantId, String ruleKey);

    void upsertAssignment(AssignmentCommand command);

    default void upsertAssignment(
            String tenantId,
            String ruleKey,
            UUID deploymentId,
            long revision,
            UUID planId,
            String planHash,
            String groupKey,
            long generation) {
        upsertAssignment(
                new AssignmentCommand(
                        tenantId,
                        ruleKey,
                        deploymentId,
                        revision,
                        planId,
                        planHash,
                        groupKey,
                        generation));
    }

    void insertAssignment(AssignmentCommand command);

    default void insertAssignment(
            String tenantId,
            String ruleKey,
            UUID deploymentId,
            long revision,
            UUID planId,
            String planHash,
            String groupKey,
            long generation) {
        insertAssignment(
                new AssignmentCommand(
                        tenantId,
                        ruleKey,
                        deploymentId,
                        revision,
                        planId,
                        planHash,
                        groupKey,
                        generation));
    }

    void updateAssignmentGenerations(String tenantId, String groupKey, long generation);

    void upsertGroup(GroupCommand command);

    default void upsertGroup(
            String tenantId,
            String groupKey,
            String targetCluster,
            String sourceFamily,
            String category,
            int bucket,
            long generation,
            String expectedJson,
            String expectedHash) {
        upsertGroup(
                new GroupCommand(
                        tenantId,
                        groupKey,
                        targetCluster,
                        sourceFamily,
                        category,
                        bucket,
                        generation,
                        expectedJson,
                        expectedHash));
    }

    void upsertPendingStatus(PendingStatusCommand command);

    default void upsertPendingStatus(
            String tenantId,
            String ruleKey,
            UUID deploymentId,
            String groupKey,
            String targetCluster) {
        upsertPendingStatus(
                new PendingStatusCommand(tenantId, ruleKey, deploymentId, groupKey, targetCluster));
    }

    void insertObservedManifest(ManifestCommand command);

    default void insertObservedManifest(
            String tenantId,
            String groupKey,
            String targetCluster,
            String jobId,
            String jobKey,
            long generation,
            String json,
            String hash) {
        insertObservedManifest(
                new ManifestCommand(
                        tenantId, groupKey, targetCluster, jobId, jobKey, generation, json, hash));
    }

    int insertObservedManifestFenced(FencedManifestCommand command);

    default int insertObservedManifestFenced(
            ObservationFence fence, String jobId, String jobKey, String json, String hash) {
        return insertObservedManifestFenced(
                new FencedManifestCommand(fence, jobId, jobKey, json, hash));
    }

    void updateGroupObserved(ObservedGroupCommand command);

    default void updateGroupObserved(
            String tenantId,
            String groupKey,
            String targetCluster,
            RuleRuntimeState state,
            String jobId,
            String jobKey,
            String errorMessage) {
        updateGroupObserved(
                new ObservedGroupCommand(
                        tenantId, groupKey, targetCluster, state, jobId, jobKey, errorMessage));
    }

    int updateGroupObservedFenced(FencedObservedGroupCommand command);

    default int updateGroupObservedFenced(
            ObservationFence fence,
            RuleRuntimeState state,
            String jobId,
            String jobKey,
            String errorMessage) {
        return updateGroupObservedFenced(
                new FencedObservedGroupCommand(fence, state, jobId, jobKey, errorMessage));
    }

    void updateRuntimeStatusObserved(ObservedStatusCommand command);

    default void updateRuntimeStatusObserved(
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
        updateRuntimeStatusObserved(
                new ObservedStatusCommand(
                        tenantId,
                        ruleKey,
                        groupKey,
                        targetCluster,
                        jobId,
                        jobKey,
                        revision,
                        generation,
                        planHash,
                        state,
                        errorCode,
                        errorMessage));
    }

    int updateRuntimeStatusObservedFenced(FencedObservedStatusCommand command);

    default int updateRuntimeStatusObservedFenced(
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
        return updateRuntimeStatusObservedFenced(
                new FencedObservedStatusCommand(
                        fence,
                        ruleKey,
                        jobId,
                        jobKey,
                        revision,
                        generation,
                        planHash,
                        state,
                        errorCode,
                        errorMessage));
    }

    void updateDeploymentRuntimeState(ObservedDeploymentCommand command);

    default void updateDeploymentRuntimeState(
            String tenantId,
            String ruleKey,
            RuleRuntimeState state,
            Long observedGeneration,
            String errorMessage) {
        updateDeploymentRuntimeState(
                new ObservedDeploymentCommand(
                        tenantId, ruleKey, state, observedGeneration, errorMessage));
    }

    int updateDeploymentRuntimeStateFenced(FencedObservedDeploymentCommand command);

    default int updateDeploymentRuntimeStateFenced(
            ObservationFence fence,
            String ruleKey,
            RuleRuntimeState state,
            Long observedGeneration,
            String errorMessage) {
        return updateDeploymentRuntimeStateFenced(
                new FencedObservedDeploymentCommand(
                        fence, ruleKey, state, observedGeneration, errorMessage));
    }

    record AssignmentCommand(
            String tenantId,
            String ruleKey,
            UUID deploymentId,
            long revision,
            UUID planId,
            String planHash,
            String groupKey,
            long generation) {}

    record GroupCommand(
            String tenantId,
            String groupKey,
            String targetCluster,
            String sourceFamily,
            String category,
            int bucket,
            long generation,
            String expectedJson,
            String expectedHash) {}

    record PendingStatusCommand(
            String tenantId,
            String ruleKey,
            UUID deploymentId,
            String groupKey,
            String targetCluster) {}

    record ManifestCommand(
            String tenantId,
            String groupKey,
            String targetCluster,
            String jobId,
            String jobKey,
            long generation,
            String json,
            String hash) {}

    record FencedManifestCommand(
            ObservationFence fence, String jobId, String jobKey, String json, String hash) {}

    record ObservedGroupCommand(
            String tenantId,
            String groupKey,
            String targetCluster,
            RuleRuntimeState state,
            String jobId,
            String jobKey,
            String errorMessage) {}

    record FencedObservedGroupCommand(
            ObservationFence fence,
            RuleRuntimeState state,
            String jobId,
            String jobKey,
            String errorMessage) {}

    record ObservedStatusCommand(
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
            String errorMessage) {}

    record FencedObservedStatusCommand(
            ObservationFence fence,
            String ruleKey,
            String jobId,
            String jobKey,
            Long revision,
            Long generation,
            String planHash,
            RuleRuntimeState state,
            String errorCode,
            String errorMessage) {}

    record ObservedDeploymentCommand(
            String tenantId,
            String ruleKey,
            RuleRuntimeState state,
            Long observedGeneration,
            String errorMessage) {}

    record FencedObservedDeploymentCommand(
            ObservationFence fence,
            String ruleKey,
            RuleRuntimeState state,
            Long observedGeneration,
            String errorMessage) {}
}
