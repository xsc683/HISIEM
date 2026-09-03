package com.xscsiem.hsiem_platform.detection.controller;

import com.xscsiem.hsiem_platform.detection.runtime.DetectionGroupLease;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** Domain port for durable controller claims and fenced lease transitions. */
public interface DetectionControllerRepositoryPort {
    List<DetectionGroupLease> claimDue(String owner, Duration leaseDuration, int batch);

    default List<DetectionGroupLease> claimDue(String owner, int batch, Duration leaseDuration) {
        return claimDue(owner, leaseDuration, batch);
    }

    default List<DetectionGroupLease> claimDue(String owner, Duration leaseDuration) {
        return claimDue(owner, leaseDuration, 100);
    }

    boolean heartbeat(DetectionGroupLease lease, Duration leaseDuration);

    default boolean heartbeat(DetectionGroupLease lease, long leaseMillis) {
        return heartbeat(lease, Duration.ofMillis(leaseMillis));
    }

    boolean transitionPhase(DetectionGroupLease lease, ReconcileState state);

    default boolean reconcilePhase(DetectionGroupLease lease, ReconcileState state) {
        return transitionPhase(lease, state);
    }

    boolean isCurrent(DetectionGroupLease lease);

    boolean release(DetectionGroupLease lease);

    boolean release(DetectionGroupLease lease, Duration nextInspection);

    boolean releaseAt(DetectionGroupLease lease, Instant nextInspection);

    boolean fail(DetectionGroupLease lease, Throwable failure);

    boolean fail(DetectionGroupLease lease, String message);
}
