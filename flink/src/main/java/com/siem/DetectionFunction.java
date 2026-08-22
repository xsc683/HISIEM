package com.siem;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.api.common.functions.FlatMapFunction;
import org.apache.flink.util.Collector;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 单事件规则评估:对每条事件逐规则求值,命中则输出符合 Alert Schema 的告警 JSON。
 *
 * 告警为扁平结构(决策 D):关键事件字段提升到告警顶层,完整事件存为扁平字符串 event.raw。
 */
public class DetectionFunction implements FlatMapFunction<Event, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RuleRegistry registry;

    public DetectionFunction(RuleRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void flatMap(Event event, Collector<String> out) throws Exception {
        Map<String, Object> fields = event.getFields();
        for (Rule rule : registry.getRules()) {
            if (rule.getCondition().matches(fields)) {
                out.collect(MAPPER.writeValueAsString(buildAlert(event.getRawJson(), fields, rule)));
            }
        }
    }

    private Map<String, Object> buildAlert(String rawJson, Map<String, Object> event, Rule rule) {
        Map<String, Object> alert = new LinkedHashMap<>();
        alert.put("@timestamp", event.getOrDefault("@timestamp", Instant.now().toString()));
        alert.put("alert.created_at", Instant.now().toString());
        alert.put("alert.status", "open");
        alert.put("alert.status_updated_at", Instant.now().toString());
        alert.put("alert.id", UUID.randomUUID().toString());
        alert.put("alert.rule_id", rule.getId());
        alert.put("alert.rule_name", rule.getName());
        alert.put("alert.type", rule.getType());
        alert.put("alert.severity", rule.getSeverity());
        alert.put("alert.description", rule.getDescription());
        alert.put("alert.risk_score", rule.getRiskScore());
        alert.put("rule.tags", rule.getTags());
        alert.put("rule.status", rule.getStatus());
        alert.put("rule.version", rule.getVersion());
        promote(alert, event, "log.source_id");
        promote(alert, event, "log.source_name");
        promote(alert, event, "source.ip");
        promote(alert, event, "user.name");
        promote(alert, event, "host.name");
        promote(alert, event, "event.action");
        promote(alert, event, "event.category");
        alert.put("event.raw", rawJson);
        alert.put("event_count", 1);
        Ocsf.applyAuthView(alert, rule.getSeverity());
        return alert;
    }

    private static void promote(Map<String, Object> alert, Map<String, Object> event, String field) {
        Object value = event.get(field);
        if (value != null) {
            alert.put(field, value);
        }
    }
}
