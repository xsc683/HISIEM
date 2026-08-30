package com.xscsiem.hsiem_platform.soar;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;

import java.util.List;

/** Converts immutable lifecycle facts into deduplicated durable executions. */
@Service
public class SoarLifecycleRuntime {

    private final SoarStore store;
    private final Counter accepted;
    private final Counter deduplicated;

    public SoarLifecycleRuntime(SoarStore store, MeterRegistry registry) {
        this.store = store;
        this.accepted = registry.counter("siem.soar.lifecycle.accepted");
        this.deduplicated = registry.counter("siem.soar.lifecycle.deduplicated");
    }

    public int accept(LifecycleEvent event) {
        return accept(SoarTriggerEnvelope.direct(event));
    }

    /** Creates at most one execution per playbook/message pair. */
    public int accept(SoarTriggerEnvelope trigger) {
        validate(trigger);
        List<SoarPlaybook> matches = store.matchingPlaybooks(
                trigger.tenantId(), trigger.objectType(), trigger.eventType());
        int created = 0;
        for (SoarPlaybook playbook : matches) {
            if (store.createExecution(playbook, trigger)) {
                accepted.increment();
                created++;
            } else {
                deduplicated.increment();
            }
        }
        return created;
    }

    private void validate(SoarTriggerEnvelope trigger) {
        if (trigger == null) throw new IllegalArgumentException("SOAR 触发信封不能为空");
        if (trigger.messageId() == null || trigger.messageId().isBlank()) {
            throw new IllegalArgumentException("messageId 不能为空");
        }
        if (trigger.tenantId() == null || trigger.tenantId().isBlank()) {
            throw new IllegalArgumentException("tenantId 不能为空");
        }
        if (!List.of("alert", "case").contains(trigger.objectType())) {
            throw new IllegalArgumentException("objectType 仅支持 alert 或 case");
        }
        if (trigger.objectId() == null || trigger.objectId().isBlank()) {
            throw new IllegalArgumentException("objectId 不能为空");
        }
    }
}
