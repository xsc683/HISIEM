package com.xscsiem.hsiem_platform.soar;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SoarKafkaConsumerTest {

    @Test
    void triggerEnvelopeKeepsKafkaCoordinatesSeparateFromBusinessMessageId() throws Exception {
        LifecycleEvent event = new LifecycleEvent("business-message", "alert.created", Instant.now(),
                "flink", "default", Map.of("id", "alert-1"), null);
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                "siem-alert-lifecycle", 2, 17L, "alert-1", "payload");

        SoarTriggerEnvelope envelope = new SoarTriggerEnvelopeMapper().map(event, record);

        assertEquals("business-message", envelope.messageId());
        assertEquals("siem-alert-lifecycle", envelope.kafka().topic());
        assertEquals(2, envelope.kafka().partition());
        assertEquals(17L, envelope.kafka().offset());
        assertEquals("alert-1", envelope.kafka().key());
        assertNull(envelope.kafka().timestamp());
        assertTrue(new ObjectMapper().findAndRegisterModules().writeValueAsString(envelope)
                .contains("\"occurredAt\":\""));
    }

    @Test
    void rewindBatchResetsEveryFetchedPartitionToItsFirstOffset() {
        SoarKafkaConsumer consumer = new SoarKafkaConsumer(mock(SoarKafkaProperties.class),
                mock(SoarLifecycleRuntime.class), new SoarTriggerEnvelopeMapper(),
                new ObjectMapper(), new SimpleMeterRegistry());
        @SuppressWarnings("unchecked")
        KafkaConsumer<String, String> kafka = mock(KafkaConsumer.class);
        @SuppressWarnings("unchecked")
        ConsumerRecords<String, String> records = mock(ConsumerRecords.class);
        TopicPartition alert = new TopicPartition("siem-alert-lifecycle", 0);
        TopicPartition caseTopic = new TopicPartition("siem-case-lifecycle", 1);
        when(records.partitions()).thenReturn(Set.of(alert, caseTopic));
        when(records.records(alert)).thenReturn(List.of(
                new ConsumerRecord<>(alert.topic(), alert.partition(), 41L, "a", "first"),
                new ConsumerRecord<>(alert.topic(), alert.partition(), 42L, "b", "second")));
        when(records.records(caseTopic)).thenReturn(List.of(
                new ConsumerRecord<>(caseTopic.topic(), caseTopic.partition(), 9L, "c", "case")));

        consumer.rewindBatch(kafka, records);

        verify(kafka).seek(alert, 41L);
        verify(kafka).seek(caseTopic, 9L);
    }
}
