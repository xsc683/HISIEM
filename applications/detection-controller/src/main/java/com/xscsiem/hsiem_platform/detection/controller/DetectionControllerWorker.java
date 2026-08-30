package com.xscsiem.hsiem_platform.detection.controller;

import com.xscsiem.hsiem_platform.detection.runtime.DetectionGroupLease;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded poller with a per-claim heartbeat and safe shutdown semantics. */
@Component
public class DetectionControllerWorker {

    private final DetectionControllerRepository repository;
    private final DetectionReconciler reconciler;
    private final ControllerPollState pollState;
    private final String owner;
    private final Duration leaseDuration;
    private final int batchSize;
    private final ScheduledExecutorService heartbeatExecutor;

    public DetectionControllerWorker(
            DetectionControllerRepository repository,
            DetectionReconciler reconciler,
            ControllerPollState pollState,
            @Value("${app.detection.controller.owner:}") String configuredOwner,
            @Value("${app.detection.controller.lease:PT30S}") Duration leaseDuration,
            @Value("${app.detection.controller.batch-size:10}") int batchSize) {
        this(repository, reconciler, pollState,
                configuredOwner == null || configuredOwner.isBlank()
                        ? "detection-controller-" + UUID.randomUUID() : configuredOwner,
                leaseDuration, batchSize,
                Executors.newScheduledThreadPool(1, daemonFactory()));
    }

    DetectionControllerWorker(DetectionControllerRepository repository,
                               DetectionReconciler reconciler,
                               ControllerPollState pollState,
                               String owner,
                               Duration leaseDuration,
                               int batchSize,
                               ScheduledExecutorService heartbeatExecutor) {
        this.repository = repository;
        this.reconciler = reconciler;
        this.pollState = pollState;
        this.owner = owner;
        if (leaseDuration == null || leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("lease duration must be positive");
        }
        this.leaseDuration = leaseDuration;
        this.batchSize = Math.max(1, Math.min(100, batchSize));
        this.heartbeatExecutor = heartbeatExecutor;
    }

    @Scheduled(fixedDelayString = "${app.detection.controller.poll-ms:5000}",
            initialDelayString = "${app.detection.controller.initial-delay-ms:1000}")
    public void scheduledPoll() {
        try {
            runOnce();
        } catch (RuntimeException failure) {
            if (Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt();
            }
            // One failed poll must not terminate Spring's scheduler; individual claims are fenced.
        }
    }

    /** Executes one bounded claim batch; useful for deterministic tests and graceful draining. */
    public void runOnce() {
        pollState.markPoll();
        int processed = 0;
        while (processed < batchSize && !Thread.currentThread().isInterrupted()) {
            // Claim only the item being processed. Claiming a whole batch would leave every
            // later lease without a heartbeat while the first adapter action is still running.
            List<DetectionGroupLease> claimed = repository.claimDue(owner, leaseDuration, 1);
            if (claimed.isEmpty()) {
                break;
            }
            runLease(claimed.getFirst());
            processed++;
        }
        if (Thread.currentThread().isInterrupted()) {
            Thread.currentThread().interrupt();
        }
    }

    private void runLease(DetectionGroupLease lease) {
        AtomicBoolean heartbeatHealthy = new AtomicBoolean(true);
        long heartbeatMillis = Math.max(50L, leaseDuration.toMillis() / 3L);
        ScheduledFuture<?> heartbeat = heartbeatExecutor.scheduleAtFixedRate(() -> {
            if (!heartbeatHealthy.get()) return;
            try {
                if (!repository.heartbeat(lease, leaseDuration)) {
                    heartbeatHealthy.set(false);
                }
            } catch (RuntimeException failure) {
                heartbeatHealthy.set(false);
            }
        }, heartbeatMillis, heartbeatMillis, TimeUnit.MILLISECONDS);
        try {
            reconciler.reconcile(lease, () -> heartbeatHealthy.get()
                    && !Thread.currentThread().isInterrupted()
                    && repository.isCurrent(lease));
        } finally {
            heartbeat.cancel(false);
        }
    }

    @PreDestroy
    public void shutdown() {
        heartbeatExecutor.shutdownNow();
        try {
            heartbeatExecutor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static ThreadFactory daemonFactory() {
        return runnable -> {
            Thread thread = new Thread(runnable, "detection-controller-heartbeat");
            thread.setDaemon(true);
            return thread;
        };
    }
}
