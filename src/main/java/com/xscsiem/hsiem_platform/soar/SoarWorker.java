package com.xscsiem.hsiem_platform.soar;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "app.soar.runtime-enabled", havingValue = "true", matchIfMissing = true)
public class SoarWorker {

    private final SoarStore store;
    private final SoarExecutionEngine engine;
    private final Duration lease;
    private final int batchSize;
    private final String owner = ManagementFactory.getRuntimeMXBean().getName() + ":" + UUID.randomUUID();

    public SoarWorker(SoarStore store, SoarExecutionEngine engine,
                      @Value("${app.soar.worker-lease:PT30S}") Duration lease,
                      @Value("${app.soar.worker-batch-size:10}") int batchSize) {
        this.store = store;
        this.engine = engine;
        this.lease = lease;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${app.soar.worker-poll-ms:500}")
    public void poll() {
        store.claimDue(owner, lease, batchSize).forEach(engine::process);
    }
}
