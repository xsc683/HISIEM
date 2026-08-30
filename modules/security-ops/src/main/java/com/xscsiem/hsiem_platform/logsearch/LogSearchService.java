package com.xscsiem.hsiem_platform.logsearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xscsiem.hsiem_platform.search.ElasticsearchGateway;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Elasticsearch 安全日志检索：字段、操作符、索引和 DSL 形状均由后端控制。
 * 原始日志(raw)索引从目标表达式显式排除，避免解析失败数据混入正常事件结果。
 */
@Service
public class LogSearchService {

    static final String SEARCH_INDEX = "siem-events-*,-siem-events-raw-*";
    static final int MAX_SIZE = 200;
    static final int MAX_CONDITIONS = 20;
    static final int MAX_VALUES_PER_CONDITION = 50;
    static final int MAX_VALUE_LENGTH = 512;
    static final int MAX_RESULT_WINDOW = 10_000;
    static final Duration DEFAULT_RANGE = Duration.ofHours(24);
    static final Duration MAX_RANGE = Duration.ofDays(90);

    private static final List<String> ALL_OPERATORS = List.of(
            "is", "contain", "exist", "is_one_of",
            "not_is", "not_contain", "not_exist", "not_is_one_of");
    private static final Set<String> EXACT_OPERATORS = Collections.unmodifiableSet(new LinkedHashSet<>(List.of(
            "is", "exist", "is_one_of", "not_is", "not_exist", "not_is_one_of")));
    private static final Set<String> TEXT_OPERATORS = Collections.unmodifiableSet(new LinkedHashSet<>(List.of(
            "contain", "exist", "not_contain", "not_exist")));
    private static final Map<String, FieldDefinition> FIELDS = fields();

    private final ElasticsearchGateway gateway;
    private final ObjectMapper mapper;

    public LogSearchService(ElasticsearchGateway gateway, ObjectMapper mapper) {
        this.gateway = gateway;
        this.mapper = mapper;
    }

    public LogSearchCatalog catalog() {
        List<LogSearchCatalog.Field> fields = FIELDS.values().stream()
                .map(field -> new LogSearchCatalog.Field(field.name(), field.label(),
                        field.type().apiName, List.copyOf(field.operators())))
                .toList();
        return new LogSearchCatalog(fields, ALL_OPERATORS);
    }

    public LogSearchResponse search(LogSearchRequest request) {
        LogSearchRequest input = request == null
                ? new LogSearchRequest(null, null, null, null, null, null, null)
                : request;
        int page = input.page() == null ? 0 : input.page();
        int size = input.size() == null ? 50 : input.size();
        validatePage(page, size);
        String sort = normalizeSort(input.sort());
        String logic = normalizeLogic(input.logic());
        TimeRange range = timeRange(input.from(), input.to());
        List<LogSearchRequest.Condition> conditions = input.conditions() == null
                ? List.of() : input.conditions();
        if (conditions.size() > MAX_CONDITIONS) {
            throw new IllegalArgumentException("筛选条件不能超过 " + MAX_CONDITIONS + " 个");
        }

        ObjectNode body = mapper.createObjectNode();
        body.put("from", page * size);
        body.put("size", size);
        body.put("track_total_hits", true);
        ArrayNode sortArray = body.putArray("sort");
        sortArray.addObject().putObject("@timestamp").put("order", sort);

        ObjectNode bool = body.putObject("query").putObject("bool");
        ArrayNode filters = bool.putArray("filter");
        filters.addObject().putObject("range").putObject("@timestamp")
                .put("gte", range.from().toString())
                .put("lte", range.to().toString());

        List<JsonNode> conditionQueries = new ArrayList<>();
        for (int i = 0; i < conditions.size(); i++) {
            conditionQueries.add(conditionQuery(conditions.get(i), i));
        }
        if ("AND".equals(logic)) {
            conditionQueries.forEach(filters::add);
        } else if (!conditionQueries.isEmpty()) {
            ObjectNode any = mapper.createObjectNode();
            ArrayNode should = any.putObject("bool").putArray("should");
            conditionQueries.forEach(should::add);
            ((ObjectNode) any.get("bool")).put("minimum_should_match", 1);
            filters.add(any);
        }

        ElasticsearchGateway.Response response;
        try {
            response = gateway.request("POST", "/" + SEARCH_INDEX + "/_search", mapper.writeValueAsString(body));
        } catch (Exception e) {
            throw new LogSearchUnavailableException("日志检索服务暂不可用", e);
        }
        if (response.code() < 200 || response.code() >= 300) {
            throw new LogSearchUnavailableException("Elasticsearch 日志检索失败");
        }
        return response(response.body(), page, size, range);
    }

