package com.xscsiem.hsiem_platform.soar;

import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class SoarStartNodeHandler implements SoarNodeHandler {

    @Override
    public String type() {
        return "start";
    }

    @Override
    public SoarNodeResult execute(SoarExecutionContext context, Map<String, Object> resolvedConfig) {
        return SoarNodeResult.advance("next", Map.of("started", true));
    }

    @Override
    public void validate(String entryType, PlaybookGraph.Node node) {
        if (!node.config().isEmpty()) throw new IllegalArgumentException("开始节点不接受参数");
    }

    @Override
    public boolean acceptsIncoming() {
        return false;
    }

    @Override
    public boolean requiresIncoming() {
        return false;
    }
}
