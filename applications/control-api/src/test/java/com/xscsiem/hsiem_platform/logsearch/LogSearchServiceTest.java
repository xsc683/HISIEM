package com.xscsiem.hsiem_platform.logsearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xscsiem.hsiem_platform.search.ElasticsearchGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LogSearchServiceTest {

    private ElasticsearchGateway gateway;
    private ObjectMapper mapper;
    private LogSearchService service;

    @BeforeEach
    void setUp() {
        gateway = mock(ElasticsearchGateway.class);
        mapper = new ObjectMapper();
        service = new LogSearchService(gateway, mapper);
    }

    @Test
    void catalog_exposesNormalizedFieldsAndTypeSafeOperators() {
        LogSearchCatalog catalog = service.catalog();

        LogSearchCatalog.Field sourceIp = catalog.fields().stream()
                .filter(field -> "source.ip".equals(field.name()))
                .findFirst().orElseThrow();
        LogSearchCatalog.Field message = catalog.fields().stream()
                .filter(field -> "message".equals(field.name()))
                .findFirst().orElseThrow();

        assertEquals("ip", sourceIp.type());
        assertFalse(sourceIp.operators().contains("contain"));
        assertTrue(message.operators().containsAll(List.of("contain", "not_contain")));
        assertFalse(message.operators().contains("is"));
        assertTrue(catalog.fields().stream().anyMatch(field -> "log.source_id".equals(field.name())));
    }

    @Test
    void search_buildsControlledAndQuery_excludesRawIndex_andMapsResponse() throws Exception {
        Map<String, Object> source = Map.of(
                "@timestamp", "2026-08-24T12:10:00Z",
                "source.ip", "198.51.100.10",
                "message", "Failed password for alice");
        when(gateway.request(eq("POST"), eq("/siem-events-*,-siem-events-raw-*/_search"), anyString()))
                .thenReturn(new ElasticsearchGateway.Response(200, Map.of(
                        "took", 7,
                        "hits", Map.of("total", 1L, "hits", List.of(
                                Map.of("_id", "event-1", "_index", "siem-events-2026.08.24",
                                        "_source", source))))));
        LogSearchRequest request = new LogSearchRequest(
                "2026-08-24T12:00:00Z", "2026-08-24T13:00:00Z",
                0, 25, "desc", "AND", List.of(
                new LogSearchRequest.Condition("source.ip", "is", "198.51.100.10"),
                new LogSearchRequest.Condition("message", "contain", "Failed password"),
                new LogSearchRequest.Condition("tags", "not_is_one_of", List.of("noise", "test"))));

        LogSearchResponse result = service.search(request);

        assertEquals(1, result.total());
        assertEquals(7, result.tookMs());
        assertEquals("event-1", result.items().getFirst().get("_id"));
        assertEquals("siem-events-2026.08.24", result.items().getFirst().get("_index"));
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(gateway).request(eq("POST"), eq("/siem-events-*,-siem-events-raw-*/_search"), body.capture());
        JsonNode json = mapper.readTree(body.getValue());
        assertEquals(0, json.path("from").asInt());
        assertEquals(25, json.path("size").asInt());
        assertTrue(json.path("track_total_hits").asBoolean());
        JsonNode filters = json.path("query").path("bool").path("filter");
        assertEquals(4, filters.size());
        assertEquals("198.51.100.10", filters.get(1).path("term").path("source.ip").asText());
        assertEquals("Failed password", filters.get(2).path("match_phrase").path("message").asText());
        assertEquals("noise", filters.get(3).path("bool").path("must_not").get(0)
                .path("terms").path("tags").get(0).asText());
    }

    @Test
    void search_buildsOrGroup_andEscapesWildcardMetacharacters() throws Exception {
        when(gateway.request(eq("POST"), anyString(), anyString()))
                .thenReturn(new ElasticsearchGateway.Response(200,
                        Map.of("hits", Map.of("total", 0L, "hits", List.of()))));
        LogSearchRequest request = new LogSearchRequest(
                "2026-08-24T00:00:00Z", "2026-08-24T01:00:00Z",
                1, 10, "asc", "OR", List.of(
                new LogSearchRequest.Condition("user.name", "contain", "ad*min?"),
                new LogSearchRequest.Condition("host.name", "not_exist", null)));

        service.search(request);

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(gateway).request(eq("POST"), eq("/siem-events-*,-siem-events-raw-*/_search"), body.capture());
        JsonNode json = mapper.readTree(body.getValue());
        assertEquals(10, json.path("from").asInt());
        assertEquals("asc", json.path("sort").get(0).path("@timestamp").path("order").asText());
        JsonNode any = json.path("query").path("bool").path("filter").get(1).path("bool");
        assertEquals(1, any.path("minimum_should_match").asInt());
        assertEquals("*ad\\*min\\?*", any.path("should").get(0)
                .path("wildcard").path("user.name").path("value").asText());
        assertEquals("host.name", any.path("should").get(1).path("bool").path("must_not").get(0)
                .path("exists").path("field").asText(), json.toPrettyString());
    }

    @Test
    void search_rejectsUnknownFieldBeforeCallingElasticsearch() {
        LogSearchRequest request = new LogSearchRequest(
                "2026-08-24T00:00:00Z", "2026-08-24T01:00:00Z",
                0, 50, "desc", "AND", List.of(
                new LogSearchRequest.Condition("source.ip\"}}],\"script\":{", "is", "x")));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.search(request));

        assertTrue(error.getMessage().contains("筛选字段不受支持"));
        verify(gateway, never()).request(anyString(), anyString(), anyString());
    }

    @Test
    void search_rejectsUnsupportedOperatorAndOversizedPage() {
        LogSearchRequest badOperator = new LogSearchRequest(
                "2026-08-24T00:00:00Z", "2026-08-24T01:00:00Z",
                0, 50, "desc", "AND", List.of(
                new LogSearchRequest.Condition("source.ip", "contain", "198.51")));
        LogSearchRequest deepPage = new LogSearchRequest(
                "2026-08-24T00:00:00Z", "2026-08-24T01:00:00Z",
                50, 200, "desc", "AND", List.of());

        assertThrows(IllegalArgumentException.class, () -> service.search(badOperator));
        assertThrows(IllegalArgumentException.class, () -> service.search(deepPage));
        verify(gateway, never()).request(anyString(), anyString(), anyString());
    }

    @Test
    void search_rejectsTimeRangeLongerThanNinetyDays() {
        LogSearchRequest request = new LogSearchRequest(
                "2026-01-01T00:00:00Z", "2026-08-24T01:00:00Z",
                0, 50, "desc", "AND", List.of());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.search(request));

        assertTrue(error.getMessage().contains("90 天"));
    }

    @Test
    void search_translatesElasticsearchFailureToServiceUnavailable() {
        when(gateway.request(eq("POST"), anyString(), anyString()))
                .thenReturn(new ElasticsearchGateway.Response(503, Map.of("error", "unavailable")));

        assertThrows(LogSearchUnavailableException.class, () -> service.search(new LogSearchRequest(
                "2026-08-24T00:00:00Z", "2026-08-24T01:00:00Z",
                0, 50, "desc", "AND", List.of())));
    }
}
