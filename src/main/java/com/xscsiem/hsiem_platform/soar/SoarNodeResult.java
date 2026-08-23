package com.xscsiem.hsiem_platform.soar;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** A handler describes the outcome; only the execution engine is allowed to commit transitions. */
public record SoarNodeResult(
        Outcome outcome,
        String branch,
        Map<String, Object> output,
        Instant resumeAt,
        String approvalPrompt) {

    public SoarNodeResult {
        output = output == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(output));
    }

    public static SoarNodeResult advance(String branch, Map<String, Object> output) {
        return new SoarNodeResult(Outcome.ADVANCE, branch, output, null, null);
    }

    public static SoarNodeResult complete(Map<String, Object> output) {
        return new SoarNodeResult(Outcome.COMPLETE, null, output, null, null);
    }

    public static SoarNodeResult waitUntil(Instant resumeAt) {
        return new SoarNodeResult(Outcome.WAIT, null, Map.of(), resumeAt, null);
    }

    public static SoarNodeResult waitForApproval(String prompt) {
        return new SoarNodeResult(Outcome.WAIT_HUMAN, null, Map.of(), null, prompt);
    }

    public enum Outcome {
        ADVANCE,
        COMPLETE,
        WAIT,
        WAIT_HUMAN
    }
}
