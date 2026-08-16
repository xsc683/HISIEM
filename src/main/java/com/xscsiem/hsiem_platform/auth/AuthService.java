package com.xscsiem.hsiem_platform.auth;

import com.xscsiem.hsiem_platform.onboarding.NotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Instant;
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
    private final Map<String, String> sessions = new ConcurrentHashMap<>();       // token -> username
    private final List<Map<String, String>> auditLogs = new CopyOnWriteArrayList<>();

    public AuthService(UserStore store) {
        this.store = store;
        bootstrap();
    }

    private void bootstrap() {
        if (store.list().isEmpty()) {
            AuthUser admin = new AuthUser();
            admin.id = "admin";
            admin.username = "admin";
            admin.passwordHash = encoder.encode("admin123");
            admin.role = "admin";
            admin.status = "active";
            admin.createdAt = Instant.now().toString();
            store.save(new ArrayList<>(List.of(admin)));
            audit("bootstrap_admin", "admin");
        }
    }

    /** 登录:校验密码 → 发 token。 */
    public Map<String, Object> login(String username, String password) {
        AuthUser u = find(username);
        if (!"active".equals(u.status)) {
            throw new UnauthorizedException("账号已禁用: " + username);
        }
        if (!encoder.matches(password, u.passwordHash)) {
            audit("login_failed", username);
            throw new UnauthorizedException("用户名或密码错误");
        }
        String token = UUID.randomUUID().toString();
        sessions.put(token, u.username);
        audit("login", username);
        return Map.of("token", token, "username", u.username, "role", u.role);
    }

    public void logout(String token) {
        sessions.remove(token);
    }

    /** 当前用户(by token)。 */
    public AuthUser me(String token) {
        String username = sessions.get(token);
        if (username == null) {
            throw new UnauthorizedException("未登录或会话过期");
        }
        return find(username);
    }

    public List<AuthUser> listUsers() {
        return store.list();
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
        List<AuthUser> users = store.list();
        users.add(u);
        store.save(users);
        audit("create_user", username + "(" + role + ")");
        return u;
    }

    public void deleteUser(String username) {
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
        List<AuthUser> users = store.list();
        AuthUser u = users.stream().filter(x -> x.username.equals(username)).findFirst()
                .orElseThrow(() -> new NotFoundException("用户不存在: " + username));
        u.role = role;
        store.save(users);
        audit("update_role", username + "->" + role);
        return u;
    }

    /** 角色与权限矩阵(前端展示)。 */
    public List<Map<String, Object>> roles() {
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
        return auditLogs;
    }

    public boolean isAdmin(AuthUser u) {
        return "admin".equals(u.role);
    }

    private void audit(String action, String target) {
        Map<String, String> entry = new LinkedHashMap<>();
        entry.put("timestamp", Instant.now().toString());
        entry.put("action", action);
        entry.put("target", target);
        auditLogs.add(entry);
    }

    private AuthUser find(String username) {
        return store.list().stream()
                .filter(x -> x.username.equals(username))
                .findFirst()
                .orElseThrow(() -> new UnauthorizedException("用户名或密码错误"));
    }

    private AuthUser findOrNull(String username) {
        return store.list().stream()
                .filter(x -> x.username.equals(username))
                .findFirst()
                .orElse(null);
    }
}
