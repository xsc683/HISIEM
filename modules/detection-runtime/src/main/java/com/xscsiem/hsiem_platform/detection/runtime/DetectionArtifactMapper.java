package com.xscsiem.hsiem_platform.detection.runtime;

import java.util.List;
import org.apache.ibatis.annotations.Param;

/** Plain MyBatis mapper for the artifact assignment join. */
public interface DetectionArtifactMapper {
    List<DetectionArtifactRepository.DetectionArtifactRuleRow> findRules(
            @Param("tenantId") String tenantId, @Param("groupKey") String groupKey);
}
