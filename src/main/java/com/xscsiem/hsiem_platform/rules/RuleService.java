package com.xscsiem.hsiem_platform.rules;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.xscsiem.hsiem_platform.onboarding.NotFoundException;
import com.xscsiem.hsiem_platform.onboarding.ConflictException;
import com.xscsiem.hsiem_platform.control.ConfigRevisionJournal;
import com.xscsiem.hsiem_platform.control.ControlPlaneStore;
import com.xscsiem.hsiem_platform.search.ElasticsearchGateway;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 检测规则管理(story-03):读 infra/rules/*.yaml(检测即代码单一来源,与 Flink 同一份)。
 * 启停 = 改写 enabled 字段(生效需 deploy → 重启检测 job,一次重部署成本)。
 */
@Service
public class RuleService {

    private static final Pattern RULE_ID = Pattern.compile("[a-z0-9][a-z0-9-]{2,95}");
    private static final Pattern FIELD_NAME = Pattern.compile("[A-Za-z@][A-Za-z0-9_.@-]{0,127}");
    private static final Set<String> SEVERITIES = Set.of("low", "medium", "high", "critical");
    private static final Set<String> EDITABLE_CATEGORIES = Set.of("single_event", "window");

    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
    private final String rulesDir;
    private final String esUrl;
    private final ElasticsearchGateway gateway;
    private final ControlPlaneStore control;

    @Autowired
    public RuleService(@Value("${app.rules-dir:infra/rules}") String rulesDir,
                       @Value("${app.elasticsearch.url:http://localhost:9200}") String esUrl,
                       ElasticsearchGateway gateway,
                       ControlPlaneStore control) {
        this.rulesDir = rulesDir;
        this.esUrl = esUrl;
        this.gateway = gateway;
        this.control = control;
    }

