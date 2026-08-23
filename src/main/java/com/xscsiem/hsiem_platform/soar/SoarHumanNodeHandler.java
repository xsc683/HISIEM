package com.xscsiem.hsiem_platform.soar;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

@Component
public class SoarHumanNodeHandler implements SoarNodeHandler {

    @Override
    public String type() {
        return "human";
    }

    @Override
    public SoarNodeResult execute(SoarExecutionContext context, Map<String, Object> resolvedConfig) {
        return SoarNodeResult.waitForApproval(text(resolvedConfig.get("prompt")));
    }

    @Override
    public void validate(String entryType, PlaybookGraph.Node node) {
        if (text(node.config().get("prompt")).isBlank()) {
            throw new IllegalArgumentException("审批提示语不能为空");
        }
    }

    @Override
    public Set<String> outgoingBranches() {
        return Set.of("approve", "reject");
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
