package com.xscsiem.hsiem_platform.alert;

import com.xscsiem.hsiem_platform.agent.AgentLaunchResponse;
import com.xscsiem.hsiem_platform.agent.AgentLaunchService;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 告警三线处置 API(story-04,替代 triage-alert.py 的交互版)。 */
@RestController
@RequestMapping("/api/alerts")
public class AlertController {

    private final AlertService service;
    private final AgentLaunchService agentLaunch;

    public AlertController(AlertService service, AgentLaunchService agentLaunch) {
        this.service = service;
        this.agentLaunch = agentLaunch;
    }

    /** 告警列表(open 默认,risk_score DESC)。 */
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST', 'AUDIT')")
    public List<Map<String, Object>> list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "100") int size) {
        return service.list(status, size);
    }

    /** 大屏告警全量聚合与最新队列。 */
    @GetMapping("/summary")
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST', 'AUDIT')")
    public Map<String, Object> summary() {
        return service.summary();
    }

    /** 告警详情。 */
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST', 'AUDIT')")
    public Map<String, Object> detail(@PathVariable String id) {
        return service.detail(id);
    }

    /** 从告警详情启动 Agent 调查；仅传递 HISIEM resource reference。 */
    @PostMapping("/{id}/agent-investigation")
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST')")
    public AgentLaunchResponse investigateWithAgent(
            @PathVariable String id, Authentication authentication) {
        return agentLaunch.launch("alert_investigation", "alert", id, authentication.getName());
    }

    /** 三线流转(open→ack→investigating→resolved/closed)。 */
    @PostMapping("/{id}/status")
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST')")
    public Map<String, Object> updateStatus(
            @PathVariable String id, @RequestBody StatusRequest req) {
        return service.update(id, req.status(), null, operator());
    }

    /** 打 verdict(true_positive/false_positive/duplicate)。 */
    @PostMapping("/{id}/verdict")
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST')")
    public Map<String, Object> updateVerdict(
            @PathVariable String id, @RequestBody VerdictRequest req) {
        return service.update(id, null, req.verdict(), operator());
    }

    /** 批量状态(批量 close 前置已补 verdict)。 */
    @PostMapping("/batch-status")
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST')")
    public Map<String, Object> batchStatus(@RequestBody BatchStatusRequest req) {
        return service.batch(req.ids(), req.status(), null, operator());
    }

    /** 批量 verdict。 */
    @PostMapping("/batch-verdict")
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST')")
    public Map<String, Object> batchVerdict(@RequestBody BatchVerdictRequest req) {
        return service.batch(req.ids(), null, req.verdict(), operator());
    }

    /** 按规则 FP 率(FP/(TP+FP),>50% 高亮)。 */
    @GetMapping("/fp-rate")
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST', 'AUDIT')")
    public List<Map<String, Object>> fpRate() {
        return service.fpRate();
    }

    /** 审计 actor 恒取自已认证 SecurityContext,避免手工解析 Authorization/异常降级。 */
    private static String operator() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null || authentication.getName() == null
                ? "system"
                : authentication.getName();
    }

    public record StatusRequest(String status) {}

    public record VerdictRequest(String verdict) {}

    public record BatchStatusRequest(List<String> ids, String status) {}

    public record BatchVerdictRequest(List<String> ids, String verdict) {}
}