    /** 纯逻辑单元测试构造器。 */
    public RuleService(String rulesDir, String esUrl) {
        this.rulesDir = rulesDir;
        this.esUrl = esUrl;
        this.gateway = null;
        this.control = null;
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

    /**
     * 创建一条检测即代码规则。UI 只开放 Flink 已完整支持并可结构化编辑的
     * single_event/window；CEP 与 baseline 仍通过代码评审维护，避免表单静默丢字段。
     */
    public Map<String, Object> create(Map<String, Object> payload, String actor) {
        Map<String, Object> rule = normalizeAndValidate(payload, null);
        String id = String.valueOf(rule.get("id"));
        Path path = rulePath(id);
        if (Files.exists(path)) {
            throw new ConflictException("规则 ID 已存在: " + id);
        }
        writeRule(path, rule, actor, "rule_create");
        return rule;
    }

    /** 更新规则完整声明；路径 ID 是稳定主键，不允许在编辑时改名。 */
    public Map<String, Object> update(String id, Map<String, Object> payload, String actor) {
        Path path = rulePath(id);
        if (!Files.isRegularFile(path)) {
            throw new NotFoundException("规则不存在: " + id);
        }
        Map<String, Object> rule = normalizeAndValidate(payload, id);
        writeRule(path, rule, actor, "rule_update");
        return rule;
    }

    /** 翻转 enabled 并写回 YAML(生效需 deploy → 重启检测 job)。 */
    public Map<String, Object> toggle(String id) {
        return toggle(id, "system");
    }

    public Map<String, Object> toggle(String id, String actor) {
        Map<String, Object> m = get(id);
        boolean enabled = Boolean.TRUE.equals(m.get("enabled"));
        m.put("enabled", !enabled);
        writeEnabledOnly(id, !enabled, actor);
        if (control != null) {
            control.audit(actor == null || actor.isBlank() ? "system" : actor,
                    "rule_toggle", id);
        }
        return m;
    }

    /** 规则是检测即代码,启停只应产生一行可 review 的 diff,不能重排/丢失 YAML 注释。 */
    private void writeEnabledOnly(String id, boolean enabled, String actor) {
        Path path = new File(rulesDir, id + ".yaml").toPath();
        try {
            String original = Files.readString(path);
            Pattern pattern = Pattern.compile("(?m)^([ \\t]*enabled:[ \\t]*)(true|false)([ \\t]*)(\\r?)$");
            Matcher matcher = pattern.matcher(original);
            if (!matcher.find()) {
                throw new IllegalStateException("规则缺少 enabled 字段: " + id);
            }
            String replacement = matcher.group(1) + enabled + matcher.group(3) + matcher.group(4);
            String updated = matcher.replaceFirst(Matcher.quoteReplacement(replacement));
            ConfigRevisionJournal.atomicWrite(path, updated);
            ConfigRevisionJournal.record(control, "rule", path, actor);
        } catch (IOException e) {
            throw new IllegalStateException("规则保存失败: " + id, e);
        }
    }

    private void writeRule(Path path, Map<String, Object> rule, String actor, String auditAction) {
        String operator = actor == null || actor.isBlank() ? "system" : actor;
        try {
            ConfigRevisionJournal.atomicWrite(path, yamlMapper.writeValueAsString(rule));
            ConfigRevisionJournal.record(control, "rule", path, operator);
            if (control != null) {
                control.audit(operator, auditAction, String.valueOf(rule.get("id")));
            }
        } catch (IOException e) {
            throw new IllegalStateException("规则保存失败: " + rule.get("id"), e);
        }
    }

    private Path rulePath(String id) {
        if (id == null || !RULE_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("规则 ID 只能包含小写字母、数字和连字符，长度 3-96");
        }
        Path directory = Path.of(rulesDir).toAbsolutePath().normalize();
        Path path = directory.resolve(id + ".yaml").normalize();
        if (!path.getParent().equals(directory)) {
            throw new IllegalArgumentException("非法规则 ID");
        }
        return path;
    }

    private Map<String, Object> normalizeAndValidate(Map<String, Object> payload, String stableId) {
        if (payload == null) {
            throw new IllegalArgumentException("规则内容不能为空");
        }
        Map<String, Object> rule = new LinkedHashMap<>(payload);
        String id = text(rule.get("id"), "id");
        rulePath(id);
        if (stableId != null && !stableId.equals(id)) {
            throw new IllegalArgumentException("规则 ID 是稳定主键，编辑时不能修改");
        }
        String name = text(rule.get("name"), "name");
        String category = text(rule.get("category"), "category");
        String type = text(rule.get("type"), "type");
        String severity = text(rule.get("severity"), "severity").toLowerCase();
        if (!EDITABLE_CATEGORIES.contains(category)) {
            throw new IllegalArgumentException("可视化写入仅支持 single_event 或 window 规则");
        }
        if (!SEVERITIES.contains(severity)) {
            throw new IllegalArgumentException("severity 必须是 low/medium/high/critical");
        }
        if (!type.matches("[a-z0-9][a-z0-9_]{1,95}")) {
            throw new IllegalArgumentException("type 只能包含小写字母、数字和下划线");
        }
        if (name.length() > 160) {
            throw new IllegalArgumentException("name 最长 160 个字符");
        }
        rule.put("id", id);
        rule.put("name", name);
        rule.put("category", category);
        rule.put("type", type);
        rule.put("severity", severity);
        rule.putIfAbsent("enabled", true);
        if (!(rule.get("enabled") instanceof Boolean)) {
            throw new IllegalArgumentException("enabled 必须是布尔值");
        }
        rule.putIfAbsent("description", "");
        rule.putIfAbsent("riskScore", 50);
        int riskScore = integer(rule.get("riskScore"), "riskScore", 0, 100);
        rule.put("riskScore", riskScore);
        rule.putIfAbsent("tags", List.of());
        rule.putIfAbsent("status", "experimental");
        rule.putIfAbsent("version", "1.0");
        rule.putIfAbsent("references", List.of());
        String status = text(rule.get("status"), "status");
        rule.put("status", status);
        rule.put("version", text(rule.get("version"), "version"));
        String description = rule.get("description") == null
                ? "" : String.valueOf(rule.get("description")).trim();
        if (description.length() > 2_000) {
            throw new IllegalArgumentException("description 最长 2000 个字符");
        }
        rule.put("description", description);
        validateStringList(rule.get("tags"), "tags", 50);
        validateStringList(rule.get("references"), "references", 50);
        if (!Set.of("experimental", "stable", "deprecated").contains(status)) {
            throw new IllegalArgumentException("status 必须是 experimental/stable/deprecated");
        }
        validateCondition(rule.get("condition"), "condition", 0);
        if ("window".equals(category)) {
            String keyField = text(rule.get("keyField"), "keyField");
            validateField(keyField, "keyField");
            long window = longValue(rule.get("windowMinutes"), "windowMinutes", 1, 1440);
            int threshold = integer(rule.get("threshold"), "threshold", 2, 1_000_000);
            rule.put("windowMinutes", window);
            rule.put("threshold", threshold);
            if (rule.get("slidingMinutes") != null) {
                long sliding = longValue(rule.get("slidingMinutes"), "slidingMinutes", 1, window);
                rule.put("slidingMinutes", sliding);
            } else {
                rule.remove("slidingMinutes");
            }
            if (rule.get("alertSuppressionMinutes") != null) {
                rule.put("alertSuppressionMinutes", longValue(rule.get("alertSuppressionMinutes"),
                        "alertSuppressionMinutes", 1, 10_080));
            } else {
                rule.remove("alertSuppressionMinutes");
            }
        } else {
            rule.remove("keyField");
            rule.remove("windowMinutes");
            rule.remove("slidingMinutes");
            rule.remove("alertSuppressionMinutes");
            rule.remove("threshold");
        }
        // 防止 UI 把只属于其他执行分支的结构混入可编辑规则。
        rule.remove("cep");
        rule.remove("baseline");
        return rule;
    }

    @SuppressWarnings("unchecked")
    private void validateCondition(Object raw, String path, int depth) {
        if (!(raw instanceof Map<?, ?> input)) {
            throw new IllegalArgumentException(path + " 必须是条件对象");
        }
        if (depth > 8) {
            throw new IllegalArgumentException("条件嵌套不能超过 8 层");
        }
        Map<String, Object> condition = (Map<String, Object>) input;
        String kind = text(condition.get("type"), path + ".type");
        switch (kind) {
            case "field_equals" -> {
                validateField(text(condition.get("field"), path + ".field"), path + ".field");
                if (!condition.containsKey("value") || condition.get("value") == null) {
                    throw new IllegalArgumentException(path + ".value 不能为空");
                }
            }
            case "field_in" -> {
                validateField(text(condition.get("field"), path + ".field"), path + ".field");
                Object values = condition.get("values");
                if (!(values instanceof List<?> list) || list.isEmpty() || list.size() > 100) {
                    throw new IllegalArgumentException(path + ".values 必须包含 1-100 个值");
                }
            }
            case "all", "any" -> validateChildren(condition.get("conditions"), path, depth, false);
            case "not" -> validateChildren(condition.get("conditions"), path, depth, true);
            default -> throw new IllegalArgumentException("未知条件类型: " + kind);
        }
    }

    private void validateChildren(Object raw, String path, int depth, boolean exactlyOne) {
        if (!(raw instanceof List<?> children) || children.isEmpty()
                || (exactlyOne && children.size() != 1) || children.size() > 20) {
            throw new IllegalArgumentException(path + ".conditions "
                    + (exactlyOne ? "必须恰好包含一个子条件" : "必须包含 1-20 个子条件"));
        }
        for (int i = 0; i < children.size(); i++) {
            validateCondition(children.get(i), path + ".conditions[" + i + "]", depth + 1);
        }
    }

    private static void validateField(String value, String name) {
        if (!FIELD_NAME.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " 不是合法 ECS/点分字段名");
        }
    }

