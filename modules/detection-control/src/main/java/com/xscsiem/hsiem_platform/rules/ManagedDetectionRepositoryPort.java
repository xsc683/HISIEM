package com.xscsiem.hsiem_platform.rules;

import java.util.UUID;

/** Domain persistence port for immutable rule artifacts and desired deployments. */
public interface ManagedDetectionRepositoryPort {
    RuleRevision findRevision(String ruleKey, String contentHash);

    RuleRevision findRevision(UUID revisionId, String ruleKey);

    DetectionPlanArtifact findPlan(UUID revisionId, String compilerVersion);

    int updateCatalog(String ruleKey, String name, String description, String category);

    void insertCatalog(String ruleKey, String name, String description, String category);

    int latestRevisionNumber(String ruleKey);

    void insertRevision(RuleRevision revision);

    void insertPlan(DetectionPlanArtifact plan);

    RuleDeployment findDeployment(String tenantId, String ruleKey);

    int updateDesiredState(DesiredStateCommand command);

    void insertDeployment(NewDeployment command);

    void insertHistory(RuleDeployment deployment, String actor);

    record DesiredStateCommand(
            UUID revisionId,
            DesiredState desiredState,
            String targetCluster,
            String tenantId,
            String ruleKey) {}

    record NewDeployment(
            UUID deploymentId,
            String tenantId,
            String ruleKey,
            UUID revisionId,
            DesiredState desiredState,
            String targetCluster) {}
}
