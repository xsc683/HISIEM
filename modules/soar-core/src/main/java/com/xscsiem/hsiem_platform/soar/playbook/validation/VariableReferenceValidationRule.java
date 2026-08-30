package com.xscsiem.hsiem_platform.soar.playbook.validation;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class VariableReferenceValidationRule implements SoarPlaybookValidationRule {

    private static final Pattern TOKEN = Pattern.compile("\\$\\{([a-zA-Z0-9_.-]+)}");
    private static final Set<String> COMMON_ROOTS = Set.of("trigger", "execution", "variables", "nodes", "loop");

    @Override
    public int order() {
        return 40;
    }

    @Override
    public void validate(SoarValidationContext context) {
        Set<String> nodeIds = new HashSet<>();
        context.graph().nodes().forEach(node -> nodeIds.add(node.id()));
        context.graph().nodes().forEach(node -> inspect(node.config(), context.entryType(), nodeIds));
    }

    private void inspect(Object value, String entryType, Set<String> nodeIds) {
        if (value instanceof Map<?, ?> map) {
            map.values().forEach(item -> inspect(item, entryType, nodeIds));
            return;
        }
        if (value instanceof Collection<?> collection) {
            collection.forEach(item -> inspect(item, entryType, nodeIds));
            return;
        }
        if (!(value instanceof String text)) return;
        Matcher matcher = TOKEN.matcher(text);
        while (matcher.find()) {
            String path = matcher.group(1);
            String[] parts = path.split("\\.");
            if (!parts[0].equals(entryType) && !COMMON_ROOTS.contains(parts[0])) {
                throw new IllegalArgumentException("模板变量根对象不受支持: ${" + path + "}");
            }
            if (parts[0].equals("nodes") && (parts.length < 3 || !nodeIds.contains(parts[1]))) {
                throw new IllegalArgumentException("模板变量引用了不存在的节点: ${" + path + "}");
            }
        }
    }
}
