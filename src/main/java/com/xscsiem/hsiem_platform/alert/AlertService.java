package com.xscsiem.hsiem_platform.alert;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xscsiem.hsiem_platform.onboarding.ConflictException;
import com.xscsiem.hsiem_platform.onboarding.NotFoundException;
import com.xscsiem.hsiem_platform.search.ElasticsearchGateway;
import com.xscsiem.hsiem_platform.soar.LifecycleEventPublisher;
import com.xscsiem.hsiem_platform.tenant.TenantContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 告警三线处置(story-04,替代 triage-alert.py 的交互版):
 * - 列表(open 默认,risk_score DESC)/详情(含 related_events、event.raw)
 * - 三线流转(5 态 open→acknowledged→investigating→resolved/closed)+ 结案强制 verdict
 * - 乐观锁更新(_seq_no/_primary_term,并发冲突 409)+ 操作审计(alert.operator/status_updated_at)
 * - 批量 ack/close(close 前置 verdict)+ 按规则 FP 率(FP/(TP+FP) 不含 duplicate)
 */
@Service
public class AlertService {

    public static final List<String> STATUSES = List.of(
            "open", "acknowledged", "investigating", "resolved", "closed");
    public static final List<String> VERDICTS = List.of(
            "true_positive", "false_positive", "duplicate");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3)).build();

    private final String esUrl;
    private final ElasticsearchGateway gateway;
    private LifecycleEventPublisher lifecyclePublisher;

    @Autowired
    public AlertService(@Value("${app.elasticsearch.url:http://localhost:9200}") String esUrl,
                        ElasticsearchGateway gateway) {
        this.esUrl = esUrl;
        this.gateway = gateway;
    }

    /** 纯逻辑单元测试构造器,生产由 Spring 注入 Java API Client 网关。 */
    public AlertService(String esUrl) {
        this.esUrl = esUrl;
        this.gateway = null;
    }

    @Autowired(required = false)
    public void setLifecyclePublisher(LifecycleEventPublisher lifecyclePublisher) {
        this.lifecyclePublisher = lifecyclePublisher;
    }

    /** 告警列表(open 默认,按 risk_score DESC + 时间倒序)。 */
    public List<Map<String, Object>> list(String status, int size) {
        String query = status != null && !status.isBlank()
                ? String.format("\"query\":{\"term\":{\"alert.status\":\"%s\"}},", status) : "";
        String body = "{\"size\":%d,%s\"sort\":[{\"alert.risk_score\":{\"order\":\"desc\"}},{\"@timestamp\":{\"order\":\"desc\"}}]}"
                .formatted(Math.min(size, 200), query);
        Map<String, Object> resp = esCall("POST", "/siem-alerts/_search", body);
        List<Map<String, Object>> out = new ArrayList<>();
        Object hits = resp.get("hits");
        if (hits instanceof Map<?, ?> hm && hm.get("hits") instanceof List<?> list) {
            for (Object h : list) {
                if (h instanceof Map<?, ?> hm2 && hm2.get("_source") instanceof Map<?, ?> src) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> s = new LinkedHashMap<>((Map<String, Object>) src);
                    s.put("_id", hm2.get("_id"));   // 详情/更新 API 用 ES _id
                    out.add(s);
                }
            }
        }
        return out;
    }

    /** 告警详情(by _id = 确定性 sha1 id)。 */
    public Map<String, Object> detail(String id) {
        return esGet("/siem-alerts/_doc/" + id);
    }

    /** 更新状态/verdict(乐观锁,操作审计)。status 与 verdict 至少给一个。 */
    public Map<String, Object> update(String id, String status, String verdict, String operator) {
        if (status != null && !STATUSES.contains(status)) {
            throw new IllegalArgumentException("状态非法(open/acknowledged/investigating/resolved/closed): " + status);
        }
        if (verdict != null && !VERDICTS.contains(verdict)) {
            throw new IllegalArgumentException("verdict 非法(true_positive/false_positive/duplicate): " + verdict);
        }
        if (status == null && verdict == null) {
            throw new IllegalArgumentException("status 或 verdict 至少给一个");
        }
        Map<String, Object> cur = esGet("/siem-alerts/_doc/" + id);
        if (cur == null) {
            throw new NotFoundException("告警不存在: " + id);
        }
        validateStatusTransition(str(cur.get("alert.status")), status,
                str(cur.get("alert.analyst_verdict")), verdict);
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("alert.status_updated_at", Instant.now().toString());
        doc.put("alert.operator", operator == null ? "anonymous" : operator);
        if (status != null) {
            doc.put("alert.status", status);
        }
        if (verdict != null) {
            doc.put("alert.analyst_verdict", verdict);
        }
        if ("open".equals(status) && !"open".equals(str(cur.get("alert.status"))) && verdict == null) {
            // 重开意味着重新分诊,清掉旧 verdict,避免 FP/TP 统计继续使用过时结论。
            doc.put("alert.analyst_verdict", null);
        }
        Map<String, Object> updated = optimisticUpdate(id, doc, cur);
        publishUpdated(updated);
        return updated;
    }

    /** 批量处置:批量 close 前置 verdict;逐条乐观锁更新,收集成功/失败。 */
    public Map<String, Object> batch(List<String> ids, String status, String verdict, String operator) {
        if (ids == null || ids.isEmpty()) {
            throw new IllegalArgumentException("ids 不能为空");
        }
        if (status != null && !STATUSES.contains(status)) {
            throw new IllegalArgumentException("状态非法: " + status);
        }
        if (verdict != null && !VERDICTS.contains(verdict)) {
            throw new IllegalArgumentException("verdict 非法: " + verdict);
        }
        Map<String, Map<String, Object>> currentById = new LinkedHashMap<>();
        List<String> missingVerdict = new ArrayList<>();
        List<String> invalidTransitions = new ArrayList<>();
        // 无论是批量状态还是仅批量 verdict,都必须先读取当前文档拿到
        // _seq_no/_primary_term;否则 verdict-only 请求会把 null 传给乐观锁更新,
        // 导致全部条目被当作失败。
        for (String id : ids) {
            Map<String, Object> a = esGet("/siem-alerts/_doc/" + id);
            currentById.put(id, a);
            if (status != null) {
                if (a == null) {
                    if ("resolved".equals(status) || "closed".equals(status)) {
                        missingVerdict.add(id);
                    }
                    continue;
                }
                if (("resolved".equals(status) || "closed".equals(status))
                        && verdict == null && a.get("alert.analyst_verdict") == null) {
                    missingVerdict.add(id);
                }
                try {
                    validateStatusTransition(str(a.get("alert.status")), status,
                            str(a.get("alert.analyst_verdict")), verdict);
                } catch (IllegalArgumentException e) {
                    invalidTransitions.add(id + ": " + e.getMessage());
                }
            }
        }
        if (!missingVerdict.isEmpty()) {
            throw new IllegalArgumentException("以下告警未打 verdict,请先批量补: " + missingVerdict);
        }
        if (!invalidTransitions.isEmpty()) {
            throw new IllegalArgumentException("告警状态流转非法: " + invalidTransitions);
        }
        int succeeded = 0;
        List<String> failed = new ArrayList<>();
        for (String id : ids) {
            try {
                Map<String, Object> current = currentById.get(id);
                Map<String, Object> doc = buildDoc(status, verdict, operator);
                if ("open".equals(status) && current != null
                        && !"open".equals(str(current.get("alert.status"))) && verdict == null) {
                    doc.put("alert.analyst_verdict", null);
                }
                Map<String, Object> updated = optimisticUpdate(id, doc, current);
                publishUpdated(updated);
                succeeded++;
            } catch (Exception e) {
                failed.add(id);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("succeeded", succeeded);
        out.put("failed", failed);
        out.put("total", ids.size());
        return out;
    }

    private void publishUpdated(Map<String, Object> alert) {
        if (lifecyclePublisher != null && alert != null) {
            lifecyclePublisher.publishAlert("alert.updated", alert, TenantContext.id());
        }
    }

    /** 按规则 FP 率(FP/(TP+FP) 不含 duplicate,>50% 高亮由前端/通知处理)。 */
    public List<Map<String, Object>> fpRate() {
        String body = """
                {"size":0,"aggs":{"rules":{"terms":{"field":"alert.rule_id.keyword","size":50},
                  "aggs":{
                    "fp":{"filter":{"term":{"alert.analyst_verdict.keyword":"false_positive"}}},
                    "tp":{"filter":{"term":{"alert.analyst_verdict.keyword":"true_positive"}}}}}}}
                """;
        Map<String, Object> resp = esCall("POST", "/siem-alerts/_search", body);
        List<Map<String, Object>> out = new ArrayList<>();
        Object aggs = resp.get("aggregations");
        if (aggs instanceof Map<?, ?> am && am.get("rules") instanceof Map<?, ?> rm
                && rm.get("buckets") instanceof List<?> buckets) {
            for (Object b : buckets) {
                if (!(b instanceof Map<?, ?> bm)) {
                    continue;
                }
                long fp = docCount(bm, "fp");
                long tp = docCount(bm, "tp");
                double rate = (fp + tp) > 0 ? (double) fp / (fp + tp) : 0.0;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("ruleId", bm.get("key"));
                row.put("total", bm.get("doc_count"));
                row.put("fp", fp);
                row.put("tp", tp);
                row.put("fpRate", Math.round(rate * 1000) / 10.0);
                row.put("high", rate > 0.5);
                out.add(row);
            }
        }
        return out;
    }

    private Map<String, Object> buildDoc(String status, String verdict, String operator) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("alert.status_updated_at", Instant.now().toString());
        doc.put("alert.operator", operator == null ? "anonymous" : operator);
        if (status != null) {
            doc.put("alert.status", status);
        }
        if (verdict != null) {
            doc.put("alert.analyst_verdict", verdict);
        }
        return doc;
    }

    /**
     * 服务层告警状态机。允许设计文档中的快捷结案和复查重开路径,
     * 但禁止跳过调查阶段的逆向流转；resolved/closed 必须有有效 verdict。
     */
    static void validateStatusTransition(String currentStatus, String targetStatus,
                                         String currentVerdict, String requestedVerdict) {
        if (targetStatus == null) {
            return;
        }
        String current = currentStatus == null ? "open" : currentStatus;
        if (!STATUSES.contains(current)) {
            throw new IllegalArgumentException("当前状态非法: " + current);
        }
        boolean allowed = switch (current) {
            case "open" -> List.of("open", "acknowledged", "resolved", "closed").contains(targetStatus);
            case "acknowledged" -> List.of("acknowledged", "investigating", "closed").contains(targetStatus);
            case "investigating" -> List.of("investigating", "resolved", "closed").contains(targetStatus);
            case "resolved", "closed" -> List.of(current, "open").contains(targetStatus);
            default -> false;
        };
        if (!allowed) {
            throw new IllegalArgumentException("不允许从 " + current + " 流转到 " + targetStatus);
        }
        String effectiveVerdict = requestedVerdict == null ? currentVerdict : requestedVerdict;
        if (("resolved".equals(targetStatus) || "closed".equals(targetStatus))
                && (effectiveVerdict == null || !VERDICTS.contains(effectiveVerdict))) {
            throw new IllegalArgumentException("结案必选 verdict(true_positive/false_positive/duplicate)");
        }
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /** 乐观锁更新:先 GET 拿 _seq_no/_primary_term,再 _update 带 if_seq_no/if_primary_term,冲突 409。 */
    private Map<String, Object> optimisticUpdate(String id, Map<String, Object> doc) {
        Map<String, Object> cur = esGet("/siem-alerts/_doc/" + id);
        return optimisticUpdate(id, doc, cur);
    }

    private Map<String, Object> optimisticUpdate(String id, Map<String, Object> doc,
                                                  Map<String, Object> cur) {
        if (cur == null) {
            throw new NotFoundException("告警不存在: " + id);
        }
        Object seqObj = cur.get("_seq_no");
        Object ptObj = cur.get("_primary_term");
        if (seqObj == null || ptObj == null) {
            throw new IllegalStateException("告警缺 _seq_no/_primary_term: " + id);
        }
        String body;
        try {
            body = "{\"doc\":" + MAPPER.writeValueAsString(doc) + "}";
        } catch (Exception e) {
            throw new IllegalStateException("序列化失败", e);
        }
        int code = esCallCode("POST",
                "/siem-alerts/_update/" + id + "?if_seq_no=" + seqObj + "&if_primary_term=" + ptObj, body);
        if (code == 409) {
            throw new ConflictException("告警 " + id + " 已被其他分析师更新,请刷新后重试");
        }
        if (code / 100 != 2) {
            throw new IllegalStateException("告警更新失败 " + code + ": " + id);
        }
        return detail(id);
    }

    private static long docCount(Map<?, ?> bucket, String aggName) {
        Object v = bucket.get(aggName);
        if (v instanceof Map<?, ?> m && m.get("doc_count") instanceof Number n) {
            return n.longValue();
        }
        return 0L;
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

    /** 单次 ES 请求,返回(状态码, 解析 body)。 */
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

    private int esCallCode(String method, String path, String body) {
        return esRequest(method, path, body).code();
    }

    private record EsResponse(int code, Map<String, Object> body) {
    }
}