    private JsonNode conditionQuery(LogSearchRequest.Condition condition, int index) {
        if (condition == null) {
            throw new IllegalArgumentException("筛选条件[" + index + "]不能为空");
        }
        String fieldName = trimmed(condition.field());
        FieldDefinition field = FIELDS.get(fieldName);
        if (field == null) {
            throw new IllegalArgumentException("筛选字段不受支持: " + fieldName);
        }
        String operator = trimmed(condition.operator()).toLowerCase(Locale.ROOT);
        if (!field.operators().contains(operator)) {
            throw new IllegalArgumentException("字段 " + fieldName + " 不支持关系: " + operator);
        }

        boolean negative = operator.startsWith("not_");
        String positiveOperator = negative ? operator.substring(4) : operator;
        JsonNode positive = positiveQuery(field, positiveOperator, condition.value(), index);
        if (!negative) {
            return positive;
        }
        ObjectNode wrapped = mapper.createObjectNode();
        wrapped.putObject("bool").putArray("must_not").add(positive);
        return wrapped;
    }

    private JsonNode positiveQuery(FieldDefinition field, String operator, Object value, int index) {
        if ("exist".equals(operator)) {
            ObjectNode root = mapper.createObjectNode();
            root.putObject("exists").put("field", field.name());
            return root;
        }
        if ("is_one_of".equals(operator)) {
            List<String> values = listValue(value, index);
            if (field.type() == FieldType.TEXT) {
                ObjectNode any = mapper.createObjectNode();
                ArrayNode should = any.putObject("bool").putArray("should");
                values.forEach(item -> should.add(matchPhrase(field.name(), item)));
                ((ObjectNode) any.get("bool")).put("minimum_should_match", 1);
                return any;
            }
            ObjectNode root = mapper.createObjectNode();
            ObjectNode terms = root.putObject("terms");
            ArrayNode items = terms.putArray(field.name());
            values.forEach(items::add);
            return root;
        }

        String item = scalarValue(value, index);
        if ("contain".equals(operator)) {
            if (field.type() == FieldType.TEXT) {
                return matchPhrase(field.name(), item);
            }
            ObjectNode root = mapper.createObjectNode();
            ObjectNode wildcard = root.putObject("wildcard").putObject(field.name());
            wildcard.put("value", "*" + escapeWildcard(item) + "*");
            wildcard.put("case_insensitive", true);
            return root;
        }
        if (field.type() == FieldType.TEXT) {
            return matchPhrase(field.name(), item);
        }
        ObjectNode root = mapper.createObjectNode();
        root.putObject("term").put(field.name(), item);
        return root;
    }

    private ObjectNode matchPhrase(String field, String value) {
        ObjectNode root = mapper.createObjectNode();
        root.putObject("match_phrase").put(field, value);
        return root;
    }

