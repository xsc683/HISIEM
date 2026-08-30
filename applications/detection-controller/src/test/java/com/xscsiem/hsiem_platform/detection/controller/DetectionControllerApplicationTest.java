package com.xscsiem.hsiem_platform.detection.controller;

import com.xscsiem.hsiem_platform.detection.runtime.DetectionRuntimeTarget;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DetectionControllerApplicationTest {

    @Test
    void compositionRootIsNonWeb() {
        var application = new SpringApplicationBuilder(DetectionControllerApplication.class).build();
        assertEquals(WebApplicationType.NONE, application.getWebApplicationType());
    }

    @Test
    void disabledPortNeverClaimsPhysicalDeployment() {
        DetectionRuntimeTarget target = new DetectionRuntimeTarget("tenant-a", "group-a", "cluster-a",
                3L, "expected", "hash");
        DisabledFlinkRuntimePort port = new DisabledFlinkRuntimePort();
        assertEquals("UNKNOWN", port.inspect(target).runtimeState());
        assertEquals("UNKNOWN", port.apply(target, null).runtimeState());
        assertEquals("UNKNOWN", port.stop(target, null).runtimeState());
        assertTrue(new DetectionRuntimeHealthIndicator("disabled", new ControllerPollState())
                .health().getDetails().get("physicalDeploymentEnabled") instanceof Boolean);
    }
}
