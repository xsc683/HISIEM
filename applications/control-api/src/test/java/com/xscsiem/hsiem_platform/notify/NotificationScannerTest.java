package com.xscsiem.hsiem_platform.notify;

import com.xscsiem.hsiem_platform.alert.AlertService;
import com.xscsiem.hsiem_platform.health.DataHealthService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationScannerTest {

    @Test
    void scanNotifiesHighFpAndHealthAnomaly() {
        AlertService alerts = mock(AlertService.class);
        DataHealthService health = mock(DataHealthService.class);
        NotificationService notifications = mock(NotificationService.class);
        when(alerts.fpRate()).thenReturn(List.of(Map.of(
                "ruleId", "rule-1", "high", true, "total", 20, "fpRate", 75.0)));
        when(health.sources()).thenReturn(List.of(Map.of(
                "sourceId", "ls-1", "sourceName", "auth", "anomalous", true, "reason", "失败率")));

        new NotificationScanner(alerts, health, notifications).scanOnce();

        verify(notifications).notify("false_positive", "rule-1",
                "规则 FP 率偏高: rule-1 (75.0%, 样本 20)");
        verify(notifications).notify("health_anomaly", "ls-1", "数据源健康异常: auth - 失败率");
        verify(notifications).cleanup(org.mockito.ArgumentMatchers.any());
    }
}
