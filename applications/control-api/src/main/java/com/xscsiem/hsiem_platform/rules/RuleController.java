package com.xscsiem.hsiem_platform.rules;

import com.xscsiem.hsiem_platform.notify.NotificationService;
import com.xscsiem.hsiem_platform.tenant.TenantContext;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 检测规则管理 API(story-03):只读展示 + 启停 + MITRE 覆盖 + 命中数 + 部署生效。 */
@RestController
@RequestMapping("/api/detection-rules")
public class RuleController {

    private final RuleService rules;
    private final RulesDeployer deployer;
    private final NotificationService notify;
    private final ManagedDetectionService managed;

    public RuleController(RuleService rules, RulesDeployer deployer, NotificationService notify) {
        this(rules, deployer, notify, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public RuleController(RuleService rules, RulesDeployer deployer, NotificationService notify,
                          ManagedDetectionService managed) {
        this.rules = rules;
        this.deployer = deployer;
        this.notify = notify;
        this.managed = managed;
    }

    /** 规则列表(infra/rules/*.yaml,含 enabled 状态)。 */
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST', 'OPS', 'AUDIT')")
    public List<Map<String, Object>> list() {
        return rules.list();
    }

    /** 规则详情(元数据 + 条件/参数)。 */
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST', 'OPS', 'AUDIT')")
    public Map<String, Object> detail(@PathVariable String id) {
        return rules.get(id);
    }

    @GetMapping("/{id}/runtime")
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST', 'OPS', 'AUDIT')")
    public Map<String, Object> runtime(@PathVariable String id) {
        requireManagedRuntime();
        return managed.inspect(id, operator());
    }

    /** Desired-state API; physical deployment is performed asynchronously by the controller. */
    @PostMapping("/{id}/deploy")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> deployDesired(@PathVariable String id,
                                                              @RequestBody(required = false)
                                                              Map<String, Object> body) {
        requireManagedRuntime();
        return ResponseEntity.accepted().body(
                managed.deploy(TenantContext.id(), id, body == null ? Map.of() : body, operator()));
    }

    @PostMapping("/{id}/stop")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> stopDesired(@PathVariable String id) {
        requireManagedRuntime();
        return ResponseEntity.accepted().body(managed.stop(TenantContext.id(), id, operator()));
    }

    @PostMapping("/{id}/rollback")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> rollback(@PathVariable String id,
                                                        @RequestBody Map<String, Object> body) {
        requireManagedRuntime();
        return ResponseEntity.accepted().body(
                managed.rollback(TenantContext.id(), id, body == null ? Map.of() : body, operator()));
    }

    private void requireManagedRuntime() {
        if (managed == null) {
            throw new IllegalStateException("managed detection runtime is unavailable");
        }
    }


    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        Map<String, Object> created = rules.create(body, operator());
        created.put("deployed", false);
        created.put("redeployRequired", true);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /** 更新完整规则 DSL。规则 ID 不可修改，成功写入后进入待部署状态。 */
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> update(@PathVariable String id, @RequestBody Map<String, Object> body) {
        Map<String, Object> updated = rules.update(id, body, operator());
        updated.put("deployed", false);
        updated.put("redeployRequired", true);
        return updated;
    }

    /** 最近 range 内该规则命中数(ES 按 alert.rule_id 聚合)。 */
    @GetMapping("/{id}/hits")
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST', 'AUDIT')")
    public Map<String, Object> hits(@PathVariable String id,
                                    @RequestParam(defaultValue = "7d") String range) {
        return Map.of("ruleId", id, "range", range, "count", rules.hits(id, range));
    }

    /** 启停:翻转 enabled 写回 YAML(生效需调用 deploy → 重启检测 job)。 */
    @PostMapping("/{id}/toggle")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> toggle(@PathVariable String id) {
        Map<String, Object> updated = rules.toggle(id, operator());
        updated.put("deployed", false);
        updated.put("redeployRequired", true);
        updated.put("note", "已写回 infra/rules,需 POST /api/detection-rules/deploy 重启检测 job 后生效");
        return updated;
    }

    /** 兼容 Story 03/08 约定的 PATCH 语义:可显式指定 enabled,不传则执行翻转。 */
    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> patch(@PathVariable String id,
                                     @org.springframework.web.bind.annotation.RequestBody(required = false)
                                     Map<String, Object> body) {
        Map<String, Object> current = rules.get(id);
        Object requested = body == null ? null : body.get("enabled");
        Map<String, Object> updated = current;
        if (!(requested instanceof Boolean desired) || desired != Boolean.TRUE.equals(current.get("enabled"))) {
            updated = rules.toggle(id, operator());
        }
        updated.put("deployed", false);
        updated.put("redeployRequired", true);
        updated.put("note", "已写回 infra/rules,需 POST /api/detection-rules/deploy 重启检测 job 后生效");
        return updated;
    }

    private String operator() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null || authentication.getName() == null
                ? "system" : authentication.getName();
    }

    /** MITRE 覆盖矩阵(由规则 tags 动态聚合;Blind 盲区见 mitre-coverage.md)。 */
    @GetMapping("/mitre")
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST', 'AUDIT')")
    public Map<String, Object> mitre() {
        return rules.mitre();
    }

    /**
     * Compatibility bulk-deploy endpoint.  Phase 4 only records desired state; the physical
     * Flink controller is deliberately not invoked until the next phase.
     */
    @PostMapping("/deploy")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> deploy() {
        Map<String, Object> response = new LinkedHashMap<>();
        List<Map<String, Object>> summaries = managed == null
                ? List.of()
                : managed.deployAll(TenantContext.id(), rules.list(), operator());
        response.put("status", "PENDING");
        response.put("jobId", null);
        response.put("summaries", summaries);
        response.put("pendingSummaries", summaries);
        response.put("note", "desired state 已写入并保持 PENDING；本阶段不执行物理 Flink 部署，等待下一阶段 controller");
        return ResponseEntity.accepted().body(response);
    }
}
