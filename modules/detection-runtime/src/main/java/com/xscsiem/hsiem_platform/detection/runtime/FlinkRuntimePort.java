package com.xscsiem.hsiem_platform.detection.runtime;

/**
 * Port for a physical (or intentionally disabled) detection runtime.  Implementations must be
 * idempotent and must not persist controller state; fencing and persistence belong to the caller.
 */
public interface FlinkRuntimePort {

    RuntimeObservation inspect(DetectionRuntimeTarget target);

    RuntimeObservation apply(DetectionRuntimeTarget target, RuntimeObservation current);

    RuntimeObservation stop(DetectionRuntimeTarget target, RuntimeObservation current);
}
