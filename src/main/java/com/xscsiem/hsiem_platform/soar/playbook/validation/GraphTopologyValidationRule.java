package com.xscsiem.hsiem_platform.soar.playbook.validation;

import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

/** Additional structure rules that are intentionally independent of handlers. */
@Component
public class GraphTopologyValidationRule implements SoarPlaybookValidationRule {

    @Override
    public int order() {
        return 30;
    }

    @Override
    public void validate(SoarValidationContext context) {
        Set<String> joinTargets = new HashSet<>();
        context.graph().nodes().stream().filter(node -> "parallel".equals(node.type())).forEach(node -> {
            String join = String.valueOf(node.config().getOrDefault("joinNode", "")).trim();
            if (!joinTargets.add(join)) {
                throw new IllegalArgumentException("不同 parallel 节点不能共享同一个 joinNode: " + join);
            }
        });
    }
}
