package com.xscsiem.hsiem_platform.rules.runtime;

/** Reconciled state of one rule in the physical detection runtime. */
public enum RuleRuntimeState {
    PENDING,
    DEPLOYING,
    RUNNING,
    DEGRADED,
    OUT_OF_SYNC,
    FAILED,
    DISABLED,
    UNKNOWN
}
