package com.xscsiem.hsiem_platform.rules;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.xscsiem.hsiem_platform.onboarding.NotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 检测规则管理(story-03):读 infra/rules/*.yaml(检测即代码单一来源,与 Flink 同一份)。
 * 启停 = 改写 enabled 字段(生效需 deploy → 重启检测 job,一次重部署成本)。
 */
@Service
public class RuleService {

    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
    private final String rulesDir;
    private final String esUrl;

    public RuleService(@Value("${app.rules-dir:infra/rules}") String rulesDir,
                       @Value("${app.elasticsearch.url:http://localhost:9200}") String esUrl) {
        this.rulesDir = rulesDir;
        this.esUrl = esUrl;
    }

    public List<Map<String, Object>> list() {
        File dir = new File(rulesDir);
        File[] files = dir.listFiles((d, n) -> n.endsWith(".yaml") || n.endsWith(".yml"));
        List<Map<String, Object>> out = new ArrayList<>();
        if (files != null) {
            for (File f : files) {
                try {
                    Map<String, Object> m = yamlMapper.readValue(f, Map.class);
                    if (m.get("id") != null) {
                        out.add(m);
                    }
                } catch (IOException e) {
                    throw new IllegalStateException("规则加载失败: " + f.getName(), e);
                }
            }
        }
        out.sort(Comparator.comparing(m -> String.valueOf(m.get("id"))));
        return out;
    }

    public Map<String, Object> get(String id) {
        return list().stream()
                .filter(m -> id.equals(m.get("id")))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("规则不存在: " + id));
    }

    /** 翻转 enabled 并写回 YAML(生效需 deploy → 重启检测 job)。 */
    public Map<String, Object> toggle(String id) {
        Map<String, Object> m = get(id);
        boolean enabled = Boolean.TRUE.equals(m.get("enabled"));
        m.put("enabled", !enabled);
        write(m, id);
        return m;
    }

    private void write(Map<String, Object> rule, String id) {
        File f = new File(rulesDir, id + ".yaml");
        try {
            yamlMapper.writeValue(f, rule);
        } catch (IOException e) {
            throw new IllegalStateException("规则保存失败: " + id, e);
        }
    }

    /** 最近 range 内该规则在 siem-alerts 的命中数;ES 不可达返回 -1。 */
    public long hits(String id, String range) {
        String body = "{\"query\":{\"bool\":{\"filter\":[{\"term\":{\"alert.rule_id\":\""
                + id + "\"}},{\"range\":{\"@timestamp\":{\"gte\":\"now-" + range + "\"}}}]}}}";
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(3)).build();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(esUrl + "/siem-alerts/_count"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            Map<String, Object> r = new ObjectMapper().readValue(resp.body(), Map.class);
            return ((Number) r.getOrDefault("count", 0)).longValue();
        } catch (Exception e) {
            return -1;
        }
    }

    /** MITRE 覆盖矩阵:按各规则 tags 聚合(Detected);Blind 盲区见 docs/design/mitre-coverage.md 手工标注。 */
    public Map<String, Object> mitre() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> rule : list()) {
            Object tags = rule.get("tags");
            List<?> tagList = tags instanceof List ? (List<?>) tags : List.of();
            for (Object tag : tagList) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("technique", String.valueOf(tag));
                row.put("ruleId", rule.get("id"));
                row.put("ruleName", rule.get("name"));
                row.put("enabled", rule.get("enabled"));
                row.put("coverage", "Detected");
                rows.add(row);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("source", "规则 YAML tags 动态聚合;Blind 盲区见 mitre-coverage.md");
        out.put("coverage", rows);
        return out;
    }
}
