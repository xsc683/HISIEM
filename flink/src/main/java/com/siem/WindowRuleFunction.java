package com.siem;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.streaming.api.functions.windowing.ProcessWindowFunction;
import org.apache.flink.streaming.api.windowing.windows.TimeWindow;
import org.apache.flink.util.Collector;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 时间窗口规则评估:窗口结束时统计满足条件的事件数,>= 阈值则输出暴力破解类告警。
 *
 * 告警复用 Alert Schema:event_count 记录命中数,related_events 存窗口内的事件快照。
 */
public class WindowRuleFunction extends ProcessWindowFunction<Event, String, String, TimeWindow> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final WindowRule rule;

    public WindowRuleFunction(WindowRule rule) {
        this.rule = rule;
    }

    @Override
    public void process(String key, Context context, Iterable<Event> elements, Collector<String> out)
            throws Exception {
        List<Map<String, Object>> matched = new ArrayList<>();
        for (Event e : elements) {
            if (rule.getCondition().matches(e.getFields())) {
                matched.add(e.getFields());
            }
        }
        if (matched.size() >= rule.getThreshold()) {
            out.collect(MAPPER.writeValueAsString(buildAlert(key, matched, context.window().getEnd())));
        }
    }

    Map<String, Object> buildAlert(String key, List<Map<String, Object>> matched, long windowEndMillis) {
        Map<String, Object> alert = new LinkedHashMap<>();
        alert.put("@timestamp", Instant.ofEpochMilli(windowEndMillis).toString());
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
        alert.put(rule.getKeyField(), key);
        if (!matched.isEmpty()) {
            Map<String, Object> first = matched.get(0);
            for (String f : new String[]{"event.action", "event.category", "user.name", "host.name"}) {
                if (first.containsKey(f)) {
                    alert.put(f, first.get(f));
                }
            }
        }
        alert.put("event_count", matched.size());
        alert.put("related_events", matched);
        Ocsf.applyAuthView(alert, rule.getSeverity());
        return alert;
    }
}
