package com.xscsiem.hsiem_platform.rules;

import com.xscsiem.hsiem_platform.notify.NotificationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.access.prepost.PreAuthorize;

import java.util.List;
import java.util.Map;

/** 检测规则管理 API(story-03):只读展示 + 启停 + MITRE 覆盖 + 命中数 + 部署生效。 */
@RestController
@RequestMapping("/api/detection-rules")
public class RuleController {

    private final RuleService rules;
    private final RulesDeployer deployer;
    private final NotificationService notify;

    public RuleController(RuleService rules, RulesDeployer deployer, NotificationService notify) {
        this.rules = rules;
        this.deployer = deployer;
        this.notify = notify;
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

    /** 最近 range 内该规则命中数(ES 按 alert.rule_id 聚合)。 */
    @GetMapping("/{id}/hits")
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST', 'AUDIT')")
    public Map<String, Object> hits(@PathVariable String id,
                                    @RequestParam(defaultValue = "7d") String range) {
        return Map.of("ruleId", id, "range", range, "count", rules.hits(id, range));
    }

    /** 启停:翻转 enabled 写回 YAML(生效需调用 deploy → 重启检测 job)。 */
    @PostMapping("/{id}/toggle")
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST')")
    public Map<String, Object> toggle(@PathVariable String id) {
        Map<String, Object> updated = rules.toggle(id);
        updated.put("deployed", false);
        updated.put("note", "已写回 infra/rules,需 POST /api/detection-rules/deploy 重启检测 job 后生效");
        return updated;
    }

    /** MITRE 覆盖矩阵(由规则 tags 动态聚合;Blind 盲区见 mitre-coverage.md)。 */
    @GetMapping("/mitre")
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST', 'AUDIT')")
    public Map<String, Object> mitre() {
        return rules.mitre();
    }

    /** 部署生效:同步规则到 Flink jobmanager + 重启检测 job(一次重部署成本,约 15-35s)。 */
    @PostMapping("/deploy")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> deploy() {
        deployer.syncRules();
        String jobId = deployer.restartDetectionJob();
        notify.notify("rule_deploy", jobId, "检测规则已部署(job " + jobId + "),enabled 变更生效");
        Map<String, Object> resp = Map.of(
                "status", "deployed",
                "jobId", jobId,
                "note", "规则已同步 /opt/flink/rules,检测 job 已从 savepoint 恢复,enabled 变更生效");
        return ResponseEntity.accepted().body(resp);
    }
}
