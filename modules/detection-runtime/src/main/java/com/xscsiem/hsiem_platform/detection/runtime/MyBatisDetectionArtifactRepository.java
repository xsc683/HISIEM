package com.xscsiem.hsiem_platform.detection.runtime;

import java.util.List;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Repository;

/** MyBatis adapter implementing the artifact repository port. */
@Primary
@Repository
public class MyBatisDetectionArtifactRepository implements DetectionArtifactRepositoryPort {
    private final DetectionArtifactMapper mapper;

    public MyBatisDetectionArtifactRepository(DetectionArtifactMapper mapper) {
        this.mapper = java.util.Objects.requireNonNull(mapper, "mapper must not be null");
    }

    @Override
    public List<DetectionArtifactRepository.DetectionArtifactRuleRow> findRules(
            String tenantId, String groupKey) {
        return mapper.findRules(tenantId, groupKey);
    }
}
