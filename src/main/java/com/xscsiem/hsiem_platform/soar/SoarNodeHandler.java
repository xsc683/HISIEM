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
