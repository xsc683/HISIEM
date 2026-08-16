package com.siem;

import java.util.Map;

/** 取反条件:子条件不命中时命中(NOT)。 */
public class NotCondition implements Condition {

    private final Condition inner;

    public NotCondition(Condition inner) {
        this.inner = inner;
    }

    @Override
    public boolean matches(Map<String, Object> event) {
        return !inner.matches(event);
    }
}
