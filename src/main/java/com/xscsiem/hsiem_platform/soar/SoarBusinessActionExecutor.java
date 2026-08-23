package com.xscsiem.hsiem_platform.soar;

import com.xscsiem.hsiem_platform.alert.AlertService;
import com.xscsiem.hsiem_platform.investigation.CaseService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class SoarBusinessActionExecutor {

    private final AlertService alerts;
    private final CaseService cases;

    public SoarBusinessActionExecutor(AlertService alerts, CaseService cases) {
        this.alerts = alerts;
        this.cases = cases;
    }

    public Map<String, Object> execute(SoarExecution execution, String action,
                                       Map<String, Object> parameters) {
        String actor = "soar:" + execution.id();
        String objectId = execution.objectId();
        return switch (action) {
            case "alert.update_status" -> alerts.update(objectId, text(parameters, "status"), null, actor);
            case "alert.update_verdict" -> alerts.update(objectId, null, text(parameters, "verdict"), actor);
            case "alert.create_case" -> cases.createFromAlert(objectId, text(parameters, "title"), actor);
            case "alert.add_to_case" -> cases.addAlerts(text(parameters, "case_id"), List.of(objectId), actor);
            case "case.update_status" -> cases.updateStatus(objectId, text(parameters, "status"), null, actor);
            case "case.close" -> cases.updateStatus(objectId, "resolved", text(parameters, "verdict"), actor);
            case "case.add_alert" -> cases.addAlerts(objectId, List.of(text(parameters, "alert_id")), actor);
            case "case.update_owner" -> updateOwner(objectId, text(parameters, "owner"), actor);
            case "case.add_evidence" -> addEvidence(objectId, parameters, actor);
            default -> throw new IllegalArgumentException("未知业务动作: " + action);
        };
    }

    private Map<String, Object> updateOwner(String caseId, String owner, String actor) {
        Map<String, Object> current = cases.detail(caseId);
        return cases.updateMetadata(caseId, owner, evidence(current), actor);
    }

    private Map<String, Object> addEvidence(String caseId, Map<String, Object> parameters, String actor) {
        Map<String, Object> current = cases.detail(caseId);
        List<Map<String, Object>> evidence = new ArrayList<>(evidence(current));
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", text(parameters, "type"));
        item.put("value", text(parameters, "value"));
        item.put("source", "soar");
        evidence.add(item);
        return cases.updateMetadata(caseId, string(current.get("case.owner")), evidence, actor);
    }

    private List<Map<String, Object>> evidence(Map<String, Object> current) {
        Object value = current.get("evidence");
        if (!(value instanceof List<?> list)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                Map<String, Object> copy = new LinkedHashMap<>();
                map.forEach((key, entry) -> copy.put(String.valueOf(key), entry));
                result.add(copy);
            }
        }
        return result;
    }

    private String text(Map<String, Object> parameters, String key) {
        String value = string(parameters.get(key));
        if (value.isBlank()) throw new IllegalArgumentException("业务动作参数不能为空: " + key);
        return value;
    }

    private String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
