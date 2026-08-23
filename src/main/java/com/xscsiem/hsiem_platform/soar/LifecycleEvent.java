package com.xscsiem.hsiem_platform.soar;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

public record LifecycleEvent(
        @JsonProperty("message_id") String messageId,
        @JsonProperty("event_type") String eventType,
        @JsonProperty("occurred_at") Instant occurredAt,
        String producer,
        @JsonProperty("tenant_id") String tenantId,
        Map<String, Object> alert,
        @JsonProperty("case") Map<String, Object> caseObject) {

    private static final Set<String> EVENT_TYPES = Set.of(
            "alert.created", "alert.updated", "case.created", "case.updated");

    public void validate() {
        if (messageId == null || messageId.isBlank()) throw new IllegalArgumentException("message_id 不能为空");
        if (!EVENT_TYPES.contains(eventType)) throw new IllegalArgumentException("不支持的生命周期事件: " + eventType);
        if (occurredAt == null) throw new IllegalArgumentException("occurred_at 不能为空");
        Map<String, Object> object = object();
        if (object == null || object.isEmpty()) throw new IllegalArgumentException(objectType() + " 对象不能为空");
        Object id = object.get("id");
        if (id == null || String.valueOf(id).isBlank()) throw new IllegalArgumentException(objectType() + ".id 不能为空");
    }

    public String objectType() {
        return eventType != null && eventType.startsWith("case.") ? "case" : "alert";
    }

    public Map<String, Object> object() {
        return "case".equals(objectType()) ? caseObject : alert;
    }

    public String objectId() {
        return String.valueOf(object().get("id"));
    }

    public String effectiveTenantId() {
        return tenantId == null || tenantId.isBlank() ? "default" : tenantId;
    }

    public Map<String, Object> payload() {
        return "case".equals(objectType()) ? Map.of("case", caseObject) : Map.of("alert", alert);
    }
}
