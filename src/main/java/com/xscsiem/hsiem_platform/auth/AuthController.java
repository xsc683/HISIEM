package com.xscsiem.hsiem_platform.auth;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 控制台认证与权限 API(story-08):登录/会话/用户/角色/审计。 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody LoginRequest req) {
        return auth.login(req.username(), req.password());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        auth.logout(extractToken(authHeader));
    }

    @GetMapping("/me")
    public Map<String, Object> me(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        AuthUser u = auth.me(extractToken(authHeader));
        return Map.of("username", u.username, "role", u.role, "status", u.status);
    }

    // ---- 用户/角色/审计(admin-only,AuthInterceptor 拦截) ----

    @GetMapping("/users")
    public List<AuthUser> users() {
        return auth.listUsers();
    }

    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthUser createUser(@RequestBody UserRequest req) {
        return auth.createUser(req.username(), req.password(), req.role());
    }

    @DeleteMapping("/users/{username}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteUser(@PathVariable String username) {
        auth.deleteUser(username);
    }

    @PutMapping("/users/{username}/role")
    public AuthUser updateRole(@PathVariable String username, @RequestBody RoleRequest req) {
        return auth.updateRole(username, req.role());
    }

    @GetMapping("/roles")
    public List<Map<String, Object>> roles() {
        return auth.roles();
    }

    @GetMapping("/audit-logs")
    public List<Map<String, String>> auditLogs() {
        return auth.auditLogs();
    }

    private static String extractToken(String header) {
        return header != null && header.startsWith("Bearer ") ? header.substring(7) : "";
    }

    public record LoginRequest(String username, String password) {
    }

    public record UserRequest(String username, String password, String role) {
    }

    public record RoleRequest(String role) {
    }
}
