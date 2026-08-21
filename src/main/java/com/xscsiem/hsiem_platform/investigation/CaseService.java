package com.xscsiem.hsiem_platform.investigation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xscsiem.hsiem_platform.alert.AlertService;
import com.xscsiem.hsiem_platform.control.ControlPlaneStore;
import com.xscsiem.hsiem_platform.onboarding.ConflictException;
import com.xscsiem.hsiem_platform.onboarding.NotFoundException;
import com.xscsiem.hsiem_platform.search.ElasticsearchGateway;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 调查台·案件聚合(story-07):
 * - 案件 CRUD(建/查/删),状态机 open→investigating→resolved
 * - 手动聚合(≥2 条 open 告警)、追加/移出
 * - 结案联动:resolved → 案内告警批量 closed + verdict(逐条乐观锁)
 * - 乐观锁(_seq_no/_primary_term,并发 409)+ operator 审计
 * - 实体提取(source.ip 优先,退 user.name)
 */
@Service
public class CaseService {

    public static final List<String> STATUSES = List.of("open", "investigating", "resolved");
    private static final Logger LOG = LoggerFactory.getLogger(CaseService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3)).build();

    private final String esUrl;
    private final AlertService alerts;
    private final ControlPlaneStore control;
    private final ElasticsearchGateway gateway;

    @Autowired
    public CaseService(@Value("${app.elasticsearch.url:http://localhost:9200}") String esUrl,
                       AlertService alerts, ControlPlaneStore control,
                       ElasticsearchGateway gateway) {
        this.esUrl = esUrl;
        this.alerts = alerts;
        this.control = control;
        this.gateway = gateway;
    }

    /** 轻量构造器仅供不启动 Spring/数据库的状态机单元测试使用。 */
    public CaseService(@Value("${app.elasticsearch.url:http://localhost:9200}") String esUrl,
                       AlertService alerts) {
        this.esUrl = esUrl;
        this.alerts = alerts;
        this.control = null;
        this.gateway = null;
    }

    /** 案件列表(按 updated_at 倒序;可按 status/entity 过滤)。 */
    public List<Map<String, Object>> list(String status, String entity, int size) {
        if (control != null) {
            List<Map<String, Object>> rows = control.listCases(status, entity, size);
            if (!rows.isEmpty()) {
                return rows;
            }
            // 兼容阶段 4.1 之前已写入 ES 的案件:首次查询时惰性导入控制面。
            List<Map<String, Object>> legacy = esList(status, entity, size);
            for (Map<String, Object> row : legacy) {
                try {
                    control.importCaseDocument(row);
                } catch (RuntimeException ignored) {
                    // 关系库已有告警归属时保留 ES 查询结果,不覆盖控制面事实。
                }
            }
            return legacy;
        }
        return esList(status, entity, size);
    }

    private List<Map<String, Object>> esList(String status, String entity, int size) {
        StringBuilder q = new StringBuilder();
        List<String> must = new ArrayList<>();
        if (status != null && !status.isBlank()) {
            must.add("{\"term\":{\"case.status\":\"%s\"}}".formatted(status));
        }
        if (entity != null && !entity.isBlank()) {
            String[] parts = entity.split(":", 2);
            String type = parts.length > 1 ? parts[0] : "ip";
            String value = parts.length > 1 ? parts[1] : parts[0];
            must.add("{\"nested\":{\"path\":\"entities\",\"query\":{\"bool\":{\"must\":["
                    + "{\"term\":{\"entities.type\":\"%s\"}},{\"term\":{\"entities.value\":\"%s\"}}]}}}}"
                    .formatted(type, value));
        }
        String query = must.isEmpty() ? "" : "\"query\":{\"bool\":{\"must\":[%s]}},".formatted(String.join(",", must));
        String body = "{\"size\":%d,%s\"sort\":[{\"case.updated_at\":{\"order\":\"desc\"}}]}"
                .formatted(Math.min(size, 200), query);
        Map<String, Object> resp = esCallLenient("POST", "/siem-cases/_search", body);
        return extractHits(resp);
    }

    /** 案件详情(含 alert_ids/entities/状态)。 */
    public Map<String, Object> detail(String id) {
        if (control != null) {
            Map<String, Object> stored = control.findCase(id);
            if (stored != null) {
                return stored;
            }
        }
        Map<String, Object> doc = esGet("/siem-cases/_doc/" + id);
        if (doc == null) {
            throw new NotFoundException("案件不存在: " + id);
        }
        if (control != null) {
            try {
                Map<String, Object> imported = control.importCaseDocument(doc);
                if (imported != null) {
                    return imported;
                }
            } catch (RuntimeException ignored) {
                // ES 仍是兼容镜像;关系库冲突时不丢失已有 ES 详情。
            }
        }
        return doc;
    }

    /** 手动聚合(≥2 条 open 告警)。 */
    public Map<String, Object> create(List<String> alertIds, String title, String operator) {
        return create(alertIds, title, operator, "manual");
    }

    /** 建案(aggregation=manual 手动 / auto 自动聚合)。 */
    public Map<String, Object> create(List<String> alertIds, String title, String operator, String aggregation) {
        if (alertIds == null || alertIds.size() < 2) {
            throw new IllegalArgumentException("手动聚合至少需要 2 条告警");
        }
        // 校验:所有告警 open 且未入他案
        List<Map<String, Object>> alertsDoc = new ArrayList<>();
        for (String id : new LinkedHashSet<>(alertIds)) {
            Map<String, Object> a = alerts.detail(id);
            if (a == null) {
                throw new IllegalArgumentException("告警不存在: " + id);
            }
            validateAlertCanJoinCase(str(a.get("alert.status")), str(a.get("alert.case_id")), id);
            alertsDoc.add(a);
        }
        Set<Map<String, Object>> entities = extractEntities(alertsDoc);
        String caseId = "case-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + "-"
                + Integer.toHexString(UUID.randomUUID().hashCode() & 0xffff);
        String now = Instant.now().toString();
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("@timestamp", now);
        doc.put("case.id", caseId);
        doc.put("case.title", title != null && !title.isBlank() ? title : autoTitle(entities, now));
        doc.put("case.status", "open");
        doc.put("case.aggregation", aggregation == null ? "manual" : aggregation);
        doc.put("case.operator", operator == null ? "anonymous" : operator);
        doc.put("case.owner", operator == null ? "anonymous" : operator);
        doc.put("case.created_at", now);
        doc.put("case.updated_at", now);
        List<String> uniqueAlertIds = alertIds.stream().distinct().toList();
        doc.put("alert_ids", uniqueAlertIds);
        doc.put("entities", new ArrayList<>(entities));
        doc.put("evidence", List.of());
        boolean controlCreated = false;
        try {
            if (control != null) {
                control.createCase(doc, uniqueAlertIds);
                controlCreated = true;
            }
            esCall("POST", "/siem-cases/_doc/" + caseId + "?refresh=wait_for",
                    MAPPER.writeValueAsString(doc));
        } catch (Exception e) {
            if (controlCreated) {
                control.deleteCase(caseId);
            }
            if (e instanceof DataIntegrityViolationException) {
                throw new ConflictException("告警已归属其他案件,案件创建被拒绝");
            }
            throw new IllegalStateException("案件序列化失败", e);
        }
        // 给案内告警写 alert.case_id 标记(聚合幂等根基:同批告警只入一案)
        try {
            markAlertsInCase(uniqueAlertIds, caseId);
        } catch (RuntimeException e) {
            // ES 没有跨索引事务:关联失败时删除已创建的空壳案件,并尽力清理已写入的标记。
            try {
                esCallCode("DELETE", "/siem-cases/_doc/" + caseId, null);
            } catch (Exception cleanup) {
                e.addSuppressed(cleanup);
            }
            if (control != null) {
                control.deleteCase(caseId);
            }
            throw e;
        }
        return detail(caseId);
    }

    /** 给告警写归属案件标记;部分失败时清理已成功标记并抛错,由调用方补偿案件文档。 */
    private void markAlertsInCase(List<String> alertIds, String caseId) {
        List<String> marked = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (String id : alertIds) {
            try {
                String body = "{\"doc\":{\"alert.case_id\":\"" + caseId + "\"}}";
                int code = esCallCode("POST", "/siem-alerts/_update/" + id + "?refresh=false", body);
                if (code / 100 != 2) {
                    throw new IllegalStateException("ES 返回 " + code);
                }
                marked.add(id);
            } catch (Exception e) {
                failed.add(id);
            }
        }
        if (!failed.isEmpty()) {
            for (String id : marked) {
                clearAlertCase(id);
            }
            throw new IllegalStateException("告警标记案件失败,已回滚成功标记: " + failed);
        }
    }

    /** 手动追加告警(须 open 且未入他案)。 */
    public Map<String, Object> addAlerts(String caseId, List<String> alertIds, String operator) {
        Map<String, Object> cur = detail(caseId);
        if ("resolved".equals(cur.get("case.status"))) {
            throw new IllegalArgumentException("案件已结案,不能追加告警");
        }
        List<String> currentIds = strList(cur.get("alert_ids"));
        List<String> originalIds = new ArrayList<>(currentIds);
        List<String> added = new ArrayList<>();
        List<String> rejected = new ArrayList<>();
        for (String id : new LinkedHashSet<>(alertIds)) {
            Map<String, Object> a = alerts.detail(id);
            if (a == null || !"open".equals(str(a.get("alert.status")))) {
                rejected.add(id);
                continue;
            }
            if (a.get("alert.case_id") != null && !caseId.equals(str(a.get("alert.case_id")))) {
                rejected.add(id); // 已属他案
                continue;
            }
            if (currentIds.contains(id)) {
                continue; // 已在案内,幂等跳过
            }
            currentIds.add(id);
            added.add(id);
        }
        if (!rejected.isEmpty()) {
            throw new IllegalArgumentException("以下告警非 open 或已属他案,追加被拒: " + rejected);
        }
        optimisticUpdate(caseId, currentIds, null, operator);
        try {
            markAlertsInCase(added, caseId);
        } catch (RuntimeException e) {
            try {
                optimisticUpdate(caseId, originalIds, null, operator);
            } catch (Exception rollback) {
                e.addSuppressed(rollback);
            }
            throw e;
        }
        return Map.of("added", added);
    }

    /** 手动移出告警(最后一条移出后提示可删空案)。 */
    public void removeAlert(String caseId, String alertId) {
        Map<String, Object> cur = detail(caseId);
        List<String> currentIds = strList(cur.get("alert_ids"));
        if (!currentIds.remove(alertId)) {
            throw new NotFoundException("告警不在案件内: " + alertId);
        }
        optimisticUpdate(caseId, currentIds, null, "anonymous");
        clearAlertCase(alertId);
    }

    /** 清除告警的 case_id(移出案件时;告警回到 open 待聚合池)。 */
    private void clearAlertCase(String alertId) {
        try {
            esCallCode("POST", "/siem-alerts/_update/" + alertId + "?refresh=false",
                    "{\"doc\":{\"alert.case_id\":null}}");
        } catch (Exception e) {
            System.out.println("[CaseService] 清除告警案件标记失败 " + alertId + ": " + e.getMessage());
        }
    }

    /** 状态流转(open→investigating→resolved);resolved 触发结案联动。 */
    public Map<String, Object> updateStatus(String caseId, String status, String verdict, String operator) {
        if (status == null || !STATUSES.contains(status)) {
            throw new IllegalArgumentException("案件状态非法(open/investigating/resolved): " + status);
        }
        if ("resolved".equals(status)
                && (verdict == null || !AlertService.VERDICTS.contains(verdict))) {
            throw new IllegalArgumentException("结案必选 verdict(true_positive/false_positive/duplicate)");
        }
        Map<String, Object> cur = detail(caseId);
        validateStatusTransition(str(cur.get("case.status")), status);
        String now = Instant.now().toString();
        if ("resolved".equals(status)) {
            // 结案联动:案内告警批量 closed + verdict(逐条乐观锁,复用 Story 04 update)
            List<String> alertIds = strList(cur.get("alert_ids"));
            List<String> failed = new ArrayList<>();
            int succeeded = 0;
            for (String id : alertIds) {
                try {
                    alerts.update(id, "closed", verdict, operator);
                    succeeded++;
                } catch (Exception e) {
                    failed.add(id);
                }
            }
            if (!failed.isEmpty()) {
                throw new IllegalStateException("部分告警结案失败(已成功的保留): " + failed);
            }
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("case.status", "resolved");
            doc.put("case.verdict", verdict);
            doc.put("case.closed_at", now);
            doc.put("case.updated_at", now);
            doc.put("case.operator", operator == null ? "anonymous" : operator);
            doc.put("case.batch_succeeded", succeeded);
            doc.put("case.batch_failed", failed);
            return optimisticUpdate(caseId, null, doc, operator);
        }
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("case.status", status);
        if ("open".equals(status) && !"open".equals(str(cur.get("case.status")))) {
            doc.put("case.verdict", null);
            doc.put("case.closed_at", null);
        }
        doc.put("case.updated_at", now);
        doc.put("case.operator", operator == null ? "anonymous" : operator);
        return optimisticUpdate(caseId, null, doc, operator);
    }

    /** 更新负责人和证据。ES 作为兼容镜像，控制面保存可查询事实。 */
    public Map<String, Object> updateMetadata(String caseId, String owner,
                                              List<Map<String, Object>> evidence, String operator) {
        detail(caseId);
        if (owner != null && owner.isBlank()) {
            throw new IllegalArgumentException("负责人不能为空字符串");
        }
        List<Map<String, Object>> safeEvidence = evidence == null ? List.of() : evidence.stream()
                .limit(100)
                .map(item -> item == null ? Map.<String, Object>of() : new LinkedHashMap<>(item))
                .toList();
        Map<String, Object> extra = new LinkedHashMap<>();
        if (owner != null) {
            extra.put("case.owner", owner);
        }
        extra.put("evidence", safeEvidence);
        return optimisticUpdate(caseId, null, extra, operator);
    }

    /** 服务层案件状态机:允许结案后重开,禁止从调查中直接回到 open。 */
    static void validateStatusTransition(String currentStatus, String targetStatus) {
        if (targetStatus == null || !STATUSES.contains(targetStatus)) {
            throw new IllegalArgumentException("案件状态非法(open/investigating/resolved): " + targetStatus);
        }
        String current = currentStatus == null ? "open" : currentStatus;
        boolean allowed = switch (current) {
            case "open" -> List.of("open", "investigating", "resolved").contains(targetStatus);
            case "investigating" -> List.of("investigating", "resolved").contains(targetStatus);
            case "resolved" -> List.of("resolved", "open").contains(targetStatus);
            default -> false;
        };
        if (!allowed) {
            throw new IllegalArgumentException("不允许从 " + current + " 流转到 " + targetStatus);
        }
    }

    static void validateAlertCanJoinCase(String status, String caseId, String alertId) {
        if (!"open".equals(status)) {
            throw new IllegalArgumentException("告警非 open 状态(已处置或已结案): " + alertId);
        }
        if (caseId != null && !caseId.isBlank()) {
            throw new IllegalArgumentException("告警已归属其他案件: " + alertId);
        }
    }

    /** 删除案件(移出全部告警后可删空案)。 */
    public void delete(String caseId) {
        detail(caseId);
        int code = esCallCode("DELETE", "/siem-cases/_doc/" + caseId, null);
        if (code / 100 != 2) {
            throw new IllegalStateException("案件删除失败 " + code);
        }
        if (control != null) {
            control.deleteCase(caseId);
        }
    }

    /** 自动聚合(供 CaseAggregateJob 调用):近 30min open 告警按实体聚类,组 ≥2 建案。幂等可重跑。 */
    public int aggregateAuto(int lookbackMinutes) {
        return aggregateAuto(lookbackMinutes, false, 2, null);
    }

    /** 可配置聚合窗口、阈值和是否按规则分组。 */
    public int aggregateAuto(int lookbackMinutes, boolean groupByRule, int threshold, String ruleId) {
        if (lookbackMinutes < 1 || lookbackMinutes > 24 * 60) {
            throw new IllegalArgumentException("聚合窗口需在 1-1440 分钟之间");
        }
        if (threshold < 2 || threshold > 1000) {
            throw new IllegalArgumentException("聚合阈值需在 2-1000 之间");
        }
        String ruleFilter = ruleId == null || ruleId.isBlank()
                ? "" : "{\"term\":{\"alert.rule_id.keyword\":\"" + ruleId.replace("\"", "") + "\"}},";
        String body = """
                {"size":200,"query":{"bool":{"must":[
                  {"term":{"alert.status":"open"}},
                  %s
                  {"range":{"alert.created_at":{"gte":"now-%dm"}}},
                  {"bool":{"must_not":[{"exists":{"field":"alert.case_id"}}]}}]}},"_source":["alert.id","alert.rule_id","source.ip","user.name","alert.risk_score"]}
                """.formatted(ruleFilter, lookbackMinutes);
        Map<String, Object> resp = esCall("POST", "/siem-alerts/_search", body);
        List<Map<String, Object>> hits = new ArrayList<>();
        Object hh = resp.get("hits");
        if (hh instanceof Map<?, ?> hm && hm.get("hits") instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m && m.get("_source") instanceof Map<?, ?> src) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> s = (Map<String, Object>) src;
                    s.put("_id", m.get("_id"));
                    hits.add(s);
                }
            }
        }
        // 按实体分组
        Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        for (Map<String, Object> a : hits) {
            String baseEntity = entityKey(a);
            if (baseEntity != null) {
                String groupKey = (groupByRule ? str(a.get("alert.rule_id")) + "|" : "") + baseEntity;
                groups.computeIfAbsent(groupKey, k -> new ArrayList<>()).add(a);
            }
        }
        AtomicInteger created = new AtomicInteger();
        for (Map.Entry<String, List<Map<String, Object>>> e : groups.entrySet()) {
            if (e.getValue().size() < threshold) {
                continue;
            }
            List<String> ids = e.getValue().stream()
                    .map(a -> str(a.get("_id"))).distinct().toList();
            // 防重复:查已有案件是否已含这些告警(实体已有 open 案 或 任一告警已入他案 → 跳过)
            String entityOnly = e.getKey().contains("|") ? e.getKey().substring(e.getKey().indexOf('|') + 1) : e.getKey();
            if (entityHasOpenCase(entityOnly) || anyAlertInCase(ids)) {
                continue;
            }
            String title = "自动聚合 " + e.getKey();
            try {
                create(ids, title, "system", "auto");
                created.incrementAndGet();
            } catch (Exception ex) {
                // 单组失败不阻塞整轮(job 幂等,下次重试)
                LOG.warn("[CaseAggregateJob] 建案失败 {}: {}", e.getKey(), ex.getMessage(), ex);
            }
        }
        return created.get();
    }

    /** 更新案件协作负责人列表；主负责人仍由 case.owner 保留兼容。 */
    public Map<String, Object> updateCollaborators(String caseId, List<String> usernames, String operator) {
        detail(caseId);
        List<String> safe = usernames == null ? List.of() : usernames.stream()
                .filter(u -> u != null && !u.isBlank())
                .map(String::trim).distinct().limit(20).toList();
        if (usernames != null && usernames.size() > 20) {
            throw new IllegalArgumentException("协作负责人最多 20 人");
        }
        return optimisticUpdate(caseId, null,
                Map.of("case.collaborators", safe), operator);
    }

    /** 时间线:按实体 + 案件时间窗实时查 siem-events-*(MVP 实时关联,不物化存储)。 */
    public List<Map<String, Object>> timeline(String caseId, int size) {
        Map<String, Object> cur = detail(caseId);
        List<Map<String, Object>> entities = nestedList(cur.get("entities"));
        List<Map<String, Object>> out = new ArrayList<>();
        if (entities.isEmpty()) {
            return out;
        }
        // 用第一个实体做时间窗查询(实体类型 ip/user 映射到对应事件字段)
        Map<String, Object> first = entities.get(0);
        String type = str(first.get("type"));
        String value = str(first.get("value"));
        String field = "ip".equals(type) ? "source.ip" : "user.name";
        String body = """
                {"size":%d,"query":{"bool":{"must":[
                  {"term":{"%s":"%s"}},
                  {"range":{"@timestamp":{"gte":"now-24h"}}}]}},"sort":[{"@timestamp":"desc"}],
                  "_source":["@timestamp","message","event.action","source.ip","user.name","host.name","log.source_name"]}
                """.formatted(Math.min(size, 50), field, value);
        Map<String, Object> resp = esCall("POST", "/siem-events-*/_search", body);
        List<Map<String, Object>> hits = list(resp, "hits", "hits");
        for (Map<String, Object> h : hits) {
            Map<String, Object> src = map(h, "_source");
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("@timestamp", src.get("@timestamp"));
            row.put("message", src.get("message"));
            row.put("event.action", src.get("event.action"));
            row.put("source.ip", src.get("source.ip"));
            row.put("user.name", src.get("user.name"));
            out.add(row);
        }
        return out;
    }

    // ---- 内部工具 ----

    /** 任一告警已存在于某案件(无论案件状态)→ 防重复聚合。 */
    private boolean anyAlertInCase(List<String> ids) {
        if (control != null) {
            return ids.stream().anyMatch(control::hasAlert);
        }
        String terms = ids.stream().map(i -> "\"" + i + "\"").reduce((a, b) -> a + "," + b).orElse("");
        String body = """
                {"size":1,"query":{"terms":{"alert_ids":[%s]}}}
                """.formatted(terms);
        Map<String, Object> resp = esCallLenient("POST", "/siem-cases/_search", body);
        Object total = map(resp, "hits").get("total");
        return total instanceof Number n && n.longValue() > 0;
    }

    private boolean entityHasOpenCase(String entityKey) {
        if (control != null) {
            return !control.listCases("open", entityKey, 1).isEmpty();
        }
        String[] parts = entityKey.split(":", 2);
        String type = parts[0];
        String value = parts[1];
        String body = """
                {"size":1,"query":{"bool":{"must":[
                  {"term":{"case.status":"open"}},
                  {"nested":{"path":"entities","query":{"bool":{"must":[
                    {"term":{"entities.type":"%s"}},{"term":{"entities.value":"%s"}}]}}}}]}}}
                """.formatted(type, value);
        Map<String, Object> resp = esCallLenient("POST", "/siem-cases/_search", body);
        Object total = map(resp, "hits").get("total");
        return total instanceof Number n && n.longValue() > 0;
    }

    /** 实体键:source.ip 优先,无 IP 退 user.name。 */
    private static String entityKey(Map<String, Object> a) {
        Object ip = a.get("source.ip");
        if (ip != null && !str(ip).isBlank()) {
            return "ip:" + ip;
        }
        Object user = a.get("user.name");
        if (user != null && !str(user).isBlank()) {
            return "user:" + user;
        }
        return null;
    }

    /** 从一组告警提取去重实体(ip 优先)。 */
    private static Set<Map<String, Object>> extractEntities(List<Map<String, Object>> alertsDoc) {
        Set<Map<String, Object>> entities = new LinkedHashSet<>();
        for (Map<String, Object> a : alertsDoc) {
            Object ip = a.get("source.ip");
            if (ip != null && !str(ip).isBlank()) {
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("type", "ip");
                e.put("value", str(ip));
                entities.add(e);
            } else {
                Object user = a.get("user.name");
                if (user != null && !str(user).isBlank()) {
                    Map<String, Object> e = new LinkedHashMap<>();
                    e.put("type", "user");
                    e.put("value", str(user));
                    entities.add(e);
                }
            }
        }
        return entities;
    }

    private static String autoTitle(Set<Map<String, Object>> entities, String now) {
        String entity = entities.isEmpty() ? "unknown"
                : entities.iterator().next().get("value") + " (" + entities.iterator().next().get("type") + ")";
        return "案件 " + entity + " " + now.substring(0, 10);
    }

    /** 乐观锁更新:alert_ids 或自定义字段,带 _seq_no/_primary_term,冲突 409。 */
    private Map<String, Object> optimisticUpdate(String caseId, List<String> alertIds,
                                                 Map<String, Object> extra, String operator) {
        Map<String, Object> cur = esGet("/siem-cases/_doc/" + caseId);
        if (cur == null) {
            throw new NotFoundException("案件不存在: " + caseId);
        }
        Object seq = cur.get("_seq_no");
        Object pt = cur.get("_primary_term");
        if (seq == null || pt == null) {
            throw new IllegalStateException("案件缺 _seq_no/_primary_term: " + caseId);
        }
        Map<String, Object> doc = new LinkedHashMap<>();
        if (alertIds != null) {
            doc.put("alert_ids", alertIds);
        }
        if (extra != null) {
            doc.putAll(extra);
        }
        if (!doc.containsKey("case.updated_at")) {
            doc.put("case.updated_at", Instant.now().toString());
        }
        String body;
        try {
            body = "{\"doc\":" + MAPPER.writeValueAsString(doc) + "}";
        } catch (Exception e) {
            throw new IllegalStateException("序列化失败", e);
        }
        int code = esCallCode("POST",
                "/siem-cases/_update/" + caseId + "?if_seq_no=" + seq + "&if_primary_term=" + pt, body);
        if (code == 409) {
            throw new ConflictException("案件 " + caseId + " 已被其他分析师更新,请刷新后重试");
        }
        if (code / 100 != 2) {
            throw new IllegalStateException("案件更新失败 " + code + ": " + caseId);
        }
        Map<String, Object> updated = esGet("/siem-cases/_doc/" + caseId);
        if (control != null) {
            Map<String, Object> controlCurrent = control.findCase(caseId);
            if (controlCurrent == null) {
                controlCurrent = control.importCaseDocument(cur);
            }
            long version = controlCurrent.get("_control_version") instanceof Number n
                    ? n.longValue() : 0L;
            List<String> finalAlertIds = alertIds == null
                    ? strList(updated.get("alert_ids")) : alertIds;
            try {
                return control.updateCase(caseId, version, updated, finalAlertIds);
            } catch (IllegalStateException e) {
                throw new ConflictException("案件控制面更新冲突,请刷新后重试: " + caseId);
            }
        }
        return updated;
    }

    private List<Map<String, Object>> extractHits(Map<String, Object> resp) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> h : list(resp, "hits", "hits")) {
            Map<String, Object> src = map(h, "_source");
            Map<String, Object> row = new LinkedHashMap<>(src);
            row.put("_id", h.get("_id"));
            out.add(row);
        }
        return out;
    }

    // ---- ES 底层(与 AlertService 同款) ----

    private record EsResponse(int code, Map<String, Object> body) {
    }

    private EsResponse esRequest(String method, String path, String body) {
        if (gateway != null) {
            ElasticsearchGateway.Response response = gateway.request(method, path, body);
            return new EsResponse(response.code(), response.body());
        }
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder().uri(URI.create(esUrl + path));
            if (body != null) {
                builder.header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(body));
            } else {
                builder.method(method, HttpRequest.BodyPublishers.noBody());
            }
            HttpResponse<String> resp = CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            Map<String, Object> parsed = resp.body().isBlank()
                    ? Map.of() : MAPPER.readValue(resp.body(), Map.class);
            return new EsResponse(resp.statusCode(), parsed);
        } catch (Exception e) {
            throw new IllegalStateException("ES 不可达: " + e.getMessage(), e);
        }
    }

    private Map<String, Object> esCall(String method, String path, String body) {
        EsResponse r = esRequest(method, path, body);
        if (r.code() / 100 != 2) {
            throw new IllegalStateException("ES 请求失败 " + r.code() + ": " + path);
        }
        return r.body();
    }

    /** 容错版 ES 调用:索引尚不存在(404,如首启无案件)时返回空 map,不抛错。 */
    private Map<String, Object> esCallLenient(String method, String path, String body) {
        EsResponse r = esRequest(method, path, body);
        if (r.code() == 404) {
            return Map.of("hits", Map.of("hits", List.of()));
        }
        if (r.code() / 100 != 2) {
            throw new IllegalStateException("ES 请求失败 " + r.code() + ": " + path);
        }
        return r.body();
    }

    private int esCallCode(String method, String path, String body) {
        return esRequest(method, path, body).code();
    }

    private Map<String, Object> esGet(String path) {
        Map<String, Object> resp = esCall("GET", path, null);
        if (resp.containsKey("found") && Boolean.FALSE.equals(resp.get("found"))) {
            return null;
        }
        Object source = resp.get("_source");
        if (source instanceof Map<?, ?> m) {
            @SuppressWarnings("unchecked")
            Map<String, Object> src = new LinkedHashMap<>((Map<String, Object>) m);
            src.put("_id", resp.get("_id"));
            src.put("_seq_no", resp.get("_seq_no"));
            src.put("_primary_term", resp.get("_primary_term"));
            return src;
        }
        return null;
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    @SuppressWarnings("unchecked")
    private static List<String> strList(Object v) {
        if (v instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object o : list) {
                out.add(str(o));
            }
            return out;
        }
        return new ArrayList<>();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> nestedList(Object v) {
        if (v instanceof List<?> list) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object o : list) {
                if (o instanceof Map) {
                    out.add((Map<String, Object>) o);
                }
            }
            return out;
        }
        return new ArrayList<>();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object root, String key) {
        if (root instanceof Map<?, ?> m) {
            Object v = m.get(key);
            return v instanceof Map ? (Map<String, Object>) v : Map.of();
        }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> root, String key, String sub) {
        Object v = root.get(key);
        if (v instanceof Map<?, ?> m && m.get(sub) instanceof List<?> l) {
            return (List<Map<String, Object>>) l;
        }
        return List.of();
    }
}
