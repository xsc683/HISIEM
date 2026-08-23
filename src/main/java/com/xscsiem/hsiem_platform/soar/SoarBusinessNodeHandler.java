package com.xscsiem.hsiem_platform.soar;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class SoarBusinessNodeHandler implements SoarNodeHandler {

    private final SoarBusinessActionInvocation actions;
    private final SoarDictionary dictionary;

    public SoarBusinessNodeHandler(SoarBusinessActionInvocation actions, SoarDictionary dictionary) {
        this.actions = actions;
        this.dictionary = dictionary;
    }

    @Override
    public String type() {
        return "business";
    }

    @Override
    public SoarNodeResult execute(SoarExecutionContext context, Map<String, Object> resolvedConfig) {
        String action = text(resolvedConfig.get("action"));
        Map<String, Object> parameters = toMap(resolvedConfig.get("parameters"));
        Map<String, Object> output = actions.execute(context, action, parameters);
        return SoarNodeResult.advance("next", output);
    }

    @Override
    public void validate(String entryType, PlaybookGraph.Node node) {
        String actionId = text(node.config().get("action"));
        SoarDictionary.ActionDefinition action = dictionary.action(entryType, actionId);
        Map<?, ?> parameters = node.config().get("parameters") instanceof Map<?, ?> map ? map : Map.of();
        for (SoarDictionary.ParameterDefinition parameter : action.parameters()) {
            if (parameter.required()) {
                Object value = parameters.get(parameter.id());
                if (value == null || text(value).isBlank()) {
                    throw new IllegalArgumentException(action.label() + " 缺少参数: " + parameter.label());
                }
            }
        }
    }

    @Override
    public int defaultMaxAttempts() {
        return 3;
    }

    private Map<String, Object> toMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
