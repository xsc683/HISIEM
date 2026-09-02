package com.siem;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.util.Collector;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class RuleEngineTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private Map<String, Object> event(String user) throws Exception {
        String json = String.format(
                "{\"@timestamp\":\"2026-08-01T11:30:00.000Z\","
                + "\"event.action\":\"authentication_failure\","
                + "\"user.name\":\"%s\","
                + "\"source.ip\":\"192.168.1.100\","
                + "\"host.name\":\"server05\"}", user);
        return EventParser.parse(json);
    }

    private Event physicalEvent(String id, String action) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("event.id", id);
        fields.put("event.action", action);
        fields.put("source.ip", "192.168.1.100");
        fields.put("user.name", "alice");
        fields.put("host.name", "server05");
        return new Event("raw-" + id, fields, 1785583800000L);
    }

    private static Collector<String> collector(List<String> records) {
        return new Collector<>() {
            @Override
            public void collect(String record) {
                records.add(record);
            }

            @Override
            public void close() {
            }
        };
    }

    private Map<String, Object> parseJson(String json) throws Exception {
        return JSON.readValue(json, new TypeReference<Map<String, Object>>() {
        });
    }

    private List<String> relatedEventIds(Map<String, Object> alert) {
        List<String> ids = new ArrayList<>();
        for (Object relatedEvent : (List<?>) alert.get("related_events")) {
            ids.add((String) ((Map<?, ?>) relatedEvent).get("event.id"));
        }
        return ids;
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
                + "\"host.name\":\"server04\","
                + "\"log.source_id\":\"ls-demo\","
                + "\"log.source_name\":\"demo-ssh\"}";
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
        assertTrue(out.stream().allMatch(a -> a.contains("\"log.source_id\":\"ls-demo\"")));
    }

    @Test
    void customCepOutputStepsConsumeConfiguredPhysicalSteps() throws Exception {
        List<Event> failures = List.of(
                physicalEvent("custom-failure-1", "authentication_failure"),
                physicalEvent("custom-failure-2", "authentication_failure"),
                physicalEvent("custom-failure-3", "authentication_failure"));
        Event success = physicalEvent("custom-success", "authentication_success");
        Event legacySuccess = physicalEvent("legacy-success", "authentication_success");
        BruteforceSuccessFunction fn = new BruteforceSuccessFunction(
                "rule-custom", "Custom brute force", "ssh_brute_force", "critical", "description",
                80, List.of("credential_access"), "enabled", "2.0", "failed-logins", "successful-login");

        List<String> withoutConfiguredSuccess = new ArrayList<>();
        Map<String, List<Event>> missingConfiguredSuccess = new LinkedHashMap<>();
        missingConfiguredSuccess.put("failed-logins", failures);
        missingConfiguredSuccess.put("success", List.of(legacySuccess));
        fn.processMatch(missingConfiguredSuccess, null, collector(withoutConfiguredSuccess));
        assertTrue(withoutConfiguredSuccess.isEmpty());

        List<String> output = new ArrayList<>();
        Map<String, List<Event>> match = new LinkedHashMap<>();
        match.put("failures", List.of(physicalEvent("legacy-failure", "authentication_failure")));
        match.put("success", List.of(legacySuccess));
        match.put("failed-logins", failures);
        match.put("successful-login", List.of(success));
        fn.processMatch(match, null, collector(output));

        assertEquals(1, output.size());
        Map<String, Object> alert = parseJson(output.get(0));
        assertEquals(4, alert.get("event_count"));
        assertEquals(List.of("custom-failure-1", "custom-failure-2", "custom-failure-3", "custom-success"),
                relatedEventIds(alert));
        assertEquals("custom-success", ((Map<?, ?>) ((List<?>) alert.get("related_events")).get(3)).get("event.id"));
    }

    @Test
    void legacyCepConstructorConsumesDefaultOutputSteps() throws Exception {
        List<Event> failures = List.of(
                physicalEvent("legacy-failure-1", "authentication_failure"),
                physicalEvent("legacy-failure-2", "authentication_failure"));
        Event success = physicalEvent("legacy-success", "authentication_success");
        BruteforceSuccessFunction fn = new BruteforceSuccessFunction(
                "rule-legacy", "Legacy brute force", "ssh_brute_force", "critical", "description",
                70, List.of("credential_access"), "enabled", "1.0");
        Map<String, List<Event>> match = new LinkedHashMap<>();
        match.put("failures", failures);
        match.put("success", List.of(success));
        List<String> output = new ArrayList<>();

        fn.processMatch(match, null, collector(output));

        assertEquals(1, output.size());
        Map<String, Object> alert = parseJson(output.get(0));
        assertEquals(3, alert.get("event_count"));
        assertEquals(List.of("legacy-failure-1", "legacy-failure-2", "legacy-success"), relatedEventIds(alert));
    }
}
