package com.xscsiem.hsiem_platform.tenant;

import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Repository;

/** MyBatis adapter implementing the tenant-directory port. */
@Repository
public class MyBatisTenantRepository implements TenantRepositoryPort {

    private final TenantMapper mapper;

    public MyBatisTenantRepository(TenantMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    }

    @Override
    public boolean hasActiveMembership(String tenantId, String username) {
        return mapper.countActiveMembership(tenantId, username) > 0;
    }

    @Override
    public int countMemberships(String username) {
        return mapper.countAllMemberships(username);
    }

    @Override
    public List<TenantRow.UserTenantRow> listForUser(String username) {
        return mapper.selectUserTenants(username);
    }

    @Override
    public List<TenantRow.TenantCountRow> listAll() {
        return mapper.selectAllTenants();
    }

    @Override
    public int createTenantWithOwner(String id, String name, String actor) {
        mapper.insertTenant(id, name, actor);
        return mapper.insertMembership(id, actor, "owner");
    }

    @Override
    public boolean tenantExists(String id) {
        return mapper.selectTenant(id) != null;
    }

    @Override
    public int countUsers(String username) {
        return mapper.countUsers(username);
    }

    @Override
    public int countMembershipsOfTenant(String tenantId, String username) {
        return mapper.countMembershipsOfTenant(tenantId, username);
    }

    @Override
    public int updateMembershipRole(String tenantId, String username, String role) {
        return mapper.updateMembershipRole(tenantId, username, role);
    }

    @Override
    public int insertMembership(String tenantId, String username, String role) {
        return mapper.insertMembership(tenantId, username, role);
    }

    @Override
    public String lockUser(String username) {
        return mapper.lockUser(username);
    }
}
