package com.xscsiem.hsiem_platform.auth;

import com.xscsiem.hsiem_platform.onboarding.NotFoundException;
import com.xscsiem.hsiem_platform.control.ControlPlaneStore;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 控制台认证与权限(story-08 RBAC):
 * - 登录 → 内存会话 token;密码 BCrypt 哈希,存 infra/auth/users.yaml(文件 + Git)。
 * - 角色矩阵(admin/analyst/ops/audit)见 ROLE_PERMS;敏感操作(设置/部署/用户管理)需 admin。
 * - 审计:登录/用户/角色变更写审计日志(who/when/what)。
 * 首次启动无用户时引导默认 admin(admin123,文档提示改密)。
 */
@Service
public class AuthService {

    public static final Map<String, Set<String>> ROLE_PERMS = Map.of(
            "admin", Set.of("all"),
            "analyst", Set.of("alerts:read", "alerts:write"),
            "ops", Set.of("sources:write", "health:read"),
            "audit", Set.of("read"));

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final UserStore store;
    private final ControlPlaneStore control;
    private final Map<String, String> sessions = new ConcurrentHashMap<>();       // token -> username
    private final List<Map<String, String>> auditLogs = new CopyOnWriteArrayList<>();
    private final Map<String, LoginAttempt> localLoginAttempts = new ConcurrentHashMap<>();
    @Value("${app.auth.session-ttl:PT8H}")
    private Duration sessionTtl = Duration.ofHours(8);
    @Value("${app.auth.login-window:PT15M}")
    private Duration loginWindow = Duration.ofMinutes(15);
    @Value("${app.auth.login-lockout:PT15M}")
    private Duration loginLockout = Duration.ofMinutes(15);
    @Value("${app.auth.max-login-failures:5}")
    private int maxLoginFailures = 5;
    private MeterRegistry metrics;

    /** 生产构造器:用户、角色、审计均落 PostgreSQL。 */
    @Autowired
    public AuthService(UserStore store, ControlPlaneStore control) {
        this.store = store;
        this.control = control;
        bootstrap();
    }

    @Autowired(required = false)
    public void setMetrics(MeterRegistry metrics) {
        this.metrics = metrics;
    }

    /** 轻量构造器仅供不启动 Spring/数据库的单元测试使用。 */
    public AuthService(UserStore store) {
        this(store, null);
    }

    private void bootstrap() {
        if (control != null && control.listUsers().isEmpty()) {
            List<AuthUser> legacy = store.list();
            if (!legacy.isEmpty()) {
                legacy.forEach(control::insertUser);
                audit("migration_users", "users.yaml");
                return;
            }
        }
        if (listUsersInternal().isEmpty()) {
            AuthUser admin = new AuthUser();
            admin.id = "admin";
            admin.username = "admin";
            admin.passwordHash = encoder.encode("admin123");
            admin.role = "admin";
            admin.status = "active";
            admin.createdAt = Instant.now().toString();
            if (control == null) {
                store.save(new ArrayList<>(List.of(admin)));
            } else {
                control.insertUser(admin);
            }
            audit("bootstrap_admin", "admin");
        }
    }

    /** 登录:校验密码 → 发 token。 */
    public Map<String, Object> login(String username, String password) {
        String loginName = username == null ? "" : username.trim();
        Instant now = Instant.now();
        if (isLoginBlocked(loginName, now)) {
            audit("login_blocked", loginName);
            counter("hsiem.auth.login.blocked");
            throw new UnauthorizedException("登录失败次数过多，请 15 分钟后重试");
        }
        AuthUser u = findOrNull(loginName);
        if (u == null || !"active".equals(u.status)) {
            recordLoginFailure(loginName, now);
            audit("login_failed", loginName);
            counter("hsiem.auth.login.failure");
            throw new UnauthorizedException("用户名或密码错误");
        }
        if (!encoder.matches(password, u.passwordHash)) {
            recordLoginFailure(loginName, now);
            audit("login_failed", loginName);
            counter("hsiem.auth.login.failure");
            throw new UnauthorizedException("用户名或密码错误");
        }
        resetLoginFailures(loginName);
        String token = UUID.randomUUID().toString();
        Instant expiresAt = now.plus(sessionTtl);
        if (control == null) {
            sessions.put(token, u.username);
        } else {
            control.cleanupExpiredSessions(now);
            control.createSession(hashToken(token), u.username, expiresAt);
        }
        audit("login", loginName);
        counter("hsiem.auth.login.success");
        return Map.of("token", token, "username", u.username, "role", u.role,
                "expiresAt", expiresAt.toString());
    }

    public void logout(String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        if (control == null) {
            sessions.remove(token);
        } else {
            control.deleteSession(hashToken(token));
        }
    }

    /** 当前用户(by token)。 */
    public AuthUser me(String token) {
        String username = sessionUsername(token);
        if (username == null) {
            throw new UnauthorizedException("未登录或会话过期");
        }
        return find(username);
    }

