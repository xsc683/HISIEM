package com.xscsiem.hsiem_platform.soar;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@ConditionalOnProperty(name = "app.soar.runtime-enabled", havingValue = "true", matchIfMissing = true)
public class SoarWorker {

    private static final Logger LOG = LoggerFactory.getLogger(SoarWorker.class);

    private final SoarStore store;
    private final SoarExecutionEngine engine;
    private final Duration lease;
    private final int batchSize;
    private final String owner = ManagementFactory.getRuntimeMXBean().getName() + ":" + UUID.randomUUID();
    private final ScheduledExecutorService leaseRenewer;

    public SoarWorker(SoarStore store, SoarExecutionEngine engine,
                      @Value("${app.soar.worker-lease:PT30S}") Duration lease,
                      @Value("${app.soar.worker-batch-size:10}") int batchSize) {
        this.store = store;
        this.engine = engine;
        if (lease.isZero() || lease.isNegative()) throw new IllegalArgumentException("SOAR worker lease 必须大于 0");
        this.lease = lease;
        this.batchSize = Math.max(1, batchSize);
        this.leaseRenewer = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "soar-lease-renewer");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Scheduled(fixedDelayString = "${app.soar.worker-poll-ms:500}")
    public void poll() {
        for (int index = 0; index < batchSize; index++) {
            List<SoarExecution> claims = store.claimDue(owner, lease, 1);
            if (claims.isEmpty()) return;
            processWithRenewal(claims.getFirst());
        }
    }

    void processWithRenewal(SoarExecution execution) {
        AtomicBoolean reportedLost = new AtomicBoolean();
        long heartbeatMillis = Math.max(1L, Math.min(10_000L, lease.toMillis() / 3L));
        ScheduledFuture<?> heartbeat = leaseRenewer.scheduleAtFixedRate(() -> {
            try {
                if (!store.renewLease(execution, lease) && reportedLost.compareAndSet(false, true)) {
                    LOG.warn("SOAR lease renewal rejected execution={} owner={} token={}",
                            execution.id(), execution.leaseOwner(), execution.fencingToken());
                }
            } catch (RuntimeException error) {
                LOG.warn("SOAR lease renewal failed execution={}", execution.id(), error);
            }
        }, heartbeatMillis, heartbeatMillis, TimeUnit.MILLISECONDS);
        try {
            engine.process(execution);
        } finally {
            heartbeat.cancel(false);
        }
    }

    @PreDestroy
    void close() {
        leaseRenewer.shutdownNow();
    }
}
