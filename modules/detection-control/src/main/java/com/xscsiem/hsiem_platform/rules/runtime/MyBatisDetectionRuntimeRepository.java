package com.xscsiem.hsiem_platform.rules.runtime;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** MyBatis adapter for the detection runtime persistence port. */
@Primary
@Repository
public class MyBatisDetectionRuntimeRepository implements DetectionRuntimeRepositoryPort {
    private final DetectionRuntimeMapper mapper;

    public MyBatisDetectionRuntimeRepository(DetectionRuntimeMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    }

    @Override
    public Set<String> findAssignmentGroups(String tenantId, String ruleKey) {
        return mapper.findAssignmentGroups(tenantId, ruleKey);
    }

    @Override
    public List<DetectionRuntimeRepository.DetectionJobGroupRow> findGroupRows(String tenantId) {
        return mapper.findGroupRows(tenantId);
    }

    @Override
    public DetectionRuntimeRepository.DetectionJobGroupRow findGroupRow(
            String tenantId, String groupKey) {
        return mapper.findGroup(tenantId, groupKey);
    }

    @Override
    public DetectionRuntimeRepository.DetectionJobGroupRow findGroupRow(
            String tenantId, String groupKey, String targetCluster) {
        return targetCluster == null
                ? mapper.findGroup(tenantId, groupKey)
                : mapper.findGroupByTarget(tenantId, groupKey, targetCluster);
    }

    @Override
    public List<DetectionRuntimeRepository.DesiredDetectionRow> findDesiredRunningRows(
            String tenantId, String compilerVersion) {
        return mapper.findDesiredRunningRows(tenantId, compilerVersion);
    }

    @Override
    public DetectionRuntimeRepository.RuleDeploymentRow findDeploymentRow(
            String tenantId, String ruleKey) {
        return mapper.findDeploymentRow(tenantId, ruleKey);
    }

    @Override
    public DetectionRuntimeRepository.RuntimeAssignmentRow findAssignmentRow(
            String tenantId, String ruleKey) {
        return mapper.findAssignmentRow(tenantId, ruleKey);
    }

    @Override
    public DetectionRuntimeRepository.RuleRuntimeStatusRow findRuntimeStatusRow(
            String tenantId, String ruleKey) {
        return mapper.findRuntimeStatusRow(tenantId, ruleKey);
    }

    @Override
    public List<DetectionRuntimeRepository.RuntimeStatusScopeRow> findStatusesInScopeRows(
            String tenantId, String groupKey, String targetCluster) {
        return mapper.findStatusesInScopeRows(tenantId, groupKey, targetCluster);
    }

    @Override
    public DetectionRuntimeRepository.RuntimeManifestRow findLatestObservedManifestRow(
            String tenantId, String groupKey, String targetCluster) {
        return mapper.findLatestObservedManifest(tenantId, groupKey, targetCluster);
    }

    @Override
    public boolean lockCurrentObservation(ObservationFence fence) {
        return mapper.lockCurrentObservation(fence) != null;
    }

    @Override
    public void deleteAssignments(String tenantId) {
        mapper.deleteAssignments(tenantId);
    }

    @Override
    public void deleteAssignment(String tenantId, String ruleKey) {
        mapper.deleteAssignment(tenantId, ruleKey);
    }

    @Override
    @Transactional
    public void upsertAssignment(DetectionRuntimeRepositoryPort.AssignmentCommand command) {
        if (mapper.updateAssignment(command) == 0) {
            try {
                mapper.insertAssignment(command);
            } catch (DuplicateKeyException ignored) {
                mapper.updateAssignment(command);
            }
        }
    }

    @Override
    public void insertAssignment(DetectionRuntimeRepositoryPort.AssignmentCommand command) {
        mapper.insertAssignment(command);
    }

    @Override
    public void updateAssignmentGenerations(String tenantId, String groupKey, long generation) {
        mapper.updateAssignmentGenerations(tenantId, groupKey, generation);
    }

    @Override
    @Transactional
    public void upsertGroup(DetectionRuntimeRepositoryPort.GroupCommand command) {
        if (mapper.updateGroup(command) == 0) {
            try {
                mapper.insertGroup(command);
            } catch (DuplicateKeyException ignored) {
                mapper.updateGroup(command);
            }
        }
    }

    @Override
    @Transactional
    public void upsertPendingStatus(DetectionRuntimeRepositoryPort.PendingStatusCommand command) {
        if (mapper.updatePendingStatus(command) == 0) {
            try {
                mapper.insertPendingStatus(command);
            } catch (DuplicateKeyException ignored) {
                mapper.updatePendingStatus(command);
            }
        }
    }

    @Override
    public void insertObservedManifest(DetectionRuntimeRepositoryPort.ManifestCommand command) {
        mapper.insertObservedManifest(command);
    }

    @Override
    public int insertObservedManifestFenced(
            DetectionRuntimeRepositoryPort.FencedManifestCommand command) {
        return mapper.insertObservedManifestFenced(command);
    }

    @Override
    public void updateGroupObserved(DetectionRuntimeRepositoryPort.ObservedGroupCommand command) {
        mapper.updateGroupObserved(command);
    }

    @Override
    public int updateGroupObservedFenced(
            DetectionRuntimeRepositoryPort.FencedObservedGroupCommand command) {
        return mapper.updateGroupObservedFenced(command);
    }

    @Override
    public void updateRuntimeStatusObserved(
            DetectionRuntimeRepositoryPort.ObservedStatusCommand command) {
        mapper.updateRuntimeStatusObserved(command);
    }

    @Override
    public int updateRuntimeStatusObservedFenced(
            DetectionRuntimeRepositoryPort.FencedObservedStatusCommand command) {
        return mapper.updateRuntimeStatusObservedFenced(command);
    }

    @Override
    public void updateDeploymentRuntimeState(
            DetectionRuntimeRepositoryPort.ObservedDeploymentCommand command) {
        mapper.updateDeploymentRuntimeState(command);
    }

    @Override
    public int updateDeploymentRuntimeStateFenced(
            DetectionRuntimeRepositoryPort.FencedObservedDeploymentCommand command) {
        return mapper.updateDeploymentRuntimeStateFenced(command);
    }
}
