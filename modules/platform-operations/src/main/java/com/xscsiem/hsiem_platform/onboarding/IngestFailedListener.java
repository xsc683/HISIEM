package com.xscsiem.hsiem_platform.onboarding;

import com.xscsiem.hsiem_platform.notify.NotificationService;
import org.springframework.stereotype.Component;

/** 数据源生效失败事件的统一通知入口，避免各异步分支自行拼通知内容。 */
@Component
public class IngestFailedListener {

    private final NotificationService notifications;

    public IngestFailedListener(NotificationService notifications) {
        this.notifications = notifications;
    }

    public void onFailed(LogSource source, String reason) {
        String target = source == null ? "unknown" : source.id;
        notifications.notify("ingest_failed", target,
                "数据源接入失败: " + (source == null ? target : source.name)
                        + (reason == null || reason.isBlank() ? "" : " - " + reason));
    }
}
