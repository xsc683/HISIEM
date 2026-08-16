package com.xscsiem.hsiem_platform.investigation;

import com.xscsiem.hsiem_platform.auth.AuthService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 调查台·案件聚合 API(story-07)。 */
@RestController
@RequestMapping("/api/cases")
public class CaseController {

    private final CaseService service;
    private final AuthService auth;

    public CaseController(CaseService service, AuthService auth) {
        this.service = service;
        this.auth = auth;
    }

    /** 案件列表(可按 status/entity 过滤)。 */
    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(required = false) String status,
                                          @RequestParam(required = false) String entity,
                                          @RequestParam(defaultValue = "100") int size) {
        return service.list(status, entity, size);
    }

    /** 案件详情(含 alert_ids/entities/状态)。 */
    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable String id) {
        return service.detail(id);
    }

    /** 手动聚合(≥2 条 open 告警)。 */
    @PostMapping
    public Map<String, Object> create(@RequestBody CreateRequest req,
                                      @RequestHeader(value = "Authorization", required = false) String authHeader) {
        return service.create(req.alertIds(), req.title(), operator(authHeader));
    }

    /** 手动追加告警。 */
    @PostMapping("/{id}/alerts")
    public Map<String, Object> addAlerts(@PathVariable String id, @RequestBody AddAlertsRequest req,
                                         @RequestHeader(value = "Authorization", required = false) String authHeader) {
        return service.addAlerts(id, req.alertIds(), operator(authHeader));
    }

    /** 手动移出告警。 */
    @DeleteMapping("/{id}/alerts/{alertId}")
    public void removeAlert(@PathVariable String id, @PathVariable String alertId) {
        service.removeAlert(id, alertId);
    }

    /** 状态流转(open→investigating→resolved);resolved 触发结案联动。 */
    @PostMapping("/{id}/status")
    public Map<String, Object> updateStatus(@PathVariable String id, @RequestBody StatusRequest req,
                                            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        return service.updateStatus(id, req.status(), req.verdict(), operator(authHeader));
    }

    /** 时间线(实时关联 siem-events)。 */
    @GetMapping("/{id}/timeline")
    public List<Map<String, Object>> timeline(@PathVariable String id,
                                              @RequestParam(defaultValue = "50") int size) {
        return service.timeline(id, size);
    }

    /** 删除案件(移空后可删空案)。 */
    @DeleteMapping("/{id}")
    public void delete(@PathVariable String id) {
        service.delete(id);
    }

    /** 手动触发一轮自动聚合(运维/测试用)。 */
    @PostMapping("/aggregate")
    public Map<String, Object> aggregate() {
        int created = service.aggregateAuto(30);
        return Map.of("created", created);
    }

    private String operator(String authHeader) {
        try {
            String token = authHeader != null && authHeader.startsWith("Bearer ")
                    ? authHeader.substring(7) : "";
            return auth.me(token).username;
        } catch (Exception e) {
            return "anonymous";
        }
    }

    public record CreateRequest(List<String> alertIds, String title) {
    }

    public record AddAlertsRequest(List<String> alertIds) {
    }

    public record StatusRequest(String status, String verdict) {
    }
}
