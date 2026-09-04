package com.xscsiem.hsiem_platform.control;

import com.xscsiem.hsiem_platform.auth.AuthUser;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 认证/账户有界上下文的持久化端口：用户、会话、登录节流与审计日志。由控制面 MyBatis 实现 ({@link MyBatisControlPlaneStore}) 提供,与 {@link
 * NotificationStore}、{@link CaseStore}、 {@link LifecycleOutboxStore}、{@link TaskStore} 共同构成 {@link
 * ControlPlaneStore} 的子面。
 */
public interface AuthStore {

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

    void recordLoginFailure(
            String username, Instant now, int maxFailures, Duration window, Duration lockout);

    void resetLoginFailures(String username);

    List<Map<String, Object>> listRoles();

    void audit(String actor, String action, String target);

    List<Map<String, String>> listAuditLogs();
}
