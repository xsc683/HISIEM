package com.xscsiem.hsiem_platform.soar;

import com.xscsiem.hsiem_platform.alert.AlertService;
import com.xscsiem.hsiem_platform.investigation.CaseService;
import com.xscsiem.hsiem_platform.notify.NotificationService;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 动作 Runner：conductor 不直接依赖告警、案件或连接器实现。 */
@Component
public class SoarActionExecutor {

    private final AlertService alerts;
    private final CaseService cases;
    private final NotificationService notifications;
    private final SoarConnectorClient connectors;

    public SoarActionExecutor(AlertService alerts, CaseService cases,
                              NotificationService notifications, SoarConnectorClient connectors) {
        this.alerts = alerts;
        this.cases = cases;
        this.notifications = notifications;
        this.connectors = connectors;
    }

    public Map<String, Object> execute(String action, Map<String, Object> input,
                                       SoarExecution execution, Map<String, Object> context) {
        String actor = execution.actor();
        return switch (action) {
            case "alert.set_status" -> {
                String alertId = reference(input, "alertId", context, "alertId");
                String status = requiredString(input, "status");
                Map<String, Object> result = alerts.update(alertId, status, null, actor);
                yield summary("alertId", alertId, "status", result.get("alert.status"));
            }
            case "alert.set_verdict" -> {
                String alertId = reference(input, "alertId", context, "alertId");
                String verdict = requiredString(input, "verdict");
                Map<String, Object> result = alerts.update(alertId, null, verdict, actor);
                yield summary("alertId", alertId, "verdict", result.get("alert.analyst_verdict"));
            }
            case "case.set_status" -> {
                String caseId = reference(input, "caseId", context, "caseId");
                String status = requiredString(input, "status");
                String verdict = nullableString(input.get("verdict"));
                Map<String, Object> result = cases.updateStatus(caseId, status, verdict, actor);
                yield summary("caseId", caseId, "status", result.get("case.status"));
            }
            case "case.add_alert" -> {
                String caseId = reference(input, "caseId", context, "caseId");
                String alertId = reference(input, "alertId", context, "alertId");
                Map<String, Object> result = cases.addAlerts(caseId, List.of(alertId), actor);
                yield summary("caseId", caseId, "alertCount", list(result.get("alert_ids")).size());
            }
            case "case.add_evidence" -> addEvidence(input, execution, context);
            case "notification.create" -> {
                String type = string(input.getOrDefault("type", "soar"));
                String target = string(input.getOrDefault("target",
                        execution.resourceType() + ":" + execution.resourceId()));
                String message = requiredString(input, "message");
                notifications.notify(type, target, message);
                yield summary("type", type, "target", target);
            }
            case "context.set" -> {
                Map<String, Object> values = map(input.get("values"));
                if (values.isEmpty()) throw new IllegalArgumentException("context.set 缺少 with.values");
                yield Map.of("values", values);
            }
            case "connector.call" -> connectors.call(execution.tenantId(),
                    requiredString(input, "connector"), requiredString(input, "operation"),
                    map(input.get("arguments")), execution.id());
            default -> throw new IllegalArgumentException("未允许的 SOAR action: " + action);
        };
    }

    private Map<String, Object> addEvidence(Map<String, Object> input, SoarExecution execution,
                                            Map<String, Object> context) {
        String caseId = reference(input, "caseId", context, "caseId");
        Map<String, Object> current = cases.detail(caseId);
        List<Map<String, Object>> evidence = new ArrayList<>(mapList(current.get("evidence")));
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", string(input.getOrDefault("type", "soar")));
        item.put("title", requiredString(input, "title"));
        putIfPresent(item, "uri", input.get("uri"));
        putIfPresent(item, "note", input.get("note"));
        item.put("createdBy", execution.actor());
        item.put("createdAt", Instant.now().toString());
        evidence.add(item);
        cases.updateMetadata(caseId, nullableString(current.get("case.owner")), evidence, execution.actor());
        return summary("caseId", caseId, "evidenceCount", evidence.size());
    }

    private static String reference(Map<String, Object> input, String inputKey,
                                    Map<String, Object> context, String contextKey) {
        Object value = input.get(inputKey);
        if (value == null) value = context.get(contextKey);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new IllegalArgumentException("SOAR action 缺少 " + inputKey);
        }
        return String.valueOf(value);
    }

    private static String requiredString(Map<String, Object> input, String key) {
        String value = nullableString(input.get(key));
        if (value == null) throw new IllegalArgumentException("SOAR action 缺少 " + key);
        return value;
    }

    private static Map<String, Object> summary(String firstKey, Object firstValue,
                                               String secondKey, Object secondValue) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(firstKey, firstValue);
        out.put(secondKey, secondValue);
        return out;
    }

    private static void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null && !String.valueOf(value).isBlank()) target.put(key, value);
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String nullableString(Object value) {
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value);
    }

    private static List<?> list(Object value) {
        return value instanceof List<?> result ? result : List.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> result ? new LinkedHashMap<>((Map<String, Object>) result) : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> mapList(Object value) {
        if (!(value instanceof List<?> values)) return List.of();
        return values.stream().filter(Map.class::isInstance)
                .map(item -> (Map<String, Object>) item).toList();
    }
}
