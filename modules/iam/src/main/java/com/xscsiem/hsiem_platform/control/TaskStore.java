package com.xscsiem.hsiem_platform.control;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 后台任务(带租约)的持久化端口。由控制面 MyBatis 实现({@link MyBatisControlPlaneStore})提供, 是 {@link ControlPlaneStore}
 * 的子面。
 */
public interface TaskStore {

    String createTask(String type, String resourceId, String message);

    void updateTask(String id, String status, int progress, String message, String error);

    boolean claimTask(String id, String owner, Instant leaseUntil);

    boolean heartbeatTask(
            String id, String owner, Instant leaseUntil, int progress, String message);

    Map<String, Object> findTask(String id);

    List<Map<String, Object>> listTasks(int size);

    /** 将进程重启或心跳超时后遗留的任务收敛为 failed。 */
    int recoverStaleTasks(Instant cutoff, String errorMessage);
}
