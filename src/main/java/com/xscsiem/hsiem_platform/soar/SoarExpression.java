package com.xscsiem.hsiem_platform.soar;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 条件树和 ${context.path} 参数解析；兼容 ECS 点分字段。 */
public final class SoarExpression {

    private static final Pattern VARIABLE = Pattern.compile("\\$\\{([^}]+)}");

    private SoarExpression() {
    }

    public static boolean matches(SoarPlaybook.Condition condition, Map<String, Object> context) {
        if (condition == null) return true;
        if (condition.all() != null && !condition.all().isEmpty()
                && !condition.all().stream().allMatch(item -> matches(item, context))) return false;
        if (condition.any() != null && !condition.any().isEmpty()
                && condition.any().stream().noneMatch(item -> matches(item, context))) return false;
        if (condition.not() != null && matches(condition.not(), context)) return false;
        if (condition.field() == null || condition.field().isBlank()) return true;
        Object actual = lookup(context, condition.field());
        Object expected = condition.value();
        return switch (condition.operator()) {
            case "exists" -> expected instanceof Boolean b ? (actual != null) == b : actual != null;
            case "eq" -> Objects.equals(normalize(actual), normalize(expected));
            case "ne" -> !Objects.equals(normalize(actual), normalize(expected));
            case "gt" -> comparable(actual, expected) && compare(actual, expected) > 0;
            case "gte" -> comparable(actual, expected) && compare(actual, expected) >= 0;
            case "lt" -> comparable(actual, expected) && compare(actual, expected) < 0;
            case "lte" -> comparable(actual, expected) && compare(actual, expected) <= 0;
            case "contains" -> actual instanceof Collection<?> values
                    ? values.stream().anyMatch(v -> Objects.equals(normalize(v), normalize(expected)))
                    : actual != null && String.valueOf(actual).contains(String.valueOf(expected));
            case "matches" -> actual != null && String.valueOf(actual).matches(String.valueOf(expected));
            default -> false;
        };
    }

    public static Map<String, Object> resolveMap(Map<String, Object> source,
                                                  Map<String, Object> context) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (source != null) source.forEach((key, value) -> out.put(key, resolve(value, context)));
        return out;
    }

    public static Object resolve(Object value, Map<String, Object> context) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((key, item) -> out.put(String.valueOf(key), resolve(item, context)));
            return out;
        }
        if (value instanceof List<?> values) return values.stream().map(item -> resolve(item, context)).toList();
        if (!(value instanceof String text)) return value;
        Matcher matcher = VARIABLE.matcher(text);
        if (matcher.matches()) return lookup(context, matcher.group(1));
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            Object replacement = lookup(context, matcher.group(1));
            matcher.appendReplacement(out, Matcher.quoteReplacement(
                    replacement == null ? "" : String.valueOf(replacement)));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    @SuppressWarnings("unchecked")
    public static Object lookup(Map<String, Object> context, String path) {
        if (context == null || path == null) return null;
        if (context.containsKey(path)) return context.get(path);
        Object current = context;
        String[] parts = path.split("\\.");
        for (int i = 0; i < parts.length; i++) {
            if (!(current instanceof Map<?, ?> map)) return null;
            String remaining = String.join(".", java.util.Arrays.copyOfRange(parts, i, parts.length));
            if (map.containsKey(remaining)) return map.get(remaining);
            current = ((Map<String, Object>) map).get(parts[i]);
        }
        return current;
    }

    private static Object normalize(Object value) {
        if (value instanceof Number number) return number.doubleValue();
        if (value instanceof Boolean bool) return bool;
        return value == null ? null : String.valueOf(value);
    }

    private static int compare(Object left, Object right) {
        try {
            return Double.compare(Double.parseDouble(String.valueOf(left)),
                    Double.parseDouble(String.valueOf(right)));
        } catch (NumberFormatException ignored) {
            return String.valueOf(left).compareTo(String.valueOf(right));
        }
    }

    private static boolean comparable(Object left, Object right) {
        return left != null && right != null;
    }
}
