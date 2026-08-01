package com.siem;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 字段值在指定集合内,如 FieldIn("user.name", "admin", "root")。
 */
public class FieldInCondition implements Condition {

    private final String field;
    private final Set<Object> expectedValues;

    public FieldInCondition(String field, Object... values) {
        this.field = field;
        this.expectedValues = new HashSet<>(Arrays.asList(values));
    }

    @Override
    public boolean matches(Map<String, Object> event) {
        return expectedValues.contains(event.get(field));
    }
}
