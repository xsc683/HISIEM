package com.xscsiem.hsiem_platform.soar;

import com.fasterxml.jackson.annotation.JsonFormat;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Serializable trigger facts. Kafka transport coordinates are audit data, not the deduplication key. */
public record SoarTriggerEnvelope(
        String messageId,
        String eventType,
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        Instant occurredAt,
        String producer,
        String tenantId,
        String objectType,
        String objectId,
        Map<String, Object> payload,
        KafkaSource kafka) {

    public SoarTriggerEnvelope {
        payload = payload == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(payload));
    }

    public static SoarTriggerEnvelope direct(LifecycleEvent event) {
        event.validate();
        return from(event, null);
    }

    public static SoarTriggerEnvelope kafka(LifecycleEvent event, ConsumerRecord<?, ?> record) {
        event.validate();
        if (record == null) throw new IllegalArgumentException("Kafka record 不能为空");
        Map<String, List<String>> headers = new LinkedHashMap<>();
        for (Header header : record.headers()) {
            String encoded = header.value() == null ? "" : Base64.getEncoder().encodeToString(header.value());
            headers.computeIfAbsent(header.key(), ignored -> new ArrayList<>()).add(encoded);
        }
        Instant timestamp = record.timestamp() < 0 ? null : Instant.ofEpochMilli(record.timestamp());
        KafkaSource source = new KafkaSource(record.topic(), record.partition(), record.offset(), timestamp,
                record.key() == null ? null : String.valueOf(record.key()), headers);
        return from(event, source);
    }

    private static SoarTriggerEnvelope from(LifecycleEvent event, KafkaSource source) {
        return new SoarTriggerEnvelope(event.messageId(), event.eventType(), event.occurredAt(),
                event.producer(), event.effectiveTenantId(), event.objectType(), event.objectId(),
                event.payload(), source);
    }

    public Map<String, Object> templateValue() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("messageId", messageId);
        value.put("eventType", eventType);
        value.put("occurredAt", occurredAt == null ? null : occurredAt.toString());
        value.put("producer", producer);
        value.put("tenantId", tenantId);
        value.put("objectType", objectType);
        value.put("objectId", objectId);
        if (kafka != null) value.put("kafka", kafka.templateValue());
        return value;
    }

    public record KafkaSource(String topic, int partition, long offset,
                              @JsonFormat(shape = JsonFormat.Shape.STRING) Instant timestamp,
                              String key, Map<String, List<String>> headers) {
        public KafkaSource {
            Map<String, List<String>> copy = new LinkedHashMap<>();
            if (headers != null) headers.forEach((name, values) -> copy.put(name, List.copyOf(values)));
            headers = Map.copyOf(copy);
        }

        public Map<String, Object> templateValue() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("topic", topic);
            value.put("partition", partition);
            value.put("offset", offset);
            value.put("timestamp", timestamp == null ? null : timestamp.toString());
            value.put("key", key);
            value.put("headers", headers);
            return value;
        }

        public String headerUtf8(String name) {
            List<String> values = headers.get(name);
            if (values == null || values.isEmpty()) return null;
            return new String(Base64.getDecoder().decode(values.getLast()), StandardCharsets.UTF_8);
        }
    }
}
