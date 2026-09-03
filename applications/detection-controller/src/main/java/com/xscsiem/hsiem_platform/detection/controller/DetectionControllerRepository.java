package com.xscsiem.hsiem_platform.detection.controller;

import com.xscsiem.hsiem_platform.detection.runtime.DetectionGroupLease;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;

/** Compatibility facade; production wiring uses {@link MyBatisDetectionControllerRepository}. */
@Deprecated(forRemoval = false)
public class DetectionControllerRepository implements DetectionControllerRepositoryPort {
    private final DetectionControllerRepositoryPort delegate;

    public DetectionControllerRepository(DetectionControllerMapper mapper) {
        this.delegate = new MyBatisDetectionControllerRepository(mapper);
    }

    /** Transitional test/CLI constructor; SQL remains exclusively in the MyBatis XML mapper. */
    public DetectionControllerRepository(JdbcTemplate jdbc) {
        this(createMapper(jdbc));
    }

    public DetectionControllerRepository(JdbcTemplate jdbc, Clock clock, Duration inspection) {
        this(createMapper(jdbc), clock, inspection);
    }

    public DetectionControllerRepository(
            DetectionControllerMapper mapper, Clock clock, Duration inspection) {
        this.delegate = new MyBatisDetectionControllerRepository(mapper, clock, inspection);
    }

    private static DetectionControllerMapper createMapper(JdbcTemplate jdbc) {
        try {
            SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
            factory.setDataSource(jdbc.getDataSource());
            factory.setMapperLocations(
                    new PathMatchingResourcePatternResolver()
                            .getResources(
                                    "classpath*:mybatis/detection/DetectionControllerMapper.xml"));
            SqlSessionFactory sessionFactory = factory.getObject();
            if (sessionFactory == null)
                throw new IllegalStateException(
                        "controller MyBatis session factory was not created");
            return new SqlSessionTemplate(sessionFactory)
                    .getMapper(DetectionControllerMapper.class);
        } catch (Exception e) {
            throw new IllegalStateException("cannot initialize controller MyBatis repository", e);
        }
    }

    @Override
    public List<DetectionGroupLease> claimDue(String owner, Duration lease, int batch) {
        return delegate.claimDue(owner, lease, batch);
    }

    @Override
    public boolean heartbeat(DetectionGroupLease lease, Duration duration) {
        return delegate.heartbeat(lease, duration);
    }

    @Override
    public boolean transitionPhase(DetectionGroupLease lease, ReconcileState state) {
        return delegate.transitionPhase(lease, state);
    }

    @Override
    public boolean isCurrent(DetectionGroupLease lease) {
        return delegate.isCurrent(lease);
    }

    @Override
    public boolean release(DetectionGroupLease lease) {
        return delegate.release(lease);
    }

    @Override
    public boolean release(DetectionGroupLease lease, Duration duration) {
        return delegate.release(lease, duration);
    }

    @Override
    public boolean releaseAt(DetectionGroupLease lease, Instant instant) {
        return delegate.releaseAt(lease, instant);
    }

    @Override
    public boolean fail(DetectionGroupLease lease, Throwable failure) {
        return delegate.fail(lease, failure);
    }

    @Override
    public boolean fail(DetectionGroupLease lease, String message) {
        return delegate.fail(lease, message);
    }
}
