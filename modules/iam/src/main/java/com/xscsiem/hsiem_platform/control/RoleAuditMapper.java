package com.xscsiem.hsiem_platform.control;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** Roles + audit_logs persistence. SQL in {@code mybatis/control/RoleAuditMapper.xml}. */
@Mapper
public interface RoleAuditMapper {

    List<ControlPlaneRow.RoleRow> selectRoles();

    void insertAudit(
            @Param("actor") String actor,
            @Param("action") String action,
            @Param("target") String target);

    List<ControlPlaneRow.AuditRow> selectRecentAuditLogs();
}
