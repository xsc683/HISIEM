package com.xscsiem.hsiem_platform.soar;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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
}
