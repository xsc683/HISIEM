package com.xscsiem.hsiem_platform.settings;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xscsiem.hsiem_platform.onboarding.NotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 资产关键度设置(story-06):读写 infra/elasticsearch/asset-criticality.json(文件+Git)。
 * 文件存权重数值(0.5/1/1.5/2,与 entity-risk.py 读取一致);UI 存级别(Low/Medium/High/Extreme),
 * 换算唯一收敛在 CriticalityService。保存后需触发 entity-risk.py 重算才生效(见 recalc)。
 */
@Service
public class CriticalityService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    public static final List<String> TYPES = List.of("ip", "user", "host");
    private static final Map<String, Double> LEVEL_WEIGHT = Map.of(
            "low", 0.5, "medium", 1.0, "high", 1.5, "extreme", 2.0);

    private final String file;

    public CriticalityService(@Value("${app.criticality-file:infra/elasticsearch/asset-criticality.json}") String file) {
        this.file = file;
    }

    /** 全量:按类型返回 {key: {level, weight}}。 */
    public Map<String, Object> all() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String type : TYPES) {
            Map<String, Object> items = new LinkedHashMap<>();
            Map<String, Object> fileMap = raw();
            Object section = fileMap.get(type);
            if (section instanceof Map<?, ?> m) {
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    double weight = ((Number) e.getValue()).doubleValue();
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("level", levelOf(weight));
                    item.put("weight", weight);
                    items.put(String.valueOf(e.getKey()), item);
                }
            }
            out.put(type, items);
        }
        return out;
    }

    /** 设置某资产级别(PUT)。 */
    public Map<String, Object> set(String type, String key, String level) {
        if (!TYPES.contains(type)) {
            throw new IllegalArgumentException("类型非法(ip/user/host): " + type);
        }
        Double weight = LEVEL_WEIGHT.get(level.toLowerCase());
        if (weight == null) {
            throw new IllegalArgumentException("级别非法(low/medium/high/extreme): " + level);
        }
        Map<String, Object> fileMap = raw();
        @SuppressWarnings("unchecked")
        Map<String, Object> section = (Map<String, Object>) fileMap.computeIfAbsent(type, k -> new LinkedHashMap<>());
        section.put(key, weight);
        write(fileMap);
        return item(type, key);
    }

    /** 删除某资产。 */
    public void delete(String type, String key) {
        if (!TYPES.contains(type)) {
            throw new IllegalArgumentException("类型非法(ip/user/host): " + type);
        }
        Map<String, Object> fileMap = raw();
        Object section = fileMap.get(type);
        if (section instanceof Map<?, ?> m && m.containsKey(key)) {
            @SuppressWarnings("unchecked")
            Map<String, Object> sectionMap = (Map<String, Object>) section;
            sectionMap.remove(key);
            write(fileMap);
        } else {
            throw new NotFoundException("资产不存在: " + type + "/" + key);
        }
    }

    private Map<String, Object> item(String type, String key) {
        Map<String, Object> fileMap = raw();
        Object section = fileMap.get(type);
        if (section instanceof Map<?, ?> m && m.get(key) instanceof Number n) {
            double weight = n.doubleValue();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("type", type);
            item.put("key", key);
            item.put("level", levelOf(weight));
            item.put("weight", weight);
            return item;
        }
        throw new NotFoundException("资产不存在: " + type + "/" + key);
    }

    /** 原始文件内容(保留 _comment 等)。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> raw() {
        try {
            File f = new File(file);
            return f.exists() ? MAPPER.readValue(f, Map.class) : new LinkedHashMap<>();
        } catch (IOException e) {
            throw new IllegalStateException("关键度文件读取失败: " + file, e);
        }
    }

    private void write(Map<String, Object> fileMap) {
        try {
            File f = new File(file);
            if (f.getParentFile() != null) {
                f.getParentFile().mkdirs();
            }
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(f, fileMap);
        } catch (IOException e) {
            throw new IllegalStateException("关键度文件保存失败: " + file, e);
        }
    }

    private static String levelOf(double weight) {
        if (weight >= 2.0) return "extreme";
        if (weight >= 1.5) return "high";
        if (weight >= 1.0) return "medium";
        return "low";
    }
}
