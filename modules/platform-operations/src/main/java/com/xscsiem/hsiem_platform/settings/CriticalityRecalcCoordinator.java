package com.xscsiem.hsiem_platform.settings;

import com.xscsiem.hsiem_platform.control.AuthStore;
import com.xscsiem.hsiem_platform.control.TaskStore;
import com.xscsiem.hsiem_platform.notify.NotificationService;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.stereotype.Component;

/** 关键度变更后的异步重算任务，状态统一落入 background_tasks。 */
@Component
public class CriticalityRecalcCoordinator {

    private static final ExecutorService EXECUTOR =
            Executors.newSingleThreadExecutor(
                    r -> {
                        Thread t = new Thread(r, "criticality-recalc");
                        t.setDaemon(true);
                        return t;
                    });
    private static final String TASK_OWNER = "criticality-worker-" + UUID.randomUUID();

    private final CriticalityDeployer deployer;
    private final TaskStore control;
    private final AuthStore auth;
    private final NotificationService notifications;

    public CriticalityRecalcCoordinator(
            CriticalityDeployer deployer,
            TaskStore control,
            AuthStore auth,
            NotificationService notifications) {
        this.deployer = deployer;
        this.control = control;
        this.auth = auth;
        this.notifications = notifications;
    }

    public String enqueue(String actor) {
        String taskId = control.createTask("criticality_recalc", "entity-risk", "等待实体风险重算");
        try {
            EXECUTOR.execute(
                    () -> {
                        if (!control.claimTask(taskId, TASK_OWNER, Instant.now().plusSeconds(600)))
                            return;
                        control.heartbeatTask(
                                taskId,
                                TASK_OWNER,
                                Instant.now().plusSeconds(600),
                                10,
                                "运行 entity-risk.py");
                        try {
                            deployer.recalcEntityRisk();
                            control.updateTask(taskId, "succeeded", 100, "实体风险已重算", null);
                            notifications.notify("criticality", "entity-risk", "实体风险已按最新资产关键度重算");
                            auth.audit(
                                    actor == null ? "system" : actor, "criticality.recalc", taskId);
                        } catch (Exception e) {
                            control.updateTask(taskId, "failed", 100, "实体风险重算失败", safeError(e));
                        }
                    });
        } catch (RuntimeException e) {
            control.updateTask(taskId, "failed", 100, "实体风险重算任务未能排队", safeError(e));
            throw e;
        }
        return taskId;
    }

    @PreDestroy
    void shutdownExecutor() {
        EXECUTOR.shutdownNow();
    }

    private static String safeError(Exception e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        // WSL 在 Windows 主机上可能把 UTF-16 转发为带 NUL 的字符串,PostgreSQL
        // 不接受 0x00。错误信息仍保留,但清洗为可持久化文本并限制长度。
        String cleaned = message.replace("\u0000", "");
        return cleaned.substring(0, Math.min(cleaned.length(), 4000));
    }
}
