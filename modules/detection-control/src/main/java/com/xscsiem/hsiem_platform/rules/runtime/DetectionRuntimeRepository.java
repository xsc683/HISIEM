package com.xscsiem.hsiem_platform.rules.runtime;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;

/** Compatibility facade for direct construction of the former runtime repository. */
@Deprecated(forRemoval = false)
public class DetectionRuntimeRepository implements DetectionRuntimeRepositoryPort {
    private final DetectionRuntimeRepositoryPort delegate;

    public DetectionRuntimeRepository(DetectionRuntimeMapper mapper) {
        this.delegate = new MyBatisDetectionRuntimeRepository(mapper);
    }

    /** Transitional test/CLI constructor; it only bootstraps the MyBatis adapter. */
    public DetectionRuntimeRepository(JdbcTemplate jdbc) {
        this(createMapper(jdbc));
    }

    private static DetectionRuntimeMapper createMapper(JdbcTemplate jdbc) {
        try {
            SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
            factory.setDataSource(jdbc.getDataSource());
            factory.setTypeHandlers(new com.xscsiem.hsiem_platform.rules.UuidTypeHandler());
            factory.setMapperLocations(
                    new PathMatchingResourcePatternResolver()
                            .getResources(
                                    "classpath*:mybatis/detection/DetectionRuntimeMapper.xml"));
            SqlSessionFactory sessionFactory = factory.getObject();
            if (sessionFactory == null)
                throw new IllegalStateException("runtime MyBatis session factory was not created");
            return new SqlSessionTemplate(sessionFactory).getMapper(DetectionRuntimeMapper.class);
        } catch (Exception e) {
            throw new IllegalStateException("cannot initialize runtime MyBatis repository", e);
        }
    }

    @Override
    public Set<String> findAssignmentGroups(String tenantId, String ruleKey) {
        return delegate.findAssignmentGroups(tenantId, ruleKey);
    }

    @Override
    public List<DetectionJobGroupRow> findGroupRows(String tenantId) {
        return delegate.findGroupRows(tenantId);
    }

    @Override
    public DetectionJobGroupRow findGroupRow(String tenantId, String groupKey) {
        return delegate.findGroupRow(tenantId, groupKey);
    }

    @Override
    public DetectionJobGroupRow findGroupRow(
            String tenantId, String groupKey, String targetCluster) {
        return delegate.findGroupRow(tenantId, groupKey, targetCluster);
    }

    @Override
    public List<DesiredDetectionRow> findDesiredRunningRows(
            String tenantId, String compilerVersion) {
        return delegate.findDesiredRunningRows(tenantId, compilerVersion);
    }

    @Override
    public RuleDeploymentRow findDeploymentRow(String tenantId, String ruleKey) {
        return delegate.findDeploymentRow(tenantId, ruleKey);
    }

    @Override
    public RuntimeAssignmentRow findAssignmentRow(String tenantId, String ruleKey) {
        return delegate.findAssignmentRow(tenantId, ruleKey);
    }

    @Override
    public RuleRuntimeStatusRow findRuntimeStatusRow(String tenantId, String ruleKey) {
        return delegate.findRuntimeStatusRow(tenantId, ruleKey);
    }

    @Override
    public List<RuntimeStatusScopeRow> findStatusesInScopeRows(
            String tenantId, String groupKey, String targetCluster) {
        return delegate.findStatusesInScopeRows(tenantId, groupKey, targetCluster);
    }

    @Override
    public RuntimeManifestRow findLatestObservedManifestRow(
            String tenantId, String groupKey, String targetCluster) {
        return delegate.findLatestObservedManifestRow(tenantId, groupKey, targetCluster);
    }

    @Override
    public boolean lockCurrentObservation(ObservationFence fence) {
        return delegate.lockCurrentObservation(fence);
    }

    @Override
    public void deleteAssignments(String tenantId) {
        delegate.deleteAssignments(tenantId);
    }

    @Override
    public void deleteAssignment(String tenantId, String ruleKey) {
        delegate.deleteAssignment(tenantId, ruleKey);
    }

    @Override
    public void upsertAssignment(DetectionRuntimeRepositoryPort.AssignmentCommand c) {
        delegate.upsertAssignment(c);
    }

    @Override
    public void insertAssignment(DetectionRuntimeRepositoryPort.AssignmentCommand c) {
        delegate.insertAssignment(c);
    }

    @Override
    public void updateAssignmentGenerations(String tenantId, String groupKey, long generation) {
        delegate.updateAssignmentGenerations(tenantId, groupKey, generation);
    }

    @Override
    public void upsertGroup(DetectionRuntimeRepositoryPort.GroupCommand c) {
        delegate.upsertGroup(c);
    }

    @Override
    public void upsertPendingStatus(DetectionRuntimeRepositoryPort.PendingStatusCommand c) {
        delegate.upsertPendingStatus(c);
    }

    @Override
    public void insertObservedManifest(DetectionRuntimeRepositoryPort.ManifestCommand c) {
        delegate.insertObservedManifest(c);
    }

    @Override
    public int insertObservedManifestFenced(
            DetectionRuntimeRepositoryPort.FencedManifestCommand c) {
        return delegate.insertObservedManifestFenced(c);
    }

    @Override
    public void updateGroupObserved(DetectionRuntimeRepositoryPort.ObservedGroupCommand c) {
        delegate.updateGroupObserved(c);
    }

    @Override
    public int updateGroupObservedFenced(
            DetectionRuntimeRepositoryPort.FencedObservedGroupCommand c) {
        return delegate.updateGroupObservedFenced(c);
    }

