package com.siem;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.cep.functions.PatternProcessFunction;
import org.apache.flink.util.Collector;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * CEP 攻击链规则产出(Phase 3.2):同一源 IP 短时间多次认证失败后成功登录 = 暴力破解得逞。
 * 输出一条 critical 告警,related_events 携带失败序列 + 成功事件,给出攻击完整叙事。
 */
public class BruteforceSuccessFunction extends PatternProcessFunction<Event, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String ruleId;
    private final String ruleName;
    private final String type;
    private final String severity;
    private final String description;
    private final int riskScore;
    private final List<String> tags;
    private final String status;

    public BruteforceSuccessFunction(String ruleId, String ruleName, String type, String severity,
                                     String description, int riskScore, List<String> tags, String status) {
        this.ruleId = ruleId;
        this.ruleName = ruleName;
        this.type = type;
        this.severity = severity;
        this.description = description;
        this.riskScore = riskScore;
        this.tags = tags;
        this.status = status;
    }

    @Override
    public void processMatch(Map<String, List<Event>> match, Context ctx, Collector<String> out) throws Exception {
        List<Event> failures = match.getOrDefault("failures", List.of());
        List<Event> successes = match.getOrDefault("success", List.of());
        if (successes.isEmpty()) {
            return;
        }
        Event success = successes.get(0);
        Map<String, Object> fields = success.getFields();

        Map<String, Object> alert = new LinkedHashMap<>();
        alert.put("@timestamp", Instant.ofEpochMilli(success.getTimestampMillis()).toString());
        alert.put("alert.created_at", Instant.now().toString());
        alert.put("alert.id", UUID.randomUUID().toString());
        alert.put("alert.rule_id", ruleId);
        alert.put("alert.rule_name", ruleName);
        alert.put("alert.type", type);
        alert.put("alert.severity", severity);
        alert.put("alert.risk_score", riskScore);
        alert.put("alert.description", description);
        alert.put("rule.tags", tags);
        alert.put("rule.status", status);

        for (String f : new String[]{"source.ip", "user.name", "host.name"}) {
            Object v = fields.get(f);
            if (v != null) {
                alert.put(f, v);
            }
        }
        alert.put("event.action", "authentication_success");
        alert.put("event_count", failures.size() + 1);

        List<Map<String, Object>> related = new ArrayList<>(failures.size() + 1);
        for (Event f : failures) {
            related.add(f.getFields());
        }
        related.add(fields);
        alert.put("related_events", related);

        Ocsf.applyAuthView(alert, severity);
        out.collect(MAPPER.writeValueAsString(alert));
    }
}
