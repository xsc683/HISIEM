package com.xscsiem.hsiem_platform.settings;

import com.xscsiem.hsiem_platform.control.ControlPlaneStore;
import com.xscsiem.hsiem_platform.notify.NotificationService;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 关键度变更后的异步重算任务，状态统一落入 background_tasks。 */
@Component
public class CriticalityRecalcCoordinator {

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "criticality-recalc");
        t.setDaemon(true);
        return t;
    });

    private final CriticalityDeployer deployer;
    private final ControlPlaneStore control;
    private final NotificationService notifications;

    public CriticalityRecalcCoordinator(CriticalityDeployer deployer, ControlPlaneStore control,
                                        NotificationService notifications) {
        this.deployer = deployer;
        this.control = control;
        this.notifications = notifications;
    }

    public String enqueue(String actor) {
        String taskId = control.createTask("criticality_recalc", "entity-risk", "等待实体风险重算");
        EXECUTOR.execute(() -> {
            control.updateTask(taskId, "running", 10, "运行 entity-risk.py", null);
            try {
                deployer.recalcEntityRisk();
                control.updateTask(taskId, "succeeded", 100, "实体风险已重算", null);
                notifications.notify("criticality", "entity-risk", "实体风险已按最新资产关键度重算");
                control.audit(actor == null ? "anonymous" : actor, "criticality.recalc", taskId);
            } catch (Exception e) {
                control.updateTask(taskId, "failed", 100, "实体风险重算失败", safeError(e));
            }
        });
        return taskId;
    }

    private static String safeError(Exception e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        // WSL 在 Windows 主机上可能把 UTF-16 转发为带 NUL 的字符串,PostgreSQL
        // 不接受 0x00。错误信息仍保留,但清洗为可持久化文本并限制长度。
        String cleaned = message.replace("\u0000", "");
        return cleaned.substring(0, Math.min(cleaned.length(), 4000));
    }
}
