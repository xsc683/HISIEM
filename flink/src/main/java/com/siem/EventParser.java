package com.siem;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 事件解析:将 Kafka 中的事件 JSON 解析为扁平点分字段 Map。
 *
 * Logstash json codec 输出的点分字段(如 source.ip)可能是嵌套对象
 * ({"source":{"ip":...}}),统一展开为扁平 key (source.ip),便于规则按字段名匹配。
 */
public final class EventParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private EventParser() {
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parse(String json) throws Exception {
        Map<String, Object> raw = MAPPER.readValue(json, Map.class);
        Map<String, Object> flat = new LinkedHashMap<>();
        flatten("", raw, flat);
        return flat;
    }

    private static void flatten(String prefix, Map<String, Object> map, Map<String, Object> out) {
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            Object value = entry.getValue();
            if (value instanceof Map) {
                flatten(key, (Map<String, Object>) value, out);
            } else {
                out.put(key, value); // List 等复杂类型原样保留
            }
        }
    }
}
