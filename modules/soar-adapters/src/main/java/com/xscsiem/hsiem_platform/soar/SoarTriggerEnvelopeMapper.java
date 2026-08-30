package com.xscsiem.hsiem_platform.soar;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Converts Kafka transport metadata into the core's transport-neutral trigger model. */
@Component
public class SoarTriggerEnvelopeMapper {

    public SoarTriggerEnvelope map(LifecycleEvent event, ConsumerRecord<?, ?> record) {
        if (record == null) throw new IllegalArgumentException("Kafka record 不能为空");
        Map<String, List<String>> headers = new LinkedHashMap<>();
        for (Header header : record.headers()) {
            String encoded = header.value() == null
                    ? ""
                    : Base64.getEncoder().encodeToString(header.value());
            headers.computeIfAbsent(header.key(), ignored -> new ArrayList<>()).add(encoded);
        }
        Instant timestamp = record.timestamp() < 0
                ? null
                : Instant.ofEpochMilli(record.timestamp());
        SoarTriggerEnvelope.KafkaSource source = new SoarTriggerEnvelope.KafkaSource(
                record.topic(), record.partition(), record.offset(), timestamp,
                record.key() == null ? null : String.valueOf(record.key()), headers);
        return SoarTriggerEnvelope.kafka(event, source);
    }

    public SoarTriggerEnvelope from(LifecycleEvent event, ConsumerRecord<?, ?> record) {
        return map(event, record);
    }
}
