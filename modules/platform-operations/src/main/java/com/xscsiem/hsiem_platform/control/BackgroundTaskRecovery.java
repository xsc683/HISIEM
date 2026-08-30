package com.xscsiem.hsiem_platform.control;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * 收敛进程重启后遗留的后台任务。
 * 任务执行器是进程内线程池，无法在进程退出后继续持有执行上下文；恢复器将超过心跳窗口的
 * 任务标记为失败，前端可以据此提示用户重试，而不是永久显示“进行中”。
 */
@Component
@ConditionalOnProperty(name = "app.operations.runtime-enabled", havingValue = "true", matchIfMissing = true)
public class BackgroundTaskRecovery {

    private static final Logger log = LoggerFactory.getLogger(BackgroundTaskRecovery.class);

    private final ControlPlaneStore control;
    private final Duration staleAfter;

    public BackgroundTaskRecovery(ControlPlaneStore control,
                                  @Value("${app.tasks.stale-after:PT5M}") Duration staleAfter) {
        this.control = control;
        this.staleAfter = staleAfter.isNegative() || staleAfter.isZero() ? Duration.ofMinutes(5) : staleAfter;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverOnStartup() {
        recover();
    }

    @Scheduled(fixedDelayString = "${app.tasks.recovery-interval-ms:60000}",
            initialDelayString = "${app.tasks.recovery-initial-delay-ms:60000}")
    public void recoverPeriodically() {
        recover();
    }

    private void recover() {
        try {
            int recovered = control.recoverStaleTasks(Instant.now().minus(staleAfter),
                    "服务重启或任务心跳超时，任务未能完成；请重新提交");
            if (recovered > 0) {
                log.warn("Recovered {} stale background task(s)", recovered);
            }
        } catch (RuntimeException e) {
            // 恢复器不能阻断主应用启动；下一次调度会重试。
            log.error("Failed to recover stale background tasks", e);
        }
    }
}
