package com.xscsiem.hsiem_platform.soar;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SoarConnectorRegistryTest {

    @Test
    void acceptsFixedBaseAndTypedActionSchema() {
        SoarConnector connector = new SoarConnector("threat-intel", "Threat Intel", "", true,
                null, "THREAT_INTEL_URL", false, false,
                new SoarConnector.Auth("bearer", "THREAT_INTEL_TOKEN", null),
                Map.of("lookup-ip", new SoarConnector.Action("GET", "/v1/ip/${ip}",
                        10, List.of("ip"), 65536)));

        assertDoesNotThrow(() -> SoarConnectorRegistry.validate(connector, Path.of("test.yaml")));
    }

    @Test
    void rejectsAmbiguousBaseAndDynamicActionUrl() {
        SoarConnector bothBases = new SoarConnector("threat-intel", "Threat Intel", "", true,
                "https://example.test", "THREAT_INTEL_URL", false, false, null,
                Map.of("lookup-ip", new SoarConnector.Action("GET", "/ip/${ip}",
                        10, List.of("ip"), 65536)));
        assertThrows(IllegalArgumentException.class,
                () -> SoarConnectorRegistry.validate(bothBases, Path.of("both.yaml")));

        SoarConnector dynamicUrl = new SoarConnector("threat-intel", "Threat Intel", "", true,
                "https://example.test", null, false, false, null,
                Map.of("lookup-ip", new SoarConnector.Action("GET", "https://${host}/ip",
                        10, List.of("host"), 65536)));
        assertThrows(IllegalArgumentException.class,
                () -> SoarConnectorRegistry.validate(dynamicUrl, Path.of("dynamic.yaml")));
    }
}
