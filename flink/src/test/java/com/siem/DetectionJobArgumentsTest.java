package com.siem;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DetectionJobArgumentsTest {

    @Test
    void parsesManagedArgumentsAndRejectsPartialOrUnsafeValues() {
        String hash = "b".repeat(64);
        DetectionJobArguments parsed = DetectionJobArguments.parse(
                new String[]{"/rules", "dg-" + "a".repeat(24), "12", hash}, Map.of());

        assertTrue(parsed.managed());
        assertEquals(12L, parsed.generation());
        assertEquals(hash, parsed.manifestHash());
        assertEquals("SIEM-DETECTION-dg-" + "a".repeat(24) + "-g12-m" + hash,
                DetectionJob.structuredJobName(parsed));
        assertThrows(IllegalArgumentException.class, () -> DetectionJobArguments.parse(
                new String[]{"/rules", "dg-" + "a".repeat(24), "12"}, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> DetectionJobArguments.parse(
                new String[]{"/rules", "dg-../escape", "12", hash}, Map.of()));
    }

    @Test
    void supportsCompleteEnvironmentBridgeAndExplicitLegacyMode() {
        String key = "dg-" + "c".repeat(24);
        String hash = "d".repeat(64);
        DetectionJobArguments managed = DetectionJobArguments.parse(new String[]{"/rules"},
                Map.of("SIEM_JOB_KEY", key, "SIEM_JOB_GENERATION", "2", "SIEM_MANIFEST_HASH", hash));
        DetectionJobArguments legacy = DetectionJobArguments.parse(new String[]{"/rules"}, Map.of());

        assertTrue(managed.managed());
        assertFalse(legacy.managed());
        assertTrue(legacy.legacy());
        assertThrows(IllegalArgumentException.class, () -> DetectionJobArguments.parse(new String[]{"/rules"},
                Map.of("SIEM_JOB_KEY", key)));
    }
}
