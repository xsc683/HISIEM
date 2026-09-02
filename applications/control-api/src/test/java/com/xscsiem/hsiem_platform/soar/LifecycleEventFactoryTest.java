package com.xscsiem.hsiem_platform.soar;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LifecycleEventFactoryTest {

    @Test
    void alertContractUsesNestedStableFieldNames() throws Exception {
        LifecycleEvent event = new LifecycleEventFactory().alert("alert.created", Map.of(
                "alert.id", "alert-1", "alert.rule_id", "rule-1", "alert.risk_score", 88,
                "source.ip", "198.51.100.1", "@timestamp", "2026-08-23T12:00:00Z"), "default");

        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(event);
        assertEquals("alert-1", event.alert().get("id"));
        assertEquals(88, event.alert().get("risk_score"));
        assertFalse(json.contains("siem-events"));
        assertFalse(json.contains("event.original"));
    }

    @Test
    void repeatedConstructionUsesStableMessageIdAndOccurrence() {
        LifecycleEventFactory factory = new LifecycleEventFactory();
        Map<String, Object> source = Map.of(
                "_id", "alert-1", "@timestamp", "2026-08-23T12:00:00Z");

        LifecycleEvent first = factory.alert("alert.created", source, "tenant-1");
        LifecycleEvent second = factory.alert("alert.created", source, "tenant-1");
        LifecycleEvent changedOccurrence = factory.alert("alert.created", Map.of(
                "_id", "alert-1", "@timestamp", "2026-08-23T12:00:01Z"), "tenant-1");

        assertEquals(first.messageId(), second.messageId());
        assertEquals(first.occurredAt(), second.occurredAt());
        assertNotEquals(first.messageId(), changedOccurrence.messageId());
    }

    @Test
    void occurredAtUsesTheBusinessTimestampForEachEventType() {
        LifecycleEventFactory factory = new LifecycleEventFactory();
        assertEquals(Instant.parse("2026-08-23T12:00:00Z"), factory.alert("alert.created", Map.of(
                "_id", "alert-1", "@timestamp", "2026-08-23T12:00:00Z",
                "alert.created_at", "2026-08-23T12:01:00Z"), "default").occurredAt());
        assertEquals(Instant.parse("2026-08-23T12:01:00Z"), factory.alert("alert.created", Map.of(
                "_id", "alert-1", "alert.created_at", "2026-08-23T12:01:00Z"), "default").occurredAt());
        assertEquals(Instant.parse("2026-08-23T12:02:00Z"), factory.alert("alert.updated", Map.of(
                "_id", "alert-1", "@timestamp", "2026-08-23T12:00:00Z",
                "alert.status_updated_at", "2026-08-23T12:02:00Z"), "default").occurredAt());
        assertEquals(Instant.parse("2026-08-23T12:03:00Z"), factory.caseEvent("case.created", Map.of(
                "case.id", "case-1", "case.created_at", "2026-08-23T12:03:00Z"), "default").occurredAt());
        assertEquals(Instant.parse("2026-08-23T12:04:00Z"), factory.caseEvent("case.updated", Map.of(
                "case.id", "case-1", "case.updated_at", "2026-08-23T12:04:00Z"), "default").occurredAt());
    }

    @Test
    void missingBlankOrMalformedBusinessTimestampIsRejected() {
        LifecycleEventFactory factory = new LifecycleEventFactory();

        assertThrows(IllegalArgumentException.class, () -> factory.alert("alert.created",
                Map.of("_id", "alert-1"), "default"));
        assertThrows(IllegalArgumentException.class, () -> factory.alert("alert.created", Map.of(
                "_id", "alert-1", "@timestamp", "not-an-instant",
                "alert.created_at", "2026-08-23T12:00:00Z"), "default"));
        assertThrows(IllegalArgumentException.class, () -> factory.alert("alert.updated",
                Map.of("_id", "alert-1", "alert.status_updated_at", " "), "default"));
        assertThrows(IllegalArgumentException.class, () -> factory.caseEvent("case.created",
                Map.of("case.id", "case-1", "case.created_at", "not-an-instant"), "default"));
        assertThrows(IllegalArgumentException.class, () -> factory.caseEvent("case.updated",
                Map.of("case.id", "case-1"), "default"));
    }
}
