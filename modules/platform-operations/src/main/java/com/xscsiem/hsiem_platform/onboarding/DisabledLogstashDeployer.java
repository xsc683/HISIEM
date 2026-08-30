package com.xscsiem.hsiem_platform.onboarding;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Safe disabled implementation for environments that do not expose operations process adapters. */
@Component
@ConditionalOnProperty(name = "app.operations.process-adapters", havingValue = "disabled", matchIfMissing = true)
public class DisabledLogstashDeployer implements LogstashDeployer {

    @Override
    public void syncLogstash() {
        // No external command is executed in the control API.
    }

    @Override
    public boolean validateConfig(String containerConfigPath) {
        return false;
    }

    @Override
    public void restartLogstash() {
        // No external command is executed in the control API.
    }

    @Override
    public void reloadLogstash() {
        // No external command is executed in the control API.
    }
}
