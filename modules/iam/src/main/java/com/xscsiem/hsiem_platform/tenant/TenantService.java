package com.xscsiem.hsiem_platform.tenant;

import com.xscsiem.hsiem_platform.auth.ForbiddenException;
import com.xscsiem.hsiem_platform.onboarding.ConflictException;
import com.xscsiem.hsiem_platform.onboarding.NotFoundException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.context.annotation.DependsOn;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** SOAR 租户目录与用户成员关系。 */
@Service
@DependsOn("flyway")
public class TenantService {

    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9-]{2,63}");
    private final TenantRepositoryPort tenants;

    public TenantService(TenantRepositoryPort tenants) {
        this.tenants = tenants;
    }

    @Transactional
    public String requireMembership(String username, String requestedTenant) {
        String tenant =
                requestedTenant == null || requestedTenant.isBlank()
                        ? TenantContext.DEFAULT_TENANT
                        : requestedTenant.trim();
        if (!ID.matcher(tenant).matches()) throw new ForbiddenException("租户标识非法");
        boolean member = tenants.hasActiveMembership(tenant, username);
        if (!member && TenantContext.DEFAULT_TENANT.equals(tenant)) {
            if (tenants.countMemberships(username) == 0) {
                ensureDefaultMembership(username);
                member = true;
            }
        }
        if (!member) throw new ForbiddenException("当前用户不属于租户: " + tenant);
        return tenant;
    }

    public List<Map<String, Object>> listForUser(String username) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (TenantRow.UserTenantRow row : tenants.listForUser(username)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.id());
            item.put("name", row.name());
            item.put("status", row.status());
            item.put("tenantRole", row.tenantRole());
            item.put("createdAt", row.createdAt().toString());
            result.add(item);
        }
        return result;
    }

    public List<Map<String, Object>> listAll() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (TenantRow.TenantCountRow row : tenants.listAll()) {
            Map<String, Object> item = new HashMap<>();
            item.put("id", row.id());
            item.put("name", row.name());
            item.put("status", row.status());
            item.put("createdBy", row.createdBy());
            item.put("createdAt", row.createdAt().toString());
            item.put("memberCount", row.memberCount());
            result.add(item);
        }
        return result;
    }

    @Transactional
    public Map<String, Object> create(String id, String name, String actor) {
        if (id == null || !ID.matcher(id).matches() || name == null || name.isBlank()) {
            throw new IllegalArgumentException("tenant id 需为 3-64 位小写字母/数字/连字符，name 不能为空");
        }
        String trimmed = name.trim();
        try {
            tenants.createTenantWithOwner(id, trimmed, actor);
        } catch (DuplicateKeyException e) {
            throw new ConflictException("租户已存在: " + id);
        }
        return Map.of("id", id, "name", trimmed, "status", "active", "tenantRole", "owner");
    }

    @Transactional
    public void addMember(String tenantId, String username, String role) {
        if (!ROLES.contains(role)) throw new IllegalArgumentException("tenantRole 非法");
        if (!tenants.tenantExists(tenantId)) throw new NotFoundException("租户不存在: " + tenantId);
        if (tenants.countUsers(username) == 0) throw new NotFoundException("用户不存在: " + username);
        int changed = tenants.updateMembershipRole(tenantId, username, role);
        if (changed == 0) {
            tenants.insertMembership(tenantId, username, role);
        }
    }

    @Transactional
    public void ensureDefaultMembership(String username) {
        // FOR UPDATE 串行化并发默认租户引导；行不存在时抛出(与 JdbcTemplate queryForObject 空结果一致)。
        if (tenants.lockUser(username) == null) throw new EmptyResultDataAccessException(1);
        if (tenants.countMembershipsOfTenant("default", username) == 0) {
            tenants.insertMembership("default", username, "member");
        }
    }

    private static final Set<String> ROLES = Set.of("owner", "member", "viewer");
}
