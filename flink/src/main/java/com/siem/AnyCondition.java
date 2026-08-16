package com.siem;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** 组合条件:任意一个子条件命中即算命中(OR)。 */
public class AnyCondition implements Condition {

    private final List<Condition> conditions;

    public AnyCondition(Condition... conditions) {
        this.conditions = Arrays.asList(conditions);
    }

    @Override
    public boolean matches(Map<String, Object> event) {
        for (Condition c : conditions) {
            if (c.matches(event)) {
                return true;
            }
        }
        return false;
    }
}
