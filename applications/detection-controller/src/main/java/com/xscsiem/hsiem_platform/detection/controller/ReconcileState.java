package com.xscsiem.hsiem_platform.detection.controller;

/** Durable controller-only reconciliation phases, intentionally separate from runtime status. */
public enum ReconcileState {
    PENDING,
    INSPECTING,
    APPLYING,
    VERIFYING,
    IDLE,
    FAILED
}
