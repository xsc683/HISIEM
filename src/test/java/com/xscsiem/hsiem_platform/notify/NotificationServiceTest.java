package com.xscsiem.hsiem_platform.notify;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 通知中心(story-10):创建/已读/清空/删除/频控。 */
class NotificationServiceTest {

    private final NotificationService svc = new NotificationService();

    @Test
    void notify_createsAndRead() {
        svc.notify("rule_deploy", "job1", "检测规则已部署");
        svc.notify("health_anomaly", "src1", "数据源解析异常");
        List<Map<String, Object>> all = svc.list(null);
        assertEquals(2, all.size());
        assertEquals(2, svc.list(true).size());   // 未读

        svc.read(String.valueOf(all.get(0).get("id")));
        assertEquals(1, svc.list(true).size());   // 一条已读
    }

    @Test
    void readAll_andDelete() {
        svc.notify("rule_deploy", "job1", "a");
        svc.notify("rule_deploy", "job2", "b");
        svc.readAll();
        assertEquals(0, svc.list(true).size());
        String id = String.valueOf(svc.list(null).get(0).get("id"));
        svc.delete(id);
        assertEquals(1, svc.list(null).size());
    }

    @Test
    void frequencyControl_suppressesWithin1h() {
        svc.notify("health_anomaly", "src1", "第一次");
        svc.notify("health_anomaly", "src1", "第二次(应被频控拦截)");
        svc.notify("health_anomaly", "src2", "不同源不受限");
        assertEquals(2, svc.list(null).size());   // src1 只有 1 条 + src2 1 条
        assertTrue(svc.list(null).stream()
                .noneMatch(n -> "第二次(应被频控拦截)".equals(n.get("message"))));
    }
}