    private LogSearchResponse response(Map<String, Object> body, int page, int size, TimeRange range) {
        List<Map<String, Object>> items = new ArrayList<>();
        long total = 0L;
        Object hitsObject = body == null ? null : body.get("hits");
        if (hitsObject instanceof Map<?, ?> hits) {
            Object totalObject = hits.get("total");
            if (totalObject instanceof Number number) {
                total = number.longValue();
            } else if (totalObject instanceof Map<?, ?> totalMap && totalMap.get("value") instanceof Number number) {
                total = number.longValue();
            }
            if (hits.get("hits") instanceof List<?> rows) {
                for (Object rowObject : rows) {
                    if (!(rowObject instanceof Map<?, ?> row) || !(row.get("_source") instanceof Map<?, ?> source)) {
                        continue;
                    }
                    Map<String, Object> item = new LinkedHashMap<>();
                    source.forEach((key, value) -> item.put(String.valueOf(key), value));
                    item.put("_id", row.get("_id"));
                    item.put("_index", row.get("_index"));
                    items.add(item);
                }
            }
        }
        long took = body != null && body.get("took") instanceof Number number ? number.longValue() : 0L;
        return new LogSearchResponse(items, page, size, total, took,
                range.from().toString(), range.to().toString());
    }

    private static void validatePage(int page, int size) {
        if (page < 0) {
            throw new IllegalArgumentException("page 不能小于 0");
        }
        if (size < 1 || size > MAX_SIZE) {
            throw new IllegalArgumentException("size 必须在 1-" + MAX_SIZE + " 之间");
        }
        long offset = (long) page * size;
        if (offset + size > MAX_RESULT_WINDOW) {
            throw new IllegalArgumentException("分页范围不能超过 " + MAX_RESULT_WINDOW + " 条，请缩小时间范围");
        }
    }

    private static String normalizeSort(String sort) {
        String normalized = sort == null || sort.isBlank() ? "desc" : sort.trim().toLowerCase(Locale.ROOT);
        if (!Set.of("asc", "desc").contains(normalized)) {
            throw new IllegalArgumentException("sort 仅支持 asc 或 desc");
        }
        return normalized;
    }

