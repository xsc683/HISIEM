package com.siem.config;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RuntimeTuningTest {

    @Test
    void defaults_reduceSinkPressureAndAllowSlowCheckpoint() {
        RuntimeTuning tuning = RuntimeTuning.defaults();
        assertEquals(30_000, tuning.checkpointIntervalMs());
        assertEquals(10 * 60_000, tuning.checkpointTimeoutMs());
        assertEquals(3, tuning.esMaxInFlightRequests());
        assertEquals(500, tuning.esMaxBufferedRequests());
    }

    @Test
    void invalidOverridesFallBackAndValidOverridesApply() {
        RuntimeTuning tuning = RuntimeTuning.from(Map.of(
                "SIEM_FLINK_ES_MAX_IN_FLIGHT", "8",
                "SIEM_FLINK_CHECKPOINT_INTERVAL_MS", "bad",
                "SIEM_FLINK_ES_MAX_BUFFER_MS", "0"));
        assertEquals(8, tuning.esMaxInFlightRequests());
        assertEquals(30_000, tuning.checkpointIntervalMs());
        assertEquals(500, tuning.esMaxTimeInBufferMs());
    }
}
