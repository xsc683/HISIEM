package com.siem;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Converts a stored alert into the deliberately small SOAR lifecycle contract. */
public final class AlertLifecycleEventMapper {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AlertLifecycleEventMapper() {
    }

    public static String map(String alertJson) {
        try {
            Map<String, Object> source = MAPPER.readValue(alertJson, Map.class);
            // The lifecycle id is the Elasticsearch document id used by AlertService,
            // not the detector's display-only alert.id field.
            String alertId = DetectionJob.alertId(alertJson);
            Map<String, Object> alert = new LinkedHashMap<>();
            put(alert, "id", alertId);
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

            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("message_id", UUID.nameUUIDFromBytes(
                    ("alert.created:" + alertId).getBytes(StandardCharsets.UTF_8)).toString());
            envelope.put("event_type", "alert.created");
            envelope.put("occurred_at", Instant.now().toString());
            envelope.put("producer", "hsiem-flink");
            envelope.put("tenant_id", "default");
            envelope.put("alert", alert);
            return MAPPER.writeValueAsString(envelope);
        } catch (Exception e) {
            throw new IllegalArgumentException("告警无法转换为 SOAR 生命周期事件", e);
        }
    }

    private static Object first(Map<String, Object> source, String first, String second) {
        Object value = source.get(first);
        return value == null ? source.get(second) : value;
    }

    private static void put(Map<String, Object> target, String key, Object value) {
        if (value != null) target.put(key, value);
    }
}
