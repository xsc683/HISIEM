package com.siem;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 组合条件:全部子条件都满足才算命中(AND)。
 */
public class AllCondition implements Condition {

    private final List<Condition> conditions;

    public AllCondition(Condition... conditions) {
        this.conditions = Arrays.asList(conditions);
    }

    @Override
    public boolean matches(Map<String, Object> event) {
        for (Condition c : conditions) {
            if (!c.matches(event)) {
                return false;
            }
        }
        return true;
    }
}
