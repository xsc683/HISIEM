package com.xscsiem.hsiem_platform.soar;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

@Component
public class SoarEndNodeHandler implements SoarNodeHandler {

    @Override
    public String type() {
        return "end";
    }

    @Override
    public SoarNodeResult execute(SoarExecutionContext context, Map<String, Object> resolvedConfig) {
        return SoarNodeResult.complete(Map.of("completed", true));
    }

    @Override
    public void validate(String entryType, PlaybookGraph.Node node) {
        if (!node.config().isEmpty()) throw new IllegalArgumentException("结束节点不接受参数");
    }

    @Override
    public Set<String> outgoingBranches() {
        return Set.of();
    }
}
