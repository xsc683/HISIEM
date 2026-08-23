package com.xscsiem.hsiem_platform.soar;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import jakarta.annotation.PreDestroy;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** 可横向扩展的数据库队列 Worker；claim 使用条件更新，宕机依靠租约恢复。 */
@Component
public class SoarWorker {

    private final SoarExecutionStore store;
    private final SoarEngine engine;
    private final Duration leaseDuration;
    private final boolean enabled;
    private final int batchSize;
    private final int maxConcurrent;
    private final AtomicInteger inFlight = new AtomicInteger();
    private final ExecutorService executionPool = Executors.newVirtualThreadPerTaskExecutor();
    private final String owner = ManagementFactory.getRuntimeMXBean().getName()
            + "-" + UUID.randomUUID().toString().substring(0, 8);

    public SoarWorker(SoarExecutionStore store, SoarEngine engine,
                      @Value("${app.soar.worker-lease:PT45S}") Duration leaseDuration,
                      @Value("${app.soar.worker-enabled:true}") boolean enabled,
                      @Value("${app.soar.worker-batch-size:10}") int batchSize,
                      @Value("${app.soar.worker-concurrency:4}") int maxConcurrent) {
        this.store = store;
        this.engine = engine;
        this.leaseDuration = leaseDuration;
        this.enabled = enabled;
        this.batchSize = Math.max(1, Math.min(batchSize, 100));
        this.maxConcurrent = Math.max(1, Math.min(maxConcurrent, 32));
    }

    @Scheduled(initialDelayString = "${app.soar.worker-initial-delay-ms:1000}",
            fixedDelayString = "${app.soar.worker-poll-ms:500}")
    public void poll() {
        if (!enabled) return;
        int capacity = Math.min(batchSize, maxConcurrent - inFlight.get());
        for (int index = 0; index < capacity; index++) {
            Instant now = Instant.now();
            SoarExecution execution = store.claimNext(owner, now, now.plus(leaseDuration));
            if (execution == null) break;
            inFlight.incrementAndGet();
            executionPool.submit(() -> {
                try {
                    engine.process(execution, owner);
                } finally {
                    inFlight.decrementAndGet();
                }
            });
        }
    }

    public int drain(int limit) {
        int processed = 0;
        while (processed < limit) {
            Instant now = Instant.now();
            SoarExecution execution = store.claimNext(owner, now, now.plus(leaseDuration));
            if (execution == null) break;
            engine.process(execution, owner);
            processed++;
        }
        return processed;
    }

    String owner() {
        return owner;
    }

    int inFlight() {
        return inFlight.get();
    }

    @PreDestroy
    public void close() {
        executionPool.shutdownNow();
    }
}
