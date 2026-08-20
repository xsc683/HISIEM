package com.xscsiem.hsiem_platform.health;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据源健康(story-05):按 log.source_id 聚合事件量/失败率/最后收到时间。
 * 失败率口径:失败事件 / (成功事件 + 失败事件)。低于最小样本数时不判定异常,
 * 避免少量失败把数据源误报为高风险。
 */
@Service
public class DataHealthService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final String esUrl;

    @Value("${app.health.minimum-samples:20}")
    private int minimumSamples = 20;

    public DataHealthService(@Value("${app.elasticsearch.url:http://localhost:9200}") String esUrl) {
        this.esUrl = esUrl;
    }

    /**
     * 每数据源健康指标(L9 raw 分流后,成功数据从 siem-events-* 取,失败数据从
     * siem-events-raw-* 取)。两个索引的 source_id 取并集,保证只有失败事件的数据源也能显示。
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
                    "source_name":{"terms":{"field":"log.source_name","size":1}},
                    "failures1h":{"filter":{"range":{"@timestamp":{"gte":"now-1h"}}}},
                    "failures_prev1h":{"filter":{"range":{"@timestamp":{"gte":"now-2h","lt":"now-1h"}}}},
                    "last_failure":{"max":{"field":"@timestamp"}}
                  }}}}
                """);
        Map<String, Map<String, Object>> rawBySource = new LinkedHashMap<>();
        for (Map<String, Object> b : list(map(respRaw, "aggregations"), "sources", "buckets")) {
            rawBySource.put(String.valueOf(b.get("key")), b);
        }
        Map<String, Map<String, Object>> eventsBySource = new LinkedHashMap<>();
        for (Map<String, Object> b : list(map(respEvents, "aggregations"), "sources", "buckets")) {
            eventsBySource.put(String.valueOf(b.get("key")), b);
        }
        LinkedHashSet<String> sourceIds = new LinkedHashSet<>(eventsBySource.keySet());
        sourceIds.addAll(rawBySource.keySet());

        List<Map<String, Object>> out = new ArrayList<>();
        for (String sourceId : sourceIds) {
            Map<String, Object> b = eventsBySource.get(sourceId);
            Map<String, Object> raw = rawBySource.get(sourceId);
            long successful1h = docCount(b, "events1h");
            long successful24h = docCount(b, "events24h");
            long successfulPrev = docCount(b, "events_prev1h");
            long failures1h = docCount(raw, "failures1h");
            long failuresPrev = docCount(raw, "failures_prev1h");
            HealthMetrics metrics = calculateMetrics(
                    successful1h, successfulPrev, failures1h, failuresPrev, minimumSamples);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("sourceId", sourceId);
            row.put("sourceName", firstBucketKey(b, "source_name", firstBucketKey(raw, "source_name", null)));
            row.put("events1h", successful1h);
            row.put("events24h", successful24h);
            row.put("totalEvents1h", metrics.total1h());
            row.put("failRate", Math.round(metrics.failRate() * 1000) / 10.0); // 百分比,一位小数
            row.put("failures1h", failures1h);
            row.put("lastSeen", latestTimestamp(
                    nested(b, "last_seen", "value_as_string"),
                    nested(raw, "last_failure", "value_as_string")));
            row.put("anomalous", metrics.anomalous());                       // U1 判定(前端高亮)
            row.put("reason", metrics.high() ? "失败率>5%且样本达标"
                    : metrics.spike() ? "失败率环比≥2×且失败样本达标" : "");
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
        Map<String, Long> eventsByHour = new LinkedHashMap<>();
        for (Map<String, Object> b : list(map(respEvents, "aggregations"), "hours", "buckets")) {
            eventsByHour.put(String.valueOf(b.get("key_as_string")), numericCount(b.get("doc_count")));
        }
        // raw 桶失败按小时归集;与正常桶取并集,否则失败-only 源趋势为空。
        Map<String, Long> failuresByHour = new LinkedHashMap<>();
        for (Map<String, Object> b : list(map(respRaw, "aggregations"), "hours", "buckets")) {
            failuresByHour.put(String.valueOf(b.get("key_as_string")), numericCount(b.get("doc_count")));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        LinkedHashSet<String> hours = new LinkedHashSet<>(eventsByHour.keySet());
        hours.addAll(failuresByHour.keySet());
        for (String hour : hours) {
            long events = eventsByHour.getOrDefault(hour, 0L);
            long failures = failuresByHour.getOrDefault(hour, 0L);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("bucket", hour);
            row.put("events", events);
            row.put("failures", failures);
            row.put("totalEvents", events + failures);
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

    static HealthMetrics calculateMetrics(long successful1h, long successfulPrev1h,
                                          long failures1h, long failuresPrev1h,
                                          long minimumSamples) {
        long total1h = successful1h + failures1h;
        long totalPrev1h = successfulPrev1h + failuresPrev1h;
        double failRate = total1h > 0 ? (double) failures1h / total1h : 0.0;
        double prevRate = totalPrev1h > 0 ? (double) failuresPrev1h / totalPrev1h : 0.0;
        boolean high = total1h >= minimumSamples && failRate > 0.05;
        boolean spike = prevRate > 0 && failRate >= 2 * prevRate
                && failures1h >= minimumSamples;
        return new HealthMetrics(total1h, failRate, prevRate,
                high, spike, high || spike);
    }

    record HealthMetrics(long total1h, double failRate, double prevFailRate,
                         boolean high, boolean spike, boolean anomalous) {
    }

    private static long docCount(Map<String, Object> bucket, String aggName) {
        if (bucket == null) {
            return 0;
        }
        Object v = bucket.get(aggName);
        if (v instanceof Map<?, ?> m) {
            Object c = m.get("doc_count");
            return c instanceof Number n ? n.longValue() : 0L;
        }
        return 0L;
    }

    private static long numericCount(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }

    private static String firstBucketKey(Map<String, Object> bucket, String aggName) {
        return firstBucketKey(bucket, aggName, null);
    }

    private static String firstBucketKey(Map<String, Object> bucket, String aggName, String fallback) {
        if (bucket == null) {
            return fallback;
        }
        Object v = bucket.get(aggName);
        if (v instanceof Map<?, ?> m && m.get("buckets") instanceof List<?> list && !list.isEmpty()
                && list.get(0) instanceof Map<?, ?> b) {
            Object k = b.get("key");
            return k == null ? fallback : String.valueOf(k);
        }
        return fallback;
    }

    private static Object nested(Map<String, Object> bucket, String aggName, String field) {
        if (bucket == null) {
            return null;
        }
        Object v = bucket.get(aggName);
        if (v instanceof Map<?, ?> m) {
            return m.get(field);
        }
        return null;
    }

    private static String latestTimestamp(Object first, Object second) {
        if (first == null) {
            return second == null ? null : String.valueOf(second);
        }
        if (second == null) {
            return String.valueOf(first);
        }
        String a = String.valueOf(first);
        String b = String.valueOf(second);
        try {
            return Instant.parse(a).isAfter(Instant.parse(b)) ? a : b;
        } catch (Exception ignored) {
            return a.compareTo(b) >= 0 ? a : b;
        }
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
