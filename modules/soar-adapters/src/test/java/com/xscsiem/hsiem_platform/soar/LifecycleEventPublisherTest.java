package com.xscsiem.hsiem_platform.soar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xscsiem.hsiem_platform.control.LifecycleOutboxStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LifecycleEventPublisherTest {

    @Test
    void validatesSerializesAndEnqueuesWithoutKafka() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        SoarKafkaProperties properties = mock(SoarKafkaProperties.class);
        when(properties.topicFor("alert")).thenReturn("alerts");
        LifecycleOutboxStore store = mock(LifecycleOutboxStore.class);
        LifecycleEvent event = event("alert.created", "tenant-a", "alert-1");

        new LifecycleEventPublisher(
                        mapper,
                        properties,
                        new LifecycleEventFactory(),
                        new SimpleMeterRegistry(),
                        store,
                        true)
                .publish(event);

        verify(store)
                .enqueueLifecycle(
                        eq(event.messageId()),
                        eq("alert.created"),
                        eq("tenant-a"),
                        eq("alert"),
                        eq("alert-1"),
                        eq(event.occurredAt()),
                        eq("alerts"),
                        eq("alert-1"),
                        eq(mapper.writeValueAsString(event)));
    }

    @Test
    void serializationFailureIsCountedAndRethrown() throws Exception {
        ObjectMapper mapper = mock(ObjectMapper.class);
        when(mapper.writeValueAsString(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new JsonProcessingException("broken") {});
        LifecycleOutboxStore store = mock(LifecycleOutboxStore.class);
        SoarKafkaProperties properties = mock(SoarKafkaProperties.class);
        when(properties.topicFor("alert")).thenReturn("alerts");
        LifecycleEventPublisher publisher =
                new LifecycleEventPublisher(
                        mapper,
                        properties,
                        new LifecycleEventFactory(),
                        new SimpleMeterRegistry(),
                        store,
                        true);

        assertThrows(
                IllegalStateException.class,
                () -> publisher.publish(event("alert.created", "t", "a")));
    }

    @Test
    void enqueueFailureIsVisibleToCaller() {
        LifecycleOutboxStore store = mock(LifecycleOutboxStore.class);
        doThrow(new IllegalStateException("database down"))
                .when(store)
                .enqueueLifecycle(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());
        SoarKafkaProperties properties = mock(SoarKafkaProperties.class);
        when(properties.topicFor("alert")).thenReturn("alerts");

        LifecycleEventPublisher publisher =
                new LifecycleEventPublisher(
                        new ObjectMapper().findAndRegisterModules(),
                        properties,
                        new LifecycleEventFactory(),
                        new SimpleMeterRegistry(),
                        store,
                        true);

        IllegalStateException failure =
                assertThrows(
                        IllegalStateException.class,
                        () -> publisher.publish(event("alert.created", "t", "a")));
        assertEquals("database down", failure.getMessage());
        verify(store)
                .enqueueLifecycle(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());
    }

    private static LifecycleEvent event(String type, String tenant, String id) {
        return new LifecycleEvent(
                "message-" + id,
                type,
                Instant.parse("2026-01-01T00:00:00Z"),
                "test",
                tenant,
                Map.of("id", id),
                null);
    }
}
