package com.xscsiem.hsiem_platform.detection.runtime;

import java.util.List;
import java.util.UUID;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;

/** MyBatis-backed compatibility facade for direct JDBC-era construction sites. */
@Deprecated(forRemoval = false)
public class DetectionArtifactRepository implements DetectionArtifactRepositoryPort {
    private final DetectionArtifactMapper mapper;

    public DetectionArtifactRepository(JdbcTemplate jdbc) {
        try {
            SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
            factory.setDataSource(jdbc.getDataSource());
            factory.setTypeHandlers(new com.xscsiem.hsiem_platform.rules.UuidTypeHandler());
            factory.setMapperLocations(
                    new PathMatchingResourcePatternResolver()
                            .getResources(
                                    "classpath*:mybatis/detection/DetectionArtifactMapper.xml"));
            SqlSessionFactory sessionFactory = factory.getObject();
            if (sessionFactory == null) {
                throw new IllegalStateException("artifact MyBatis session factory was not created");
            }
            this.mapper =
                    new SqlSessionTemplate(sessionFactory).getMapper(DetectionArtifactMapper.class);
        } catch (Exception e) {
            throw new IllegalStateException("cannot initialize artifact MyBatis repository", e);
        }
    }

    @Override
    public List<DetectionArtifactRuleRow> findRules(String tenantId, String groupKey) {
        return mapper.findRules(tenantId, groupKey);
    }

    public record DetectionArtifactRuleRow(
            String ruleKey,
            long revision,
            UUID planId,
            String planHash,
            long generation,
            String compilerVersion,
            String planJson) {}
}
