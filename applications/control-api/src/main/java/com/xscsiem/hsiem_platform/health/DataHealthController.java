package com.xscsiem.hsiem_platform.health;

import com.xscsiem.hsiem_platform.notify.NotificationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.access.prepost.PreAuthorize;

import java.util.List;
import java.util.Map;

/** 数据源健康 API(story-05):每源健康指标 / 趋势 / 失败日志下钻。 */
@RestController
@RequestMapping("/api/data-health")
public class DataHealthController {

    private final DataHealthService service;
    private final NotificationService notify;

    public DataHealthController(DataHealthService service, NotificationService notify) {
        this.service = service;
        this.notify = notify;
    }

    @GetMapping("/sources")
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST', 'OPS', 'AUDIT')")
    public List<Map<String, Object>> sources() {
        List<Map<String, Object>> sources = service.sources();
        // 健康异常 → 通知(频控:同源 1h 1 条,见 story-10)
        for (Map<String, Object> s : sources) {
            if (Boolean.TRUE.equals(s.get("anomalous"))) {
                notify.notify("health_anomaly", String.valueOf(s.get("sourceId")),
                        "数据源 " + s.get("sourceName") + " 解析异常(" + s.get("reason") + ")");
            }
        }
        return sources;
    }

    @GetMapping("/sources/{id}/trend")
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST', 'OPS', 'AUDIT')")
    public List<Map<String, Object>> trend(@PathVariable String id) {
        return service.trend(id);
    }

    @GetMapping("/sources/{id}/failures")
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST', 'OPS', 'AUDIT')")
    public List<Map<String, Object>> failures(@PathVariable String id,
                                              @RequestParam(defaultValue = "50") int size) {
        return service.failures(id, size);
    }
}
