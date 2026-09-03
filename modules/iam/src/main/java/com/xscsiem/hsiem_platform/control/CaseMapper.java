package com.xscsiem.hsiem_platform.control;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * cases + case_alerts persistence (investigation fact source). SQL in {@code
 * mybatis/control/CaseMapper.xml}.
 */
@Mapper
public interface CaseMapper {

    /** Ids only; the store then composes full rows per id so the map contract is uniform. */
    List<String> selectCaseIds(@Param("status") String status, @Param("limit") int limit);

    List<ControlPlaneRow.CaseStatusCountRow> selectCaseStatusCounts();

    ControlPlaneRow.CaseRow selectCase(@Param("id") String id);

    List<String> selectAlertIds(@Param("caseId") String caseId);

    int insertCase(ControlPlaneRow.CaseRow row);

    int updateCase(
            @Param("row") ControlPlaneRow.CaseRow row,
            @Param("expectedVersion") long expectedVersion);

    int deleteCase(@Param("id") String id);

    int deleteCaseAlerts(@Param("caseId") String caseId);

    int insertCaseAlert(@Param("caseId") String caseId, @Param("alertId") String alertId);

    int countAlertReferences(@Param("alertId") String alertId);
}
