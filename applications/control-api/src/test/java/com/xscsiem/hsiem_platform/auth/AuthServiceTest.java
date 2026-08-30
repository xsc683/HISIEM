package com.xscsiem.hsiem_platform.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 控制台 RBAC(story-08):登录/会话/用户角色 CRUD/审计。 */
class AuthServiceTest {

    @TempDir
    Path temp;

    private AuthService svc;

    @BeforeEach
    void setUp() {
        svc = new AuthService(new UserStore(temp.resolve("users.yaml").toString()));
    }

    @Test
    void bootstrap_createsDefaultAdmin() {
        Map<String, Object> login = svc.login("admin", "Admin-bootstrap-1");
        assertEquals("admin", login.get("role"));
        assertEquals(true, login.get("passwordChangeRequired"));
        assertNotNull(login.get("token"));
    }

    @Test
    void changePassword_clearsRotationFlag_andRejectsWeakPassword() {
        AuthUser updated = svc.changePassword("admin", "Admin-bootstrap-1", "Admin-password-1");
        assertEquals(false, updated.passwordChangeRequired);
        Map<String, Object> login = svc.login("admin", "Admin-password-1");
        assertEquals(false, login.get("passwordChangeRequired"));
        assertThrows(IllegalArgumentException.class,
                () -> svc.changePassword("admin", "Admin-password-1", "short"));
    }

    @Test
    void login_wrongPassword_throws401() {
        assertThrows(UnauthorizedException.class, () -> svc.login("admin", "wrong"));
        assertThrows(UnauthorizedException.class, () -> svc.login("nobody", "x"));
    }

    @Test
    void repeatedFailures_areThrottled() {
        for (int i = 0; i < 5; i++) {
            assertThrows(UnauthorizedException.class, () -> svc.login("admin", "wrong"));
        }
        UnauthorizedException blocked = assertThrows(UnauthorizedException.class,
                () -> svc.login("admin", "Admin-bootstrap-1"));
        assertTrue(blocked.getMessage().contains("15 分钟"));
    }

    @Test
    void createUser_loginWorks_andWeakPasswordRejected() {
        svc.createUser("alice", "secret-password-1", "analyst");
        Map<String, Object> login = svc.login("alice", "secret-password-1");
        assertEquals("analyst", login.get("role"));
        assertThrows(IllegalArgumentException.class, () -> svc.createUser("bob", "123", "ops"));
        assertThrows(IllegalArgumentException.class, () -> svc.createUser("bob", "secret-password-1", "superadmin"));
    }

    @Test
    void me_requiresValidToken() {
        Map<String, Object> login = svc.login("admin", "Admin-bootstrap-1");
        AuthUser u = svc.me(String.valueOf(login.get("token")));
        assertEquals("admin", u.username);
        assertThrows(UnauthorizedException.class, () -> svc.me("badtoken"));
    }

    @Test
    void updateRole_and_deleteUser() {
        svc.createUser("alice", "secret-password-1", "analyst");
        svc.updateRole("alice", "ops");
        Map<String, Object> login = svc.login("alice", "secret-password-1");
        assertEquals("ops", login.get("role"));
        svc.deleteUser("alice");
        assertThrows(UnauthorizedException.class, () -> svc.login("alice", "secret-password-1"));
    }

    @Test
    void roles_returnsFour() {
        assertEquals(4, svc.roles().size());
    }

    @Test
    void audit_logsRecorded() {
        svc.login("admin", "Admin-bootstrap-1");
        svc.createUser("alice", "secret-password-1", "ops");
        assertTrue(svc.auditLogs().stream().anyMatch(a -> "create_user".equals(a.get("action"))));
        assertTrue(svc.auditLogs().stream().anyMatch(a -> "login".equals(a.get("action"))));
    }
}
