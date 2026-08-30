package com.xscsiem.hsiem_platform.soar;

import com.xscsiem.hsiem_platform.soar.port.SecurityOperationPort;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class SoarBusinessActionExecutor {

    private final SecurityOperationPort operations;

    public SoarBusinessActionExecutor(SecurityOperationPort operations) {
        this.operations = operations;
    }

    public Map<String, Object> execute(SoarExecution execution, String action,
                                       Map<String, Object> parameters) {
        String actor = "soar:" + execution.id();
        String objectId = execution.objectId();
        Map<String, Object> input = parameters == null ? Map.of() : parameters;
        return switch (action) {
            case "alert.update_status" -> operations.updateAlertStatus(
                    objectId, text(input, "status"), actor);
            case "alert.update_verdict" -> operations.updateAlertVerdict(
                    objectId, text(input, "verdict"), actor);
            case "alert.create_case" -> operations.createCaseFromAlert(
                    objectId, text(input, "title"), actor);
            case "alert.add_to_case" -> operations.addAlertsToCase(
                    text(input, "case_id"), List.of(objectId), actor);
            case "case.update_status" -> operations.updateCaseStatus(
                    objectId, text(input, "status"), null, actor);
            case "case.close" -> operations.updateCaseStatus(
                    objectId, "resolved", text(input, "verdict"), actor);
            case "case.add_alert" -> operations.addAlertsToCase(
                    objectId, List.of(text(input, "alert_id")), actor);
            case "case.update_owner" -> operations.updateCaseOwner(
                    objectId, text(input, "owner"), actor);
            case "case.add_evidence" -> operations.addCaseEvidence(
                    objectId, evidence(input), actor);
            default -> throw new IllegalArgumentException("未知业务动作: " + action);
        };
    }

    private Map<String, Object> evidence(Map<String, Object> parameters) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", text(parameters, "type"));
        item.put("value", text(parameters, "value"));
        return Collections.unmodifiableMap(item);
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