    @Override
    public void updateRuntimeStatusObserved(
            DetectionRuntimeRepositoryPort.ObservedStatusCommand c) {
        delegate.updateRuntimeStatusObserved(c);
    }

    @Override
    public int updateRuntimeStatusObservedFenced(
            DetectionRuntimeRepositoryPort.FencedObservedStatusCommand c) {
        return delegate.updateRuntimeStatusObservedFenced(c);
    }

    @Override
    public void updateDeploymentRuntimeState(
            DetectionRuntimeRepositoryPort.ObservedDeploymentCommand c) {
        delegate.updateDeploymentRuntimeState(c);
    }

    @Override
    public int updateDeploymentRuntimeStateFenced(
            DetectionRuntimeRepositoryPort.FencedObservedDeploymentCommand c) {
        return delegate.updateDeploymentRuntimeStateFenced(c);
    }

    public void upsertAssignment(
            String a, String b, UUID c, long d, UUID e, String f, String g, long h) {
        delegate.upsertAssignment(a, b, c, d, e, f, g, h);
    }

    public void insertAssignment(
            String a, String b, UUID c, long d, UUID e, String f, String g, long h) {
        delegate.insertAssignment(a, b, c, d, e, f, g, h);
    }

    public void upsertGroup(
            String a, String b, String c, String d, String e, int f, long g, String h, String i) {
        delegate.upsertGroup(a, b, c, d, e, f, g, h, i);
    }

    public void upsertPendingStatus(String a, String b, UUID c, String d, String e) {
        delegate.upsertPendingStatus(a, b, c, d, e);
    }

    public void insertObservedManifest(
            String a, String b, String c, String d, String e, long f, String g, String h) {
        delegate.insertObservedManifest(a, b, c, d, e, f, g, h);
    }

    public int insertObservedManifestFenced(
            ObservationFence a, String b, String c, String d, String e) {
        return delegate.insertObservedManifestFenced(a, b, c, d, e);
    }

    public void updateGroupObserved(
            String a, String b, String c, RuleRuntimeState d, String e, String f, String g) {
        delegate.updateGroupObserved(a, b, c, d, e, f, g);
    }

    public int updateGroupObservedFenced(
            ObservationFence a, RuleRuntimeState b, String c, String d, String e) {
        return delegate.updateGroupObservedFenced(a, b, c, d, e);
    }

    public void updateRuntimeStatusObserved(
            String a,
            String b,
            String c,
            String d,
            String e,
            String f,
            Long g,
            Long h,
            String i,
            RuleRuntimeState j,
            String k,
            String l) {
        delegate.updateRuntimeStatusObserved(a, b, c, d, e, f, g, h, i, j, k, l);
    }

    public int updateRuntimeStatusObservedFenced(
            ObservationFence a,
            String b,
            String c,
            String d,
            Long e,
            Long f,
            String g,
            RuleRuntimeState h,
            String i,
            String j) {
        return delegate.updateRuntimeStatusObservedFenced(a, b, c, d, e, f, g, h, i, j);
    }

    public void updateDeploymentRuntimeState(
            String a, String b, RuleRuntimeState c, Long d, String e) {
        delegate.updateDeploymentRuntimeState(a, b, c, d, e);
    }

    public int updateDeploymentRuntimeStateFenced(
            ObservationFence a, String b, RuleRuntimeState c, Long d, String e) {
        return delegate.updateDeploymentRuntimeStateFenced(a, b, c, d, e);
    }

    public record DetectionJobGroupRow(
            String tenantId,
            String groupKey,
            String targetCluster,
            String sourceFamily,
            String category,
            int bucket,
            long desiredGeneration,
            String expectedManifestJson,
            String expectedManifestHash,
            String status,
            String jobId,
            String jobKey,
            String lastError,
            Instant createdAt,
            Instant updatedAt) {}

    public record DesiredDetectionRow(
            UUID deploymentId,
            String tenantId,
            String ruleKey,
            UUID desiredRevisionId,
            long deploymentGeneration,
            String targetCluster,
            long revision,
            UUID planId,
            String planHash,
            String planJson) {}

    public record RuleDeploymentRow(
            UUID deploymentId,
            String tenantId,
            String ruleKey,
            UUID desiredRevisionId,
            String desiredState,
            long generation,
            long observedGeneration,
            String targetCluster,
            String status,
            String lastError,
            Instant createdAt,
            Instant updatedAt) {}

    public record RuntimeAssignmentRow(
            String tenantId,
            String ruleKey,
            UUID deploymentId,
            long revision,
            UUID planId,
            String planHash,
            String groupKey,
            long generation,
            Instant createdAt,
            Instant updatedAt) {}

    public record RuleRuntimeStatusRow(
            String tenantId,
            String ruleKey,
            UUID deploymentId,
            String groupKey,
            String targetCluster,
            String jobId,
            String jobKey,
            Long observedRevision,
            Long observedGeneration,
            String observedPlanHash,
            String runtimeState,
            String errorCode,
            String errorMessage,
            Instant createdAt,
            Instant updatedAt) {}

    public record RuntimeStatusScopeRow(
            String tenantId,
            String ruleKey,
            UUID deploymentId,
            String groupKey,
            String targetCluster,
            String jobId,
            String jobKey,
            Long observedRevision,
            Long observedGeneration,
            String observedPlanHash,
            String runtimeState,
            String errorCode,
            String errorMessage,
            String desiredState,
            UUID desiredRevisionId,
            Long desiredGeneration,
            String deploymentStatus) {}

    public record RuntimeManifestRow(
            long manifestId,
            String tenantId,
            String groupKey,
            String targetCluster,
            String jobId,
            String jobKey,
            long generation,
            String manifestJson,
            String manifestHash,
            Instant observedAt) {}
}
