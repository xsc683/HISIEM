package com.siem;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AlertLifecycleEventMapperTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void usesTimestampBeforeCreatedAtAndPreservesEnvelopeFields() throws Exception {
        Map<String, Object> envelope = map("{\"@timestamp\":\"2026-08-23T12:00:00Z\","
                + "\"alert.created_at\":\"2026-08-23T12:01:00Z\","
                + "\"alert.rule_id\":\"rule-1\",\"alert.risk_score\":88}");

        assertEquals("alert.created", envelope.get("event_type"));
        assertEquals("hsiem-flink", envelope.get("producer"));
        assertEquals("default", envelope.get("tenant_id"));
        assertEquals("2026-08-23T12:00:00Z", envelope.get("occurred_at"));
        assertEquals(88, ((Map<?, ?>) envelope.get("alert")).get("risk_score"));
    }

    @Test
    void repeatedMappingIsStableAndChangedOccurrenceChangesMessageId() throws Exception {
        String alert = "{\"alert.created_at\":\"2026-08-23T12:00:00Z\","
                + "\"alert.rule_id\":\"rule-1\",\"source.ip\":\"198.51.100.1\"}";
        Map<String, Object> first = map(alert);
        Map<String, Object> second = map(alert);
        Map<String, Object> changedOccurrence = map(alert.replace(
                "2026-08-23T12:00:00Z", "2026-08-23T12:00:01Z"));

        assertEquals(first.get("message_id"), second.get("message_id"));
        assertEquals(first.get("occurred_at"), second.get("occurred_at"));
        assertNotEquals(first.get("message_id"), changedOccurrence.get("message_id"));
        assertEquals(Instant.parse("2026-08-23T12:00:00Z"),
                Instant.parse((String) first.get("occurred_at")));
    }

    @Test
    void fallsBackToCreatedAtWhenTimestampIsAbsent() throws Exception {
        Map<String, Object> envelope = map("{\"alert.created_at\":\"2026-08-23T12:05:00Z\","
                + "\"alert.rule_id\":\"rule-1\"}");

        assertEquals("2026-08-23T12:05:00Z", envelope.get("occurred_at"));
    }

    @Test
    void rejectsMissingBlankOrMalformedBusinessTimestamp() {
        assertThrows(IllegalArgumentException.class, () -> map(
                "{\"alert.rule_id\":\"rule-1\"}"));
        assertThrows(IllegalArgumentException.class, () -> map(
                "{\"@timestamp\":\" \",\"alert.created_at\":\"2026-08-23T12:00:00Z\"}"));
        assertThrows(IllegalArgumentException.class, () -> map(
                "{\"@timestamp\":\"not-an-instant\",\"alert.created_at\":\"2026-08-23T12:00:00Z\"}"));
    }

    private Map<String, Object> map(String alertJson) throws Exception {
        return mapper.readValue(AlertLifecycleEventMapper.map(alertJson), new TypeReference<>() { });
    }
}
