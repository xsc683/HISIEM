package com.siem;

import java.io.Serializable;
import java.util.Map;

/**
 * 解析后的事件:原始 JSON + 扁平字段 + 事件时间戳(毫秒)。
 * 作为流元素在 Flink 算子间传递(POJO,可序列化)。
 */
public class Event implements Serializable {

    private String rawJson;
    private Map<String, Object> fields;
    private long timestampMillis;

    public Event() {
    }

    public Event(String rawJson, Map<String, Object> fields, long timestampMillis) {
        this.rawJson = rawJson;
        this.fields = fields;
        this.timestampMillis = timestampMillis;
    }

    public String getRawJson() {
        return rawJson;
    }

    public Map<String, Object> getFields() {
        return fields;
    }

    public long getTimestampMillis() {
        return timestampMillis;
    }

    public void setRawJson(String rawJson) {
        this.rawJson = rawJson;
    }

    public void setFields(Map<String, Object> fields) {
        this.fields = fields;
    }

    public void setTimestampMillis(long timestampMillis) {
        this.timestampMillis = timestampMillis;
    }
}
