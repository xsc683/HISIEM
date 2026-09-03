package com.xscsiem.hsiem_platform.control;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * background_tasks persistence (durable task leases). SQL in {@code
 * mybatis/control/TaskMapper.xml}.
 */
@Mapper
public interface TaskMapper {

    int insertTask(
            @Param("id") String id,
            @Param("type") String type,
            @Param("resourceId") String resourceId,
            @Param("message") String message);

    int updateTask(
            @Param("id") String id,
            @Param("status") String status,
            @Param("progress") int progress,
            @Param("message") String message,
            @Param("error") String error);

    int claimTask(
            @Param("id") String id,
            @Param("owner") String owner,
            @Param("leaseUntil") Instant leaseUntil);

    int heartbeatTask(
            @Param("id") String id,
            @Param("owner") String owner,
            @Param("leaseUntil") Instant leaseUntil,
            @Param("progress") int progress,
            @Param("message") String message);

    ControlPlaneRow.TaskRow selectTask(@Param("id") String id);

    List<ControlPlaneRow.TaskRow> selectTasks(@Param("limit") int limit);

    int recoverStaleTasks(
            @Param("cutoff") Instant cutoff, @Param("errorMessage") String errorMessage);
}
