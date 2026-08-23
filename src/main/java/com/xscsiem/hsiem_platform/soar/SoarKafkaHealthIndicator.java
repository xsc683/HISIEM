package com.xscsiem.hsiem_platform.soar;

import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Component("soarKafkaHealthIndicator")
@ConditionalOnProperty(name = "management.health.soarKafka.enabled", havingValue = "true", matchIfMissing = true)
public class SoarKafkaHealthIndicator implements HealthIndicator {

    private final SoarKafkaProperties properties;
    private final SoarKafkaConsumer consumer;
    private final AtomicLong lag = new AtomicLong(-1);

    public SoarKafkaHealthIndicator(SoarKafkaProperties properties, MeterRegistry registry,
                                    ObjectProvider<SoarKafkaConsumer> consumerProvider) {
        this.properties = properties;
        this.consumer = consumerProvider.getIfAvailable();
        registry.gauge("siem.soar.kafka.lag", lag);
    }

    @Override
    public Health health() {
        long started = System.nanoTime();
        try (Admin admin = Admin.create(properties.admin())) {
            Set<String> existing = admin.listTopics().names().get(5, TimeUnit.SECONDS);
            Set<String> missing = new java.util.LinkedHashSet<>(properties.topics());
            missing.removeAll(existing);
            if (!missing.isEmpty()) {
                lag.set(-1);
                return Health.down().withDetail("missingTopics", missing)
                        .withDetail("group", properties.group()).build();
            }
            if (consumer == null || !consumer.isRunning()) {
                lag.set(-1);
                return Health.down().withDetail("topics", properties.topics())
                        .withDetail("group", properties.group())
                        .withDetail("consumerConfigured", consumer != null)
                        .withDetail("consumerRunning", false).build();
            }

            Map<TopicPartition, OffsetSpec> requests = new LinkedHashMap<>();
            var descriptions = admin.describeTopics(properties.topics()).allTopicNames()
                    .get(5, TimeUnit.SECONDS);
            descriptions.forEach((topic, description) -> description.partitions().forEach(
                    partition -> requests.put(new TopicPartition(topic, partition.partition()), OffsetSpec.latest())));
            Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> ends =
                    admin.listOffsets(requests).all().get(5, TimeUnit.SECONDS);
            Map<TopicPartition, OffsetAndMetadata> committed = admin.listConsumerGroupOffsets(properties.group())
                    .partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS);
            long totalLag = 0;
            for (Map.Entry<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> entry : ends.entrySet()) {
                OffsetAndMetadata offset = committed.get(entry.getKey());
                totalLag += Math.max(0, entry.getValue().offset() - (offset == null ? 0 : offset.offset()));
            }
            lag.set(totalLag);
            return Health.up().withDetail("topics", properties.topics())
                    .withDetail("group", properties.group())
                    .withDetail("consumerRunning", true)
                    .withDetail("lag", totalLag)
                    .withDetail("latencyMs", Duration.ofNanos(System.nanoTime() - started).toMillis())
                    .build();
        } catch (Exception e) {
            lag.set(-1);
            return Health.down(e).withDetail("topics", properties.topics())
                    .withDetail("group", properties.group())
                    .withDetail("latencyMs", Duration.ofNanos(System.nanoTime() - started).toMillis())
                    .build();
        }
    }
}
