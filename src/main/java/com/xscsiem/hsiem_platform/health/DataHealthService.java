package com.xscsiem.hsiem_platform.health;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据源健康(story-05):按 log.source_id 聚合事件量/失败率/最后收到时间。
 * 失败率口径(U1,默认阈值,用户可配置见 08 §5.0):本 1h 失败率 &gt;5% 或 本1h/前一1h 环比 ≥2×,
 * 且本 1h 失败事件数 ≥20 才判定异常(高亮)。
 */
@Service
public class DataHealthService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final String esUrl;

    public DataHealthService(@Value("${app.elasticsearch.url:http://localhost:9200}") String esUrl) {
        this.esUrl = esUrl;
    }

    /**
     * 每数据源健康指标(L9 raw 分流后,失败数据从 siem-events-raw-* 取,总事件从 siem-events-* 取):
     * 失败率 = raw 桶失败事件 / 事件桶总事件(U1 口径不变)。
     */
    public List<Map<String, Object>> sources() {
        // 总事件数(事件桶,不含解析失败)
        Map<String, Object> respEvents = esPost("/siem-events-*/_search", """
                {"size":0,"aggs":{"sources":{"terms":{"field":"log.source_id","size":100},
                  "aggs":{
                    "source_name":{"terms":{"field":"log.source_name","size":1}},
                    "events24h":{"filter":{"range":{"@timestamp":{"gte":"now-24h"}}}},
                    "events1h":{"filter":{"range":{"@timestamp":{"gte":"now-1h"}}}},
                    "events_prev1h":{"filter":{"range":{"@timestamp":{"gte":"now-2h","lt":"now-1h"}}}},
                    "last_seen":{"max":{"field":"@timestamp"}}
                  }}}}
                """);
        // 解析失败数(raw 桶,L9:失败事件路由到 siem-events-raw-*)
        Map<String, Object> respRaw = esPost("/siem-events-raw-*/_search", """
                {"size":0,"aggs":{"sources":{"terms":{"field":"log.source_id","size":100},
                  "aggs":{
                    "failures1h":{"filter":{"range":{"@timestamp":{"gte":"now-1h"}}}},
                    "failures_prev1h":{"filter":{"range":{"@timestamp":{"gte":"now-2h","lt":"now-1h"}}}}
                  }}}}
                """);
        Map<String, Object> rawBySource = new LinkedHashMap<>();
        for (Map<String, Object> b : list(map(respRaw, "aggregations"), "sources", "buckets")) {
            rawBySource.put(String.valueOf(b.get("key")), b);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> b : list(map(respEvents, "aggregations"), "sources", "buckets")) {
            Map<String, Object> raw = (Map<String, Object>) rawBySource.get(String.valueOf(b.get("key")));
            long events1h = docCount(b, "events1h");
            long events24h = docCount(b, "events24h");
            long eventsPrev = docCount(b, "events_prev1h");
            long failures1h = raw == null ? 0 : docCount(raw, "failures1h");
            long failuresPrev = raw == null ? 0 : docCount(raw, "failures_prev1h");
            double failRate = events1h > 0 ? (double) failures1h / events1h : 0.0;
            double prevRate = eventsPrev > 0 ? (double) failuresPrev / eventsPrev : 0.0;
            boolean spike = prevRate > 0 && failRate >= 2 * prevRate && failures1h >= 20;
            boolean high = failRate > 0.05;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("sourceId", b.get("key"));
            row.put("sourceName", firstBucketKey(b, "source_name"));
            row.put("events1h", events1h);
            row.put("events24h", events24h);
            row.put("failRate", Math.round(failRate * 1000) / 10.0);       // 百分比,一位小数
            row.put("failures1h", failures1h);
            row.put("lastSeen", nested(b, "last_seen", "value_as_string"));
            row.put("anomalous", spike || high);                            // U1 判定(前端高亮)
            row.put("reason", high ? "失败率>5%" : spike ? "失败率环比≥2×且样本≥20" : "");
            out.add(row);
        }
        return out;
    }

    /** 某源最近 24h 逐小时事件/失败趋势(L9:事件从 siem-events-*,失败从 siem-events-raw-*)。 */
    public List<Map<String, Object>> trend(String sourceId) {
        Map<String, Object> respEvents = esPost("/siem-events-*/_search", """
                {"size":0,"query":{"term":{"log.source_id":"%s"}},
                  "aggs":{"hours":{"date_histogram":{"field":"@timestamp","fixed_interval":"1h","min_doc_count":0}}}}
                """.formatted(sourceId));
        Map<String, Object> respRaw = esPost("/siem-events-raw-*/_search", """
                {"size":0,"query":{"term":{"log.source_id":"%s"}},
                  "aggs":{"hours":{"date_histogram":{"field":"@timestamp","fixed_interval":"1h","min_doc_count":0}}}}
                """.formatted(sourceId));
        // raw 桶失败按小时归集
        Map<String, Object> rawFailByHour = new LinkedHashMap<>();
        for (Map<String, Object> b : list(map(respRaw, "aggregations"), "hours", "buckets")) {
            rawFailByHour.put(String.valueOf(b.get("key_as_string")), b.get("doc_count"));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        List<Map<String, Object>> buckets = list(map(respEvents, "aggregations"), "hours", "buckets");
        for (Map<String, Object> b : buckets) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("bucket", b.get("key_as_string"));
            row.put("events", b.get("doc_count"));
            Object rawCount = rawFailByHour.get(String.valueOf(b.get("key_as_string")));
            row.put("failures", rawCount instanceof Number n ? n.longValue() : 0L);
            out.add(row);
        }
        return out;
    }

    /** 某源最近解析失败日志(原文下钻,查 siem-events-raw-*)。 */
    public List<Map<String, Object>> failures(String sourceId, int size) {
        String body = """
                {"size":%d,"query":{"term":{"log.source_id":"%s"}},
                  "sort":[{"@timestamp":"desc"}],"_source":["@timestamp","message","log.source_name","host.name"]}
                """.formatted(Math.min(size, 100), sourceId);
        Map<String, Object> resp = esPost("/siem-events-raw-*/_search", body);
        List<Map<String, Object>> out = new ArrayList<>();
        List<Map<String, Object>> hits = list(resp, "hits", "hits");
        for (Map<String, Object> h : hits) {
            Map<String, Object> src = map(h, "_source");
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("@timestamp", src.get("@timestamp"));
            row.put("message", src.get("message"));
            row.put("sourceName", src.get("log.source_name"));
            out.add(row);
        }
        return out;
    }

    private Map<String, Object> esPost(String path, String body) {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(3)).build();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(esUrl + path))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                throw new IllegalStateException("ES 查询失败 " + resp.statusCode() + ": " + resp.body());
            }
            return MAPPER.readValue(resp.body(), Map.class);
        } catch (Exception e) {
            throw new IllegalStateException("ES 不可达: " + e.getMessage(), e);
        }
    }

    private static long docCount(Map<String, Object> bucket, String aggName) {
        Object v = bucket.get(aggName);
        if (v instanceof Map<?, ?> m) {
            Object c = m.get("doc_count");
            return c instanceof Number n ? n.longValue() : 0L;
        }
        return 0L;
    }

    private static String firstBucketKey(Map<String, Object> bucket, String aggName) {
        Object v = bucket.get(aggName);
        if (v instanceof Map<?, ?> m && m.get("buckets") instanceof List<?> list && !list.isEmpty()
                && list.get(0) instanceof Map<?, ?> b) {
            Object k = b.get("key");
            return k == null ? null : String.valueOf(k);
        }
        return null;
    }

    private static Object nested(Map<String, Object> bucket, String aggName, String field) {
        Object v = bucket.get(aggName);
        if (v instanceof Map<?, ?> m) {
            return m.get(field);
        }
        return null;
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
