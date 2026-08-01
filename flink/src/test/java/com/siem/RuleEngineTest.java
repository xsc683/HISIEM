package com.siem;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class RuleEngineTest {

    private Map<String, Object> event(String user) throws Exception {
        String json = String.format(
                "{\"@timestamp\":\"2026-08-01T11:30:00.000Z\","
                + "\"event.action\":\"authentication_failure\","
                + "\"user.name\":\"%s\","
                + "\"source.ip\":\"192.168.1.100\","
                + "\"host.name\":\"server05\"}", user);
        return EventParser.parse(json);
    }

    @Test
    void sshFailureRuleHitsAnyAuthFailure() throws Exception {
        Rule rule = new Rule("r", "t", "x", "medium", "d",
                new FieldEqualsCondition("event.action", "authentication_failure"));
        assertTrue(rule.getCondition().matches(event("alice")));
    }

    @Test
    void rootRuleHitsOnlyRoot() throws Exception {
        Rule rootRule = new Rule("r", "t", "x", "high", "d",
                new AllCondition(
                        new FieldEqualsCondition("event.action", "authentication_failure"),
                        new FieldEqualsCondition("user.name", "root")));
        assertTrue(rootRule.getCondition().matches(event("root")));
        assertFalse(rootRule.getCondition().matches(event("alice")));
    }

    @Test
    void commonUserRuleHitsCommonUsersOnly() throws Exception {
        Rule common = new Rule("r", "t", "x", "high", "d",
                new AllCondition(
                        new FieldEqualsCondition("event.action", "authentication_failure"),
                        new FieldInCondition("user.name", "admin", "test", "guest")));
        assertTrue(common.getCondition().matches(event("admin")));
        assertTrue(common.getCondition().matches(event("test")));
        assertFalse(common.getCondition().matches(event("alice")));
    }

    @Test
    void registryHasThreeRules() {
        assertEquals(3, new RuleRegistry().getRules().size());
    }

    @Test
    void flattenNestedEventToDottedKeys() throws Exception {
        Map<String, Object> ev = EventParser.parse(
                "{\"source\":{\"ip\":\"1.2.3.4\"},"
                + "\"event\":{\"action\":\"authentication_failure\"},"
                + "\"message\":\"raw\"}");
        assertEquals("1.2.3.4", ev.get("source.ip"));
        assertEquals("authentication_failure", ev.get("event.action"));
        assertEquals("raw", ev.get("message"));
    }

    @Test
    void detectionFunctionEmitsAlertForMatchingRule() throws Exception {
        RuleRegistry registry = new RuleRegistry();
        DetectionFunction fn = new DetectionFunction(registry);
        String eventJson = "{\"@timestamp\":\"2026-08-01T11:30:00.000Z\","
                + "\"event.action\":\"authentication_failure\","
                + "\"user.name\":\"root\","
                + "\"source.ip\":\"10.0.0.8\","
                + "\"host.name\":\"server04\"}";
        java.util.List<String> out = new java.util.ArrayList<>();
        fn.flatMap(EventParser.parseEvent(eventJson), new org.apache.flink.util.Collector<>() {
            @Override
            public void collect(String record) {
                out.add(record);
            }

            @Override
            public void close() {
            }
        });
        // root 事件命中 2 条规则:SSH 认证失败 + root 认证失败
        assertEquals(2, out.size());
        assertTrue(out.stream().anyMatch(a -> a.contains("\"alert.rule_id\":\"rule-root-login-failure-001\"")));
    }
}
