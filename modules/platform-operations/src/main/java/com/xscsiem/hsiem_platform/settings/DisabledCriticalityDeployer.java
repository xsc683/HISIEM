package com.xscsiem.hsiem_platform.settings;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Safe disabled implementation for environments that do not expose operations process adapters. */
@Component
@ConditionalOnProperty(name = "app.operations.process-adapters", havingValue = "disabled", matchIfMissing = true)
public class DisabledCriticalityDeployer implements CriticalityDeployer {

    @Override
    public String recalcEntityRisk() {
        throw new IllegalStateException(
                "criticality process adapter is not available in the control-api process");
    }
}
