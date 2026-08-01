package com.siem;

import java.util.Map;

/**
 * 字段等于指定值,如 FieldEquals("event.action", "authentication_failure")。
 */
public class FieldEqualsCondition implements Condition {

    private final String field;
    private final Object expected;

    public FieldEqualsCondition(String field, Object expected) {
        this.field = field;
        this.expected = expected;
    }

    @Override
    public boolean matches(Map<String, Object> event) {
        Object value = event.get(field);
        return expected.equals(value);
    }
}
