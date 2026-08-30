package com.xscsiem.hsiem_platform.soar;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;

@Component
public class SoarConditionEvaluator {

    public boolean evaluate(Map<String, Object> payload, Map<String, Object> config) {
        Object rawConditions = config.get("conditions");
        if (!(rawConditions instanceof List<?> conditions)) {
            throw new IllegalArgumentException("条件节点缺少 conditions");
        }
        for (Object raw : conditions) {
            if (!(raw instanceof Map<?, ?> condition)) throw new IllegalArgumentException("条件格式错误");
            Object actual = valueAt(payload, String.valueOf(condition.get("field")));
            String operator = String.valueOf(condition.get("operator"));
            Object expected = condition.get("value");
            if (!matches(actual, operator, expected)) return false;
        }
        return true;
    }

    Object valueAt(Map<String, Object> root, String path) {
        Object current = root;
        for (String part : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) return null;
            current = map.get(part);
        }
        return current;
    }

    private boolean matches(Object actual, String operator, Object expected) {
        return switch (operator) {
            case "eq" -> equal(actual, expected);
            case "ne" -> !equal(actual, expected);
            case "contains" -> contains(actual, expected);
            case "gt" -> number(actual).compareTo(number(expected)) > 0;
            case "lt" -> number(actual).compareTo(number(expected)) < 0;
            case "is_empty" -> empty(actual);
            case "not_empty" -> !empty(actual);
            default -> throw new IllegalArgumentException("未知条件操作符: " + operator);
        };
    }

    private boolean equal(Object actual, Object expected) {
        if (actual instanceof Number || expected instanceof Number) {
            try {
                return number(actual).compareTo(number(expected)) == 0;
            } catch (IllegalArgumentException ignored) {
                return false;
            }
        }
        return String.valueOf(actual == null ? "" : actual)
                .equals(String.valueOf(expected == null ? "" : expected));
    }

    private boolean contains(Object actual, Object expected) {
        if (actual instanceof Collection<?> collection) {
            return collection.stream().anyMatch(item -> equal(item, expected));
        }
        return actual != null && String.valueOf(actual).contains(String.valueOf(expected));
    }

    private boolean empty(Object value) {
        return value == null || value instanceof String text && text.isBlank()
                || value instanceof Collection<?> collection && collection.isEmpty()
                || value instanceof Map<?, ?> map && map.isEmpty();
    }

    private BigDecimal number(Object value) {
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("数值条件收到非数值: " + value);
        }
    }
}
