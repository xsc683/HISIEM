package com.xscsiem.hsiem_platform.soar;

import java.util.Map;
import java.util.Set;

public interface SoarNodeHandler {

    String type();

    SoarNodeResult execute(SoarExecutionContext context, Map<String, Object> resolvedConfig);

    default void validate(String entryType, PlaybookGraph.Node node) {
    }

    default Set<String> outgoingBranches() {
        return Set.of("next");
    }

    /** Parallel nodes declare their branch labels in configuration. */
    default boolean variableOutgoingBranches() {
        return false;
    }

    /** Allows a handler to remove credentials before its resolved config is persisted. */
    default Map<String, Object> auditSafeConfig(Map<String, Object> resolvedConfig) {
        return resolvedConfig;
    }

    default boolean acceptsIncoming() {
        return true;
    }

    default boolean requiresIncoming() {
        return true;
    }

    default int defaultMaxAttempts() {
        return 1;
    }
}
