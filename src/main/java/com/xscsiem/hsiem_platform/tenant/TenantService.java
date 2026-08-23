package com.xscsiem.hsiem_platform.tenant;

import com.xscsiem.hsiem_platform.auth.ForbiddenException;
import com.xscsiem.hsiem_platform.onboarding.ConflictException;
import com.xscsiem.hsiem_platform.onboarding.NotFoundException;
import org.springframework.context.annotation.DependsOn;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** SOAR 租户目录与用户成员关系。 */
@Service
@DependsOn("flyway")
public class TenantService {

    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9-]{2,63}");
    private final JdbcTemplate jdbc;

    public TenantService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public String requireMembership(String username, String requestedTenant) {
        String tenant = requestedTenant == null || requestedTenant.isBlank()
                ? TenantContext.DEFAULT_TENANT : requestedTenant.trim();
        if (!ID.matcher(tenant).matches()) throw new ForbiddenException("租户标识非法");
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM tenant_memberships m JOIN tenants t ON t.id = m.tenant_id
                WHERE m.tenant_id = ? AND m.username = ? AND t.status = 'active'
                """, Integer.class, tenant, username);
        if ((count == null || count == 0) && TenantContext.DEFAULT_TENANT.equals(tenant)) {
            Integer allMemberships = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM tenant_memberships WHERE username = ?
                    """, Integer.class, username);
            if (allMemberships == null || allMemberships == 0) {
                ensureDefaultMembership(username);
                count = 1;
            }
        }
        if (count == null || count == 0) throw new ForbiddenException("当前用户不属于租户: " + tenant);
        return tenant;
    }

    public List<Map<String, Object>> listForUser(String username) {
        return jdbc.queryForList("""
                SELECT t.id, t.name, t.status, m.tenant_role AS "tenantRole", t.created_at AS "createdAt"
                FROM tenants t JOIN tenant_memberships m ON m.tenant_id = t.id
                WHERE m.username = ? ORDER BY t.id
                """, username);
    }

    public List<Map<String, Object>> listAll() {
        return jdbc.queryForList("""
                SELECT t.id, t.name, t.status, t.created_by AS "createdBy", t.created_at AS "createdAt",
                       COUNT(m.username) AS "memberCount"
                FROM tenants t LEFT JOIN tenant_memberships m ON m.tenant_id = t.id
                GROUP BY t.id, t.name, t.status, t.created_by, t.created_at ORDER BY t.id
                """);
    }

    @Transactional
    public Map<String, Object> create(String id, String name, String actor) {
        if (id == null || !ID.matcher(id).matches() || name == null || name.isBlank()) {
            throw new IllegalArgumentException("tenant id 需为 3-64 位小写字母/数字/连字符，name 不能为空");
        }
        try {
            jdbc.update("INSERT INTO tenants(id, name, created_by) VALUES (?, ?, ?)", id, name.trim(), actor);
            jdbc.update("INSERT INTO tenant_memberships(tenant_id, username, tenant_role) VALUES (?, ?, 'owner')",
                    id, actor);
        } catch (DuplicateKeyException e) {
            throw new ConflictException("租户已存在: " + id);
        }
        return Map.of("id", id, "name", name.trim(), "status", "active", "tenantRole", "owner");
    }

    @Transactional
    public void addMember(String tenantId, String username, String role) {
        if (!SetValues.TENANT_ROLES.contains(role)) throw new IllegalArgumentException("tenantRole 非法");
        if (!exists(tenantId)) throw new NotFoundException("租户不存在: " + tenantId);
        Integer users = jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE username = ?", Integer.class, username);
        if (users == null || users == 0) throw new NotFoundException("用户不存在: " + username);
        int changed = jdbc.update("""
                UPDATE tenant_memberships SET tenant_role = ? WHERE tenant_id = ? AND username = ?
                """, role, tenantId, username);
        if (changed == 0) {
            jdbc.update("INSERT INTO tenant_memberships(tenant_id, username, tenant_role) VALUES (?, ?, ?)",
                    tenantId, username, role);
        }
    }

    @Transactional
    public void ensureDefaultMembership(String username) {
        jdbc.queryForObject("SELECT username FROM users WHERE username = ? FOR UPDATE", String.class, username);
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM tenant_memberships WHERE tenant_id = 'default' AND username = ?
                """, Integer.class, username);
        if (count == null || count == 0) {
            jdbc.update("INSERT INTO tenant_memberships(tenant_id, username, tenant_role) VALUES ('default', ?, 'member')",
                    username);
        }
    }

    private boolean exists(String tenantId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM tenants WHERE id = ?", Integer.class, tenantId);
        return count != null && count > 0;
    }

    private static final class SetValues {
        private static final java.util.Set<String> TENANT_ROLES = java.util.Set.of("owner", "member", "viewer");
    }
}
