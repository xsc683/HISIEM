package com.xscsiem.hsiem_platform.rules;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DetectionPlanCompilerTest {

    private final DetectionPlanCompiler compiler = new DetectionPlanCompiler();

    @Test
    void compilesSingleEventToStableAuditablePlan() {
        Map<String, Object> rule = singleEvent();

        DetectionPlanCompiler.CompiledPlan first = compiler.compile(rule, 3);
        DetectionPlanCompiler.CompiledPlan second = compiler.compile(rule, 3);

        assertEquals(first.json(), second.json());
        assertEquals(first.hash(), second.hash());
        assertTrue(first.json().contains("rule:rule-test:rev3:single_event_match"));
        assertTrue(first.json().contains("\"operator\":\"eq\""));
    }

    @Test
    void compilesWindowWithoutOptionalSlidingValue() {
        Map<String, Object> rule = new LinkedHashMap<>(singleEvent());
        rule.put("category", "window");
        rule.put("keyField", "source.ip");
        rule.put("windowMinutes", 5);
        rule.put("threshold", 5);

        String plan = compiler.compile(rule, 1).json();

        assertTrue(plan.contains("\"type\":\"window\""));
        assertTrue(plan.contains("\"minutes\":5"));
        assertTrue(!plan.contains("sliding_minutes"));
    }

    private static Map<String, Object> singleEvent() {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("id", "rule-test");
        rule.put("category", "single_event");
        rule.put("type", "test_detection");
        rule.put("severity", "high");
        rule.put("riskScore", 50);
        rule.put("description", "test");
        rule.put("condition", Map.of(
                "type", "field_equals", "field", "event.action", "value", "login"));
        rule.put("tags", List.of());
        return rule;
    }
}
