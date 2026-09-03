package com.xscsiem.hsiem_platform.detection.runtime;

import java.util.List;

/** Domain port for loading the immutable assignment rows needed by artifact materialization. */
public interface DetectionArtifactRepositoryPort {
    List<DetectionArtifactRepository.DetectionArtifactRuleRow> findRules(
            String tenantId, String groupKey);
}
