package com.xscsiem.hsiem_platform.rules;

import java.util.UUID;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;

/** MyBatis-backed compatibility facade for direct JDBC-era construction sites. */
@Deprecated(forRemoval = false)
public class ManagedDetectionRepository implements ManagedDetectionRepositoryPort {
    private final ManagedDetectionMapper mapper;

    public ManagedDetectionRepository(JdbcTemplate jdbc) {
        try {
            SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
            factory.setDataSource(jdbc.getDataSource());
            factory.setTypeHandlers(new UuidTypeHandler());
            factory.setMapperLocations(
                    new PathMatchingResourcePatternResolver()
                            .getResources(
                                    "classpath*:mybatis/detection/ManagedDetectionMapper.xml"));
            SqlSessionFactory sessionFactory = factory.getObject();
            if (sessionFactory == null) {
                throw new IllegalStateException("managed MyBatis session factory was not created");
            }
            this.mapper =
                    new SqlSessionTemplate(sessionFactory).getMapper(ManagedDetectionMapper.class);
        } catch (Exception e) {
            throw new IllegalStateException("cannot initialize managed MyBatis repository", e);
        }
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
    public int updateDesiredState(DesiredStateCommand command) {
        return mapper.updateDesiredState(command);
    }

    @Override
    public void insertDeployment(NewDeployment command) {
        mapper.insertDeployment(command);
    }

    public UUID findDeploymentId(String tenantId, String ruleKey) {
        RuleDeployment deployment = findDeployment(tenantId, ruleKey);
        return deployment == null ? null : deployment.deploymentId();
    }

    @Override
    public void insertHistory(RuleDeployment deployment, String actor) {
        if (deployment != null)
            mapper.insertHistory(deployment, actor == null || actor.isBlank() ? "system" : actor);
    }
}
