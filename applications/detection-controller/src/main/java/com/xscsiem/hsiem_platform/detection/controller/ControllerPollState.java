package com.xscsiem.hsiem_platform.detection.controller;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/** Small process-local status source for readiness/health details. */
@Component
public class ControllerPollState {

    private final AtomicReference<Instant> lastPoll = new AtomicReference<>();

    public void markPoll() {
        lastPoll.set(Instant.now());
    }

    public Instant lastPoll() {
        return lastPoll.get();
    }
}
