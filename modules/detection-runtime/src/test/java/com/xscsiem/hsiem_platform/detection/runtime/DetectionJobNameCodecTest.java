package com.xscsiem.hsiem_platform.detection.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DetectionJobNameCodecTest {

    @Test
    void stableKeyAndStructuredNameAreRoundTrippableWithoutUserStrings() {
        DetectionJobNameCodec codec = new DetectionJobNameCodec();
        String key = codec.jobKey("tenant/with spaces", "cluster", "group/with?input");
        String hash = "a".repeat(64);
        String name = codec.encode(key, 9L, hash);

        assertEquals(key, codec.decode(name).jobKey());
        assertEquals(9L, codec.decode(name).generation());
        assertEquals(hash, codec.decode(name).manifestHash());
        org.junit.jupiter.api.Assertions.assertFalse(name.contains("tenant"));
        org.junit.jupiter.api.Assertions.assertFalse(name.contains("group"));
    }

    @Test
    void malformedNameAndUnsafeIdentityAreRejected() {
        DetectionJobNameCodec codec = new DetectionJobNameCodec();
        assertThrows(IllegalArgumentException.class, () -> codec.decode("SIEM Detection Engine"));
        assertThrows(IllegalArgumentException.class, () -> codec.encode("dg-../escape", 1L, "a".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> codec.encode("dg-" + "a".repeat(24), 1L, "bad"));
    }
}
