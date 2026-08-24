package com.xscsiem.hsiem_platform.soar;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;

/** A handler describes the outcome; only the execution engine is allowed to commit transitions. */
public record SoarNodeResult(
        Outcome outcome,
        String branch,
        Map<String, Object> output,
        Instant resumeAt,
        String approvalPrompt,
        List<String> branches,
        String joinNode,
        String loopBodyStart,
        String loopBodyEnd,
        List<Object> loopItems,
        int loopMaxIterations) {

    public SoarNodeResult {
        output = output == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(output));
        branches = branches == null ? List.of() : List.copyOf(branches);
        loopItems = loopItems == null ? List.of() : List.copyOf(loopItems);
    }

    public static SoarNodeResult advance(String branch, Map<String, Object> output) {
        return new SoarNodeResult(Outcome.ADVANCE, branch, output, null, null, List.of(), null, null, null, List.of(), 0);
    }

    public static SoarNodeResult complete(Map<String, Object> output) {
        return new SoarNodeResult(Outcome.COMPLETE, null, output, null, null, List.of(), null, null, null, List.of(), 0);
    }

    public static SoarNodeResult waitUntil(Instant resumeAt) {
        return new SoarNodeResult(Outcome.WAIT, null, Map.of(), resumeAt, null, List.of(), null, null, null, List.of(), 0);
    }

    public static SoarNodeResult waitForApproval(String prompt) {
        return new SoarNodeResult(Outcome.WAIT_HUMAN, null, Map.of(), null, prompt, List.of(), null, null, null, List.of(), 0);
    }

    public static SoarNodeResult fanOut(List<String> branches, String joinNode,
                                        Map<String, Object> output) {
        return new SoarNodeResult(Outcome.FAN_OUT, null, output, null, null, branches, joinNode, null, null, List.of(), 0);
    }

    public static SoarNodeResult loop(String bodyStart, String bodyEnd, List<Object> items,
                                      int maxIterations, Map<String, Object> output) {
        return new SoarNodeResult(Outcome.LOOP, null, output, null, null, List.of(), null,
                bodyStart, bodyEnd, items, maxIterations);
    }

    public enum Outcome {
        ADVANCE,
        COMPLETE,
        WAIT,
        WAIT_HUMAN,
        FAN_OUT,
        LOOP
    }
}
