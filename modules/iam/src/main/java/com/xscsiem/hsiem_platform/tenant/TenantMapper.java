package com.xscsiem.hsiem_platform.tenant;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * tenants + tenant_memberships persistence (tenant-directory fact source). SQL in {@code
 * mybatis/control/TenantMapper.xml}.
 */
@Mapper
public interface TenantMapper {

    TenantRow.TenantRowValues selectTenant(@Param("id") String id);

    int countActiveMembership(
            @Param("tenantId") String tenantId, @Param("username") String username);

    int countAllMemberships(@Param("username") String username);

    int countMembershipsOfTenant(
            @Param("tenantId") String tenantId, @Param("username") String username);

    List<TenantRow.UserTenantRow> selectUserTenants(@Param("username") String username);

    List<TenantRow.TenantCountRow> selectAllTenants();

    int insertTenant(
            @Param("id") String id,
            @Param("name") String name,
            @Param("createdBy") String createdBy);

    int insertMembership(
            @Param("tenantId") String tenantId,
            @Param("username") String username,
            @Param("role") String role);

    int updateMembershipRole(
            @Param("tenantId") String tenantId,
            @Param("username") String username,
            @Param("role") String role);

    int countUsers(@Param("username") String username);

    String lockUser(@Param("username") String username);
}
