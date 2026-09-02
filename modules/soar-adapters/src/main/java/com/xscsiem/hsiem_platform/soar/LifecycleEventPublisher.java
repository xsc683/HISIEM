package com.xscsiem.hsiem_platform.soar;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xscsiem.hsiem_platform.control.ControlPlaneStore;
import com.xscsiem.hsiem_platform.lifecycle.LifecycleEventPort;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class LifecycleEventPublisher implements LifecycleEventPort {

    private static final Logger LOG = LoggerFactory.getLogger(LifecycleEventPublisher.class);

    private final ObjectMapper objectMapper;
    private final SoarKafkaProperties properties;
    private final LifecycleEventFactory factory;
    private final ControlPlaneStore store;
    private final boolean enabled;
    private final Counter enqueued;
    private final Counter enqueueFailed;

    public LifecycleEventPublisher(ObjectMapper objectMapper, SoarKafkaProperties properties,
                                   LifecycleEventFactory factory, MeterRegistry registry,
                                   ControlPlaneStore store,
                                   @Value("${app.soar.runtime-enabled:true}") boolean enabled) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.factory = factory;
        this.store = store;
        this.enabled = enabled;
        this.enqueued = registry.counter("siem.soar.lifecycle.outbox.enqueued");
        this.enqueueFailed = registry.counter("siem.soar.lifecycle.outbox.enqueue.failed");
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
        String body;
        try {
            body = objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            enqueueFailed.increment();
            LOG.error("SOAR lifecycle serialization failed type={} object={} message={}",
                    event.eventType(), event.objectId(), event.messageId(), e);
            throw e instanceof RuntimeException runtime
                    ? runtime : new IllegalStateException("SOAR lifecycle serialization failed", e);
        }
        try {
            store.enqueueLifecycle(event.messageId(), event.eventType(), event.effectiveTenantId(),
                    event.objectType(), event.objectId(), event.occurredAt(),
                    properties.topicFor(event.objectType()), event.objectId(), body);
            enqueued.increment();
        } catch (RuntimeException e) {
            enqueueFailed.increment();
            LOG.error("SOAR lifecycle outbox enqueue failed type={} object={} message={}",
                    event.eventType(), event.objectId(), event.messageId(), e);
            throw e;
        }
    }
}
