package com.xscsiem.hsiem_platform.soar;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/** 将进程退出后遗留的 queued/running SOAR 执行收敛为可人工重试的 failed。 */
@Component
public class SoarExecutionRecovery {

    private final SoarExecutionStore store;
    private final Duration staleAfter;

    public SoarExecutionRecovery(SoarExecutionStore store,
                                 @Value("${app.soar.stale-after:PT5M}") Duration staleAfter) {
        this.store = store;
        this.staleAfter = staleAfter;
    }

    @Scheduled(initialDelayString = "${app.soar.recovery-initial-delay-ms:60000}",
            fixedDelayString = "${app.soar.recovery-interval-ms:60000}")
    public void recover() {
        store.recoverStale(Instant.now().minus(staleAfter));
    }
}
