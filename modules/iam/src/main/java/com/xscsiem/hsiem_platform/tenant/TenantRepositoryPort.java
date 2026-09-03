package com.xscsiem.hsiem_platform.tenant;

import java.util.List;

/** 租户目录与成员关系持久化端口。SQL 实现走「Mapper 接口 + XML + MyBatis Repository」四件套， 不再内联 JDBC。 */
public interface TenantRepositoryPort {

    /** true 当租户存在且 active 且用户是其成员。 */
    boolean hasActiveMembership(String tenantId, String username);

    int countMemberships(String username);

    List<TenantRow.UserTenantRow> listForUser(String username);

    List<TenantRow.TenantCountRow> listAll();

    /** 尝试创建租户 + owner 成员关系，返回受影响成员行数(0 表示该用户非 owner 或租户创建被跳过)。 */
    int createTenantWithOwner(String id, String name, String actor);

    boolean tenantExists(String id);

    int countUsers(String username);

    int countMembershipsOfTenant(String tenantId, String username);

    int updateMembershipRole(String tenantId, String username, String role);

    int insertMembership(String tenantId, String username, String role);

    /** 对 users 行加行锁以序列化默认租户引导；返回锁定行的 username(无行则 null)。 */
    String lockUser(String username);
}
