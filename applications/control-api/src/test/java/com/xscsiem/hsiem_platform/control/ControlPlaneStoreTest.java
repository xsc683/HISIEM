package com.xscsiem.hsiem_platform.control;

import com.xscsiem.hsiem_platform.auth.AuthUser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
        assertTrue(store.claimTask(task, "worker-a", Instant.now().plusSeconds(60)));
        assertFalse(store.claimTask(task, "worker-b", Instant.now().plusSeconds(60)));
        assertTrue(store.heartbeatTask(task, "worker-a", Instant.now().plusSeconds(60), 50, "working"));
        store.updateTask(task, "succeeded", 100, "done", null);
        assertTrue(store.listTasks(20).stream().anyMatch(t -> task.equals(t.get("id"))
                && "succeeded".equals(t.get("status"))));
    }

    @Test
    void staleQueuedAndRunningTasksAreMarkedFailed() {
        String queued = store.createTask("test", "stale-q-" + System.nanoTime(), "queued");
        String running = store.createTask("test", "stale-r-" + System.nanoTime(), "running");
        store.updateTask(running, "running", 40, "working", null);
        jdbc.update("UPDATE background_tasks SET updated_at = CURRENT_TIMESTAMP - INTERVAL '10' MINUTE "
                + "WHERE id IN (?, ?)", queued, running);

        assertEquals(2, store.recoverStaleTasks(Instant.now().minusSeconds(60), "worker stopped"));
        assertEquals("failed", store.findTask(queued).get("status"));
        assertEquals("failed", store.findTask(running).get("status"));
        assertEquals("worker stopped", store.findTask(running).get("error"));
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
        document.put("case.owner", "analyst-1");
        document.put("case.created_at", now);
        document.put("case.updated_at", now);
        document.put("entities", List.of(Map.of("type", "ip", "value", "198.51.100.10")));
        document.put("evidence", List.of(Map.of("type", "reference", "title", "ticket-1", "uri", "https://example.test/1")));
        store.createCase(document, List.of(alertId));

        List<Map<String, Object>> outbox = store.claimCaseMirrorBatch("case-test", Instant.now().plusSeconds(60), 10);
        assertTrue(outbox.stream().anyMatch(row -> caseId.equals(row.get("caseId"))
                && "upsert".equals(row.get("operation"))));
        Map<String, Object> mirror = outbox.stream()
                .filter(row -> caseId.equals(row.get("caseId"))).findFirst().orElseThrow();
        store.completeCaseMirror(((Number) mirror.get("id")).longValue(), "case-test", true, null, Instant.now());

        Map<String, Object> stored = store.findCase(caseId);
        assertNotNull(stored);
        assertEquals(List.of(alertId), stored.get("alert_ids"));
        assertEquals("analyst-1", stored.get("case.owner"));
        assertEquals(1, ((List<?>) stored.get("evidence")).size());
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
        List<Map<String, Object>> deleteOutbox = store.claimCaseMirrorBatch("case-test", Instant.now().plusSeconds(60), 10);
        assertTrue(deleteOutbox.stream().anyMatch(row -> caseId.equals(row.get("caseId"))
                && "delete".equals(row.get("operation"))));
    }

    @Test
    void lifecycleOutboxIsIdempotentAndOwnerFenced() {
        String suffix = String.valueOf(System.nanoTime());
        String messageId = "lifecycle-" + suffix;
        String tenantId = "tenant-" + suffix;
        String objectId = "alert-" + suffix;
        Instant occurredAt = Instant.now().minusSeconds(5).truncatedTo(ChronoUnit.MICROS);
        String payload = "{\"version\":1}";

        store.enqueueLifecycle(messageId, "alert.created", tenantId, "alert", objectId, occurredAt,
                "lifecycle-topic", "key-" + suffix, payload);
        store.enqueueLifecycle(messageId, "alert.updated", "other-tenant", "case", "other-object",
                Instant.now(), "other-topic", "other-key", "{\"version\":2}");

        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM lifecycle_outbox WHERE message_id = ?", Integer.class, messageId));
        Map<String, Object> inserted = jdbc.queryForMap("""
                SELECT event_type, tenant_id, object_type, object_id, topic, message_key, payload_json
                FROM lifecycle_outbox WHERE message_id = ?
                """, messageId);
        assertEquals("alert.created", inserted.get("event_type"));
        assertEquals(tenantId, inserted.get("tenant_id"));
        assertEquals("alert", inserted.get("object_type"));
        assertEquals(objectId, inserted.get("object_id"));
        assertEquals("lifecycle-topic", inserted.get("topic"));
        assertEquals("key-" + suffix, inserted.get("message_key"));
        assertEquals(payload, inserted.get("payload_json"));

        List<Map<String, Object>> firstClaim = store.claimLifecycleBatch("lifecycle-owner-a",
                Instant.now().plusSeconds(60), 10);
        Map<String, Object> claimed = firstClaim.stream()
                .filter(row -> messageId.equals(row.get("messageId"))).findFirst().orElseThrow();
        assertEquals("alert.created", claimed.get("eventType"));
        assertEquals(tenantId, claimed.get("tenantId"));
        assertEquals("alert", claimed.get("objectType"));
        assertEquals(objectId, claimed.get("objectId"));
        assertEquals(occurredAt, claimed.get("occurredAt"));
        assertEquals("lifecycle-topic", claimed.get("topic"));
        assertEquals("key-" + suffix, claimed.get("messageKey"));
        assertEquals(payload, claimed.get("payload"));
        assertEquals(1, ((Number) claimed.get("attempts")).intValue());
        assertEquals("in_flight", jdbc.queryForObject(
                "SELECT status FROM lifecycle_outbox WHERE message_id = ?", String.class, messageId));

        assertFalse(store.completeLifecycle(messageId, "lifecycle-wrong-owner", true, null, Instant.now()));
        Map<String, Object> stillInFlight = jdbc.queryForMap(
                "SELECT status, lease_owner FROM lifecycle_outbox WHERE message_id = ?", messageId);
        assertEquals("in_flight", stillInFlight.get("status"));
        assertEquals("lifecycle-owner-a", stillInFlight.get("lease_owner"));

        Instant retryAt = Instant.now().plusSeconds(60);
        assertTrue(store.completeLifecycle(messageId, "lifecycle-owner-a", false,
                "temporary publish failure", retryAt));
        Map<String, Object> failed = jdbc.queryForMap("""
                SELECT status, available_at, locked_until, lease_owner, last_error
                FROM lifecycle_outbox WHERE message_id = ?
                """, messageId);
        assertEquals("failed", failed.get("status"));
        assertNull(failed.get("locked_until"));
        assertNull(failed.get("lease_owner"));
        assertEquals("temporary publish failure", failed.get("last_error"));
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM lifecycle_outbox
                WHERE message_id = ? AND available_at > CURRENT_TIMESTAMP + INTERVAL '50' SECOND
                """, Integer.class, messageId));

        assertTrue(store.claimLifecycleBatch("lifecycle-early-owner", Instant.now().plusSeconds(60), 10)
                .stream().noneMatch(row -> messageId.equals(row.get("messageId"))));

        jdbc.update("UPDATE lifecycle_outbox SET available_at = CURRENT_TIMESTAMP - INTERVAL '1' SECOND "
                + "WHERE message_id = ?", messageId);
        Map<String, Object> retryClaim = store.claimLifecycleBatch("lifecycle-owner-b",
                Instant.now().plusSeconds(60), 10).stream()
                .filter(row -> messageId.equals(row.get("messageId"))).findFirst().orElseThrow();
        assertEquals(2, ((Number) retryClaim.get("attempts")).intValue());
        assertTrue(store.completeLifecycle(messageId, "lifecycle-owner-b", true,
                "ignored on success", Instant.now()));
        Map<String, Object> succeeded = jdbc.queryForMap("""
                SELECT status, locked_until, lease_owner, last_error
                FROM lifecycle_outbox WHERE message_id = ?
                """, messageId);
        assertEquals("succeeded", succeeded.get("status"));
        assertNull(succeeded.get("locked_until"));
        assertNull(succeeded.get("lease_owner"));
        assertNull(succeeded.get("last_error"));

        String expiredMessageId = "lifecycle-expired-" + suffix;
        store.enqueueLifecycle(expiredMessageId, "case.created", tenantId, "case", "case-" + suffix,
                occurredAt, "lifecycle-topic", "expired-key-" + suffix, "{\"case\":{}}");
        Map<String, Object> expiredInitial = store.claimLifecycleBatch("lifecycle-expired-owner",
                Instant.now().plusSeconds(60), 10).stream()
                .filter(row -> expiredMessageId.equals(row.get("messageId"))).findFirst().orElseThrow();
        assertEquals(1, ((Number) expiredInitial.get("attempts")).intValue());
        jdbc.update("UPDATE lifecycle_outbox SET locked_until = CURRENT_TIMESTAMP - INTERVAL '1' SECOND "
                + "WHERE message_id = ?", expiredMessageId);

        Map<String, Object> reclaimed = store.claimLifecycleBatch("lifecycle-new-owner",
                Instant.now().plusSeconds(60), 10).stream()
                .filter(row -> expiredMessageId.equals(row.get("messageId"))).findFirst().orElseThrow();
        assertEquals(2, ((Number) reclaimed.get("attempts")).intValue());
        assertTrue(store.completeLifecycle(expiredMessageId, "lifecycle-new-owner", true,
                null, Instant.now()));
    }
}
