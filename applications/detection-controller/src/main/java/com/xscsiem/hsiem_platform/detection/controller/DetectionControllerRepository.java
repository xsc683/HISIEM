package com.xscsiem.hsiem_platform.detection.controller;

import com.xscsiem.hsiem_platform.detection.runtime.DetectionGroupLease;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Durable controller lease boundary.  Every write is fenced by owner, token, and the generation
 * captured by claimDue; no method in this class writes runtime status or job identity columns.
 */
@Repository
public class DetectionControllerRepository {

    private static final int MAX_BATCH = 100;
    private static final Duration DEFAULT_INSPECTION = Duration.ofSeconds(30);
    private static final Duration BASE_BACKOFF = Duration.ofSeconds(1);
    private static final Duration MAX_BACKOFF = Duration.ofMinutes(15);

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final Duration defaultInspection;

    public DetectionControllerRepository(JdbcTemplate jdbc) {
        this(jdbc, Clock.systemUTC(), DEFAULT_INSPECTION);
    }

    public DetectionControllerRepository(JdbcTemplate jdbc, Clock clock,
                                         Duration defaultInspection) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.defaultInspection = positive(defaultInspection, "defaultInspection");
    }

    /**
     * Claims due groups atomically. PostgreSQL and H2 (PostgreSQL mode) both support the locking
     * clause used here. The select and all claim updates execute in one transaction.
     */
    @Transactional
    public List<DetectionGroupLease> claimDue(String owner, Duration leaseDuration, int batch) {
        require(owner, "owner");
        Duration lease = positive(leaseDuration, "leaseDuration");
        int boundedBatch = Math.max(1, Math.min(MAX_BATCH, batch));
        Timestamp until = Timestamp.from(now().plus(lease));
        List<GroupKey> due = jdbc.query("""
                SELECT tenant_id, group_key
                FROM detection_job_group
                WHERE reconcile_available_at <= CURRENT_TIMESTAMP
                  AND (controller_lease_until IS NULL
                       OR controller_lease_until < CURRENT_TIMESTAMP
                       OR reconcile_state = 'PENDING')
                  AND reconcile_state IN ('PENDING', 'INSPECTING', 'APPLYING', 'VERIFYING', 'IDLE', 'FAILED')
                ORDER BY reconcile_available_at, updated_at, tenant_id, group_key
                LIMIT ? FOR UPDATE SKIP LOCKED
                """, (rs, rowNum) -> new GroupKey(
                rs.getString("tenant_id"), rs.getString("group_key")), boundedBatch);
        List<DetectionGroupLease> result = new java.util.ArrayList<>(due.size());
        for (GroupKey key : due) {
            int updated = jdbc.update("""
                    UPDATE detection_job_group
                    SET controller_lease_owner = ?, controller_lease_until = ?,
                        controller_fencing_token = controller_fencing_token + 1,
                        reconcile_attempts = reconcile_attempts + 1,
                        reconcile_state = 'INSPECTING', updated_at = CURRENT_TIMESTAMP
                    WHERE tenant_id = ? AND group_key = ?
                    """, owner, until, key.tenantId(), key.groupKey());
            if (updated == 1) {
                result.add(jdbc.queryForObject("""
                        SELECT tenant_id, group_key, target_cluster, desired_generation,
                               expected_manifest_json, expected_manifest_hash,
                               controller_lease_owner, controller_fencing_token, reconcile_attempts
                        FROM detection_job_group
                        WHERE tenant_id = ? AND group_key = ?
                        """, this::lease, key.tenantId(), key.groupKey()));
            }
        }
        return List.copyOf(result);
    }

    private record GroupKey(String tenantId, String groupKey) {
    }

    public List<DetectionGroupLease> claimDue(String owner, int batch, Duration leaseDuration) {
        return claimDue(owner, leaseDuration, batch);
    }

    public List<DetectionGroupLease> claimDue(String owner, Duration leaseDuration) {
        return claimDue(owner, leaseDuration, MAX_BATCH);
    }

    /** Extends a lease only while the exact owner/token/generation fence is still current. */
    @Transactional
    public boolean heartbeat(DetectionGroupLease lease, Duration leaseDuration) {
        Objects.requireNonNull(lease, "lease must not be null");
        Duration duration = positive(leaseDuration, "leaseDuration");
        int updated = jdbc.update("""
                UPDATE detection_job_group
                SET controller_lease_until = ?, updated_at = CURRENT_TIMESTAMP
                WHERE tenant_id = ? AND group_key = ?
                  AND controller_lease_owner = ?
                  AND controller_fencing_token = ?
                  AND desired_generation = ?
                  AND controller_lease_until > CURRENT_TIMESTAMP
                """, Timestamp.from(now().plus(duration)), lease.tenantId(), lease.groupKey(),
                lease.owner(), lease.fencingToken(), lease.desiredGeneration());
        return updated == 1;
    }

    public boolean heartbeat(DetectionGroupLease lease, long leaseMillis) {
        return heartbeat(lease, Duration.ofMillis(leaseMillis));
    }

    /** Advances the independent controller phase under the same fencing tuple. */
    @Transactional
    public boolean transitionPhase(DetectionGroupLease lease, ReconcileState state) {
        Objects.requireNonNull(lease, "lease must not be null");
        Objects.requireNonNull(state, "state must not be null");
        int updated = jdbc.update("""
                UPDATE detection_job_group
                SET reconcile_state = ?, updated_at = CURRENT_TIMESTAMP
                WHERE tenant_id = ? AND group_key = ?
                  AND controller_lease_owner = ?
                  AND controller_fencing_token = ?
                  AND desired_generation = ?
                  AND controller_lease_until > CURRENT_TIMESTAMP
                """, state.name(), lease.tenantId(), lease.groupKey(), lease.owner(),
                lease.fencingToken(), lease.desiredGeneration());
        return updated == 1;
    }

    public boolean reconcilePhase(DetectionGroupLease lease, ReconcileState state) {
        return transitionPhase(lease, state);
    }

    /** Returns false for an expired, stolen, or generation-obsolete lease. */
    @Transactional(readOnly = true)
    public boolean isCurrent(DetectionGroupLease lease) {
        Objects.requireNonNull(lease, "lease must not be null");
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM detection_job_group
                WHERE tenant_id = ? AND group_key = ?
                  AND controller_lease_owner = ?
                  AND controller_fencing_token = ?
                  AND desired_generation = ?
                  AND controller_lease_until > CURRENT_TIMESTAMP
                """, Integer.class, lease.tenantId(), lease.groupKey(), lease.owner(),
                lease.fencingToken(), lease.desiredGeneration());
        return count != null && count == 1;
    }

    /** Clears the lease and schedules a normal next inspection. */
    @Transactional
    public boolean release(DetectionGroupLease lease) {
        return release(lease, defaultInspection);
    }

    @Transactional
    public boolean release(DetectionGroupLease lease, Duration nextInspection) {
        Objects.requireNonNull(lease, "lease must not be null");
        Duration delay = positive(nextInspection, "nextInspection");
        return releaseAt(lease, now().plus(delay));
    }

    @Transactional
    public boolean releaseAt(DetectionGroupLease lease, Instant nextInspection) {
        Objects.requireNonNull(lease, "lease must not be null");
        Objects.requireNonNull(nextInspection, "nextInspection must not be null");
        return releaseAtInternal(lease, nextInspection);
    }

    /** Fails and backs off without touching status/job identity written by DetectionRuntimeService. */
    @Transactional
    public boolean fail(DetectionGroupLease lease, Throwable failure) {
        Objects.requireNonNull(failure, "failure must not be null");
        return fail(lease, failure.getMessage() == null
                ? failure.getClass().getSimpleName() : failure.getMessage());
    }

    @Transactional
    public boolean fail(DetectionGroupLease lease, String message) {
        Objects.requireNonNull(lease, "lease must not be null");
        String error = message == null || message.isBlank() ? "reconciliation failed" : message;
        if (error.length() > 2000) error = error.substring(0, 2000);
        long exponent = Math.min(20L, Math.max(0L, lease.attempt() - 1L));
        long multiplier = 1L << exponent;
        Duration backoff;
        try {
            backoff = BASE_BACKOFF.multipliedBy(multiplier);
        } catch (ArithmeticException ignored) {
            backoff = MAX_BACKOFF;
        }
        if (backoff.compareTo(MAX_BACKOFF) > 0) backoff = MAX_BACKOFF;
        int updated = jdbc.update("""
                UPDATE detection_job_group
                SET reconcile_state = 'FAILED', reconcile_available_at = ?,
                    controller_lease_owner = NULL, controller_lease_until = NULL,
                    last_error = ?, updated_at = CURRENT_TIMESTAMP
                WHERE tenant_id = ? AND group_key = ?
                  AND controller_lease_owner = ?
                  AND controller_fencing_token = ?
                  AND desired_generation = ?
                  AND controller_lease_until > CURRENT_TIMESTAMP
                """, Timestamp.from(now().plus(backoff)), error, lease.tenantId(), lease.groupKey(),
                lease.owner(), lease.fencingToken(), lease.desiredGeneration());
        return updated == 1;
    }

    private boolean releaseAtInternal(DetectionGroupLease lease, Instant nextInspection) {
        int updated = jdbc.update("""
                UPDATE detection_job_group
                SET reconcile_state = 'IDLE', reconcile_available_at = ?, reconcile_attempts = 0,
                    controller_lease_owner = NULL, controller_lease_until = NULL,
                    last_reconciled_at = CURRENT_TIMESTAMP, last_error = NULL,
                    updated_at = CURRENT_TIMESTAMP
                WHERE tenant_id = ? AND group_key = ?
                  AND controller_lease_owner = ?
                  AND controller_fencing_token = ?
                  AND desired_generation = ?
                  AND controller_lease_until > CURRENT_TIMESTAMP
                """, Timestamp.from(nextInspection), lease.tenantId(), lease.groupKey(),
                lease.owner(), lease.fencingToken(), lease.desiredGeneration());
        return updated == 1;
    }

    private DetectionGroupLease lease(ResultSet rs, int rowNum) throws SQLException {
        return new DetectionGroupLease(rs.getString("tenant_id"), rs.getString("group_key"),
                rs.getString("target_cluster"), rs.getLong("desired_generation"),
                rs.getString("expected_manifest_json"), rs.getString("expected_manifest_hash"),
                currentOwner(rs), rs.getLong("controller_fencing_token"),
                rs.getInt("reconcile_attempts"));
    }

    /* The owner is read explicitly after claim; queryForObject supplies no query parameter here. */
    private String currentOwner(ResultSet rs) throws SQLException {
        String owner = rs.getString("controller_lease_owner");
        return owner == null ? "unknown" : owner;
    }

    private Instant now() {
        return clock.instant();
    }

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }

    private static Duration positive(Duration value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }
}
