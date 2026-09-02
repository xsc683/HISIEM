package com.xscsiem.hsiem_platform.control;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.xscsiem.hsiem_platform.auth.AuthUser;
import com.xscsiem.hsiem_platform.onboarding.NotFoundException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** PostgreSQL 控制面实现。所有跨表案件操作都在同一事务中完成。 */
@Repository
@DependsOn("flyway")
public class JdbcControlPlaneStore implements ControlPlaneStore {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};
    private static final TypeReference<List<Map<String, Object>>> MAP_LIST = new TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final boolean h2;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public JdbcControlPlaneStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.h2 = Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>) connection ->
                "H2".equals(connection.getMetaData().getDatabaseProductName())));
    }

    @Override
    public List<AuthUser> listUsers() {
        return jdbc.query("""
                        SELECT id, username, password_hash, password_change_required, role_name, status, created_at
                        FROM users ORDER BY username
                        """,
                (rs, rowNum) -> {
                    AuthUser u = new AuthUser();
                    u.id = rs.getString("id");
                    u.username = rs.getString("username");
                    u.passwordHash = rs.getString("password_hash");
                    u.passwordChangeRequired = rs.getBoolean("password_change_required");
                    u.role = rs.getString("role_name");
                    u.status = rs.getString("status");
                    u.createdAt = rs.getTimestamp("created_at").toInstant().toString();
                    return u;
                });
    }

    @Override
    public AuthUser findUser(String username) {
        List<AuthUser> users = jdbc.query("""
                        SELECT id, username, password_hash, password_change_required, role_name, status, created_at
                        FROM users WHERE username = ?
                        """, (rs, rowNum) -> {
                    AuthUser u = new AuthUser();
                    u.id = rs.getString("id");
                    u.username = rs.getString("username");
                    u.passwordHash = rs.getString("password_hash");
                    u.passwordChangeRequired = rs.getBoolean("password_change_required");
                    u.role = rs.getString("role_name");
                    u.status = rs.getString("status");
                    u.createdAt = rs.getTimestamp("created_at").toInstant().toString();
                    return u;
                }, username);
        return users.isEmpty() ? null : users.get(0);
    }

    @Override
    public void insertUser(AuthUser user) {
        jdbc.update("""
                        INSERT INTO users(id, username, password_hash, password_change_required, role_name, status, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                        """, user.id, user.username, user.passwordHash, user.passwordChangeRequired,
                user.role, user.status == null ? "active" : user.status, timestamp(user.createdAt));
    }

    @Override
    public void updateUser(AuthUser user) {
        int changed = jdbc.update("""
                        UPDATE users SET role_name = ?, status = ?, password_hash = ?, password_change_required = ?, updated_at = CURRENT_TIMESTAMP
                        WHERE username = ?
                        """, user.role, user.status, user.passwordHash, user.passwordChangeRequired, user.username);
        if (changed == 0) {
            throw new NotFoundException("用户不存在: " + user.username);
        }
    }

    @Override
    public void deleteUser(String username) {
        int changed = jdbc.update("DELETE FROM users WHERE username = ?", username);
        if (changed == 0) {
            throw new NotFoundException("用户不存在: " + username);
        }
    }

    @Override
    public String findSessionUsername(String tokenHash, Instant now) {
        List<String> usernames = jdbc.query("""
                        SELECT username FROM auth_sessions
                        WHERE token_hash = ? AND expires_at > ?
                        """, (rs, rowNum) -> rs.getString("username"), tokenHash, timestamp(now.toString()));
        if (usernames.isEmpty()) {
            jdbc.update("DELETE FROM auth_sessions WHERE token_hash = ? OR expires_at <= ?",
                    tokenHash, timestamp(now.toString()));
            return null;
        }
        jdbc.update("UPDATE auth_sessions SET last_seen_at = CURRENT_TIMESTAMP WHERE token_hash = ?", tokenHash);
        return usernames.get(0);
    }

    @Override
    public void createSession(String tokenHash, String username, Instant expiresAt) {
        jdbc.update("""
                        INSERT INTO auth_sessions(token_hash, username, expires_at)
                        VALUES (?, ?, ?)
                        """, tokenHash, username, timestamp(expiresAt.toString()));
    }

    @Override
    public void deleteSession(String tokenHash) {
        jdbc.update("DELETE FROM auth_sessions WHERE token_hash = ?", tokenHash);
    }

    @Override
    public void deleteSessionsForUser(String username) {
        jdbc.update("DELETE FROM auth_sessions WHERE username = ?", username);
    }

    @Override
    public void cleanupExpiredSessions(Instant now) {
        jdbc.update("DELETE FROM auth_sessions WHERE expires_at <= ?", timestamp(now.toString()));
    }

    @Override
    public boolean isLoginBlocked(String username, Instant now) {
        Integer count = jdbc.queryForObject("""
                        SELECT COUNT(*) FROM login_attempts
                        WHERE username = ? AND locked_until > ?
                        """, Integer.class, username, timestamp(now.toString()));
        return count != null && count > 0;
    }

    @Override
    @Transactional
    public void recordLoginFailure(String username, Instant now, int maxFailures,
                                   Duration window, Duration lockout) {
        List<Map<String, Object>> rows = jdbc.query("""
                        SELECT failure_count, first_failure_at FROM login_attempts
                        WHERE username = ? FOR UPDATE
                        """, (rs, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("failureCount", rs.getInt("failure_count"));
                    row.put("firstFailureAt", rs.getTimestamp("first_failure_at").toInstant());
                    return row;
                }, username);
        if (rows.isEmpty()) {
            jdbc.update("""
                            INSERT INTO login_attempts(username, failure_count, first_failure_at, locked_until)
                            VALUES (?, 1, ?, NULL)
                            """, username, timestamp(now.toString()));
            return;
        }
        Map<String, Object> row = rows.get(0);
        Instant first = (Instant) row.get("firstFailureAt");
        int failures = first.isBefore(now.minus(window)) ? 1 : ((Number) row.get("failureCount")).intValue() + 1;
        Instant firstFailure = first.isBefore(now.minus(window)) ? now : first;
        Instant lockedUntil = failures >= maxFailures ? now.plus(lockout) : null;
        jdbc.update("""
                        UPDATE login_attempts SET failure_count = ?, first_failure_at = ?,
                            locked_until = ?, updated_at = CURRENT_TIMESTAMP WHERE username = ?
                        """, failures, timestamp(firstFailure.toString()),
                lockedUntil == null ? null : timestamp(lockedUntil.toString()), username);
    }

    @Override
    public void resetLoginFailures(String username) {
        jdbc.update("DELETE FROM login_attempts WHERE username = ?", username);
    }

    @Override
    public List<Map<String, Object>> listRoles() {
        return jdbc.query("SELECT name, description, permissions_json FROM roles ORDER BY name", (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", rs.getString("name"));
            row.put("description", rs.getString("description"));
            row.put("permissions", readStringList(rs.getString("permissions_json")));
            return row;
        });
    }

    @Override
    public void audit(String actor, String action, String target) {
        jdbc.update("INSERT INTO audit_logs(actor, action, target) VALUES (?, ?, ?)",
                actor == null ? "system" : actor, action, target);
    }

    @Override
    public List<Map<String, String>> listAuditLogs() {
        return jdbc.query("""
                        SELECT actor, action, target, created_at
                        FROM audit_logs ORDER BY created_at DESC, id DESC LIMIT 500
                        """, (rs, rowNum) -> {
                    Map<String, String> row = new LinkedHashMap<>();
                    row.put("timestamp", rs.getTimestamp("created_at").toInstant().toString());
                    row.put("actor", rs.getString("actor"));
                    row.put("action", rs.getString("action"));
                    row.put("target", rs.getString("target"));
                    return row;
                });
    }

    @Override
    public List<Map<String, Object>> listNotifications(Boolean unread) {
        String sql = "SELECT id, type, target, message, is_read, created_at FROM notifications";
        if (Boolean.TRUE.equals(unread)) {
            sql += " WHERE is_read = FALSE";
        }
        sql += " ORDER BY created_at DESC";
        return jdbc.query(sql, (rs, rowNum) -> notificationRow(rs.getString("id"), rs.getString("type"),
                rs.getString("target"), rs.getString("message"), rs.getBoolean("is_read"),
                rs.getTimestamp("created_at").toInstant()));
    }

    @Override
    @Transactional
    public boolean createNotificationIfAllowed(String type, String target, String message, Instant notBefore) {
        Integer existing = jdbc.queryForObject("""
                        SELECT COUNT(*) FROM notifications
                        WHERE type = ? AND target = ? AND created_at >= ?
                        """, Integer.class, type, target, timestamp(notBefore.toString()));
        if (existing != null && existing > 0) {
            return false;
        }
        jdbc.update("""
                        INSERT INTO notifications(id, type, target, message, is_read, created_at)
                        VALUES (?, ?, ?, ?, FALSE, CURRENT_TIMESTAMP)
                        """, "ntf-" + UUID.randomUUID(), type, target, message);
        return true;
    }

    @Override
    public boolean markNotificationRead(String id) {
        return jdbc.update("UPDATE notifications SET is_read = TRUE WHERE id = ?", id) > 0;
    }

    @Override
    public void markAllNotificationsRead() {
        jdbc.update("UPDATE notifications SET is_read = TRUE WHERE is_read = FALSE");
    }

    @Override
    public boolean deleteNotification(String id) {
        return jdbc.update("DELETE FROM notifications WHERE id = ?", id) > 0;
    }

    @Override
    public int deleteReadNotificationsBefore(Instant before) {
        return jdbc.update("DELETE FROM notifications WHERE is_read = TRUE AND created_at < ?",
                timestamp(before.toString()));
    }

    @Override
    public List<Map<String, Object>> listCases(String status, String entity, int size) {
        String sql = "SELECT id FROM cases";
        List<Object> args = new ArrayList<>();
        if (status != null && !status.isBlank()) {
            sql += " WHERE status = ?";
            args.add(status);
        }
        sql += " ORDER BY updated_at DESC LIMIT ?";
        args.add(Math.min(Math.max(size, 1), 200));
        List<Map<String, Object>> rows = jdbc.query(sql, (rs, rowNum) -> findCase(rs.getString("id")), args.toArray());
        if (entity == null || entity.isBlank()) {
            return rows;
        }
        String[] parts = entity.split(":", 2);
        String type = parts.length > 1 ? parts[0] : "ip";
        String value = parts.length > 1 ? parts[1] : parts[0];
        return rows.stream().filter(row -> entitiesContain(row.get("entities"), type, value)).toList();
    }

    @Override
    public Map<String, Long> caseStatusCounts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT status, COUNT(*) AS total FROM cases GROUP BY status")) {
            Object total = row.get("total");
            if (row.get("status") != null && total instanceof Number number) {
                counts.put(String.valueOf(row.get("status")), number.longValue());
            }
        }
        return counts;
    }

    @Override
    public Map<String, Object> findCase(String id) {
        List<Map<String, Object>> rows = jdbc.query("""
                        SELECT id, title, status, aggregation, operator, owner, verdict,
                               created_at, updated_at, closed_at, alert_ids_json, entities_json, evidence_json,
                               collaborators_json, version
                        FROM cases WHERE id = ?
                        """, (rs, rowNum) -> caseRow(rs, id), id);
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> row = rows.get(0);
        List<String> relationIds = jdbc.query("""
                        SELECT alert_id FROM case_alerts WHERE case_id = ? ORDER BY created_at, alert_id
                        """, (rs, rowNum) -> rs.getString("alert_id"), id);
        if (!relationIds.isEmpty()) {
            row.put("alert_ids", relationIds);
        }
        return row;
    }

    @Override
    @Transactional
    public void createCase(Map<String, Object> document, List<String> alertIds) {
        jdbc.update("""
                        INSERT INTO cases(id, title, status, aggregation, operator, owner, verdict,
                            created_at, updated_at, closed_at, alert_ids_json, entities_json, evidence_json,
                            collaborators_json, version)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
                        """, value(document, "case.id"), value(document, "case.title"),
                valueOr(document, "case.status", "open"), valueOr(document, "case.aggregation", "manual"),
                valueOr(document, "case.operator", "anonymous"), value(document, "case.owner"), value(document, "case.verdict"),
                timestamp(value(document, "case.created_at")), timestamp(value(document, "case.updated_at")),
                timestamp(value(document, "case.closed_at")), writeJson(alertIds),
                writeJson(document.getOrDefault("entities", List.of())),
                writeJson(document.getOrDefault("evidence", List.of())),
                writeJson(document.getOrDefault("case.collaborators", List.of())));
        insertRelations(value(document, "case.id"), alertIds);
        enqueueCaseMirror(value(document, "case.id"), "upsert", document);
    }

    @Override
    @Transactional
    public Map<String, Object> importCaseDocument(Map<String, Object> document) {
        String id = value(document, "case.id");
        Map<String, Object> existing = findCase(id);
        if (existing != null) {
            return existing;
        }
        List<String> ids = stringList(document.get("alert_ids"));
        try {
            createCase(document, ids);
        } catch (RuntimeException e) {
            // 老 ES 案件可能与已导入案件共享告警;不覆盖关系库已有事实。
            if (findCase(id) == null) {
                throw e;
            }
        }
        return findCase(id);
    }

    @Override
    @Transactional
    public Map<String, Object> updateCase(String id, long expectedVersion,
                                           Map<String, Object> document, List<String> alertIds) {
        Map<String, Object> current = findCase(id);
        if (current == null) {
            throw new NotFoundException("案件不存在: " + id);
        }
        String title = valueOr(document, "case.title", value(current, "case.title"));
        String status = valueOr(document, "case.status", value(current, "case.status"));
        String aggregation = valueOr(document, "case.aggregation", value(current, "case.aggregation"));
        String operator = valueOr(document, "case.operator", value(current, "case.operator"));
        String owner = valueOr(document, "case.owner", value(current, "case.owner"));
        String verdict = document.containsKey("case.verdict") ? value(document, "case.verdict") : value(current, "case.verdict");
        String createdAt = valueOr(document, "case.created_at", value(current, "case.created_at"));
        String updatedAt = valueOr(document, "case.updated_at", value(current, "case.updated_at"));
        String closedAt = document.containsKey("case.closed_at") ? value(document, "case.closed_at") : value(current, "case.closed_at");
        List<String> finalAlertIds = alertIds == null ? stringList(current.get("alert_ids")) : alertIds;
        Object entities = document.containsKey("entities") ? document.get("entities") : current.get("entities");
        Object evidence = document.containsKey("evidence") ? document.get("evidence") : current.get("evidence");
        Object collaborators = document.containsKey("case.collaborators")
                ? document.get("case.collaborators") : current.get("case.collaborators");
        int changed = jdbc.update("""
                        UPDATE cases SET title = ?, status = ?, aggregation = ?, operator = ?, owner = ?, verdict = ?,
                            created_at = ?, updated_at = ?, closed_at = ?, alert_ids_json = ?, entities_json = ?,
                            evidence_json = ?, collaborators_json = ?,
                            version = version + 1
                        WHERE id = ? AND version = ?
                        """, title, status, aggregation, operator, owner, verdict, timestamp(createdAt), timestamp(updatedAt),
                timestamp(closedAt), writeJson(finalAlertIds), writeJson(entities), writeJson(evidence),
                writeJson(collaborators), id, expectedVersion);
        if (changed == 0) {
            throw new IllegalStateException("案件已被其他请求更新,请刷新后重试: " + id);
        }
        jdbc.update("DELETE FROM case_alerts WHERE case_id = ?", id);
        insertRelations(id, finalAlertIds);
        Map<String, Object> updated = findCase(id);
        enqueueCaseMirror(id, "upsert", updated);
        return updated;
    }

    @Override
    @Transactional
    public boolean deleteCase(String id) {
        int changed = jdbc.update("DELETE FROM cases WHERE id = ?", id);
        if (changed > 0) {
            enqueueCaseMirror(id, "delete", null);
        }
        return changed > 0;
    }

    @Override
    public void enqueueCaseMirror(String caseId, String operation, Map<String, Object> document) {
        String payload = document == null ? null : writeJson(document);
        // 同一案件的连续更新只保留最后一个待处理 upsert；delete 不可被后续旧 upsert 覆盖。
        if ("upsert".equals(operation)) {
            jdbc.update("""
                    UPDATE case_mirror_outbox SET payload_json = ?, status = 'pending',
                        available_at = CURRENT_TIMESTAMP, locked_until = NULL, lease_owner = NULL,
                        last_error = NULL, updated_at = CURRENT_TIMESTAMP
                    WHERE case_id = ? AND operation = 'upsert' AND status IN ('pending', 'failed')
                    """, payload, caseId);
            Integer pending = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM case_mirror_outbox
                    WHERE case_id = ? AND operation = 'upsert' AND status = 'pending'
                    """, Integer.class, caseId);
            if (pending != null && pending > 0) return;
        }
        jdbc.update("""
                INSERT INTO case_mirror_outbox(case_id, operation, payload_json)
                VALUES (?, ?, ?)
                """, caseId, operation, payload);
    }

    @Override
    @Transactional
    public List<Map<String, Object>> claimCaseMirrorBatch(String owner, Instant leaseUntil, int size) {
        int limit = Math.min(Math.max(size, 1), 100);
        List<Map<String, Object>> rows = jdbc.query("""
                SELECT id, case_id, operation, payload_json, attempts
                FROM case_mirror_outbox
                WHERE status IN ('pending', 'failed')
                  AND available_at <= CURRENT_TIMESTAMP
                  AND (locked_until IS NULL OR locked_until < CURRENT_TIMESTAMP)
                ORDER BY id
                LIMIT ? FOR UPDATE
                """, (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", rs.getLong("id"));
            row.put("caseId", rs.getString("case_id"));
            row.put("operation", rs.getString("operation"));
            row.put("payload", rs.getString("payload_json"));
            row.put("attempts", rs.getInt("attempts"));
            return row;
        }, limit);
        for (Map<String, Object> row : rows) {
            jdbc.update("""
                    UPDATE case_mirror_outbox SET status = 'in_flight', lease_owner = ?,
                        locked_until = ?, attempts = attempts + 1, updated_at = CURRENT_TIMESTAMP
                    WHERE id = ?
                    """, owner, timestamp(leaseUntil.toString()), row.get("id"));
        }
        return rows;
    }

    @Override
    public void completeCaseMirror(long id, String owner, boolean success, String error, Instant nextAttemptAt) {
        if (success) {
            jdbc.update("""
                    UPDATE case_mirror_outbox SET status = 'succeeded', lease_owner = NULL,
                        locked_until = NULL, last_error = NULL, updated_at = CURRENT_TIMESTAMP
                    WHERE id = ? AND lease_owner = ?
                    """, id, owner);
        } else {
            jdbc.update("""
                    UPDATE case_mirror_outbox SET status = 'failed', lease_owner = NULL,
                        locked_until = NULL, last_error = ?, available_at = ?, updated_at = CURRENT_TIMESTAMP
                    WHERE id = ? AND lease_owner = ?
                    """, error, timestamp(nextAttemptAt.toString()), id, owner);
        }
    }

    @Override
    public void enqueueLifecycle(String messageId, String eventType, String tenantId, String objectType,
                                 String objectId, Instant occurredAt, String topic, String messageKey, String payload) {
        if (h2) {
            jdbc.update("""
                    INSERT INTO lifecycle_outbox(message_id, event_type, tenant_id, object_type, object_id,
                        occurred_at, topic, message_key, payload_json)
                    SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?
                    WHERE NOT EXISTS (SELECT 1 FROM lifecycle_outbox WHERE message_id = ?)
                    """, messageId, eventType, tenantId, objectType, objectId,
                    timestamp(occurredAt.toString()), topic, messageKey, payload, messageId);
            return;
        }
        jdbc.update("""
                INSERT INTO lifecycle_outbox(message_id, event_type, tenant_id, object_type, object_id,
                    occurred_at, topic, message_key, payload_json)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (message_id) DO NOTHING
                """, messageId, eventType, tenantId, objectType, objectId, timestamp(occurredAt.toString()),
                topic, messageKey, payload);
    }

    @Override
    @Transactional
    public List<Map<String, Object>> claimLifecycleBatch(String owner, Instant leaseUntil, int size) {
        int limit = Math.min(Math.max(size, 1), 100);
        List<Map<String, Object>> rows = jdbc.query("""
                SELECT message_id, event_type, tenant_id, object_type, object_id, occurred_at,
                       topic, message_key, payload_json, attempts
                FROM lifecycle_outbox
                WHERE (status IN ('pending', 'failed') AND available_at <= CURRENT_TIMESTAMP)
                   OR (status = 'in_flight' AND locked_until < CURRENT_TIMESTAMP)
                ORDER BY available_at, created_at
                LIMIT ? FOR UPDATE SKIP LOCKED
                """, (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("messageId", rs.getString("message_id"));
            row.put("eventType", rs.getString("event_type"));
            row.put("tenantId", rs.getString("tenant_id"));
            row.put("objectType", rs.getString("object_type"));
            row.put("objectId", rs.getString("object_id"));
            row.put("occurredAt", rs.getTimestamp("occurred_at").toInstant());
            row.put("topic", rs.getString("topic"));
            row.put("messageKey", rs.getString("message_key"));
            row.put("payload", rs.getString("payload_json"));
            row.put("attempts", rs.getInt("attempts"));
            return row;
        }, limit);
        for (Map<String, Object> row : rows) {
            int changed = jdbc.update("""
                    UPDATE lifecycle_outbox SET status = 'in_flight', lease_owner = ?,
                        locked_until = ?, attempts = attempts + 1, updated_at = CURRENT_TIMESTAMP
                    WHERE message_id = ?
                    """, owner, timestamp(leaseUntil.toString()), row.get("messageId"));
            if (changed == 1) {
                row.put("attempts", ((Number) row.get("attempts")).intValue() + 1);
            }
        }
        return rows;
    }

    @Override
    public boolean completeLifecycle(String messageId, String owner, boolean success, String error,
                                     Instant nextAttemptAt) {
        int changed;
        if (success) {
            changed = jdbc.update("""
                    UPDATE lifecycle_outbox SET status = 'succeeded', lease_owner = NULL,
                        locked_until = NULL, last_error = NULL, updated_at = CURRENT_TIMESTAMP
                    WHERE message_id = ? AND status = 'in_flight' AND lease_owner = ?
                    """, messageId, owner);
        } else {
            changed = jdbc.update("""
                    UPDATE lifecycle_outbox SET status = 'failed', available_at = ?,
                        lease_owner = NULL, locked_until = NULL, last_error = ?, updated_at = CURRENT_TIMESTAMP
                    WHERE message_id = ? AND status = 'in_flight' AND lease_owner = ?
                    """, timestamp(nextAttemptAt.toString()), error, messageId, owner);
        }
        return changed == 1;
    }

    @Override
    public boolean hasAlert(String alertId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM case_alerts WHERE alert_id = ?", Integer.class, alertId);
        return count != null && count > 0;
    }

    @Override
    public String createTask(String type, String resourceId, String message) {
        String id = "task-" + UUID.randomUUID();
        jdbc.update("""
                        INSERT INTO background_tasks(id, task_type, resource_id, status, progress, message)
                        VALUES (?, ?, ?, 'queued', 0, ?)
                        """, id, type, resourceId, message);
        return id;
    }

    @Override
    public void updateTask(String id, String status, int progress, String message, String error) {
        jdbc.update("""
                        UPDATE background_tasks SET status = ?, progress = ?, message = ?, error = ?,
                            started_at = CASE WHEN ? = 'running' AND started_at IS NULL THEN CURRENT_TIMESTAMP ELSE started_at END,
                            finished_at = CASE WHEN ? IN ('succeeded', 'failed', 'cancelled') THEN CURRENT_TIMESTAMP ELSE finished_at END,
                            lease_owner = CASE WHEN ? IN ('succeeded', 'failed', 'cancelled') THEN NULL ELSE lease_owner END,
                            lease_until = CASE WHEN ? IN ('succeeded', 'failed', 'cancelled') THEN NULL ELSE lease_until END,
                            updated_at = CURRENT_TIMESTAMP WHERE id = ?
                        """, status, Math.max(0, Math.min(progress, 100)), message, error, status, status,
                status, status, id);
    }

    @Override
    public boolean claimTask(String id, String owner, Instant leaseUntil) {
        return jdbc.update("""
                UPDATE background_tasks SET status = 'running', lease_owner = ?, lease_until = ?,
                    heartbeat_at = CURRENT_TIMESTAMP, attempts = attempts + 1,
                    started_at = COALESCE(started_at, CURRENT_TIMESTAMP), updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status IN ('queued', 'running')
                  AND (lease_until IS NULL OR lease_until < CURRENT_TIMESTAMP)
                """, owner, timestamp(leaseUntil.toString()), id) > 0;
    }

    @Override
    public boolean heartbeatTask(String id, String owner, Instant leaseUntil, int progress, String message) {
        return jdbc.update("""
                UPDATE background_tasks SET status = 'running', progress = ?, message = ?,
                    lease_until = ?, heartbeat_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status = 'running' AND lease_owner = ?
                """, Math.max(0, Math.min(progress, 100)), message, timestamp(leaseUntil.toString()), id, owner) > 0;
    }

    @Override
    public Map<String, Object> findTask(String id) {
        List<Map<String, Object>> rows = jdbc.query("""
                        SELECT id, task_type, resource_id, status, progress, message, error,
                               started_at, finished_at, created_at, updated_at, attempts, max_attempts,
                               lease_owner, lease_until, heartbeat_at
                        FROM background_tasks WHERE id = ?
                        """, (rs, rowNum) -> taskRow(rs), id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public List<Map<String, Object>> listTasks(int size) {
        return jdbc.query("""
                        SELECT id, task_type, resource_id, status, progress, message, error,
                               started_at, finished_at, created_at, updated_at, attempts, max_attempts,
                               lease_owner, lease_until, heartbeat_at
                        FROM background_tasks ORDER BY updated_at DESC LIMIT ?
                        """, (rs, rowNum) -> taskRow(rs), Math.min(Math.max(size, 1), 200));
    }

    @Override
    public int recoverStaleTasks(Instant cutoff, String errorMessage) {
        return jdbc.update("""
                        UPDATE background_tasks
                        SET status = 'failed', progress = 100,
                            message = '任务未完成，已由服务恢复器收敛',
                            error = ?, finished_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP,
                            lease_owner = NULL, lease_until = NULL
                        WHERE status IN ('queued', 'running') AND updated_at < ?
                        """, errorMessage, timestamp(cutoff.toString()));
    }

    private Map<String, Object> taskRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", rs.getString("id"));
        row.put("type", rs.getString("task_type"));
        row.put("resourceId", rs.getString("resource_id"));
        row.put("status", rs.getString("status"));
        row.put("progress", rs.getInt("progress"));
        row.put("message", rs.getString("message"));
        row.put("error", rs.getString("error"));
        row.put("startedAt", instant(rs.getTimestamp("started_at")));
        row.put("finishedAt", instant(rs.getTimestamp("finished_at")));
        row.put("createdAt", instant(rs.getTimestamp("created_at")));
        row.put("updatedAt", instant(rs.getTimestamp("updated_at")));
        row.put("attempts", rs.getInt("attempts"));
        row.put("maxAttempts", rs.getInt("max_attempts"));
        row.put("leaseOwner", rs.getString("lease_owner"));
        row.put("leaseUntil", instant(rs.getTimestamp("lease_until")));
        row.put("heartbeatAt", instant(rs.getTimestamp("heartbeat_at")));
        return row;
    }

    private void insertRelations(String caseId, List<String> alertIds) {
        for (String alertId : alertIds.stream().distinct().toList()) {
            jdbc.update("INSERT INTO case_alerts(case_id, alert_id) VALUES (?, ?)", caseId, alertId);
        }
    }

    private Map<String, Object> caseRow(java.sql.ResultSet rs, String id) throws java.sql.SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("_id", id);
        row.put("case.id", rs.getString("id"));
        row.put("case.title", rs.getString("title"));
        row.put("case.status", rs.getString("status"));
        row.put("case.aggregation", rs.getString("aggregation"));
        row.put("case.operator", rs.getString("operator"));
        row.put("case.owner", rs.getString("owner"));
        row.put("case.verdict", rs.getString("verdict"));
        row.put("case.created_at", instant(rs.getTimestamp("created_at")));
        row.put("case.updated_at", instant(rs.getTimestamp("updated_at")));
        row.put("case.closed_at", instant(rs.getTimestamp("closed_at")));
        row.put("alert_ids", readStringList(rs.getString("alert_ids_json")));
        row.put("entities", readMapList(rs.getString("entities_json")));
        row.put("evidence", readMapList(rs.getString("evidence_json")));
        row.put("case.collaborators", readStringList(rs.getString("collaborators_json")));
        row.put("_control_version", rs.getLong("version"));
        return row;
    }

    private Map<String, Object> notificationRow(String id, String type, String target, String message,
                                                 boolean read, Instant timestamp) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("type", type);
        row.put("target", target);
        row.put("message", message);
        row.put("timestamp", timestamp.toString());
        row.put("read", read);
        return row;
    }

    private boolean entitiesContain(Object raw, String type, String value) {
        if (!(raw instanceof List<?> list)) {
            return false;
        }
        return list.stream().anyMatch(item -> item instanceof Map<?, ?> m
                && type.equals(String.valueOf(m.get("type")))
                && value.equals(String.valueOf(m.get("value"))));
    }

    private String writeJson(Object value) {
        try {
            return mapper.writeValueAsString(value == null ? List.of() : value);
        } catch (Exception e) {
            throw new IllegalStateException("控制面 JSON 序列化失败", e);
        }
    }

    private List<String> readStringList(String value) {
        try {
            return value == null || value.isBlank() ? new ArrayList<>() : mapper.readValue(value, STRING_LIST);
        } catch (Exception e) {
            throw new IllegalStateException("控制面字符串列表解析失败", e);
        }
    }

    private List<Map<String, Object>> readMapList(String value) {
        try {
            return value == null || value.isBlank() ? new ArrayList<>() : mapper.readValue(value, MAP_LIST);
        } catch (Exception e) {
            throw new IllegalStateException("控制面实体列表解析失败", e);
        }
    }

    private List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return readStringList(value == null ? null : String.valueOf(value));
    }

    private static String value(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static String valueOr(Map<String, Object> map, String key, String fallback) {
        String value = value(map, key);
        return value == null ? fallback : value;
    }

    private static Timestamp timestamp(String value) {
        return value == null ? null : Timestamp.from(Instant.parse(value));
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
