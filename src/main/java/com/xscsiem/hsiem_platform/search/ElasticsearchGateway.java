package com.xscsiem.hsiem_platform.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch.core.CountRequest;
import co.elastic.clients.elasticsearch.core.DeleteRequest;
import co.elastic.clients.elasticsearch.core.GetRequest;
import co.elastic.clients.elasticsearch.core.GetResponse;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.UpdateRequest;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用 Java API Client 承载项目当前需要的 ES 动词，返回兼容现有服务层的 Map 结构。
 * 查询 DSL 仍由各领域服务构造，网关负责索引、连接和响应转换。
 */
@Component
public class ElasticsearchGateway {

    private final ElasticsearchClient client;
    private final ObjectMapper mapper;

    public ElasticsearchGateway(ElasticsearchClient client, ObjectMapper mapper) {
        this.client = client;
        this.mapper = mapper;
    }

    public Response request(String method, String path, String body) {
        try {
            String cleanPath = path.startsWith("/") ? path.substring(1) : path;
            String[] segments = cleanPath.split("/", 4);
            if (segments.length < 2) {
                throw new IllegalArgumentException("ES 路径非法: " + path);
            }
            String index = segments[0];
            String operation = segments[1];
            if ("_search".equals(operation)) {
                return search(index, body);
            }
            if ("_count".equals(operation)) {
                return count(index, body);
            }
            if ("_doc".equals(operation) && "GET".equalsIgnoreCase(method) && segments.length >= 3) {
                return get(index, segments[2]);
            }
            if ("_doc".equals(operation) && "DELETE".equalsIgnoreCase(method) && segments.length >= 3) {
                return delete(index, segments[2]);
            }
            if ("_update".equals(operation) && "POST".equalsIgnoreCase(method) && segments.length >= 3) {
                return update(index, segments[2], path, body);
            }
            throw new UnsupportedOperationException("ES 动词暂未接入 Java Client: " + method + " " + path);
        } catch (ElasticsearchException e) {
            int status = e.status();
            return new Response(status, Map.of("error", e.getMessage()));
        } catch (Exception e) {
            throw new IllegalStateException("ES 不可达: " + e.getMessage(), e);
        }
    }

    private Response search(String index, String body) throws Exception {
        SearchRequest.Builder builder = new SearchRequest.Builder().index(index);
        if (body != null) {
            builder.withJson(new StringReader(normalizeSearchDsl(body)));
        }
        SearchResponse<Map> response = client.search(builder.build(), Map.class);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("took", response.took());
        Map<String, Object> hits = new LinkedHashMap<>();
        hits.put("total", response.hits().total() == null ? 0 : response.hits().total().value());
        List<Map<String, Object>> rows = new ArrayList<>();
        response.hits().hits().forEach(hit -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("_id", hit.id());
            row.put("_source", hit.source());
            if (hit.seqNo() != null) row.put("_seq_no", hit.seqNo());
            if (hit.primaryTerm() != null) row.put("_primary_term", hit.primaryTerm());
            rows.add(row);
        });
        hits.put("hits", rows);
        out.put("hits", hits);
        if (response.aggregations() != null && !response.aggregations().isEmpty()) {
            out.put("aggregations", mapper.convertValue(response.aggregations(),
                    new TypeReference<Map<String, Object>>() {}));
        }
        return new Response(200, out);
    }

    /**
     * 旧 REST DSL 允许 `_source: ["field"]`，Java API Client 8.x 只接受对象/布尔配置。
     * 删除该可选裁剪字段后仍返回完整 source，由现有服务层按需取字段，保持语义一致。
     */
    private String normalizeSearchDsl(String body) throws Exception {
        var json = mapper.readTree(body);
        if (json instanceof ObjectNode object && object.get("_source") != null
                && object.get("_source").isArray()) {
            object.remove("_source");
        }
        return mapper.writeValueAsString(json);
    }

    private Response count(String index, String body) throws Exception {
        CountRequest.Builder builder = new CountRequest.Builder().index(index);
        if (body != null) {
            builder.withJson(new StringReader(body));
        }
        return new Response(200, Map.of("count", client.count(builder.build()).count()));
    }

    private Response get(String index, String id) throws Exception {
        GetResponse<Map> response = client.get(new GetRequest.Builder()
                .index(index).id(id).build(), Map.class);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("_id", response.id());
        out.put("found", response.found());
        out.put("_source", response.source());
        if (response.seqNo() != null) out.put("_seq_no", response.seqNo());
        if (response.primaryTerm() != null) out.put("_primary_term", response.primaryTerm());
        return new Response(200, out);
    }

    private Response update(String index, String id, String path, String body) throws Exception {
        UpdateRequest.Builder<Map<String, Object>, Map<String, Object>> builder = new UpdateRequest.Builder<>();
        builder.index(index).id(id);
        if (path.contains("if_seq_no=")) {
            builder.ifSeqNo(Long.parseLong(queryValue(path, "if_seq_no")));
        }
        if (path.contains("if_primary_term=")) {
            builder.ifPrimaryTerm(Long.parseLong(queryValue(path, "if_primary_term")));
        }
        builder.withJson(new StringReader(body == null ? "{}" : body));
        var response = client.update(builder.build(), Map.class);
        return new Response(200, Map.of("result", response.result().jsonValue()));
    }

    private Response delete(String index, String id) throws Exception {
        var response = client.delete(new DeleteRequest.Builder().index(index).id(id).build());
        return new Response(200, Map.of("result", response.result().jsonValue()));
    }

    private static String queryValue(String path, String key) {
        for (String pair : path.substring(path.indexOf('?') + 1).split("&")) {
            String[] parts = pair.split("=", 2);
            if (parts.length == 2 && key.equals(parts[0])) return parts[1];
        }
        return "0";
    }

    public record Response(int code, Map<String, Object> body) {
    }
}
