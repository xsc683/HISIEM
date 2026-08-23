package com.xscsiem.hsiem_platform.soar;

import com.xscsiem.hsiem_platform.alert.AlertService;
import com.xscsiem.hsiem_platform.investigation.CaseService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 将 Sentinel 式自动化规则与 Playbook 解耦：扫描事实源、条件匹配、dedup 后入队。 */
@Component
public class SoarTriggerScanner {

    private final SoarPlaybookRegistry registry;
    private final SoarService service;
    private final AlertService alerts;
    private final CaseService cases;
    private final boolean enabled;

    public SoarTriggerScanner(SoarPlaybookRegistry registry, SoarService service,
                              AlertService alerts, CaseService cases,
                              @Value("${app.soar.auto-trigger-enabled:false}") boolean enabled) {
        this.registry = registry;
        this.service = service;
        this.alerts = alerts;
        this.cases = cases;
        this.enabled = enabled;
    }

    @Scheduled(initialDelayString = "${app.soar.trigger-initial-delay-ms:10000}",
            fixedDelayString = "${app.soar.trigger-scan-ms:10000}")
    public void scheduledScan() {
        if (enabled) scanNow();
    }

    public Map<String, Object> scanNow() {
        int checked = 0;
        int matched = 0;
        int submitted = 0;
        List<String> errors = new ArrayList<>();
        for (SoarPlaybook playbook : registry.list()) {
            if (!playbook.isEnabled() || playbook.triggers() == null) continue;
            for (SoarPlaybook.Trigger trigger : playbook.triggers()) {
                if (!trigger.isEnabled()) continue;
                List<Map<String, Object>> resources = resources(trigger.type());
                for (Map<String, Object> resource : resources) {
                    checked++;
                    String id = resourceId(trigger.type(), resource);
                    if (id == null) continue;
                    Map<String, Object> context = triggerContext(playbook, trigger.type(), id, resource);
                    if (!SoarExpression.matches(trigger.when(), context)) continue;
                    matched++;
                    try {
                        service.startTriggered(playbook, trigger, trigger.type(), id, resource);
                        submitted++;
                    } catch (Exception e) {
                        errors.add(playbook.id() + "/" + trigger.id() + "/" + id + ": " + e.getMessage());
                    }
                }
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("checked", checked);
        result.put("matched", matched);
        result.put("submittedOrDeduplicated", submitted);
        result.put("errors", errors.stream().limit(20).toList());
        return result;
    }

    private List<Map<String, Object>> resources(String type) {
        return switch (type) {
            case "alert" -> alerts.list("open", 200);
            case "case" -> cases.list("open", null, 200);
            default -> List.of();
        };
    }

    private static String resourceId(String type, Map<String, Object> resource) {
        Object value = "alert".equals(type) ? resource.get("_id") : resource.get("case.id");
        return value == null ? null : String.valueOf(value);
    }

    private static Map<String, Object> triggerContext(SoarPlaybook playbook, String type,
                                                       String id, Map<String, Object> resource) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("playbookId", playbook.id());
        context.put("resourceType", type);
        context.put("resourceId", id);
        context.put("resource", resource);
        if ("alert".equals(type)) context.put("alertId", id);
        if ("case".equals(type)) context.put("caseId", id);
        return context;
    }
}
