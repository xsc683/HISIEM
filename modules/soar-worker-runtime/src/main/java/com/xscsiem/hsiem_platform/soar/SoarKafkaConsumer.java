package com.xscsiem.hsiem_platform.soar;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@ConditionalOnProperty(name = "app.soar.kafka-consumer-enabled", havingValue = "true", matchIfMissing = true)
public class SoarKafkaConsumer implements SmartLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(SoarKafkaConsumer.class);

    private final SoarKafkaProperties properties;
    private final SoarLifecycleRuntime runtime;
    private final SoarTriggerEnvelopeMapper envelopeMapper;
    private final ObjectMapper objectMapper;
    private final Counter invalid;
    private final Counter failures;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile KafkaConsumer<String, String> consumer;
    private volatile Thread thread;

    public SoarKafkaConsumer(SoarKafkaProperties properties, SoarLifecycleRuntime runtime,
                             SoarTriggerEnvelopeMapper envelopeMapper,
                             ObjectMapper objectMapper, MeterRegistry registry) {
        this.properties = properties;
        this.runtime = runtime;
        this.envelopeMapper = envelopeMapper;
        this.objectMapper = objectMapper;
        this.invalid = registry.counter("siem.soar.kafka.invalid");
        this.failures = registry.counter("siem.soar.kafka.consume.failed");
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) return;
        thread = Thread.ofPlatform().name("siem-soar-kafka").daemon(true).start(this::consume);
    }

    private void consume() {
        try {
            while (running.get()) {
                try (KafkaConsumer<String, String> active = new KafkaConsumer<>(properties.consumer())) {
                    consumer = active;
                    active.subscribe(properties.topics());
                    while (running.get()) {
                        ConsumerRecords<String, String> records = active.poll(Duration.ofSeconds(1));
                        boolean retry = false;
                        for (ConsumerRecord<String, String> record : records) {
                            try {
                                LifecycleEvent event = objectMapper.readValue(record.value(), LifecycleEvent.class);
                                runtime.accept(envelopeMapper.map(event, record));
                            } catch (IllegalArgumentException | com.fasterxml.jackson.core.JsonProcessingException e) {
                                invalid.increment();
                                LOG.error("Discard invalid SOAR lifecycle message topic={} partition={} offset={}: {}",
                                        record.topic(), record.partition(), record.offset(), e.getMessage());
                            } catch (RuntimeException e) {
                                failures.increment();
                                LOG.error("SOAR lifecycle consumption failed topic={} partition={} offset={}",
                                        record.topic(), record.partition(), record.offset(), e);
                                rewindBatch(active, records);
                                retry = true;
                                break;
                            }
                        }
                        if (!retry && !records.isEmpty()) active.commitSync();
                    }
                } catch (WakeupException ignored) {
                    if (running.get()) LOG.warn("SOAR Kafka consumer woke unexpectedly; reconnecting");
                } catch (RuntimeException e) {
                    failures.increment();
                    LOG.error("SOAR Kafka consumer disconnected; retrying", e);
                } finally {
                    consumer = null;
                }
                if (running.get()) pauseBeforeReconnect();
            }
        } finally {
            consumer = null;
            running.set(false);
        }
    }

    private void pauseBeforeReconnect() {
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            running.set(false);
        }
    }

    /**
     * poll() advances the in-memory position of every fetched partition. If one record fails,
     * rewinding only that partition could let a later commit skip unprocessed records from the
     * other partitions in the same batch. Rewind the complete batch; execution uniqueness makes
     * already-processed lifecycle messages safe to replay.
     */
    void rewindBatch(KafkaConsumer<String, String> active,
                     ConsumerRecords<String, String> records) {
        for (TopicPartition partition : records.partitions()) {
            var partitionRecords = records.records(partition);
            if (!partitionRecords.isEmpty()) {
                active.seek(partition, partitionRecords.getFirst().offset());
            }
        }
    }

    @Override
    public void stop() {
        running.set(false);
        KafkaConsumer<String, String> active = consumer;
        if (active != null) active.wakeup();
        Thread current = thread;
        if (current != null) {
            try {
                current.join(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 100;
    }
}
