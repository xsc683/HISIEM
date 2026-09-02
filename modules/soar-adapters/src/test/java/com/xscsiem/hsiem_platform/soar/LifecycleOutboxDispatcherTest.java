package com.xscsiem.hsiem_platform.soar;

import com.xscsiem.hsiem_platform.control.ControlPlaneStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LifecycleOutboxDispatcherTest {

    @Test
    void producerFailureCompletesFailedUsingPostClaimAttempts() {
        ControlPlaneStore store = mock(ControlPlaneStore.class);
        SoarKafkaProperties properties = mock(SoarKafkaProperties.class);
        Producer<String, String> producer = mock(Producer.class);
        Future<RecordMetadata> failed = CompletableFuture.failedFuture(new IllegalStateException("broker down"));
        when(producer.send(any())).thenReturn(failed);
        Map<String, Object> item = item(3);
        when(store.claimLifecycleBatch(any(), any(), eq(10))).thenReturn(java.util.List.of(item));
        LifecycleOutboxDispatcher dispatcher = new LifecycleOutboxDispatcher(store, properties,
                new SimpleMeterRegistry(), true, Duration.ofMinutes(2), 10, producer);

        dispatcher.dispatch();

        var retry = org.mockito.ArgumentCaptor.forClass(Instant.class);
        verify(store).completeLifecycle(eq("message-1"), any(), eq(false), eq("broker down"), retry.capture());
        long delay = retry.getValue().getEpochSecond() - Instant.now().getEpochSecond();
        assertEquals(4L, delay, 1L);
    }

    @Test
    void ackCompletionFailureDoesNotPublishFalseOrHideCrashWindow() throws Exception {
        ControlPlaneStore store = mock(ControlPlaneStore.class);
        SoarKafkaProperties properties = mock(SoarKafkaProperties.class);
        Producer<String, String> producer = mock(Producer.class);
        when(producer.send(any())).thenReturn(CompletableFuture.completedFuture(null));
        when(store.claimLifecycleBatch(any(), any(), eq(10))).thenReturn(java.util.List.of(item(1)));
        doThrow(new IllegalStateException("db unavailable"))
                .when(store).completeLifecycle(eq("message-1"), any(), eq(true), eq(null), any());
        LifecycleOutboxDispatcher dispatcher = new LifecycleOutboxDispatcher(store, properties,
                new SimpleMeterRegistry(), true, Duration.ofMinutes(2), 10, producer);

        dispatcher.dispatch();

        verify(store).completeLifecycle(eq("message-1"), any(), eq(true), eq(null), any());
        org.mockito.Mockito.verify(store, org.mockito.Mockito.never())
                .completeLifecycle(eq("message-1"), any(), eq(false), any(), any());
    }

    @Test
    void restartedDispatcherReclaimsExpiredAckedRowAndMaySendDuplicate() {
        ControlPlaneStore store = mock(ControlPlaneStore.class);
        SoarKafkaProperties properties = mock(SoarKafkaProperties.class);
        Producer<String, String> firstProducer = mock(Producer.class);
        Producer<String, String> restartedProducer = mock(Producer.class);
        when(firstProducer.send(any())).thenReturn(CompletableFuture.completedFuture(null));
        when(restartedProducer.send(any())).thenReturn(CompletableFuture.completedFuture(null));
        when(store.claimLifecycleBatch(any(), any(), eq(10)))
                .thenReturn(java.util.List.of(item(1)), java.util.List.of(item(2)));
        when(store.completeLifecycle(eq("message-1"), any(), eq(true), eq(null), any()))
                .thenThrow(new IllegalStateException("crash after ACK"))
                .thenReturn(true);

        LifecycleOutboxDispatcher first = new LifecycleOutboxDispatcher(store, properties,
                new SimpleMeterRegistry(), true, Duration.ofMinutes(2), 10, firstProducer);
        LifecycleOutboxDispatcher restarted = new LifecycleOutboxDispatcher(store, properties,
                new SimpleMeterRegistry(), true, Duration.ofMinutes(2), 10, restartedProducer);

        first.dispatch();
        restarted.dispatch();

        verify(firstProducer).send(any());
        verify(restartedProducer).send(any());
        verify(store, org.mockito.Mockito.times(2))
                .completeLifecycle(eq("message-1"), any(), eq(true), eq(null), any());
        org.mockito.Mockito.verify(store, org.mockito.Mockito.never())
                .completeLifecycle(eq("message-1"), any(), eq(false), any(), any());
    }

    @Test
    void backoffIsExponentialAndCappedAtFiveMinutes() {
        assertEquals(1, LifecycleOutboxDispatcher.backoffSeconds(1));
        assertEquals(4, LifecycleOutboxDispatcher.backoffSeconds(3));
        assertEquals(300, LifecycleOutboxDispatcher.backoffSeconds(10));
        assertEquals(300, LifecycleOutboxDispatcher.backoffSeconds(Integer.MAX_VALUE));
    }

    @Test
    void closesInjectedProducerOnShutdown() {
        Producer<String, String> producer = mock(Producer.class);
        LifecycleOutboxDispatcher dispatcher = new LifecycleOutboxDispatcher(mock(ControlPlaneStore.class),
                mock(SoarKafkaProperties.class), new SimpleMeterRegistry(), true,
                Duration.ofMinutes(2), 10, producer);

        dispatcher.close();

        verify(producer).close(Duration.ofSeconds(3));
    }

    private static Map<String, Object> item(int attempts) {
        return Map.of("messageId", "message-1", "topic", "alerts", "messageKey", "alert-1",
                "payload", "{}", "attempts", attempts);
    }
}
