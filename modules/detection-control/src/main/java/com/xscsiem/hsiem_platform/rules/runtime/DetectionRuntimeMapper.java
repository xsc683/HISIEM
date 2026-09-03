package com.xscsiem.hsiem_platform.rules.runtime;

import java.util.List;
import java.util.Set;
import org.apache.ibatis.annotations.Param;

/** Plain MyBatis mapper for the detection runtime persistence contract. */
public interface DetectionRuntimeMapper {
    Set<String> findAssignmentGroups(
            @Param("tenantId") String tenantId, @Param("ruleKey") String ruleKey);

    List<DetectionRuntimeRepository.DetectionJobGroupRow> findGroupRows(
            @Param("tenantId") String tenantId);

    DetectionRuntimeRepository.DetectionJobGroupRow findGroup(
            @Param("tenantId") String tenantId, @Param("groupKey") String groupKey);

    DetectionRuntimeRepository.DetectionJobGroupRow findGroupByTarget(
            @Param("tenantId") String tenantId,
            @Param("groupKey") String groupKey,
            @Param("targetCluster") String targetCluster);

    List<DetectionRuntimeRepository.DesiredDetectionRow> findDesiredRunningRows(
            @Param("tenantId") String tenantId, @Param("compilerVersion") String compilerVersion);

    DetectionRuntimeRepository.RuleDeploymentRow findDeploymentRow(
            @Param("tenantId") String tenantId, @Param("ruleKey") String ruleKey);

    DetectionRuntimeRepository.RuntimeAssignmentRow findAssignmentRow(
            @Param("tenantId") String tenantId, @Param("ruleKey") String ruleKey);

    DetectionRuntimeRepository.RuleRuntimeStatusRow findRuntimeStatusRow(
            @Param("tenantId") String tenantId, @Param("ruleKey") String ruleKey);

    List<DetectionRuntimeRepository.RuntimeStatusScopeRow> findStatusesInScopeRows(
            @Param("tenantId") String tenantId,
            @Param("groupKey") String groupKey,
            @Param("targetCluster") String targetCluster);

    DetectionRuntimeRepository.RuntimeManifestRow findLatestObservedManifest(
            @Param("tenantId") String tenantId,
            @Param("groupKey") String groupKey,
            @Param("targetCluster") String targetCluster);

    Integer lockCurrentObservation(
            @org.apache.ibatis.annotations.Param("fence") ObservationFence fence);

    int deleteAssignments(@Param("tenantId") String tenantId);

    int deleteAssignment(@Param("tenantId") String tenantId, @Param("ruleKey") String ruleKey);

    int updateAssignment(DetectionRuntimeRepositoryPort.AssignmentCommand command);

    int insertAssignment(DetectionRuntimeRepositoryPort.AssignmentCommand command);

    int updateAssignmentGenerations(
            @Param("tenantId") String tenantId,
            @Param("groupKey") String groupKey,
            @Param("generation") long generation);

    int updateGroup(DetectionRuntimeRepositoryPort.GroupCommand command);

    int insertGroup(DetectionRuntimeRepositoryPort.GroupCommand command);

    int updatePendingStatus(DetectionRuntimeRepositoryPort.PendingStatusCommand command);

    int insertPendingStatus(DetectionRuntimeRepositoryPort.PendingStatusCommand command);

    int insertObservedManifest(DetectionRuntimeRepositoryPort.ManifestCommand command);

    int insertObservedManifestFenced(DetectionRuntimeRepositoryPort.FencedManifestCommand command);

    int updateGroupObserved(DetectionRuntimeRepositoryPort.ObservedGroupCommand command);

    int updateGroupObservedFenced(
            DetectionRuntimeRepositoryPort.FencedObservedGroupCommand command);

    int updateRuntimeStatusObserved(DetectionRuntimeRepositoryPort.ObservedStatusCommand command);

    int updateRuntimeStatusObservedFenced(
            DetectionRuntimeRepositoryPort.FencedObservedStatusCommand command);

    int updateDeploymentRuntimeState(
            DetectionRuntimeRepositoryPort.ObservedDeploymentCommand command);

    int updateDeploymentRuntimeStateFenced(
            DetectionRuntimeRepositoryPort.FencedObservedDeploymentCommand command);
}
