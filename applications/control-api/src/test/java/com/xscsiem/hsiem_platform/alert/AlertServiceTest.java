package com.xscsiem.hsiem_platform.alert;

import com.xscsiem.hsiem_platform.search.ElasticsearchGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 告警三线(story-04)校验逻辑(纯逻辑,不触 ES):
 * 状态/verdict 枚举校验、批量 close 前置 verdict、空 ids。
 */
class AlertServiceTest {

    private AlertService svc;

    @BeforeEach
    void setUp() {
        // ES 地址无效,但校验在请求前抛出,故可安全测试
        svc = new AlertService("http://127.0.0.1:1");
    }

    @Test
    void update_invalidStatus_rejected() {
        assertThrows(IllegalArgumentException.class,
                () -> svc.update("x", "bogus", null, "alice"));
        assertThrows(IllegalArgumentException.class,
                () -> svc.update("x", null, "TP", "alice"));
    }

    @Test
    void update_neitherStatusNorVerdict_rejected() {
        assertThrows(IllegalArgumentException.class,
                () -> svc.update("x", null, null, "alice"));
    }

    @Test
    void batch_emptyIds_rejected() {
        assertThrows(IllegalArgumentException.class,
                () -> svc.batch(List.of(), "open", null, "alice"));
        assertThrows(IllegalArgumentException.class,
                () -> svc.batch(null, "open", null, "alice"));
    }

    @Test
    void batch_invalidVerdict_rejected() {
        assertThrows(IllegalArgumentException.class,
                () -> svc.batch(List.of("a"), "acknowledged", "yes", "alice"));
    }

    @Test
    void batch_verdictOnly_loadsCurrentDocuments_andUpdatesAll() {
        ElasticsearchGateway gateway = mock(ElasticsearchGateway.class);
        for (String id : List.of("a", "b")) {
            Map<String, Object> source = new LinkedHashMap<>();
            source.put("alert.status", "open");
            source.put("alert.id", id);
            Map<String, Object> getBody = new LinkedHashMap<>();
            getBody.put("found", true);
            getBody.put("_id", id);
            getBody.put("_source", source);
            getBody.put("_seq_no", 1);
            getBody.put("_primary_term", 1);
            when(gateway.request(eq("GET"), eq("/siem-alerts/_doc/" + id), isNull()))
                    .thenReturn(new ElasticsearchGateway.Response(200, getBody));
            when(gateway.request(eq("POST"),
                    org.mockito.ArgumentMatchers.startsWith("/siem-alerts/_update/" + id), anyString()))
                    .thenReturn(new ElasticsearchGateway.Response(200, Map.of()));
        }

        AlertService service = new AlertService("http://unused", gateway);
        Map<String, Object> result = service.batch(List.of("a", "b"), null,
                "false_positive", "alice");

        assertEquals(2, result.get("succeeded"));
        assertEquals(List.of(), result.get("failed"));
    }

    @Test
    void statusTransition_rejectsReverseMoveAndClosingWithoutVerdict() {
        assertThrows(IllegalArgumentException.class,
                () -> AlertService.validateStatusTransition("acknowledged", "open", null, null));
        assertThrows(IllegalArgumentException.class,
                () -> AlertService.validateStatusTransition("investigating", "closed", null, null));
    }

    @Test
    void statusTransition_allowsTriageAndReopenPaths() {
        assertDoesNotThrow(() -> AlertService.validateStatusTransition(
                "open", "acknowledged", null, null));
        assertDoesNotThrow(() -> AlertService.validateStatusTransition(
                "open", "closed", null, "false_positive"));
        assertDoesNotThrow(() -> AlertService.validateStatusTransition(
                "closed", "open", "false_positive", null));
    }

    @Test
    void summary_usesFullAggregationsAndNewestHits() {
        ElasticsearchGateway gateway = mock(ElasticsearchGateway.class);
        Map<String, Object> response = Map.of(
                "hits", Map.of(
                        "total", Map.of("value", 503),
                        "hits", List.of(Map.of("_id", "newest", "_source", Map.of(
                                "alert.status", "open", "alert.created_at", "2026-08-24T14:00:00Z")))),
                "aggregations", Map.of(
                        "statuses", Map.of("buckets", Map.of(
                                "open", Map.of("doc_count", 201),
                                "closed", Map.of("doc_count", 302))),
                        "linked", Map.of("doc_count", 77)));
        when(gateway.request(eq("POST"), eq("/siem-alerts/_search"), anyString()))
                .thenReturn(new ElasticsearchGateway.Response(200, response));

        Map<String, Object> summary = new AlertService("http://unused", gateway).summary();

        assertEquals(503L, summary.get("total"));
        assertEquals(77L, summary.get("linked"));
        assertEquals(Map.of("open", 201L, "closed", 302L), summary.get("statuses"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> recent = (List<Map<String, Object>>) summary.get("recent");
        assertEquals("newest", recent.get(0).get("_id"));
    }
}
