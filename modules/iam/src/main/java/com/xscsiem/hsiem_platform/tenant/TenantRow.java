package com.xscsiem.hsiem_platform.tenant;

import java.time.Instant;

/**
 * Typed persistence rows for the tenant-directory tables. Mapper XML maps columns into these
 * immutable records via constructor {@code resultMap}; the repository exposes them to {@link
 * TenantService}.
 */
public final class TenantRow {

    private TenantRow() {}

    /** tenants */
    public record TenantRowValues(
            String id, String name, String status, String createdBy, Instant createdAt) {}

    /** tenants JOIN tenant_memberships (a membership of the given user) */
    public record UserTenantRow(
            String id, String name, String status, String tenantRole, Instant createdAt) {}

    /** tenants with a LEFT JOIN count of members */
    public record TenantCountRow(
            String id,
            String name,
            String status,
            String createdBy,
            Instant createdAt,
            long memberCount) {}
}
