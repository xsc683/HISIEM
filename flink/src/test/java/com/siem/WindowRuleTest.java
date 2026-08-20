package com.siem;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class WindowRuleTest {

    private WindowRule bruteForceRule() {
        return new WindowRule(
                "rule-ssh-brute-force-001", "SSH 暴力破解", "ssh_brute_force", "critical", "desc",
                "source.ip", new FieldEqualsCondition("event.action", "authentication_failure"), 5, 5);
    }

    private Map<String, Object> authEvent(String ip) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("@timestamp", "2026-08-01T11:30:00.000Z");
        m.put("event.action", "authentication_failure");
        m.put("source.ip", ip);
        return m;
    }

    @Test
    void bruteForceAlertHasCountAndRelatedEvents() {
        WindowRuleFunction fn = new WindowRuleFunction(bruteForceRule());
        List<Map<String, Object>> matched = new ArrayList<>();
        matched.add(authEvent("1.2.3.4"));
        matched.add(authEvent("1.2.3.4"));
        matched.add(authEvent("1.2.3.4"));

        Map<String, Object> alert = fn.buildAlert("1.2.3.4", matched, 0L);

        assertEquals("critical", alert.get("alert.severity"));
        assertEquals("ssh_brute_force", alert.get("alert.type"));
        assertEquals("rule-ssh-brute-force-001", alert.get("alert.rule_id"));
        assertEquals("1.2.3.4", alert.get("alert.entity"));
        assertEquals("1.2.3.4", alert.get("source.ip"));
        assertEquals("1.0", alert.get("rule.version"));
        assertEquals(3, alert.get("event_count"));
        assertEquals(3, ((List<?>) alert.get("related_events")).size());
        assertEquals("authentication_failure", alert.get("event.action"));
    }

    @Test
    void windowRuleConditionMatchesOnlyAuthFailure() {
        WindowRule rule = bruteForceRule();
        assertTrue(rule.getCondition().matches(authEvent("1.2.3.4")));
        assertFalse(rule.getCondition().matches(Map.of("event.action", "other")));
    }

    @Test
    void eventParserExtractsTimestampAndFlattens() throws Exception {
        Event e = EventParser.parseEvent(
                "{\"@timestamp\":\"2026-08-01T11:30:00.000Z\","
                + "\"event\":{\"action\":\"authentication_failure\"},"
                + "\"source\":{\"ip\":\"1.2.3.4\"}}");
        assertEquals(Instant.parse("2026-08-01T11:30:00.000Z").toEpochMilli(), e.getTimestampMillis());
        assertEquals("authentication_failure", e.getFields().get("event.action"));
        assertEquals("1.2.3.4", e.getFields().get("source.ip"));
    }
}
