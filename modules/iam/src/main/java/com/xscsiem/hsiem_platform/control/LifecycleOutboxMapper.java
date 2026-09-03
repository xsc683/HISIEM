package com.xscsiem.hsiem_platform.control;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * lifecycle_outbox persistence (durable lifecycle event delivery). SQL in {@code
 * mybatis/control/LifecycleOutboxMapper.xml}.
 */
@Mapper
public interface LifecycleOutboxMapper {

    int insert(
            @Param("messageId") String messageId,
            @Param("eventType") String eventType,
            @Param("tenantId") String tenantId,
            @Param("objectType") String objectType,
            @Param("objectId") String objectId,
            @Param("occurredAt") Instant occurredAt,
            @Param("topic") String topic,
            @Param("messageKey") String messageKey,
            @Param("payload") String payload);

    int insertOnConflictDoNothing(
            @Param("messageId") String messageId,
            @Param("eventType") String eventType,
            @Param("tenantId") String tenantId,
            @Param("objectType") String objectType,
            @Param("objectId") String objectId,
            @Param("occurredAt") Instant occurredAt,
            @Param("topic") String topic,
            @Param("messageKey") String messageKey,
            @Param("payload") String payload);

    List<ControlPlaneRow.LifecycleClaimRow> selectClaimable(@Param("limit") int limit);

    int claim(
            @Param("messageId") String messageId,
            @Param("owner") String owner,
            @Param("leaseUntil") Instant leaseUntil);

    int succeed(@Param("messageId") String messageId, @Param("owner") String owner);

    int fail(
            @Param("messageId") String messageId,
            @Param("owner") String owner,
            @Param("error") String error,
            @Param("nextAttemptAt") Instant nextAttemptAt);
}
