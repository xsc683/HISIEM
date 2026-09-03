package com.xscsiem.hsiem_platform.control;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.xscsiem.hsiem_platform.auth.AuthUser;
import com.xscsiem.hsiem_platform.onboarding.NotFoundException;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * MyBatis 控制面实现。所有跨表案件操作仍在同一事务中完成;内部使用 typed 持久化行, 在接口边界处映射回 {@link ControlPlaneStore} 的 Map 契约。
 */
@Repository
@DependsOn("flyway")
public class MyBatisControlPlaneStore implements ControlPlaneStore {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};
    private static final TypeReference<List<Map<String, Object>>> MAP_LIST =
            new TypeReference<>() {};

    private final boolean h2;
    private final ObjectMapper mapper =
            new ObjectMapper()
                    .findAndRegisterModules()
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final UserAuthMapper userAuth;
    private final RoleAuditMapper roleAudit;
    private final NotificationMapper notification;
    private final CaseMapper caseMapper;
    private final CaseMirrorOutboxMapper caseMirrorOutbox;
    private final TaskMapper task;
    private final LifecycleOutboxMapper lifecycleOutbox;

    public MyBatisControlPlaneStore(
            DataSource dataSource,
            UserAuthMapper userAuth,
            RoleAuditMapper roleAudit,
            NotificationMapper notification,
            CaseMapper caseMapper,
            CaseMirrorOutboxMapper caseMirrorOutbox,
            TaskMapper task,
            LifecycleOutboxMapper lifecycleOutbox) {
        this.h2 = isH2(dataSource);
        this.userAuth = userAuth;
        this.roleAudit = roleAudit;
        this.notification = notification;
        this.caseMapper = caseMapper;
        this.caseMirrorOutbox = caseMirrorOutbox;
        this.task = task;
        this.lifecycleOutbox = lifecycleOutbox;
    }

    private static boolean isH2(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            return "H2".equals(connection.getMetaData().getDatabaseProductName());
        } catch (Exception e) {
            throw new IllegalStateException("无法探测控制面数据库方言", e);
        }
    }

    @Override
    public List<AuthUser> listUsers() {
        return userAuth.selectUsers().stream().map(this::toUser).toList();
    }

    @Override
    public AuthUser findUser(String username) {
        ControlPlaneRow.UserRow row = userAuth.selectUser(username);
        return row == null ? null : toUser(row);
    }

    private AuthUser toUser(ControlPlaneRow.UserRow row) {
        AuthUser u = new AuthUser();
        u.id = row.id();
        u.username = row.username();
        u.passwordHash = row.passwordHash();
        u.passwordChangeRequired = row.passwordChangeRequired();
        u.role = row.role();
        u.status = row.status();
        u.createdAt = row.createdAt() == null ? null : row.createdAt().toString();
        return u;
    }

    @Override
    public void insertUser(AuthUser user) {
        userAuth.insertUser(
                user.id,
                user.username,
                user.passwordHash,
                user.passwordChangeRequired,
                user.role,
                user.status == null ? "active" : user.status,
                parseInstant(user.createdAt));
    }

    @Override
    public void updateUser(AuthUser user) {
        int changed =
                userAuth.updateUser(
                        user.username,
                        user.passwordHash,
                        user.passwordChangeRequired,
                        user.role,
                        user.status);
        if (changed == 0) {
            throw new NotFoundException("用户不存在: " + user.username);
        }
    }

    @Override
    public void deleteUser(String username) {
        int changed = userAuth.deleteUser(username);
        if (changed == 0) {
            throw new NotFoundException("用户不存在: " + username);
        }
    }

    @Override
    public String findSessionUsername(String tokenHash, Instant now) {
        String username = userAuth.selectSessionUsername(tokenHash, now);
        if (username == null) {
            userAuth.deleteExpiredSession(tokenHash, now);
            return null;
        }
        userAuth.touchSession(tokenHash);
        return username;
    }

    @Override
    public void createSession(String tokenHash, String username, Instant expiresAt) {
        userAuth.insertSession(tokenHash, username, expiresAt);
    }

    @Override
    public void deleteSession(String tokenHash) {
        userAuth.deleteSession(tokenHash);
    }

    @Override
    public void deleteSessionsForUser(String username) {
        userAuth.deleteSessionsForUser(username);
    }

    @Override
    public void cleanupExpiredSessions(Instant now) {
        userAuth.deleteExpiredSessions(now);
    }

    @Override
    public boolean isLoginBlocked(String username, Instant now) {
        return userAuth.countActiveLoginBlock(username, now) > 0;
    }

    @Override
    @Transactional
    public void recordLoginFailure(
            String username, Instant now, int maxFailures, Duration window, Duration lockout) {
        List<ControlPlaneRow.LoginAttemptRow> rows = userAuth.selectLoginAttemptForUpdate(username);
        if (rows.isEmpty()) {
            userAuth.insertLoginAttempt(username, now);
            return;
        }
        ControlPlaneRow.LoginAttemptRow row = rows.get(0);
        Instant first = row.firstFailureAt();
        int failures = first.isBefore(now.minus(window)) ? 1 : row.failureCount() + 1;
        Instant firstFailure = first.isBefore(now.minus(window)) ? now : first;
        Instant lockedUntil = failures >= maxFailures ? now.plus(lockout) : null;
        userAuth.updateLoginAttempt(username, failures, firstFailure, lockedUntil);
    }

    @Override
    public void resetLoginFailures(String username) {
        userAuth.deleteLoginAttempts(username);
    }

    @Override
    public List<Map<String, Object>> listRoles() {
        List<Map<String, Object>> roles = new ArrayList<>();
        for (ControlPlaneRow.RoleRow row : roleAudit.selectRoles()) {
            Map<String, Object> role = new LinkedHashMap<>();
            role.put("name", row.name());
            role.put("description", row.description());
            role.put("permissions", readStringList(row.permissionsJson()));
            roles.add(role);
        }
        return roles;
    }

    @Override
    public void audit(String actor, String action, String target) {
        roleAudit.insertAudit(actor == null ? "system" : actor, action, target);
    }

    @Override
    public List<Map<String, String>> listAuditLogs() {
        List<Map<String, String>> logs = new ArrayList<>();
        for (ControlPlaneRow.AuditRow row : roleAudit.selectRecentAuditLogs()) {
            Map<String, String> log = new LinkedHashMap<>();
            log.put("timestamp", row.createdAt().toString());
            log.put("actor", row.actor());
            log.put("action", row.action());
            log.put("target", row.target());
            logs.add(log);
        }
        return logs;
    }

    @Override
    public List<Map<String, Object>> listNotifications(Boolean unread) {
        List<ControlPlaneRow.NotificationRow> rows =
                Boolean.TRUE.equals(unread)
                        ? notification.selectUnreadNotifications()
                        : notification.selectAllNotifications();
        List<Map<String, Object>> notifications = new ArrayList<>();
        for (ControlPlaneRow.NotificationRow row : rows) {
            notifications.add(notificationRow(row));
        }
        return notifications;
    }

    private Map<String, Object> notificationRow(ControlPlaneRow.NotificationRow row) {
        Map<String, Object> notification = new LinkedHashMap<>();
        notification.put("id", row.id());
        notification.put("type", row.type());
        notification.put("target", row.target());
        notification.put("message", row.message());
        notification.put("timestamp", row.createdAt().toString());
        notification.put("read", row.read());
        return notification;
    }

    @Override
    @Transactional
    public boolean createNotificationIfAllowed(
            String type, String target, String message, Instant notBefore) {
        if (notification.countNotificationsSince(type, target, notBefore) > 0) {
            return false;
        }
        notification.insertNotification("ntf-" + UUID.randomUUID(), type, target, message);
        return true;
    }

    @Override
    public boolean markNotificationRead(String id) {
        return notification.markRead(id) > 0;
    }

    @Override
    public void markAllNotificationsRead() {
        notification.markAllRead();
    }

    @Override
    public boolean deleteNotification(String id) {
        return notification.deleteById(id) > 0;
    }

    @Override
    public int deleteReadNotificationsBefore(Instant before) {
        return notification.deleteReadBefore(before);
    }

    @Override
    public List<Map<String, Object>> listCases(String status, String entity, int size) {
        int limit = Math.min(Math.max(size, 1), 200);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String id : caseMapper.selectCaseIds(status, limit)) {
            rows.add(findCase(id));
        }
        if (entity == null || entity.isBlank()) {
            return rows;
        }
        String[] parts = entity.split(":", 2);
        String type = parts.length > 1 ? parts[0] : "ip";
        String value = parts.length > 1 ? parts[1] : parts[0];
        return rows.stream()
                .filter(row -> entitiesContain(row.get("entities"), type, value))
                .toList();
    }

    @Override
    public Map<String, Long> caseStatusCounts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (ControlPlaneRow.CaseStatusCountRow row : caseMapper.selectCaseStatusCounts()) {
            if (row.status() != null) {
                counts.put(row.status(), row.total());
            }
        }
        return counts;
    }

    @Override
    public Map<String, Object> findCase(String id) {
        ControlPlaneRow.CaseRow row = caseMapper.selectCase(id);
        if (row == null) {
            return null;
        }
        Map<String, Object> result = caseRow(row);
        List<String> relationIds = caseMapper.selectAlertIds(id);
        if (!relationIds.isEmpty()) {
            result.put("alert_ids", relationIds);
        }
        return result;
    }

    private Map<String, Object> caseRow(ControlPlaneRow.CaseRow row) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("_id", row.id());
        result.put("case.id", row.id());
        result.put("case.title", row.title());
        result.put("case.status", row.status());
        result.put("case.aggregation", row.aggregation());
        result.put("case.operator", row.operator());
        result.put("case.owner", row.owner());
        result.put("case.verdict", row.verdict());
        result.put("case.created_at", row.createdAt());
        result.put("case.updated_at", row.updatedAt());
        result.put("case.closed_at", row.closedAt());
        result.put("alert_ids", readStringList(row.alertIdsJson()));
        result.put("entities", readMapList(row.entitiesJson()));
        result.put("evidence", readMapList(row.evidenceJson()));
        result.put("case.collaborators", readStringList(row.collaboratorsJson()));
        result.put("_control_version", row.version());
        return result;
    }

    @Override
    @Transactional
    public void createCase(Map<String, Object> document, List<String> alertIds) {
        String id = value(document, "case.id");
        caseMapper.insertCase(buildCaseRow(document, alertIds, 0));
        insertRelations(id, alertIds);
        enqueueCaseMirror(id, "upsert", document);
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
    public Map<String, Object> updateCase(
            String id, long expectedVersion, Map<String, Object> document, List<String> alertIds) {
        Map<String, Object> current = findCase(id);
        if (current == null) {
            throw new NotFoundException("案件不存在: " + id);
        }
        String title = valueOr(document, "case.title", value(current, "case.title"));
        String status = valueOr(document, "case.status", value(current, "case.status"));
        String aggregation =
                valueOr(document, "case.aggregation", value(current, "case.aggregation"));
        String operator = valueOr(document, "case.operator", value(current, "case.operator"));
        String owner = valueOr(document, "case.owner", value(current, "case.owner"));
        String verdict =
                document.containsKey("case.verdict")
                        ? value(document, "case.verdict")
                        : value(current, "case.verdict");
        String createdAt = valueOr(document, "case.created_at", value(current, "case.created_at"));
        String updatedAt = valueOr(document, "case.updated_at", value(current, "case.updated_at"));
        String closedAt =
                document.containsKey("case.closed_at")
                        ? value(document, "case.closed_at")
                        : value(current, "case.closed_at");
        List<String> finalAlertIds =
                alertIds == null ? stringList(current.get("alert_ids")) : alertIds;
        Object entities =
                document.containsKey("entities")
                        ? document.get("entities")
                        : current.get("entities");
        Object evidence =
                document.containsKey("evidence")
                        ? document.get("evidence")
                        : current.get("evidence");
        Object collaborators =
                document.containsKey("case.collaborators")
                        ? document.get("case.collaborators")
                        : current.get("case.collaborators");

        ControlPlaneRow.CaseRow row =
                new ControlPlaneRow.CaseRow(
                        id,
                        title,
                        status,
                        aggregation,
                        operator,
                        owner,
                        verdict,
                        parseInstant(createdAt),
                        parseInstant(updatedAt),
                        parseInstant(closedAt),
                        writeJson(finalAlertIds),
                        writeJson(entities),
                        writeJson(evidence),
                        writeJson(collaborators),
                        expectedVersion);
        int changed = caseMapper.updateCase(row, expectedVersion);
        if (changed == 0) {
            throw new IllegalStateException("案件已被其他请求更新,请刷新后重试: " + id);
        }
        caseMapper.deleteCaseAlerts(id);
        insertRelations(id, finalAlertIds);
        Map<String, Object> updated = findCase(id);
        enqueueCaseMirror(id, "upsert", updated);
        return updated;
    }

    private ControlPlaneRow.CaseRow buildCaseRow(
            Map<String, Object> document, List<String> alertIds, long version) {
        return new ControlPlaneRow.CaseRow(
                value(document, "case.id"),
                value(document, "case.title"),
                valueOr(document, "case.status", "open"),
                valueOr(document, "case.aggregation", "manual"),
                valueOr(document, "case.operator", "anonymous"),
                value(document, "case.owner"),
                value(document, "case.verdict"),
                parseInstant(value(document, "case.created_at")),
                parseInstant(value(document, "case.updated_at")),
                parseInstant(value(document, "case.closed_at")),
                writeJson(alertIds),
                writeJson(document.getOrDefault("entities", List.of())),
                writeJson(document.getOrDefault("evidence", List.of())),
                writeJson(document.getOrDefault("case.collaborators", List.of())),
                version);
    }

    private void insertRelations(String caseId, List<String> alertIds) {
        for (String alertId : alertIds.stream().distinct().toList()) {
            caseMapper.insertCaseAlert(caseId, alertId);
        }
    }

    @Override
    @Transactional
    public boolean deleteCase(String id) {
        int changed = caseMapper.deleteCase(id);
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
            caseMirrorOutbox.compactPendingUpsert(caseId, payload);
            if (caseMirrorOutbox.countPendingUpsert(caseId) > 0) {
                return;
            }
        }
        caseMirrorOutbox.insert(caseId, operation, payload);
    }

    @Override
    @Transactional
    public List<Map<String, Object>> claimCaseMirrorBatch(
            String owner, Instant leaseUntil, int size) {
        int limit = Math.min(Math.max(size, 1), 100);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ControlPlaneRow.CaseMirrorClaimRow row : caseMirrorOutbox.selectClaimable(limit)) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", row.id());
            map.put("caseId", row.caseId());
            map.put("operation", row.operation());
            map.put("payload", row.payload());
            map.put("attempts", row.attempts());
            rows.add(map);
            caseMirrorOutbox.claim(row.id(), owner, leaseUntil);
        }
        return rows;
    }

    @Override
    public void completeCaseMirror(
            long id, String owner, boolean success, String error, Instant nextAttemptAt) {
        if (success) {
            caseMirrorOutbox.succeed(id, owner);
        } else {
            caseMirrorOutbox.fail(id, owner, error, nextAttemptAt);
        }
    }

    @Override
    public void enqueueLifecycle(
            String messageId,
            String eventType,
            String tenantId,
            String objectType,
            String objectId,
            Instant occurredAt,
            String topic,
            String messageKey,
            String payload) {
        if (h2) {
            lifecycleOutbox.insert(
                    messageId,
                    eventType,
                    tenantId,
                    objectType,
                    objectId,
                    occurredAt,
                    topic,
                    messageKey,
                    payload);
            return;
        }
        lifecycleOutbox.insertOnConflictDoNothing(
                messageId,
                eventType,
                tenantId,
                objectType,
                objectId,
                occurredAt,
                topic,
                messageKey,
                payload);
    }

    @Override
    @Transactional
    public List<Map<String, Object>> claimLifecycleBatch(
            String owner, Instant leaseUntil, int size) {
        int limit = Math.min(Math.max(size, 1), 100);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ControlPlaneRow.LifecycleClaimRow row : lifecycleOutbox.selectClaimable(limit)) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("messageId", row.messageId());
            map.put("eventType", row.eventType());
            map.put("tenantId", row.tenantId());
            map.put("objectType", row.objectType());
            map.put("objectId", row.objectId());
            map.put("occurredAt", row.occurredAt());
            map.put("topic", row.topic());
            map.put("messageKey", row.messageKey());
            map.put("payload", row.payload());
            map.put("attempts", row.attempts());
            rows.add(map);
            int changed = lifecycleOutbox.claim(row.messageId(), owner, leaseUntil);
            if (changed == 1) {
                map.put("attempts", row.attempts() + 1);
            }
        }
        return rows;
    }

    @Override
    public boolean completeLifecycle(
            String messageId, String owner, boolean success, String error, Instant nextAttemptAt) {
        int changed;
        if (success) {
            changed = lifecycleOutbox.succeed(messageId, owner);
        } else {
            changed = lifecycleOutbox.fail(messageId, owner, error, nextAttemptAt);
        }
        return changed == 1;
    }

    @Override
    public boolean hasAlert(String alertId) {
        return caseMapper.countAlertReferences(alertId) > 0;
    }

    @Override
    public String createTask(String type, String resourceId, String message) {
        String id = "task-" + UUID.randomUUID();
        task.insertTask(id, type, resourceId, message);
        return id;
    }

    @Override
    public void updateTask(String id, String status, int progress, String message, String error) {
        task.updateTask(id, status, Math.max(0, Math.min(progress, 100)), message, error);
    }

    @Override
    public boolean claimTask(String id, String owner, Instant leaseUntil) {
        return task.claimTask(id, owner, leaseUntil) > 0;
    }

    @Override
    public boolean heartbeatTask(
            String id, String owner, Instant leaseUntil, int progress, String message) {
        return task.heartbeatTask(
                        id, owner, leaseUntil, Math.max(0, Math.min(progress, 100)), message)
                > 0;
    }

    @Override
    public Map<String, Object> findTask(String id) {
        ControlPlaneRow.TaskRow row = task.selectTask(id);
        return row == null ? null : taskRow(row);
    }

    @Override
    public List<Map<String, Object>> listTasks(int size) {
        int limit = Math.min(Math.max(size, 1), 200);
        List<Map<String, Object>> tasks = new ArrayList<>();
        for (ControlPlaneRow.TaskRow row : task.selectTasks(limit)) {
            tasks.add(taskRow(row));
        }
        return tasks;
    }

    private Map<String, Object> taskRow(ControlPlaneRow.TaskRow row) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", row.id());
        map.put("type", row.type());
        map.put("resourceId", row.resourceId());
        map.put("status", row.status());
        map.put("progress", row.progress());
        map.put("message", row.message());
        map.put("error", row.error());
        map.put("startedAt", row.startedAt());
        map.put("finishedAt", row.finishedAt());
        map.put("createdAt", row.createdAt());
        map.put("updatedAt", row.updatedAt());
        map.put("attempts", row.attempts());
        map.put("maxAttempts", row.maxAttempts());
        map.put("leaseOwner", row.leaseOwner());
        map.put("leaseUntil", row.leaseUntil());
        map.put("heartbeatAt", row.heartbeatAt());
        return map;
    }

    @Override
    public int recoverStaleTasks(Instant cutoff, String errorMessage) {
        return task.recoverStaleTasks(cutoff, errorMessage);
    }

    private boolean entitiesContain(Object raw, String type, String value) {
        if (!(raw instanceof List<?> list)) {
            return false;
        }
        return list.stream()
                .anyMatch(
                        item ->
                                item instanceof Map<?, ?> m
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
            return value == null || value.isBlank()
                    ? new ArrayList<>()
                    : mapper.readValue(value, STRING_LIST);
        } catch (Exception e) {
            throw new IllegalStateException("控制面字符串列表解析失败", e);
        }
    }

    private List<Map<String, Object>> readMapList(String value) {
        try {
            return value == null || value.isBlank()
                    ? new ArrayList<>()
                    : mapper.readValue(value, MAP_LIST);
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

    private static Instant parseInstant(String value) {
        return value == null ? null : Instant.parse(value);
    }
}
