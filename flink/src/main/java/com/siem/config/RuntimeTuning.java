package com.siem.config;

import java.util.Map;

/** Flink checkpoint/ES sink 运行参数；环境变量或同名 system property 可覆盖。 */
public record RuntimeTuning(
        long checkpointIntervalMs,
        long checkpointTimeoutMs,
        long minPauseBetweenCheckpointsMs,
        int tolerableCheckpointFailures,
        int esBatchSize,
        int esMaxInFlightRequests,
        int esMaxBufferedRequests,
        long esMaxTimeInBufferMs) {

    public static RuntimeTuning defaults() {
        return new RuntimeTuning(30_000, 10 * 60_000, 10_000, 5, 250, 3, 500, 500);
    }

    public static RuntimeTuning fromEnvironment() {
        RuntimeTuning d = defaults();
        return new RuntimeTuning(
                longValue("SIEM_FLINK_CHECKPOINT_INTERVAL_MS", d.checkpointIntervalMs),
                longValue("SIEM_FLINK_CHECKPOINT_TIMEOUT_MS", d.checkpointTimeoutMs),
                longValue("SIEM_FLINK_CHECKPOINT_MIN_PAUSE_MS", d.minPauseBetweenCheckpointsMs),
                intValue("SIEM_FLINK_CHECKPOINT_TOLERABLE_FAILURES", d.tolerableCheckpointFailures),
                intValue("SIEM_FLINK_ES_BATCH_SIZE", d.esBatchSize),
                intValue("SIEM_FLINK_ES_MAX_IN_FLIGHT", d.esMaxInFlightRequests),
                intValue("SIEM_FLINK_ES_MAX_BUFFERED", d.esMaxBufferedRequests),
                longValue("SIEM_FLINK_ES_MAX_BUFFER_MS", d.esMaxTimeInBufferMs));
    }

    static RuntimeTuning from(Map<String, String> values) {
        RuntimeTuning d = defaults();
        return new RuntimeTuning(
                longValue(values, "SIEM_FLINK_CHECKPOINT_INTERVAL_MS", d.checkpointIntervalMs),
                longValue(values, "SIEM_FLINK_CHECKPOINT_TIMEOUT_MS", d.checkpointTimeoutMs),
                longValue(values, "SIEM_FLINK_CHECKPOINT_MIN_PAUSE_MS", d.minPauseBetweenCheckpointsMs),
                intValue(values, "SIEM_FLINK_CHECKPOINT_TOLERABLE_FAILURES", d.tolerableCheckpointFailures),
                intValue(values, "SIEM_FLINK_ES_BATCH_SIZE", d.esBatchSize),
                intValue(values, "SIEM_FLINK_ES_MAX_IN_FLIGHT", d.esMaxInFlightRequests),
                intValue(values, "SIEM_FLINK_ES_MAX_BUFFERED", d.esMaxBufferedRequests),
                longValue(values, "SIEM_FLINK_ES_MAX_BUFFER_MS", d.esMaxTimeInBufferMs));
    }

    private static String value(String key) {
        String property = System.getProperty(key);
        return property == null || property.isBlank() ? System.getenv(key) : property;
    }

    private static long longValue(String key, long fallback) {
        return parseLong(value(key), fallback, 1);
    }

    private static int intValue(String key, int fallback) {
        return (int) parseLong(value(key), fallback, 1);
    }

    private static long longValue(Map<String, String> values, String key, long fallback) {
        return parseLong(values.get(key), fallback, 1);
    }

    private static int intValue(Map<String, String> values, String key, int fallback) {
        return (int) parseLong(values.get(key), fallback, 1);
    }

    private static long parseLong(String value, long fallback, long minimum) {
        try {
            long parsed = Long.parseLong(value);
            return parsed >= minimum ? parsed : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }
}
