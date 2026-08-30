package com.xscsiem.hsiem_platform.detection.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/** Exposes adapter mode and the last controller poll without claiming physical runtime health. */
@Component("detectionRuntimeController")
public class DetectionRuntimeHealthIndicator implements HealthIndicator {

    private final String adapterMode;
    private final ControllerPollState pollState;

    public DetectionRuntimeHealthIndicator(
            @Value("${app.detection.runtime-adapter:disabled}") String adapterMode,
            ControllerPollState pollState) {
        this.adapterMode = adapterMode;
        this.pollState = pollState;
    }

    @Override
    public Health health() {
        Health.Builder health = Health.up()
                .withDetail("adapterMode", adapterMode)
                .withDetail("physicalDeploymentEnabled", !"disabled".equalsIgnoreCase(adapterMode));
        if (pollState.lastPoll() == null) {
            health.withDetail("lastPoll", "never");
        } else {
            health.withDetail("lastPoll", pollState.lastPoll().toString());
        }
        if ("disabled".equalsIgnoreCase(adapterMode)) {
            health.withDetail("note", "5A foundation: physical Flink adapter is disabled; 5B pending");
        }
        return health.build();
    }
}
