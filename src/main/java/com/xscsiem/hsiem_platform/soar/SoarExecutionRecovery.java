package com.xscsiem.hsiem_platform.soar;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Instant;

/** 回收崩溃 Worker 的过期租约并重新入队，不把可恢复执行误标为终态失败。 */
@Component
public class SoarExecutionRecovery {

    private final SoarExecutionStore store;

    public SoarExecutionRecovery(SoarExecutionStore store) {
        this.store = store;
    }

    @Scheduled(initialDelayString = "${app.soar.recovery-initial-delay-ms:60000}",
            fixedDelayString = "${app.soar.recovery-interval-ms:60000}")
    public void recover() {
        store.recoverExpiredLeases(Instant.now());
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverOnStartup() {
        recover();
    }
}
