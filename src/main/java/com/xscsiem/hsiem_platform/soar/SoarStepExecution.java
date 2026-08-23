package com.xscsiem.hsiem_platform.soar;

import java.time.Instant;
import java.util.Map;

/** Playbook 中单一步骤的输入、输出和结果。 */
public record SoarStepExecution(
        String executionId,
        String stepId,
        int stepIndex,
        String stepName,
        String action,
        String nodeType,
        String status,
        int attempt,
        int maxAttempts,
        Map<String, Object> input,
        Map<String, Object> output,
        String error,
        Instant scheduledAt,
        Instant startedAt,
        Instant finishedAt,
        Long durationMs) {

    public SoarStepExecution(String executionId, String stepId, int stepIndex,
                             String stepName, String action, String status,
                             Map<String, Object> input, Map<String, Object> output,
                             String error, Instant startedAt, Instant finishedAt) {
        this(executionId, stepId, stepIndex, stepName, action, "action", status,
                1, 1, input, output, error, startedAt, startedAt, finishedAt,
                startedAt == null || finishedAt == null ? null
                        : java.time.Duration.between(startedAt, finishedAt).toMillis());
    }
}
