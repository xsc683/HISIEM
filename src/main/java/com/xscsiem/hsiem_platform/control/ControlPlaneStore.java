package com.xscsiem.hsiem_platform.control;

import com.xscsiem.hsiem_platform.auth.AuthUser;

import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 控制面持久化边界。事件、告警正文和实体风险不进入这里，仍由 Elasticsearch 负责检索。
 * 接口让服务层可以在单元测试中使用原有轻量构造器，生产运行则统一走 JDBC 实现。
 */
public interface ControlPlaneStore {

    List<AuthUser> listUsers();

    AuthUser findUser(String username);

    void insertUser(AuthUser user);

    void updateUser(AuthUser user);

    void deleteUser(String username);

    String findSessionUsername(String tokenHash, Instant now);

    void createSession(String tokenHash, String username, Instant expiresAt);

    void deleteSession(String tokenHash);

    void deleteSessionsForUser(String username);

    void cleanupExpiredSessions(Instant now);

    boolean isLoginBlocked(String username, Instant now);

    void recordLoginFailure(String username, Instant now, int maxFailures,
                            Duration window, Duration lockout);

    void resetLoginFailures(String username);

    List<Map<String, Object>> listRoles();

    void audit(String actor, String action, String target);

    List<Map<String, String>> listAuditLogs();

    List<Map<String, Object>> listNotifications(Boolean unread);

    boolean createNotificationIfAllowed(String type, String target, String message, Instant notBefore);

    boolean markNotificationRead(String id);

    void markAllNotificationsRead();

    boolean deleteNotification(String id);

    /** 删除已读且超过保留期的通知，返回删除数。 */
    int deleteReadNotificationsBefore(Instant before);

    List<Map<String, Object>> listCases(String status, String entity, int size);

    Map<String, Object> findCase(String id);

    void createCase(Map<String, Object> document, List<String> alertIds);

    Map<String, Object> importCaseDocument(Map<String, Object> document);

    Map<String, Object> updateCase(String id, long expectedVersion,
                                   Map<String, Object> document, List<String> alertIds);

    boolean deleteCase(String id);

    boolean hasAlert(String alertId);

    String createTask(String type, String resourceId, String message);

    void updateTask(String id, String status, int progress, String message, String error);

    Map<String, Object> findTask(String id);

    List<Map<String, Object>> listTasks(int size);

    /** 将进程重启或心跳超时后遗留的任务收敛为 failed。 */
    int recoverStaleTasks(Instant cutoff, String errorMessage);
}
