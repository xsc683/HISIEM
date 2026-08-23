package com.xscsiem.hsiem_platform.soar;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class SoarConditionNodeHandler implements SoarNodeHandler {

    private static final Set<String> NO_VALUE_OPERATORS = Set.of("is_empty", "not_empty");

    private final SoarConditionEvaluator evaluator;
    private final SoarDictionary dictionary;

    public SoarConditionNodeHandler(SoarConditionEvaluator evaluator, SoarDictionary dictionary) {
        this.evaluator = evaluator;
        this.dictionary = dictionary;
    }

    @Override
    public String type() {
        return "condition";
    }

    @Override
    public SoarNodeResult execute(SoarExecutionContext context, Map<String, Object> resolvedConfig) {
        boolean matched = evaluator.evaluate(context.templateVariables(), resolvedConfig);
        String branch = matched ? "true" : "false";
        return SoarNodeResult.advance(branch, Map.of("matched", matched, "branch", branch));
    }

    @Override
    public void validate(String entryType, PlaybookGraph.Node node) {
        Map<String, Object> config = node.config();
        if (!"AND".equals(config.getOrDefault("mode", "AND"))) {
            throw new IllegalArgumentException("MVP 条件节点仅支持 AND 组合");
        }
        Object raw = config.get("conditions");
        if (!(raw instanceof List<?> conditions) || conditions.isEmpty() || conditions.size() > 10) {
            throw new IllegalArgumentException("条件节点必须包含 1 到 10 条条件");
        }
        for (Object item : conditions) {
            if (!(item instanceof Map<?, ?> condition)) throw new IllegalArgumentException("条件格式错误");
            String field = text(condition.get("field"));
            String operator = text(condition.get("operator"));
            SoarDictionary.FieldDefinition definition = dictionary.field(entryType, field);
            if (definition.operators().stream().noneMatch(candidate -> candidate.id().equals(operator))) {
                throw new IllegalArgumentException("字段 " + field + " 不支持操作符 " + operator);
            }
            if (!NO_VALUE_OPERATORS.contains(operator) && !condition.containsKey("value")) {
                throw new IllegalArgumentException("条件缺少比较值: " + field);
            }
        }
    }

    @Override
    public Set<String> outgoingBranches() {
        return Set.of("true", "false");
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
