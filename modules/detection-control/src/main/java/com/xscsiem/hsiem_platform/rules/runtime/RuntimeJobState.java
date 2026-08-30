package com.xscsiem.hsiem_platform.rules.runtime;

import java.util.Locale;

/** Lifecycle state reported by a physical runtime/controller. */
public enum RuntimeJobState {
    PENDING,
    DEPLOYING,
    RUNNING,
    STOPPED,
    FAILED,
    UNKNOWN;

    public static RuntimeJobState from(String value) {
        if (value == null || value.isBlank()) {
            return UNKNOWN;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return UNKNOWN;
        }
    }
}
