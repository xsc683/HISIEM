package com.xscsiem.hsiem_platform.soar;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SoarTriggerEnvelopeTest {

    @Test
    void manualEnvelopeUsesStableRequestIdAndKeepsActorAsProducerMetadata() {
        SoarTriggerEnvelope envelope = SoarTriggerEnvelope.manual(
                "req-123", "alert.created", "default", "alert", "a-1",
                Map.of("severity", "high"), "analyst");
        assertEquals("req-123", envelope.messageId());
        assertEquals("manual:analyst", envelope.producer());
        assertEquals("alert.created", envelope.eventType());
        assertTrue(envelope.kafka() == null);
    }

    @Test
    void manualEnvelopeRejectsRequestIdThatCannotFitDurableDeduplicationColumn() {
        assertThrows(IllegalArgumentException.class, () -> SoarTriggerEnvelope.manual(
                "x".repeat(129), "alert.created", "default", "alert", "a-1", Map.of(), "analyst"));
    }
}
