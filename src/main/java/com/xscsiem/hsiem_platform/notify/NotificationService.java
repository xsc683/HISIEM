package com.xscsiem.hsiem_platform.notify;

import com.xscsiem.hsiem_platform.onboarding.NotFoundException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 通知中心(story-10,MVP):控制台内横幅+日志通知,不投递外部。
 * - 触发:检测规则部署、实体风险重算、数据源健康异常(高 FP/接入失败/健康异常)。
 * - 频控:同 type+target 1h 内最多 1 条(防轰炸)。
 * - 存储:内存(MVP;持久化/外部投递邮件 Webhook 为 P1+)。
 */
@Service
public class NotificationService {

    private static final long FREQ_MS = 3600_000;   // 1h

    private final List<Map<String, Object>> notifications = new CopyOnWriteArrayList<>();
    private final Map<String, Long> lastNotify = new ConcurrentHashMap<>();
    private final AtomicLong idSeq = new AtomicLong();

    public List<Map<String, Object>> list(Boolean unread) {
        return notifications.stream()
                .filter(n -> unread == null || !Boolean.TRUE.equals(unread) || !Boolean.TRUE.equals(n.get("read")))
                .toList();
    }

    public void read(String id) {
        Map<String, Object> n = find(id);
        n.put("read", true);
    }

    public void readAll() {
        notifications.forEach(n -> n.put("read", true));
    }

    public void delete(String id) {
        notifications.removeIf(n -> id.equals(n.get("id")));
    }

    /** 创建通知(频控:同 type+target 1h 内 1 条)。 */
    public void notify(String type, String target, String message) {
        String key = type + ":" + target;
        long now = System.currentTimeMillis();
        Long last = lastNotify.get(key);
        if (last != null && now - last < FREQ_MS) {
            return;
        }
        lastNotify.put(key, now);
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("id", "ntf-" + idSeq.incrementAndGet());
        n.put("type", type);
        n.put("target", target);
        n.put("message", message);
        n.put("timestamp", Instant.now().toString());
        n.put("read", false);
        notifications.add(n);
    }

    private Map<String, Object> find(String id) {
        return notifications.stream()
                .filter(n -> id.equals(n.get("id")))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("通知不存在: " + id));
    }
}
