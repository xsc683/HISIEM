package com.xscsiem.hsiem_platform.soar;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class LifecycleEventPublisher {

    private static final Logger LOG = LoggerFactory.getLogger(LifecycleEventPublisher.class);

    private final ObjectMapper objectMapper;
    private final SoarKafkaProperties properties;
    private final LifecycleEventFactory factory;
    private final boolean enabled;
    private final Counter published;
    private final Counter failed;
    private volatile KafkaProducer<String, String> producer;

    public LifecycleEventPublisher(ObjectMapper objectMapper, SoarKafkaProperties properties,
                                   LifecycleEventFactory factory, MeterRegistry registry,
                                   @Value("${app.soar.runtime-enabled:true}") boolean enabled) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.factory = factory;
        this.enabled = enabled;
        this.published = registry.counter("siem.soar.lifecycle.published");
        this.failed = registry.counter("siem.soar.lifecycle.publish.failed");
    }

    public void publishAlert(String eventType, Map<String, Object> source, String tenantId) {
        publish(factory.alert(eventType, source, tenantId));
    }

    public void publishCase(String eventType, Map<String, Object> source, String tenantId) {
        publish(factory.caseEvent(eventType, source, tenantId));
    }

    public void publish(LifecycleEvent event) {
        if (!enabled) return;
        event.validate();
        try {
            String body = objectMapper.writeValueAsString(event);
            producer().send(new ProducerRecord<>(properties.topicFor(event.objectType()),
                    event.objectId(), body), (metadata, error) -> {
                if (error == null) {
                    published.increment();
                } else {
                    failed.increment();
                    LOG.error("SOAR lifecycle publish failed type={} object={} message={}",
                            event.eventType(), event.objectId(), event.messageId(), error);
                }
            });
        } catch (RuntimeException | JsonProcessingException e) {
            failed.increment();
            LOG.error("SOAR lifecycle publish could not be scheduled type={} object={} message={}",
                    event.eventType(), event.objectId(), event.messageId(), e);
        }
    }

    private KafkaProducer<String, String> producer() {
        KafkaProducer<String, String> current = producer;
        if (current != null) return current;
        synchronized (this) {
            if (producer == null) producer = new KafkaProducer<>(properties.producer());
            return producer;
        }
    }

    @PreDestroy
    public void close() {
        KafkaProducer<String, String> current = producer;
        if (current != null) current.close(java.time.Duration.ofSeconds(3));
    }
}
