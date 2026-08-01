package com.siem;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.api.common.functions.FlatMapFunction;
import org.apache.flink.util.Collector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 规则评估函数:解析事件 → 逐规则求值 → 命中则输出符合 Alert Schema 的告警 JSON。
 *
 * 告警为扁平结构(决策 D):关键事件字段提升到告警顶层,完整事件存为扁平字符串 event.raw。
 */
public class DetectionFunction implements FlatMapFunction<String, String> {

    private static final Logger LOG = LoggerFactory.getLogger(DetectionFunction.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RuleRegistry registry;

    public DetectionFunction(RuleRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void flatMap(String json, Collector<String> out) throws Exception {
        Map<String, Object> event;
        try {
            event = EventParser.parse(json);
        } catch (Exception e) {
            LOG.warn("跳过无法解析的事件: {}", json, e);
            return;
        }

        for (Rule rule : registry.getRules()) {
            if (rule.getCondition().matches(event)) {
                out.collect(MAPPER.writeValueAsString(buildAlert(json, event, rule)));
            }
        }
    }

    private Map<String, Object> buildAlert(String rawJson, Map<String, Object> event, Rule rule) {
        Map<String, Object> alert = new LinkedHashMap<>();
        alert.put("@timestamp", event.getOrDefault("@timestamp", Instant.now().toString()));
        alert.put("alert.created_at", Instant.now().toString());
        alert.put("alert.id", UUID.randomUUID().toString());
        alert.put("alert.rule_id", rule.getId());
        alert.put("alert.rule_name", rule.getName());
        alert.put("alert.type", rule.getType());
        alert.put("alert.severity", rule.getSeverity());
        alert.put("alert.description", rule.getDescription());
        promote(alert, event, "source.ip");
        promote(alert, event, "user.name");
        promote(alert, event, "host.name");
        promote(alert, event, "event.action");
        promote(alert, event, "event.category");
        alert.put("event.raw", rawJson);
        alert.put("event_count", 1);
        return alert;
    }

    private static void promote(Map<String, Object> alert, Map<String, Object> event, String field) {
        Object value = event.get(field);
        if (value != null) {
            alert.put(field, value);
        }
    }
}
