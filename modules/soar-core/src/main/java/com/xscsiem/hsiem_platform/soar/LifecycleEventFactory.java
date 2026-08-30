package com.xscsiem.hsiem_platform.soar;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class LifecycleEventFactory {

    public LifecycleEvent alert(String eventType, Map<String, Object> source, String tenantId) {
        Map<String, Object> alert = new LinkedHashMap<>();
        // SOAR business actions address alerts by Elasticsearch document _id.
        put(alert, "id", first(source, "_id", "alert.id"));
        put(alert, "rule_id", source.get("alert.rule_id"));
        put(alert, "rule_name", source.get("alert.rule_name"));
        put(alert, "severity", source.get("alert.severity"));
        put(alert, "status", source.get("alert.status"));
        put(alert, "verdict", first(source, "alert.analyst_verdict", "alert.verdict"));
        put(alert, "risk_score", source.get("alert.risk_score"));
        put(alert, "source_ip", source.get("source.ip"));
        put(alert, "user_name", source.get("user.name"));
        put(alert, "host_name", source.get("host.name"));
        put(alert, "timestamp", first(source, "@timestamp", "alert.created_at"));
        return new LifecycleEvent(UUID.randomUUID().toString(), eventType, Instant.now(),
                "hsiem-control", tenantId, alert, null);
    }

    public LifecycleEvent caseEvent(String eventType, Map<String, Object> source, String tenantId) {
        Map<String, Object> caseObject = new LinkedHashMap<>();
        put(caseObject, "id", first(source, "case.id", "_id"));
        put(caseObject, "title", source.get("case.title"));
        put(caseObject, "status", source.get("case.status"));
        put(caseObject, "verdict", source.get("case.verdict"));
        put(caseObject, "owner", source.get("case.owner"));
        Object alertIds = source.get("alert_ids");
        put(caseObject, "alert_ids", alertIds == null ? List.of() : alertIds);
        return new LifecycleEvent(UUID.randomUUID().toString(), eventType, Instant.now(),
                "hsiem-control", tenantId, null, caseObject);
    }

    private Object first(Map<String, Object> source, String first, String second) {
        Object value = source.get(first);
        return value == null ? source.get(second) : value;
    }

    private void put(Map<String, Object> target, String key, Object value) {
        if (value != null) target.put(key, value);
    }
}
