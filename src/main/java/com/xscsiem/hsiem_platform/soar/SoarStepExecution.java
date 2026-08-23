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
        String status,
        Map<String, Object> input,
        Map<String, Object> output,
        String error,
        Instant startedAt,
        Instant finishedAt) {
}
