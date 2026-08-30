package com.xscsiem.hsiem_platform.detection.controller;

import com.xscsiem.hsiem_platform.detection.runtime.DetectionRuntimeTarget;
import com.xscsiem.hsiem_platform.detection.runtime.FlinkRuntimePort;
import com.xscsiem.hsiem_platform.detection.runtime.RuntimeObservation;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Safe 5A default. It never starts/stops/submits a job and always reports UNKNOWN. A physical
 * adapter is deliberately deferred to 5B.
 */
@Component
@ConditionalOnProperty(name = "app.detection.runtime-adapter", havingValue = "disabled",
        matchIfMissing = true)
public class DisabledFlinkRuntimePort implements FlinkRuntimePort {

    @Override
    public RuntimeObservation inspect(DetectionRuntimeTarget target) {
        return RuntimeObservation.unknown(target);
    }

    @Override
    public RuntimeObservation apply(DetectionRuntimeTarget target, RuntimeObservation current) {
        return RuntimeObservation.unknown(target);
    }

    @Override
    public RuntimeObservation stop(DetectionRuntimeTarget target, RuntimeObservation current) {
        return RuntimeObservation.unknown(target);
    }
}
