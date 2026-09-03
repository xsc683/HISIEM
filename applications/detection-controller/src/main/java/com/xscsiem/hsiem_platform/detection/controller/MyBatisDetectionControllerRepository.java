package com.xscsiem.hsiem_platform.detection.controller;

import com.xscsiem.hsiem_platform.detection.runtime.DetectionGroupLease;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** MyBatis adapter retaining claim transaction and all lease-fence orchestration. */
@Primary
@Repository
public class MyBatisDetectionControllerRepository implements DetectionControllerRepositoryPort {
    private static final int MAX_BATCH = 100;
    private static final Duration DEFAULT_INSPECTION = Duration.ofSeconds(30);
    private static final Duration BASE_BACKOFF = Duration.ofSeconds(1);
    private static final Duration MAX_BACKOFF = Duration.ofMinutes(15);
    private final DetectionControllerMapper mapper;
    private final Clock clock;
    private final Duration defaultInspection;

    public MyBatisDetectionControllerRepository(DetectionControllerMapper mapper) {
        this(mapper, Clock.systemUTC(), DEFAULT_INSPECTION);
    }

    public MyBatisDetectionControllerRepository(
            DetectionControllerMapper mapper, Clock clock, Duration defaultInspection) {
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.defaultInspection = positive(defaultInspection, "defaultInspection");
    }

    @Override
    @Transactional
    public List<DetectionGroupLease> claimDue(String owner, Duration leaseDuration, int batch) {
        require(owner, "owner");
        Duration lease = positive(leaseDuration, "leaseDuration");
        int boundedBatch = Math.max(1, Math.min(MAX_BATCH, batch));
        Instant until = now().plus(lease);
        List<DetectionGroupLease> result = new ArrayList<>();
        for (DetectionControllerMapper.GroupKeyRow key : mapper.selectDueGroupKeys(boundedBatch)) {
            if (mapper.claimGroup(
                            new DetectionControllerMapper.ClaimCommand(
                                    owner, until, key.tenantId(), key.groupKey()))
                    == 1) {
                DetectionGroupLease claimed = mapper.selectLease(key.tenantId(), key.groupKey());
                if (claimed != null) result.add(claimed);
            }
        }
        return List.copyOf(result);
    }

    @Override
    @Transactional
    public boolean heartbeat(DetectionGroupLease lease, Duration duration) {
        return mapper.heartbeat(
                        new DetectionControllerMapper.LeaseCommand(
                                requireLease(lease),
                                now().plus(positive(duration, "leaseDuration"))))
                == 1;
    }

    @Override
    @Transactional
    public boolean transitionPhase(DetectionGroupLease lease, ReconcileState state) {
        Objects.requireNonNull(state, "state must not be null");
        return mapper.transitionPhase(
                        new DetectionControllerMapper.PhaseCommand(requireLease(lease), state))
                == 1;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isCurrent(DetectionGroupLease lease) {
        return mapper.isCurrent(requireLease(lease)) != null;
    }

    @Override
    @Transactional
    public boolean release(DetectionGroupLease lease) {
        return release(lease, defaultInspection);
    }

    @Override
    @Transactional
    public boolean release(DetectionGroupLease lease, Duration delay) {
        return releaseAt(lease, now().plus(positive(delay, "nextInspection")));
    }

    @Override
    @Transactional
    public boolean releaseAt(DetectionGroupLease lease, Instant nextInspection) {
        return mapper.release(
                        new DetectionControllerMapper.ReleaseCommand(
                                requireLease(lease),
                                Objects.requireNonNull(
                                        nextInspection, "nextInspection must not be null")))
                == 1;
    }

    @Override
    @Transactional
    public boolean fail(DetectionGroupLease lease, Throwable failure) {
        Objects.requireNonNull(failure, "failure must not be null");
        return fail(
                lease,
                failure.getMessage() == null
                        ? failure.getClass().getSimpleName()
                        : failure.getMessage());
    }

    @Override
    @Transactional
    public boolean fail(DetectionGroupLease lease, String message) {
        DetectionGroupLease current = requireLease(lease);
        String error = message == null || message.isBlank() ? "reconciliation failed" : message;
        if (error.length() > 2000) error = error.substring(0, 2000);
        long exponent = Math.min(20L, Math.max(0L, current.attempt() - 1L));
        Duration backoff;
        try {
            backoff = BASE_BACKOFF.multipliedBy(1L << exponent);
        } catch (ArithmeticException ignored) {
            backoff = MAX_BACKOFF;
        }
        if (backoff.compareTo(MAX_BACKOFF) > 0) backoff = MAX_BACKOFF;
        return mapper.fail(
                        new DetectionControllerMapper.FailCommand(
                                current, now().plus(backoff), error))
                == 1;
    }

    private DetectionGroupLease requireLease(DetectionGroupLease lease) {
        return Objects.requireNonNull(lease, "lease must not be null");
    }

    private Instant now() {
        return clock.instant();
    }

    private static String require(String value, String field) {
        if (value == null || value.isBlank())
            throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }

    private static Duration positive(Duration value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isZero() || value.isNegative())
            throw new IllegalArgumentException(field + " must be positive");
        return value;
    }
}
