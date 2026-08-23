package com.xscsiem.hsiem_platform.soar;

import java.time.Instant;
import java.util.Map;

/** 面向分析师的不可变执行时间线事件。 */
public record SoarExecutionEvent(
        long sequence,
        String executionId,
        String eventType,
        String nodeId,
        String actor,
        Map<String, Object> details,
        Instant createdAt) {
}
