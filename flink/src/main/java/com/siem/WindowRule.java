package com.siem;

import java.io.Serializable;

/**
 * 时间窗口规则:对 keyField 分组,在 windowMinutes 窗口内,
 * 满足 condition 的事件数 >= threshold 时触发告警。
 *
 * 例:SSH 暴力破解 = 同一 source.ip 5 分钟内 authentication_failure >= 5 次。
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
    private final int threshold;

    public WindowRule(String id, String name, String type, String severity, String description,
                      String keyField, Condition condition, long windowMinutes, int threshold) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.severity = severity;
        this.description = description;
        this.keyField = keyField;
        this.condition = condition;
        this.windowMinutes = windowMinutes;
        this.threshold = threshold;
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

    public int getThreshold() {
        return threshold;
    }
}
