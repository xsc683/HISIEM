package com.xscsiem.hsiem_platform.control;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * lifecycle 事件 outbox 的持久化端口：把 SOAR 事件可靠投递给 Kafka 的中转队列。 由控制面 MyBatis 实现({@link
 * MyBatisControlPlaneStore})提供,是 {@link ControlPlaneStore} 的子面。
 */
public interface LifecycleOutboxStore {

    void enqueueLifecycle(
            String messageId,
            String eventType,
            String tenantId,
            String objectType,
            String objectId,
            Instant occurredAt,
            String topic,
            String messageKey,
            String payload);

    List<Map<String, Object>> claimLifecycleBatch(String owner, Instant leaseUntil, int size);

    boolean completeLifecycle(
            String messageId, String owner, boolean success, String error, Instant nextAttemptAt);
}
