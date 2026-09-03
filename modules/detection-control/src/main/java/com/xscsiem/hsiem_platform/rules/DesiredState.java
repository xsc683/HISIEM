package com.xscsiem.hsiem_platform.rules;

/** Durable desired state for a managed detection rule. */
public enum DesiredState {
    RUNNING,
    STOPPED;

    public static DesiredState parse(String value) {
        if (value == null) {
            throw new IllegalArgumentException("desired state must not be null");
        }
        try {
            return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unsupported desired state: " + value, e);
        }
    }
}
