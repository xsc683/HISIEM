package com.xscsiem.hsiem_platform.control;

import com.xscsiem.hsiem_platform.auth.AuthUser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 阶段 4.1:迁移、控制面 CRUD、案件关系唯一约束和后台任务持久化。 */
@SpringBootTest
class ControlPlaneStoreTest {

    @Autowired
    private ControlPlaneStore store;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void flywayCreatesSchemaAndSeedsRoles() {
        assertTrue(jdbc.queryForObject(
                "SELECT COUNT(*) FROM \"flyway_schema_history\" WHERE \"success\" = TRUE", Integer.class) >= 1);
        assertEquals(4, store.listRoles().size());
    }

    @Test
    void usersNotificationsAndTasksPersist() {
        String username = "jdbc-test-" + System.nanoTime();
        AuthUser user = new AuthUser();
        user.id = username;
        user.username = username;
        user.passwordHash = "hash";
        user.role = "analyst";
        user.status = "active";
        user.createdAt = Instant.now().toString();
        store.insertUser(user);
        assertEquals(username, store.findUser(username).username);

        assertTrue(store.createNotificationIfAllowed("test", username, "first",
                Instant.now().minusSeconds(60)));
        assertFalse(store.createNotificationIfAllowed("test", username, "second",
                Instant.now().minusSeconds(60)));
        assertEquals(1, store.listNotifications(true).stream()
                .filter(n -> username.equals(n.get("target"))).count());

        String task = store.createTask("test", username, "queued");
        store.updateTask(task, "succeeded", 100, "done", null);
        assertTrue(store.listTasks(20).stream().anyMatch(t -> task.equals(t.get("id"))
                && "succeeded".equals(t.get("status"))));
    }

    @Test
    void caseRelationsAreTransactionalAndUnique() {
        String suffix = String.valueOf(System.nanoTime());
        String caseId = "case-jdbc-" + suffix;
        String alertId = "alert-jdbc-" + suffix;
        String now = Instant.now().toString();
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("case.id", caseId);
        document.put("case.title", "JDBC case");
        document.put("case.status", "open");
        document.put("case.aggregation", "manual");
        document.put("case.operator", "test");
        document.put("case.created_at", now);
        document.put("case.updated_at", now);
        document.put("entities", List.of(Map.of("type", "ip", "value", "198.51.100.10")));
        store.createCase(document, List.of(alertId));

        Map<String, Object> stored = store.findCase(caseId);
        assertNotNull(stored);
        assertEquals(List.of(alertId), stored.get("alert_ids"));
        assertTrue(store.hasAlert(alertId));

        Map<String, Object> update = new LinkedHashMap<>();
        update.put("case.status", "investigating");
        update.put("case.updated_at", Instant.now().toString());
        Map<String, Object> updated = store.updateCase(caseId, 0, update, List.of(alertId));
        assertEquals("investigating", updated.get("case.status"));
        assertEquals(1L, ((Number) updated.get("_control_version")).longValue());

        Map<String, Object> other = new LinkedHashMap<>(document);
        other.put("case.id", caseId + "-other");
        assertThrows(DataIntegrityViolationException.class,
                () -> store.createCase(other, List.of(alertId)));

        assertTrue(store.deleteCase(caseId));
        assertFalse(store.hasAlert(alertId));
    }
}
