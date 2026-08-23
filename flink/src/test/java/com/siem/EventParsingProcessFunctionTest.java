package com.siem;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventParsingProcessFunctionTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void validEventContinuesToDetectionStream() {
        EventParsingProcessFunction.ParseOutcome outcome = EventParsingProcessFunction.parse(
                "{\"@timestamp\":\"2026-08-24T00:00:00Z\",\"source\":{\"ip\":\"192.0.2.7\"}}");

        assertNotNull(outcome.event());
        assertNull(outcome.dlqRecord());
        assertEquals("192.0.2.7", outcome.event().getFields().get("source.ip"));
    }

    @Test
    void malformedJsonIsIsolatedWithOriginalAndFailureMetadata() throws Exception {
        EventParsingProcessFunction.ParseOutcome outcome = EventParsingProcessFunction.parse("{broken-json");

        assertNull(outcome.event());
        Map<String, Object> dlq = mapper.readValue(outcome.dlqRecord(), new TypeReference<>() { });
        assertEquals("flink.event-parser", dlq.get("dlq.stage"));
        assertEquals("{broken-json", dlq.get("event.original"));
        assertTrue(String.valueOf(dlq.get("dlq.id")).matches("[0-9a-f]{64}"));
        assertTrue(String.valueOf(dlq.get("dlq.error_message")).length() > 0);
    }

    @Test
    void missingOrInvalidTimestampGoesToDlqInsteadOfUsingProcessingTime() throws Exception {
        EventParsingProcessFunction.ParseOutcome missing = EventParsingProcessFunction.parse("{\"event.action\":\"x\"}");
        EventParsingProcessFunction.ParseOutcome invalid = EventParsingProcessFunction.parse(
                "{\"@timestamp\":\"not-a-time\"}");

        assertNull(missing.event());
        assertNull(invalid.event());
        Map<String, Object> missingDlq = mapper.readValue(missing.dlqRecord(), new TypeReference<>() { });
        Map<String, Object> invalidDlq = mapper.readValue(invalid.dlqRecord(), new TypeReference<>() { });
        assertTrue(String.valueOf(missingDlq.get("dlq.error_message")).contains("缺少 @timestamp"));
        assertTrue(String.valueOf(invalidDlq.get("dlq.error_message")).contains("不是 ISO-8601"));
    }
}
