package com.xscsiem.hsiem_platform.control;

import java.time.Instant;

/**
 * Typed persistence rows for the control-plane tables. Mapper XML maps columns into these immutable
 * records via constructor {@code resultMap}; the adapter converts them to the {@link
 * ControlPlaneStore} map contract at the seam.
 */
public final class ControlPlaneRow {

    private ControlPlaneRow() {}

    /** users / auth_sessions / login_attempts / roles / audit_logs */
    public record UserRow(
            String id,
            String username,
            String passwordHash,
            boolean passwordChangeRequired,
            String role,
            String status,
            Instant createdAt) {}

    public record LoginAttemptRow(int failureCount, Instant firstFailureAt) {}

    public record RoleRow(String name, String description, String permissionsJson) {}

    public record AuditRow(Instant createdAt, String actor, String action, String target) {}

    /** notifications */
    public record NotificationRow(
            String id,
            String type,
            String target,
            String message,
            boolean read,
            Instant createdAt) {}

    /** cases + case_alerts */
    public record CaseRow(
            String id,
            String title,
            String status,
            String aggregation,
            String operator,
            String owner,
            String verdict,
            Instant createdAt,
            Instant updatedAt,
            Instant closedAt,
            String alertIdsJson,
            String entitiesJson,
            String evidenceJson,
            String collaboratorsJson,
            long version) {}

    public record CaseStatusCountRow(String status, long total) {}

    /** case_mirror_outbox */
    public record CaseMirrorClaimRow(
            long id, String caseId, String operation, String payload, int attempts) {}

    /** background_tasks */
    public record TaskRow(
            String id,
            String type,
            String resourceId,
            String status,
            int progress,
            String message,
            String error,
            Instant startedAt,
            Instant finishedAt,
            Instant createdAt,
            Instant updatedAt,
            int attempts,
            int maxAttempts,
            String leaseOwner,
            Instant leaseUntil,
            Instant heartbeatAt) {}

    /** lifecycle_outbox */
    public record LifecycleClaimRow(
            String messageId,
            String eventType,
            String tenantId,
            String objectType,
            String objectId,
            Instant occurredAt,
            String topic,
            String messageKey,
            String payload,
            int attempts) {}
}
