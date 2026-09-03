package com.xscsiem.hsiem_platform.rules;

import java.util.UUID;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Repository;

/** MyBatis adapter implementing the managed-detection domain port. */
@Primary
@Repository
public class MyBatisManagedDetectionRepository implements ManagedDetectionRepositoryPort {
    private final ManagedDetectionMapper mapper;

    public MyBatisManagedDetectionRepository(ManagedDetectionMapper mapper) {
        this.mapper = java.util.Objects.requireNonNull(mapper, "mapper must not be null");
    }

    @Override
    public RuleRevision findRevision(String ruleKey, String contentHash) {
        return mapper.findRevisionByHash(ruleKey, contentHash);
    }

    @Override
    public RuleRevision findRevision(UUID revisionId, String ruleKey) {
        return mapper.findRevision(revisionId, ruleKey);
    }

    @Override
    public DetectionPlanArtifact findPlan(UUID revisionId, String compilerVersion) {
        return mapper.findPlan(revisionId, compilerVersion);
    }

    @Override
    public int updateCatalog(String ruleKey, String name, String description, String category) {
        return mapper.updateCatalog(ruleKey, name, description, category);
    }

    @Override
    public void insertCatalog(String ruleKey, String name, String description, String category) {
        mapper.insertCatalog(ruleKey, name, description, category);
    }

    @Override
    public int latestRevisionNumber(String ruleKey) {
        return mapper.latestRevisionNumber(ruleKey);
    }

    @Override
    public void insertRevision(RuleRevision revision) {
        mapper.insertRevision(revision);
    }

    @Override
    public void insertPlan(DetectionPlanArtifact plan) {
        mapper.insertPlan(plan);
    }

    @Override
    public RuleDeployment findDeployment(String tenantId, String ruleKey) {
        return mapper.findDeployment(tenantId, ruleKey);
    }

    @Override
    public int updateDesiredState(ManagedDetectionRepositoryPort.DesiredStateCommand command) {
        return mapper.updateDesiredState(command);
    }

    @Override
    public void insertDeployment(ManagedDetectionRepositoryPort.NewDeployment command) {
        mapper.insertDeployment(command);
    }

    @Override
    public void insertHistory(RuleDeployment deployment, String actor) {
        mapper.insertHistory(deployment, actor);
    }
}
