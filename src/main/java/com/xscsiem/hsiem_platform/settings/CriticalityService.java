package com.xscsiem.hsiem_platform.settings;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xscsiem.hsiem_platform.control.ControlPlaneStore;
import com.xscsiem.hsiem_platform.onboarding.NotFoundException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 资产关键度：严格校验后以临时文件 + 原子替换写入。 */
@Service
public class CriticalityService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    public static final List<String> TYPES = List.of("ip", "user", "host");
    private static final Map<String, Double> LEVEL_WEIGHT = Map.of(
            "low", 0.5, "medium", 1.0, "high", 1.5, "extreme", 2.0);
    private static final String KEY_PATTERN = "[A-Za-z0-9][A-Za-z0-9._:@/\\\\-]{0,254}";

    private final String file;
    private final ControlPlaneStore control;

    @Autowired
    public CriticalityService(
            @Value("$" + "{app.criticality-file:infra/elasticsearch/asset-criticality.json}") String file,
            ControlPlaneStore control) {
        this.file = file;
        this.control = control;
    }

    public CriticalityService(String file) {
        this(file, null);
    }

    public synchronized Map<String, Object> all() {
        Map<String, Object> raw = raw();
        Map<String, Object> out = new LinkedHashMap<>();
        for (String type : TYPES) {
            Map<String, Object> items = new LinkedHashMap<>();
            Object section = raw.get(type);
            if (section instanceof Map<?, ?> m) {
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    String key = String.valueOf(e.getKey());
                    items.put(key, display(type, key, number(e.getValue(), type, key)));
                }
            }
            out.put(type, items);
        }
        return out;
    }

    /** 前缀匹配搜索，返回扁平项。 */
    public synchronized List<Map<String, Object>> search(String type, String query, int size) {
        if (type != null && !TYPES.contains(type)) {
            throw new IllegalArgumentException("类型非法(ip/user/host): " + type);
        }
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (q.endsWith("*")) q = q.substring(0, q.length() - 1);
        List<Map<String, Object>> out = new ArrayList<>();
        for (String candidate : type == null ? TYPES : List.of(type)) {
            Object section = raw().get(candidate);
            if (!(section instanceof Map<?, ?> m)) continue;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                String key = String.valueOf(e.getKey());
                if (q.isBlank() || key.toLowerCase(Locale.ROOT).startsWith(q)) {
                    out.add(display(candidate, key, number(e.getValue(), candidate, key)));
                    if (out.size() >= Math.min(Math.max(size, 1), 500)) return out;
                }
            }
        }
        return out;
    }

    public Map<String, Object> set(String type, String key, String level) {
        return set(type, key, level, "anonymous");
    }

    public synchronized Map<String, Object> set(String type, String key, String level, String actor) {
        validate(type, key, level);
        Map<String, Object> fileMap = raw();
        section(fileMap, type).put(key, LEVEL_WEIGHT.get(level.toLowerCase(Locale.ROOT)));
        write(fileMap);
        audit(actor, "criticality.set", type + "/" + key);
        return item(type, key);
    }

    public void delete(String type, String key) {
        delete(type, key, "anonymous");
    }

    public synchronized void delete(String type, String key, String actor) {
        validateKey(type, key);
        Map<String, Object> fileMap = raw();
        Map<String, Object> section = section(fileMap, type);
        if (!section.containsKey(key)) throw new NotFoundException("资产不存在: " + type + "/" + key);
        section.remove(key);
        write(fileMap);
        audit(actor, "criticality.delete", type + "/" + key);
    }

    /** 先验证所有批量项，随后只写一次；任意一项失败不会改变原文件。 */
    public synchronized Map<String, Object> batch(List<Entry> entries, String actor) {
        if (entries == null || entries.isEmpty() || entries.size() > 1000) {
            throw new IllegalArgumentException("批量导入数量需在 1-1000 之间");
        }
        Map<String, Object> updated = raw();
        int count = 0;
        for (Entry entry : entries) {
            if (entry == null) throw new IllegalArgumentException("批量项不能为空");
            validate(entry.type(), entry.key(), entry.level());
            section(updated, entry.type()).put(entry.key(),
                    LEVEL_WEIGHT.get(entry.level().toLowerCase(Locale.ROOT)));
            count++;
        }
        write(updated);
        audit(actor, "criticality.batch", "items=" + count);
        return Map.of("imported", count, "recalculationRequired", true);
    }

    private Map<String, Object> item(String type, String key) {
        Map<String, Object> section = section(raw(), type);
        if (section.get(key) instanceof Number n) return display(type, key, n.doubleValue());
        throw new NotFoundException("资产不存在: " + type + "/" + key);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> raw() {
        try {
            Path path = Path.of(file);
            return Files.exists(path) ? MAPPER.readValue(path.toFile(), Map.class) : new LinkedHashMap<>();
        } catch (IOException e) {
            throw new IllegalStateException("关键度文件读取失败: " + file, e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(Map<String, Object> raw, String type) {
        return (Map<String, Object>) raw.computeIfAbsent(type, k -> new LinkedHashMap<>());
    }

    /** 原子写入，临时文件失败时保留旧文件。 */
    private void write(Map<String, Object> fileMap) {
        Path target = Path.of(file).toAbsolutePath();
        Path parent = target.getParent();
        Path temp = null;
        try {
            if (parent != null) Files.createDirectories(parent);
            temp = Files.createTempFile(parent, target.getFileName().toString(), ".tmp");
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), fileMap);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("关键度文件保存失败: " + file, e);
        } finally {
            if (temp != null) {
                try { Files.deleteIfExists(temp); } catch (IOException ignored) { }
            }
        }
    }

    private void validate(String type, String key, String level) {
        validateKey(type, key);
        if (level == null || !LEVEL_WEIGHT.containsKey(level.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("级别非法(low/medium/high/extreme): " + level);
        }
    }

    private static void validateKey(String type, String key) {
        if (!TYPES.contains(type)) throw new IllegalArgumentException("类型非法(ip/user/host): " + type);
        if (key == null || key.isBlank() || key.length() > 255 || !key.matches(KEY_PATTERN)) {
            throw new IllegalArgumentException("资产 key 非法: " + key);
        }
        if ("ip".equals(type)) {
            try {
                if (!key.matches("[0-9A-Fa-f:.]+") || InetAddress.getByName(key).getHostAddress().isBlank()) {
                    throw new IllegalArgumentException("IP key 非法: " + key);
                }
            } catch (Exception e) {
                throw new IllegalArgumentException("IP key 非法: " + key, e);
            }
        }
    }

    private static double number(Object value, String type, String key) {
        if (!(value instanceof Number n)) throw new IllegalStateException("关键度权重非法: " + type + "/" + key);
        return n.doubleValue();
    }

    private static Map<String, Object> display(String type, String key, double weight) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", type);
        item.put("key", key);
        item.put("level", levelOf(weight));
        item.put("weight", weight);
        return item;
    }

    private void audit(String actor, String action, String target) {
        if (control != null) control.audit(actor == null || actor.isBlank() ? "anonymous" : actor, action, target);
    }

    private static String levelOf(double weight) {
        if (weight >= 2.0) return "extreme";
        if (weight >= 1.5) return "high";
        if (weight >= 1.0) return "medium";
        return "low";
    }

    public record Entry(String type, String key, String level) {
    }
}
