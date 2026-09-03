package com.xscsiem.hsiem_platform.detection.controller;

import com.xscsiem.hsiem_platform.detection.runtime.DetectionGroupLease;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** Plain MyBatis mapper; SQL is defined in DetectionControllerMapper.xml. */
public interface DetectionControllerMapper {
    List<GroupKeyRow> selectDueGroupKeys(@Param("batch") int batch);

    int claimGroup(ClaimCommand command);

    DetectionGroupLease selectLease(
            @Param("tenantId") String tenantId, @Param("groupKey") String groupKey);

    int heartbeat(LeaseCommand command);

    int transitionPhase(PhaseCommand command);

    Integer isCurrent(@Param("lease") DetectionGroupLease lease);

    int release(ReleaseCommand command);

    int fail(FailCommand command);

    record GroupKeyRow(String tenantId, String groupKey) {}

    record ClaimCommand(String owner, Instant leaseUntil, String tenantId, String groupKey) {}

    record LeaseCommand(DetectionGroupLease lease, Instant leaseUntil) {}

    record PhaseCommand(DetectionGroupLease lease, ReconcileState state) {}

    record ReleaseCommand(DetectionGroupLease lease, Instant nextInspection) {}

    record FailCommand(DetectionGroupLease lease, Instant availableAt, String error) {}
}
