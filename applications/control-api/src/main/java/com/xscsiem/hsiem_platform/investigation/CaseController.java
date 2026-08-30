package com.xscsiem.hsiem_platform.investigation;

import com.xscsiem.hsiem_platform.agent.AgentLaunchResponse;
import com.xscsiem.hsiem_platform.agent.AgentLaunchService;
import com.xscsiem.hsiem_platform.auth.AuthService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.Map;

/** 调查台·案件聚合 API(story-07)。 */
@RestController
@RequestMapping("/api/cases")
public class CaseController {

    private final CaseService service;
    private final AuthService auth;
    private final AgentLaunchService agentLaunch;

    public CaseController(CaseService service, AuthService auth, AgentLaunchService agentLaunch) {
        this.service = service;
        this.auth = auth;
        this.agentLaunch = agentLaunch;
    }

    /** 案件列表(可按 status/entity 过滤)。 */
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST', 'AUDIT')")
    public List<Map<String, Object>> list(@RequestParam(required = false) String status,
                                          @RequestParam(required = false) String entity,
                                          @RequestParam(defaultValue = "100") int size) {
        return service.list(status, entity, size);
    }

    /** 大屏案件全量状态聚合与最新队列。 */
    @GetMapping("/summary")
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST', 'AUDIT')")
    public Map<String, Object> summary() {
        return service.summary();
    }

    /** 案件详情(含 alert_ids/entities/状态)。 */
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST', 'AUDIT')")
    public Map<String, Object> detail(@PathVariable String id) {
        return service.detail(id);
    }

    /** 从案件详情启动 Agent 调查；仅传递 HISIEM resource reference。 */
    @PostMapping("/{id}/agent-investigation")
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST')")
    public AgentLaunchResponse investigateWithAgent(@PathVariable String id,
                                                    Authentication authentication) {
        return agentLaunch.launch("case_investigation", "case", id,
                authentication.getName());
    }

    /** 手动聚合(≥2 条 open 告警)。 */
    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST')")
    public Map<String, Object> create(@RequestBody CreateRequest req,
                                      @RequestHeader(value = "Authorization", required = false) String authHeader) {
        return service.create(req.alertIds(), req.title(), operator(authHeader));
    }

    /** 手动追加告警。 */
    @PostMapping("/{id}/alerts")
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST')")
    public Map<String, Object> addAlerts(@PathVariable String id, @RequestBody AddAlertsRequest req,
                                         @RequestHeader(value = "Authorization", required = false) String authHeader) {
        return service.addAlerts(id, req.alertIds(), operator(authHeader));
    }

    /** 手动移出告警。 */
    @DeleteMapping("/{id}/alerts/{alertId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST')")
    public void removeAlert(@PathVariable String id, @PathVariable String alertId) {
        service.removeAlert(id, alertId);
    }

    /** 状态流转(open→investigating→resolved);resolved 触发结案联动。 */
    @PostMapping("/{id}/status")
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST')")
    public Map<String, Object> updateStatus(@PathVariable String id, @RequestBody StatusRequest req,
                                            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        return service.updateStatus(id, req.status(), req.verdict(), operator(authHeader));
    }

    /** 更新案件负责人和证据清单。证据项建议包含 type、title、uri、note。 */
    @PatchMapping("/{id}/metadata")
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST')")
    public Map<String, Object> updateMetadata(@PathVariable String id, @RequestBody MetadataRequest req,
                                              @RequestHeader(value = "Authorization", required = false) String authHeader) {
        return service.updateMetadata(id, req.owner(), req.evidence(), operator(authHeader));
    }

    /** 设置案件协作负责人列表，避免只能通过单一 owner 协作。 */
    @PostMapping("/{id}/collaborators")
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST')")
    public Map<String, Object> collaborators(@PathVariable String id, @RequestBody CollaboratorsRequest req,
                                              @RequestHeader(value = "Authorization", required = false) String authHeader) {
        return service.updateCollaborators(id, req.usernames(), operator(authHeader));
    }

    /** 时间线(实时关联 siem-events)。 */
    @GetMapping("/{id}/timeline")
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST', 'AUDIT')")
    public List<Map<String, Object>> timeline(@PathVariable String id,
                                              @RequestParam(defaultValue = "50") int size) {
        return service.timeline(id, size);
    }

    /** 删除案件(移空后可删空案)。 */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public void delete(@PathVariable String id) {
        service.delete(id);
    }

    /** 手动触发一轮自动聚合(运维/测试用)。 */
    @PostMapping("/aggregate")
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST')")
    public Map<String, Object> aggregate(@RequestParam(defaultValue = "30") int windowMinutes,
                                         @RequestParam(defaultValue = "false") boolean groupByRule,
                                         @RequestParam(defaultValue = "2") int threshold,
                                         @RequestParam(required = false) String ruleId) {
        int created = service.aggregateAuto(windowMinutes, groupByRule, threshold, ruleId);
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

    public record MetadataRequest(String owner, List<Map<String, Object>> evidence) {
    }

    public record CollaboratorsRequest(List<String> usernames) {
    }
}
