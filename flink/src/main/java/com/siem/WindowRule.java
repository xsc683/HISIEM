package com.siem;

import java.io.Serializable;
import java.util.List;

/**
 * 时间窗口规则:对 keyField 分组,在 windowMinutes 窗口内,
 * 满足 condition 的事件数 >= threshold 时触发告警。
 *
 * 例:SSH 暴力破解 = 同一 source.ip 5 分钟内 authentication_failure >= 5 次。
 *
 * 元数据(riskScore/tags/status)与 {@link Rule} 一致,用于告警风险分与覆盖度分析。
 */
public class WindowRule implements Serializable {

    private final String id;
    private final String name;
    private final String type;
    private final String severity;
    private final String description;
    private final String keyField;
    private final Condition condition;
    private final long windowMinutes;
    /** 滑动步长(分钟);null/0 = tumbling 固定窗口;>0 = 滑动窗口(修边界盲区,F7)。 */
    private final Long slidingMinutes;
    /** 窗口告警抑制时长(分钟),用于收敛重叠滑动窗口的重复命中。 */
    private final long alertSuppressionMinutes;
    private final int threshold;
    /** 数值风险分(0-100)。 */
    private final int riskScore;
    /** MITRE ATT&amp;CK 技术 ID,如 attack.t1110.001。 */
    private final List<String> tags;
    /** 规则状态:experimental / stable / deprecated。 */
    private final String status;
    /** 规则版本(如 1.0)。 */
    private final String version;

    public WindowRule(String id, String name, String type, String severity, String description,
                      String keyField, Condition condition, long windowMinutes, int threshold) {
        this(id, name, type, severity, description, keyField, condition, windowMinutes, threshold,
                0, List.of(), "experimental", "1.0", null);
    }

    public WindowRule(String id, String name, String type, String severity, String description,
                      String keyField, Condition condition, long windowMinutes, int threshold,
                      int riskScore, List<String> tags, String status) {
        this(id, name, type, severity, description, keyField, condition, windowMinutes, threshold,
                riskScore, tags, status, "1.0", null);
    }

    public WindowRule(String id, String name, String type, String severity, String description,
                      String keyField, Condition condition, long windowMinutes, int threshold,
                      int riskScore, List<String> tags, String status, String version) {
        this(id, name, type, severity, description, keyField, condition, windowMinutes, threshold,
                riskScore, tags, status, version, null, null);
    }

    public WindowRule(String id, String name, String type, String severity, String description,
                      String keyField, Condition condition, long windowMinutes, int threshold,
                      int riskScore, List<String> tags, String status, String version, Long slidingMinutes) {
        this(id, name, type, severity, description, keyField, condition, windowMinutes, threshold,
                riskScore, tags, status, version, slidingMinutes, null);
    }

    public WindowRule(String id, String name, String type, String severity, String description,
                      String keyField, Condition condition, long windowMinutes, int threshold,
                      int riskScore, List<String> tags, String status, String version,
                      Long slidingMinutes, Long alertSuppressionMinutes) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.severity = severity;
        this.description = description;
        this.keyField = keyField;
        this.condition = condition;
        this.windowMinutes = windowMinutes;
        this.slidingMinutes = slidingMinutes;
        if (windowMinutes <= 0) {
            throw new IllegalArgumentException("windowMinutes 必须 > 0: " + id);
        }
        if (threshold <= 0) {
            throw new IllegalArgumentException("threshold 必须 > 0: " + id);
        }
        long suppression = alertSuppressionMinutes == null ? windowMinutes : alertSuppressionMinutes;
        if (suppression <= 0) {
            throw new IllegalArgumentException("alertSuppressionMinutes 必须 > 0: " + id);
        }
        this.alertSuppressionMinutes = suppression;
        this.threshold = threshold;
        this.riskScore = riskScore;
        this.tags = tags;
        this.status = status;
        this.version = version;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getType() {
        return type;
    }

    public String getSeverity() {
        return severity;
    }

    public String getDescription() {
        return description;
    }

    public String getKeyField() {
        return keyField;
    }

    public Condition getCondition() {
        return condition;
    }

    public long getWindowMinutes() {
        return windowMinutes;
    }

    /** 滑动步长(分钟);null = tumbling 固定窗口。 */
    public Long getSlidingMinutes() {
        return slidingMinutes;
    }

    public long getAlertSuppressionMinutes() {
        return alertSuppressionMinutes;
    }

    public int getThreshold() {
        return threshold;
    }

    public int getRiskScore() {
        return riskScore;
    }

    public List<String> getTags() {
        return tags;
    }

    public String getStatus() {
        return status;
    }

    public String getVersion() {
        return version;
    }
}
