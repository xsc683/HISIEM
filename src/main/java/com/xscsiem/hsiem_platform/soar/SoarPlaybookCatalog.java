package com.xscsiem.hsiem_platform.soar;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.xscsiem.hsiem_platform.onboarding.ConflictException;
import com.xscsiem.hsiem_platform.onboarding.NotFoundException;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.DependsOn;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据库版本目录：Git YAML 只负责引入初始版本，运行时只解析已发布 revision。
 * 发布采用四眼审批；灰度选择对 tenant/playbook/routingKey 做稳定哈希。
 */
@Service
@DependsOn("flyway")
public class SoarPlaybookCatalog {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
    private final JdbcTemplate jdbc;
    private final SoarPlaybookRegistry gitRegistry;
    private final TransactionTemplate transactions;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public SoarPlaybookCatalog(JdbcTemplate jdbc, SoarPlaybookRegistry gitRegistry,
                               PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.gitRegistry = gitRegistry;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @PostConstruct
    void bootstrapDefaultTenant() {
        transactions.executeWithoutResult(status -> bootstrapLocked());
    }

    private void bootstrapLocked() {
        jdbc.queryForObject("SELECT id FROM tenants WHERE id = 'default' FOR UPDATE", String.class);
        for (SoarPlaybook playbook : gitRegistry.list()) {
            Integer count = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM soar_playbook_revisions
                    WHERE tenant_id = 'default' AND playbook_id = ?
                    """, Integer.class, playbook.id());
            if (count != null && count > 0) continue;
            insert("default", playbook, Map.of(), 1, "published", 100,
                    "git-bootstrap", "git-bootstrap", "git-bootstrap", "初始 Git 版本");
        }
    }

    public List<SoarPlaybook> listPublished(String tenantId) {
        Map<String, SoarPlaybook> selected = new LinkedHashMap<>();
        listRevisions(tenantId, "published").stream()
                .filter(item -> item.rolloutPercentage() > 0)
                .sorted(Comparator.comparing(SoarPlaybookRevision::playbookId)
                        .thenComparing(SoarPlaybookRevision::rolloutPercentage).reversed())
                .forEach(item -> selected.putIfAbsent(item.playbookId(), item.definition()));
        return new ArrayList<>(selected.values());
    }

    public SoarPlaybook resolve(String tenantId, String playbookId, String routingKey) {
        List<SoarPlaybookRevision> candidates = revisions(tenantId, playbookId).stream()
                .filter(item -> "published".equals(item.state()) && item.rolloutPercentage() > 0)
                .sorted(Comparator.comparingInt(SoarPlaybookRevision::revision).reversed()).toList();
        if (candidates.isEmpty()) throw new NotFoundException("租户中没有已发布 Playbook: " + playbookId);
        int bucket = Math.floorMod((tenantId + ":" + playbookId + ":" + routingKey).hashCode(), 100);
        int cursor = 0;
        for (SoarPlaybookRevision candidate : candidates) {
            cursor += candidate.rolloutPercentage();
            if (bucket < cursor) return candidate.definition();
        }
        return candidates.get(candidates.size() - 1).definition();
    }

    public List<SoarPlaybookRevision> listRevisions(String tenantId, String state) {
        String sql = "SELECT * FROM soar_playbook_revisions WHERE tenant_id = ?"
                + (state == null || state.isBlank() ? "" : " AND state = ?")
                + " ORDER BY playbook_id, revision DESC";
        return state == null || state.isBlank()
                ? jdbc.query(sql, (rs, rowNum) -> row(rs), tenantId)
                : jdbc.query(sql, (rs, rowNum) -> row(rs), tenantId, state);
    }

    public List<SoarPlaybookRevision> revisions(String tenantId, String playbookId) {
        return jdbc.query("""
                SELECT * FROM soar_playbook_revisions
                WHERE tenant_id = ? AND playbook_id = ? ORDER BY revision DESC
                """, (rs, rowNum) -> row(rs), tenantId, playbookId);
    }

    public SoarPlaybookRevision get(String tenantId, String playbookId, int revision) {
        List<SoarPlaybookRevision> rows = jdbc.query("""
                SELECT * FROM soar_playbook_revisions
                WHERE tenant_id = ? AND playbook_id = ? AND revision = ?
                """, (rs, rowNum) -> row(rs), tenantId, playbookId, revision);
        if (rows.isEmpty()) throw new NotFoundException("Playbook revision 不存在");
        return rows.get(0);
    }

    @Transactional
    public SoarPlaybookRevision createDraft(String tenantId, SoarPlaybook definition,
                                            Map<String, Object> layout, String actor) {
        SoarPlaybookRegistry.validate(definition, null);
        jdbc.queryForObject("SELECT id FROM tenants WHERE id = ? FOR UPDATE", String.class, tenantId);
        Integer next = jdbc.queryForObject("""
                SELECT COALESCE(MAX(revision), 0) + 1 FROM soar_playbook_revisions
                WHERE tenant_id = ? AND playbook_id = ?
                """, Integer.class, tenantId, definition.id());
        insert(tenantId, definition, layout, next == null ? 1 : next,
                "draft", 0, actor, null, null, null);
        return get(tenantId, definition.id(), next == null ? 1 : next);
    }

    @Transactional
    public SoarPlaybookRevision updateDraft(String tenantId, String playbookId, int revision,
                                            SoarPlaybook definition, Map<String, Object> layout,
                                            long expectedVersion) {
        if (!playbookId.equals(definition.id())) throw new IllegalArgumentException("definition.id 不可变");
        SoarPlaybookRegistry.validate(definition, null);
        int changed = jdbc.update("""
                UPDATE soar_playbook_revisions SET semantic_version = ?, definition_json = ?,
                    layout_json = ?, state = 'draft', review_note = NULL, updated_at = CURRENT_TIMESTAMP,
                    lock_version = lock_version + 1
                WHERE tenant_id = ? AND playbook_id = ? AND revision = ?
                  AND state IN ('draft', 'rejected') AND lock_version = ?
                """, definition.version(), json(definition), json(layout == null ? Map.of() : layout),
                tenantId, playbookId, revision, expectedVersion);
        if (changed != 1) throw new ConflictException("草稿已被修改或当前状态不可编辑，请刷新后重试");
        return get(tenantId, playbookId, revision);
    }

    @Transactional
    public SoarPlaybookRevision submit(String tenantId, String playbookId, int revision, String actor) {
        int changed = jdbc.update("""
                UPDATE soar_playbook_revisions SET state = 'pending_approval', review_note = NULL,
                    updated_at = CURRENT_TIMESTAMP, lock_version = lock_version + 1
                WHERE tenant_id = ? AND playbook_id = ? AND revision = ?
                  AND state IN ('draft', 'rejected') AND created_by = ?
                """, tenantId, playbookId, revision, actor);
        if (changed != 1) throw new ConflictException("只有草稿创建者可以提交待审批版本");
        return get(tenantId, playbookId, revision);
    }

    @Transactional
    public SoarPlaybookRevision review(String tenantId, String playbookId, int revision,
                                       boolean approved, String note, String actor) {
        SoarPlaybookRevision current = get(tenantId, playbookId, revision);
        if (!"pending_approval".equals(current.state())) throw new ConflictException("版本不在待审批状态");
        if (actor.equals(current.createdBy())) throw new ConflictException("四眼原则：创建者不能审批自己的版本");
        jdbc.update("""
                UPDATE soar_playbook_revisions SET state = ?, review_note = ?, reviewed_by = ?,
                    reviewed_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP,
                    lock_version = lock_version + 1
                WHERE tenant_id = ? AND playbook_id = ? AND revision = ? AND state = 'pending_approval'
                """, approved ? "approved" : "rejected", note, actor,
                tenantId, playbookId, revision);
        return get(tenantId, playbookId, revision);
    }

    @Transactional
    public SoarPlaybookRevision publish(String tenantId, String playbookId, int revision,
                                        int rollout, String actor) {
        if (rollout < 1 || rollout > 100) throw new IllegalArgumentException("灰度比例需在 1-100");
        SoarPlaybookRevision candidate = get(tenantId, playbookId, revision);
        if (!"approved".equals(candidate.state()) && !"published".equals(candidate.state())) {
            throw new ConflictException("只有已审批版本可以发布");
        }
        List<SoarPlaybookRevision> active = revisions(tenantId, playbookId).stream()
                .filter(item -> "published".equals(item.state()) && item.revision() != revision).toList();
        if (active.isEmpty() && !"published".equals(candidate.state()) && rollout < 100) {
            throw new ConflictException("首次发布没有稳定版本，灰度比例必须为 100%");
        }
        jdbc.update("""
                UPDATE soar_playbook_revisions SET state = 'retired', rollout_percentage = 0,
                    updated_at = CURRENT_TIMESTAMP, lock_version = lock_version + 1
                WHERE tenant_id = ? AND playbook_id = ? AND state = 'published' AND revision <> ?
                """, tenantId, playbookId, revision);
        if (rollout < 100 && !active.isEmpty()) {
            SoarPlaybookRevision stable = active.stream()
                    .max(Comparator.comparingInt(SoarPlaybookRevision::revision)).orElseThrow();
            jdbc.update("""
                    UPDATE soar_playbook_revisions SET state = 'published', rollout_percentage = ?,
                        updated_at = CURRENT_TIMESTAMP, lock_version = lock_version + 1
                    WHERE tenant_id = ? AND playbook_id = ? AND revision = ?
                    """, 100 - rollout, tenantId, playbookId, stable.revision());
        }
        jdbc.update("""
                UPDATE soar_playbook_revisions SET state = 'published', rollout_percentage = ?,
                    published_by = ?, published_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP,
                    lock_version = lock_version + 1
                WHERE tenant_id = ? AND playbook_id = ? AND revision = ?
                """, rollout, actor, tenantId, playbookId, revision);
        return get(tenantId, playbookId, revision);
    }

    @Transactional
    public List<SoarPlaybookRevision> importGitAsDraft(String tenantId, String actor) {
        List<SoarPlaybookRevision> imported = new ArrayList<>();
        for (SoarPlaybook playbook : gitRegistry.reload()) {
            boolean same = revisions(tenantId, playbook.id()).stream()
                    .anyMatch(item -> json(item.definition()).equals(json(playbook)));
            if (!same) imported.add(createDraft(tenantId, playbook, Map.of(), actor));
        }
        return imported;
    }

    private void insert(String tenantId, SoarPlaybook playbook, Map<String, Object> layout,
                        int revision, String state, int rollout, String createdBy,
                        String reviewedBy, String publishedBy, String note) {
        try {
            Instant now = Instant.now();
            jdbc.update("""
                    INSERT INTO soar_playbook_revisions(tenant_id, playbook_id, revision,
                        semantic_version, state, definition_json, layout_json, rollout_percentage,
                        review_note, created_by, reviewed_by, published_by, reviewed_at, published_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, tenantId, playbook.id(), revision, playbook.version(), state, json(playbook),
                    json(layout == null ? Map.of() : layout), rollout, note, createdBy, reviewedBy,
                    publishedBy, reviewedBy == null ? null : Timestamp.from(now),
                    publishedBy == null ? null : Timestamp.from(now));
        } catch (DuplicateKeyException e) {
            throw new ConflictException("Playbook revision 已存在");
        }
    }

    private SoarPlaybookRevision row(ResultSet rs) throws SQLException {
        return new SoarPlaybookRevision(rs.getString("tenant_id"), rs.getString("playbook_id"),
                rs.getInt("revision"), rs.getString("semantic_version"), rs.getString("state"),
                read(rs.getString("definition_json"), SoarPlaybook.class),
                readMap(rs.getString("layout_json")), rs.getInt("rollout_percentage"),
                rs.getString("review_note"), rs.getString("created_by"), rs.getString("reviewed_by"),
                rs.getString("published_by"), instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("reviewed_at")), instant(rs.getTimestamp("published_at")),
                instant(rs.getTimestamp("updated_at")), rs.getLong("lock_version"));
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Playbook JSON 序列化失败", e);
        }
    }

    private <T> T read(String value, Class<T> type) {
        try {
            return mapper.readValue(value, type);
        } catch (Exception e) {
            throw new IllegalStateException("Playbook JSON 反序列化失败", e);
        }
    }

    private Map<String, Object> readMap(String value) {
        try {
            return value == null || value.isBlank() ? Map.of() : mapper.readValue(value, MAP);
        } catch (Exception e) {
            throw new IllegalStateException("Playbook layout 反序列化失败", e);
        }
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