    private static String text(Object value, String name) {
        String text = value == null ? "" : String.valueOf(value).trim();
        if (text.isEmpty()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return text;
    }

    private static int integer(Object value, String name, int min, int max) {
        try {
            int number = value instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(value));
            if (number < min || number > max) throw new NumberFormatException();
            return number;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " 必须在 " + min + "-" + max + " 之间");
        }
    }

    private static long longValue(Object value, String name, long min, long max) {
        try {
            long number = value instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(value));
            if (number < min || number > max) throw new NumberFormatException();
            return number;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " 必须在 " + min + "-" + max + " 之间");
        }
    }

    private static void validateStringList(Object raw, String name, int max) {
        if (!(raw instanceof List<?> values) || values.size() > max) {
            throw new IllegalArgumentException(name + " 必须是最多 " + max + " 项的数组");
        }
        Set<String> unique = new HashSet<>();
        for (Object value : values) {
            String item = text(value, name);
            if (item.length() > 500 || !unique.add(item)) {
                throw new IllegalArgumentException(name + " 含重复项或超长内容");
            }
        }
    }

    /** 最近 range 内该规则在 siem-alerts 的命中数;ES 不可达返回 -1。 */
    public long hits(String id, String range) {
        String body = "{\"query\":{\"bool\":{\"filter\":[{\"term\":{\"alert.rule_id\":\""
                + id + "\"}},{\"range\":{\"@timestamp\":{\"gte\":\"now-" + range + "\"}}}]}}}";
        try {
            if (gateway != null) {
                ElasticsearchGateway.Response response = gateway.request("POST", "/siem-alerts/_count", body);
                if (response.code() / 100 != 2) {
                    return -1;
                }
                Object count = response.body().get("count");
                return count instanceof Number n ? n.longValue() : 0L;
            }
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
