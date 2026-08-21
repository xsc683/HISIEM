package com.xscsiem.hsiem_platform.notify;

import com.xscsiem.hsiem_platform.alert.AlertService;
import com.xscsiem.hsiem_platform.health.DataHealthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/** 定时通知扫描：高 FP、接入健康异常和通知保留期清理。 */
@Component
public class NotificationScanner {

    private static final Logger LOG = LoggerFactory.getLogger(NotificationScanner.class);
    private final AlertService alerts;
    private final DataHealthService health;
    private final NotificationService notifications;

    public NotificationScanner(AlertService alerts, DataHealthService health,
                               NotificationService notifications) {
        this.alerts = alerts;
        this.health = health;
        this.notifications = notifications;
    }

    @Scheduled(initialDelayString = "$" + "{app.notifications.initial-delay-ms:30000}",
            fixedDelayString = "$" + "{app.notifications.scan-delay-ms:60000}")
    public void scheduledScan() {
        scanOnce();
    }

    /** 可供运维接口和单元测试显式调用的一轮扫描。 */
    public void scanOnce() {
        try {
            for (Map<String, Object> row : alerts.fpRate()) {
                Object high = row.get("high");
                Object total = row.get("total");
                if (Boolean.TRUE.equals(high) && total instanceof Number n && n.intValue() >= 20) {
                    String rule = String.valueOf(row.get("ruleId"));
                    notifications.notify("false_positive", rule,
                            "规则 FP 率偏高: " + rule + " (" + row.get("fpRate") + "%, 样本 " + n + ")");
                }
            }
        } catch (Exception e) {
            LOG.warn("FP 通知扫描失败: {}", e.getMessage());
        }
        try {
            for (Map<String, Object> row : health.sources()) {
                if (Boolean.TRUE.equals(row.get("anomalous"))) {
                    String source = String.valueOf(row.get("sourceId"));
                    notifications.notify("health_anomaly", source,
                            "数据源健康异常: " + row.getOrDefault("sourceName", source)
                                    + " - " + row.getOrDefault("reason", "unknown"));
                }
            }
        } catch (Exception e) {
            LOG.warn("数据源健康通知扫描失败: {}", e.getMessage());
        }
        try {
            notifications.cleanup(Instant.now().minus(Duration.ofDays(30)));
        } catch (Exception e) {
            LOG.warn("通知清理失败: {}", e.getMessage());
        }
    }
}