    private static String normalizeLogic(String logic) {
        String normalized = logic == null || logic.isBlank() ? "AND" : logic.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("AND", "OR").contains(normalized)) {
            throw new IllegalArgumentException("logic 仅支持 AND 或 OR");
        }
        return normalized;
    }

    private static TimeRange timeRange(String fromText, String toText) {
        Instant to = toText == null || toText.isBlank() ? Instant.now() : instant(toText, "to");
        Instant from = fromText == null || fromText.isBlank() ? to.minus(DEFAULT_RANGE) : instant(fromText, "from");
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("from 不能晚于 to");
        }
        if (Duration.between(from, to).compareTo(MAX_RANGE) > 0) {
            throw new IllegalArgumentException("单次检索时间跨度不能超过 90 天");
        }
        return new TimeRange(from, to);
    }

    private static Instant instant(String value, String field) {
        if (value.length() > 64) {
            throw new IllegalArgumentException(field + " 时间格式非法");
        }
        try {
            return Instant.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(field + " 必须是 ISO-8601 UTC 时间", e);
        }
    }

    private static String scalarValue(Object value, int index) {
        if (value == null || value instanceof Iterable<?> || value.getClass().isArray()) {
            throw new IllegalArgumentException("筛选条件[" + index + "]需要单个 value");
        }
        return validateValue(String.valueOf(value), index);
    }

    private static List<String> listValue(Object value, int index) {
        if (!(value instanceof List<?> values) || values.isEmpty()) {
            throw new IllegalArgumentException("筛选条件[" + index + "]的 value 必须是非空数组");
        }
        if (values.size() > MAX_VALUES_PER_CONDITION) {
            throw new IllegalArgumentException("单个 is_one_of 最多支持 " + MAX_VALUES_PER_CONDITION + " 个值");
        }
        List<String> out = new ArrayList<>();
        for (Object item : values) {
            if (item == null || item instanceof Map<?, ?> || item instanceof Iterable<?>) {
                throw new IllegalArgumentException("筛选条件[" + index + "]包含非法 value");
            }
            out.add(validateValue(String.valueOf(item), index));
        }
        return out;
    }

    private static String validateValue(String value, int index) {
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("筛选条件[" + index + "]的 value 不能为空");
        }
        if (normalized.length() > MAX_VALUE_LENGTH) {
            throw new IllegalArgumentException("筛选条件[" + index + "]的 value 不能超过 "
                    + MAX_VALUE_LENGTH + " 个字符");
        }
        return normalized;
    }

    private static String escapeWildcard(String value) {
        return value.replace("\\", "\\\\").replace("*", "\\*").replace("?", "\\?");
    }

    private static String trimmed(String value) {
        return value == null ? "" : value.trim();
    }

    private static Map<String, FieldDefinition> fields() {
        LinkedHashMap<String, FieldDefinition> fields = new LinkedHashMap<>();
        add(fields, "@timestamp", "事件时间", FieldType.DATE);
        add(fields, "message", "日志消息", FieldType.TEXT);
        add(fields, "event.original", "原始日志", FieldType.TEXT);
        add(fields, "event.category", "事件类别", FieldType.KEYWORD);
        add(fields, "event.action", "事件动作", FieldType.KEYWORD);
        add(fields, "event.outcome", "事件结果", FieldType.KEYWORD);
        add(fields, "event.type", "事件类型", FieldType.KEYWORD);
        add(fields, "event.code", "事件代码", FieldType.KEYWORD);
        add(fields, "event.schema_version", "事件模型版本", FieldType.KEYWORD);
        add(fields, "source.ip", "源 IP", FieldType.IP);
        add(fields, "source.port", "源端口", FieldType.KEYWORD);
        add(fields, "destination.ip", "目的 IP", FieldType.IP);
        add(fields, "destination.port", "目的端口", FieldType.KEYWORD);
        add(fields, "client.ip", "客户端 IP", FieldType.IP);
        add(fields, "user.name", "用户名", FieldType.KEYWORD);
        add(fields, "host.name", "主机名", FieldType.KEYWORD);
        add(fields, "related.ip", "关联 IP", FieldType.KEYWORD);
        add(fields, "source.geo.country_name", "源国家/地区", FieldType.KEYWORD);
        add(fields, "source.geo.city_name", "源城市", FieldType.KEYWORD);
        add(fields, "network.transport", "网络协议", FieldType.KEYWORD);
        add(fields, "network.direction", "网络方向", FieldType.KEYWORD);
        add(fields, "http.request.method", "HTTP 方法", FieldType.KEYWORD);
        add(fields, "http.request.url", "HTTP 请求路径", FieldType.KEYWORD);
        add(fields, "http.response.status_code", "HTTP 状态码", FieldType.KEYWORD);
        add(fields, "http.response.body.bytes", "HTTP 响应字节数", FieldType.KEYWORD);
        add(fields, "log.source_id", "日志源 ID", FieldType.KEYWORD);
        add(fields, "log.source_name", "日志源名称", FieldType.KEYWORD);
        add(fields, "pipeline", "处理管道", FieldType.KEYWORD);
        add(fields, "tags", "标签", FieldType.KEYWORD);
        add(fields, "winlog.domain", "Windows 域", FieldType.KEYWORD);
        add(fields, "winlog.audit_outcome", "Windows 审计结果", FieldType.KEYWORD);
        add(fields, "logon.type", "登录类型", FieldType.KEYWORD);
        return Collections.unmodifiableMap(fields);
    }

    private static void add(Map<String, FieldDefinition> fields, String name, String label, FieldType type) {
        Set<String> operators = switch (type) {
            case TEXT -> TEXT_OPERATORS;
            case IP, DATE -> EXACT_OPERATORS;
            case KEYWORD -> Collections.unmodifiableSet(new LinkedHashSet<>(ALL_OPERATORS));
        };
        fields.put(name, new FieldDefinition(name, label, type, operators));
    }

    private enum FieldType {
        KEYWORD("keyword"), TEXT("text"), IP("ip"), DATE("date");

        private final String apiName;

        FieldType(String apiName) {
            this.apiName = apiName;
        }
    }

    private record FieldDefinition(String name, String label, FieldType type, Set<String> operators) {
    }

    private record TimeRange(Instant from, Instant to) {
    }
}
