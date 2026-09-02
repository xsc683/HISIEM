package com.xscsiem.hsiem_platform.soar;

import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class LifecycleEventFactory {

    public LifecycleEvent alert(String eventType, Map<String, Object> source, String tenantId) {
        Instant occurredAt = occurredAt(eventType, source);
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
        return new LifecycleEvent(messageId(eventType, tenantId, "alert", alert.get("id"), occurredAt),
                eventType, occurredAt, "hsiem-control", tenantId, alert, null);
    }

    public LifecycleEvent caseEvent(String eventType, Map<String, Object> source, String tenantId) {
        Instant occurredAt = occurredAt(eventType, source);
        Map<String, Object> caseObject = new LinkedHashMap<>();
        put(caseObject, "id", first(source, "case.id", "_id"));
        put(caseObject, "title", source.get("case.title"));
        put(caseObject, "status", source.get("case.status"));
        put(caseObject, "verdict", source.get("case.verdict"));
        put(caseObject, "owner", source.get("case.owner"));
        Object alertIds = source.get("alert_ids");
        put(caseObject, "alert_ids", alertIds == null ? List.of() : alertIds);
        return new LifecycleEvent(messageId(eventType, tenantId, "case", caseObject.get("id"), occurredAt),
                eventType, occurredAt, "hsiem-control", tenantId, null, caseObject);
    }

    private Instant occurredAt(String eventType, Map<String, Object> source) {
        if (source == null) throw new IllegalArgumentException("生命周期事件来源不能为空");
        if (eventType == null) throw new IllegalArgumentException("生命周期事件类型不能为空");

        String primary = switch (eventType) {
            case "alert.created" -> "@timestamp";
            case "alert.updated" -> "alert.status_updated_at";
            case "case.created" -> "case.created_at";
            case "case.updated" -> "case.updated_at";
            default -> throw new IllegalArgumentException("不支持的生命周期事件: " + eventType);
        };
        Object value = source.get(primary);
        if (value == null && "alert.created".equals(eventType)) {
            value = source.get("alert.created_at");
        }
        if (value == null) {
            throw new IllegalArgumentException("生命周期事件缺少 occurred_at 业务时间: " + primary);
        }

        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("生命周期事件 occurred_at 业务时间不能为空: " + primary);
        }
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("生命周期事件 occurred_at 业务时间格式无效: " + text, e);
        }
    }

    private String messageId(String eventType, String tenantId, String objectType,
                             Object objectId, Instant occurredAt) {
        return UUID.nameUUIDFromBytes(canonicalIdentity(
                eventType, tenantId, objectType,
                objectId == null ? null : String.valueOf(objectId), occurredAt.toString())).toString();
    }

    /** Encodes each identity component with its byte length to avoid delimiter collisions. */
    private byte[] canonicalIdentity(String... components) {
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        for (String component : components) {
            byte[] bytes = component == null ? null : component.getBytes(StandardCharsets.UTF_8);
            int length = bytes == null ? -1 : bytes.length;
            encoded.write(length >>> 24);
            encoded.write(length >>> 16);
            encoded.write(length >>> 8);
            encoded.write(length);
            if (bytes != null) encoded.writeBytes(bytes);
        }
        return encoded.toByteArray();
    }

    private Object first(Map<String, Object> source, String first, String second) {
        Object value = source.get(first);
        return value == null ? source.get(second) : value;
    }

    private void put(Map<String, Object> target, String key, Object value) {
        if (value != null) target.put(key, value);
    }
}
