package com.xscsiem.hsiem_platform.soar;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SoarTriggerEnvelopeMapperTest {

    @Test
    void mapsKafkaCoordinatesAndPreservesBase64EncodedHeaders() {
        LifecycleEvent event = new LifecycleEvent("business-message", "alert.created", Instant.now(),
                "flink", "tenant-a", Map.of("id", "alert-1"), null);
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                "siem-alert-lifecycle", 2, 17L, "alert-1", "payload");
        record.headers()
                .add("trace-id", "trace-1".getBytes(StandardCharsets.UTF_8))
                .add("trace-id", new byte[]{0, 1, 2})
                .add("empty", null);

        SoarTriggerEnvelope envelope = new SoarTriggerEnvelopeMapper().map(event, record);

        assertEquals("business-message", envelope.messageId());
        assertEquals("siem-alert-lifecycle", envelope.kafka().topic());
        assertEquals(2, envelope.kafka().partition());
        assertEquals(17L, envelope.kafka().offset());
        assertEquals("alert-1", envelope.kafka().key());
        assertNull(envelope.kafka().timestamp());
        assertEquals(List.of("dHJhY2UtMQ==", "AAEC"),
                envelope.kafka().headers().get("trace-id"));
        assertEquals(new String(new byte[]{0, 1, 2}, StandardCharsets.UTF_8),
                envelope.kafka().headerUtf8("trace-id"));
        assertEquals("", envelope.kafka().headers().get("empty").getFirst());
    }
}
