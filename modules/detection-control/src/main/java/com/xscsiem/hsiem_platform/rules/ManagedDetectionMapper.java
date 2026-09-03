package com.xscsiem.hsiem_platform.rules;

import java.util.UUID;
import org.apache.ibatis.annotations.Param;

/** Plain MyBatis mapper; SQL is kept in the adjacent XML resource. */
public interface ManagedDetectionMapper {
    RuleRevision findRevisionByHash(
            @Param("ruleKey") String ruleKey, @Param("contentHash") String contentHash);

    RuleRevision findRevision(
            @Param("revisionId") UUID revisionId, @Param("ruleKey") String ruleKey);

    DetectionPlanArtifact findPlan(
            @Param("revisionId") UUID revisionId, @Param("compilerVersion") String compilerVersion);

    int updateCatalog(
            @Param("ruleKey") String ruleKey,
            @Param("name") String name,
            @Param("description") String description,
            @Param("category") String category);

    void insertCatalog(
            @Param("ruleKey") String ruleKey,
            @Param("name") String name,
            @Param("description") String description,
            @Param("category") String category);

    int latestRevisionNumber(@Param("ruleKey") String ruleKey);

    void insertRevision(RuleRevision revision);

    void insertPlan(DetectionPlanArtifact plan);

    RuleDeployment findDeployment(
            @Param("tenantId") String tenantId, @Param("ruleKey") String ruleKey);

    int updateDesiredState(ManagedDetectionRepositoryPort.DesiredStateCommand command);

    void insertDeployment(ManagedDetectionRepositoryPort.NewDeployment command);

    void insertHistory(
            @Param("deployment") RuleDeployment deployment, @Param("actor") String actor);
}