    /** Spring Security Bearer 过滤器使用的认证入口。无效 token 返回 null，由安全链统一返回 401。 */
    public AuthUser authenticateToken(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            return me(token);
        } catch (UnauthorizedException e) {
            return null;
        }
    }

    /** 将控制面角色权限映射为 Spring Security authorities。 */
    public Set<String> authorities(AuthUser user) {
        Set<String> permissions = ROLE_PERMS.getOrDefault(user.role, Set.of("read"));
        Set<String> result = new java.util.HashSet<>();
        result.add("ROLE_" + user.role.toUpperCase());
        permissions.forEach(permission -> result.add("PERM_" + permission.toUpperCase().replace(':', '_')));
        return Set.copyOf(result);
    }

    public List<AuthUser> listUsers() {
        return listUsersInternal();
    }

    public AuthUser createUser(String username, String password, String role) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("用户名不能为空");
        }
        if (!ROLE_PERMS.containsKey(role)) {
            throw new IllegalArgumentException("角色非法(admin/analyst/ops/audit): " + role);
        }
        if (password == null || password.length() < 6) {
            throw new IllegalArgumentException("密码至少 6 位");
        }
        if (findOrNull(username) != null) {
            throw new IllegalArgumentException("用户已存在: " + username);
        }
        AuthUser u = new AuthUser();
        u.id = username;
        u.username = username;
        u.passwordHash = encoder.encode(password);
        u.role = role;
        u.status = "active";
        u.createdAt = Instant.now().toString();
        if (control == null) {
            List<AuthUser> users = store.list();
            users.add(u);
            store.save(users);
        } else {
            control.insertUser(u);
        }
        audit("create_user", username + "(" + role + ")");
        return u;
    }

    public void deleteUser(String username) {
        if (control != null) {
            control.deleteSessionsForUser(username);
            control.deleteUser(username);
            audit("delete_user", username);
            return;
        }
        List<AuthUser> users = store.list();
        boolean removed = users.removeIf(x -> x.username.equals(username));
        if (!removed) {
            throw new NotFoundException("用户不存在: " + username);
        }
        store.save(users);
        audit("delete_user", username);
    }

    public AuthUser updateRole(String username, String role) {
        if (!ROLE_PERMS.containsKey(role)) {
            throw new IllegalArgumentException("角色非法(admin/analyst/ops/audit): " + role);
        }
        AuthUser u = findOrNull(username);
        if (u == null) {
            throw new NotFoundException("用户不存在: " + username);
        }
        u.role = role;
        if (control == null) {
            List<AuthUser> users = store.list();
            users.stream().filter(x -> x.username.equals(username)).findFirst().ifPresent(x -> x.role = role);
            store.save(users);
        } else {
            control.updateUser(u);
        }
        audit("update_role", username + "->" + role);
        return u;
    }

    /** 角色与权限矩阵(前端展示)。 */
    public List<Map<String, Object>> roles() {
        if (control != null) {
            return control.listRoles();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, Set<String>> e : ROLE_PERMS.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", e.getKey());
            row.put("permissions", e.getValue());
            out.add(row);
        }
        return out;
    }

    public List<Map<String, String>> auditLogs() {
        return control == null ? auditLogs : control.listAuditLogs();
    }

    public boolean isAdmin(AuthUser u) {
        return "admin".equals(u.role);
    }

    private void audit(String action, String target) {
        String actor = currentActor();
        if (control != null) {
            control.audit(actor, action, target);
            return;
        }
        Map<String, String> entry = new LinkedHashMap<>();
        entry.put("timestamp", Instant.now().toString());
        entry.put("actor", actor);
        entry.put("action", action);
        entry.put("target", target);
        auditLogs.add(entry);
    }

    private static String currentActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication.getName() == null
                || "anonymousUser".equals(authentication.getName())) {
            return "system";
        }
        return authentication.getName();
    }

    private AuthUser find(String username) {
        AuthUser user = findOrNull(username);
        if (user == null) {
            throw new UnauthorizedException("用户名或密码错误");
        }
        return user;
    }

    private AuthUser findOrNull(String username) {
        if (control != null) {
            return control.findUser(username);
        }
        return store.list().stream()
                .filter(x -> x.username.equals(username))
                .findFirst()
                .orElse(null);
    }

    private List<AuthUser> listUsersInternal() {
        return control == null ? store.list() : control.listUsers();
    }

    private String sessionUsername(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        if (control == null) {
            return sessions.get(token);
        }
        return control.findSessionUsername(hashToken(token), Instant.now());
    }

    private boolean isLoginBlocked(String username, Instant now) {
        if (control != null) {
            return control.isLoginBlocked(username, now);
        }
        LoginAttempt attempt = localLoginAttempts.get(username);
        return attempt != null && attempt.lockedUntil != null && attempt.lockedUntil.isAfter(now);
    }

    private void recordLoginFailure(String username, Instant now) {
        if (control != null) {
            control.recordLoginFailure(username, now, maxLoginFailures, loginWindow, loginLockout);
            return;
        }
        localLoginAttempts.compute(username, (key, current) -> {
            if (current == null || current.firstFailureAt.isBefore(now.minus(loginWindow))) {
                return new LoginAttempt(1, now, null);
            }
            int failures = current.failureCount + 1;
            return new LoginAttempt(failures, current.firstFailureAt,
                    failures >= maxLoginFailures ? now.plus(loginLockout) : null);
        });
    }

    private void resetLoginFailures(String username) {
        if (control != null) {
            control.resetLoginFailures(username);
        } else {
            localLoginAttempts.remove(username);
        }
    }

    private void counter(String name) {
        if (metrics != null) {
            metrics.counter(name).increment();
        }
    }

    private static String hashToken(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                out.append(String.format("%02x", value));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 不支持 SHA-256", e);
        }
    }

    private record LoginAttempt(int failureCount, Instant firstFailureAt, Instant lockedUntil) {
    }
}
