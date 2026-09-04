package com.xscsiem.hsiem_platform.soar;

import com.xscsiem.hsiem_platform.control.LifecycleOutboxStore;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Delivers the durable lifecycle outbox to Kafka. The database lease is the authority for
 * ownership; Kafka delivery is intentionally at-least-once.
 */
@Component
@ConditionalOnProperty(
        name = "app.soar.lifecycle-outbox-enabled",
        havingValue = "true",
        matchIfMissing = true)
public class LifecycleOutboxDispatcher {

    private static final Logger LOG = LoggerFactory.getLogger(LifecycleOutboxDispatcher.class);
    private static final int MAX_ERROR_LENGTH = 4000;

    private final LifecycleOutboxStore store;
    private final SoarKafkaProperties properties;
    private final boolean enabled;
    private final Duration lease;
    private final int batchSize;
    private final String owner = "lifecycle-outbox-" + UUID.randomUUID();
    private final Counter delivered;
    private final Counter deliveryFailed;
    private final Counter completionFailed;
    private volatile Producer<String, String> producer;

    @Autowired
    public LifecycleOutboxDispatcher(
            LifecycleOutboxStore store,
            SoarKafkaProperties properties,
            MeterRegistry registry,
            @Value("${app.soar.lifecycle-outbox-enabled:true}") boolean enabled,
            @Value("${app.soar.lifecycle-outbox-lease:PT2M}") Duration lease,
            @Value("${app.soar.lifecycle-outbox-batch-size:50}") int batchSize) {
        this(store, properties, registry, enabled, lease, batchSize, null);
    }

    /** Constructor with a producer seam for deterministic adapter tests. */
    public LifecycleOutboxDispatcher(
            LifecycleOutboxStore store,
            SoarKafkaProperties properties,
            MeterRegistry registry,
            boolean enabled,
            Duration lease,
            int batchSize,
            Producer<String, String> producer) {
        this.store = store;
        this.properties = properties;
        this.enabled = enabled;
        this.lease = requirePositive(lease, "lease");
        this.batchSize = Math.min(Math.max(batchSize, 1), 100);
        this.producer = producer;
        this.delivered = registry.counter("siem.soar.lifecycle.outbox.delivered");
        this.deliveryFailed = registry.counter("siem.soar.lifecycle.outbox.delivery.failed");
        this.completionFailed = registry.counter("siem.soar.lifecycle.outbox.completion.failed");
    }

    @Scheduled(
            initialDelayString = "${app.soar.lifecycle-outbox-initial-delay-ms:5000}",
            fixedDelayString = "${app.soar.lifecycle-outbox-poll-ms:5000}")
    public void dispatch() {
        if (!enabled) return;

        List<Map<String, Object>> batch;
        try {
            batch = store.claimLifecycleBatch(owner, Instant.now().plus(lease), batchSize);
        } catch (Exception e) {
            LOG.error("SOAR lifecycle outbox claim failed: {}", safe(e));
            return;
        }
        if (batch == null) return;
        for (Map<String, Object> item : batch) dispatchOne(item);
    }

    private void dispatchOne(Map<String, Object> item) {
        String messageId = String.valueOf(item.get("messageId"));
        String topic = String.valueOf(item.get("topic"));
        String key = String.valueOf(item.get("messageKey"));
        String payload = String.valueOf(item.get("payload"));
        try {
            producer().send(new ProducerRecord<>(topic, key, payload)).get();
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            deliveryFailed.increment();
            completeFailure(item, messageId, e);
            return;
        }

        // A successful Kafka ACK followed by a database failure is a deliberate
        // crash window: leave in_flight so lease expiry permits a duplicate.
        try {
            boolean completed =
                    store.completeLifecycle(messageId, owner, true, null, Instant.now());
            if (!completed) {
                completionFailed.increment();
                LOG.warn(
                        "SOAR lifecycle outbox success completion was fenced messageId={}",
                        messageId);
            } else {
                delivered.increment();
            }
        } catch (Exception e) {
            completionFailed.increment();
            LOG.error(
                    "SOAR lifecycle outbox success completion failed messageId={}: {}",
                    messageId,
                    safe(e));
        }
    }

    private void completeFailure(Map<String, Object> item, String messageId, Exception cause) {
        int attempts = attempts(item);
        Instant retryAt = Instant.now().plusSeconds(backoffSeconds(attempts));
        try {
            boolean completed =
                    store.completeLifecycle(messageId, owner, false, safe(cause), retryAt);
            if (!completed) {
                completionFailed.increment();
                LOG.warn(
                        "SOAR lifecycle outbox failure completion was fenced messageId={}",
                        messageId);
            }
        } catch (Exception completionError) {
            completionFailed.increment();
            LOG.error(
                    "SOAR lifecycle outbox failure completion failed messageId={}: {}",
                    messageId,
                    safe(completionError));
        }
        LOG.warn(
                "SOAR lifecycle outbox delivery failed messageId={}, attempts={}, retryAt={}: {}",
                messageId,
                attempts,
                retryAt,
                safe(cause));
    }

    /** Exponential retry delay based on the post-claim attempt number, capped at five minutes. */
    static long backoffSeconds(int postClaimAttempts) {
        int exponent = Math.max(0, Math.min(30, postClaimAttempts - 1));
        return Math.min(300L, 1L << exponent);
    }

    private static int attempts(Map<String, Object> item) {
        Object value = item.get("attempts");
        return value instanceof Number number ? Math.max(1, number.intValue()) : 1;
    }

    private Producer<String, String> producer() {
        Producer<String, String> current = producer;
        if (current != null) return current;
        synchronized (this) {
            if (producer == null) producer = new KafkaProducer<>(properties.producer());
            return producer;
        }
    }

    @PreDestroy
    public void close() {
        Producer<String, String> current;
        synchronized (this) {
            current = producer;
            producer = null;
        }
        if (current != null) current.close(Duration.ofSeconds(3));
    }

    private static Duration requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    static String safe(Exception e) {
        Throwable cause =
                e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
        String value =
                cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
        value = value.replace("\u0000", "");
        return value.substring(0, Math.min(MAX_ERROR_LENGTH, value.length()));
    }
}
