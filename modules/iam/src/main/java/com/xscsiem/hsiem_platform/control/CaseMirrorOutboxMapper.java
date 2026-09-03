package com.xscsiem.hsiem_platform.control;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * case_mirror_outbox persistence (ES mirror dispatch). SQL in {@code
 * mybatis/control/CaseMirrorOutboxMapper.xml}.
 */
@Mapper
public interface CaseMirrorOutboxMapper {

    int compactPendingUpsert(@Param("caseId") String caseId, @Param("payload") String payload);

    int countPendingUpsert(@Param("caseId") String caseId);

    int insert(
            @Param("caseId") String caseId,
            @Param("operation") String operation,
            @Param("payload") String payload);

    List<ControlPlaneRow.CaseMirrorClaimRow> selectClaimable(@Param("limit") int limit);

    int claim(
            @Param("id") long id,
            @Param("owner") String owner,
            @Param("leaseUntil") Instant leaseUntil);

    int succeed(@Param("id") long id, @Param("owner") String owner);

    int fail(
            @Param("id") long id,
            @Param("owner") String owner,
            @Param("error") String error,
            @Param("nextAttemptAt") Instant nextAttemptAt);
}
