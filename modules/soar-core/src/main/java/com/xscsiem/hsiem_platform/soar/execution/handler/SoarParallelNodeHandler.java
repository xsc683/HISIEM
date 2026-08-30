package com.xscsiem.hsiem_platform.soar.execution.handler;

import com.xscsiem.hsiem_platform.soar.PlaybookGraph;
import com.xscsiem.hsiem_platform.soar.SoarExecutionContext;
import com.xscsiem.hsiem_platform.soar.SoarNodeHandler;
import com.xscsiem.hsiem_platform.soar.SoarNodeResult;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Starts durable branch executions; the engine/store own all state transitions. */
@Component
public class SoarParallelNodeHandler implements SoarNodeHandler {

    @Override
    public String type() {
        return "parallel";
    }

    @Override
    public SoarNodeResult execute(SoarExecutionContext context, Map<String, Object> resolvedConfig) {
        List<String> branches = branches(resolvedConfig.get("branches"));
        return SoarNodeResult.fanOut(branches, text(resolvedConfig.get("joinNode")),
                Map.of("branches", branches, "joinNode", text(resolvedConfig.get("joinNode"))));
    }

    @Override
    public void validate(String entryType, PlaybookGraph.Node node) {
        List<String> branches = branches(node.config().get("branches"));
        String joinNode = text(node.config().get("joinNode"));
        if (branches.size() < 2 || branches.size() > 16) {
            throw new IllegalArgumentException("并行节点分支数必须在 2 到 16 之间");
        }
        if (joinNode.isBlank()) throw new IllegalArgumentException("并行节点必须配置 joinNode");
    }

    @Override
    public boolean variableOutgoingBranches() {
        return true;
    }

    @Override
    public int defaultMaxAttempts() {
        return 1;
    }

    private List<String> branches(Object value) {
        if (!(value instanceof List<?> list)) throw new IllegalArgumentException("并行节点 branches 必须是数组");
        Set<String> unique = new LinkedHashSet<>();
        list.forEach(item -> {
            String branch = text(item);
            if (branch.isBlank()) throw new IllegalArgumentException("并行分支标签不能为空");
            if (!branch.equals(branch.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("并行分支标签必须使用小写: " + branch);
            }
            unique.add(branch);
        });
        if (unique.size() != list.size()) throw new IllegalArgumentException("并行分支标签不能重复");
        return List.copyOf(unique);
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
