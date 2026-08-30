package com.xscsiem.hsiem_platform.soar.execution.handler;

import com.xscsiem.hsiem_platform.soar.PlaybookGraph;
import com.xscsiem.hsiem_platform.soar.SoarExecutionContext;
import com.xscsiem.hsiem_platform.soar.SoarNodeHandler;
import com.xscsiem.hsiem_platform.soar.SoarNodeResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Starts a persisted serial loop over a bounded item list. */
@Component
public class SoarLoopNodeHandler implements SoarNodeHandler {

    @Override
    public String type() {
        return "loop";
    }

    @Override
    public SoarNodeResult execute(SoarExecutionContext context, Map<String, Object> resolvedConfig) {
        List<Object> items = items(resolvedConfig.get("items"));
        int max = max(resolvedConfig.getOrDefault("maxIterations", items.size()));
        return SoarNodeResult.loop(text(resolvedConfig.get("bodyStart")), text(resolvedConfig.get("bodyEnd")),
                items, max, Map.of("iterations", items.size(), "maxIterations", max));
    }

    @Override
    public void validate(String entryType, PlaybookGraph.Node node) {
        List<Object> values = items(node.config().get("items"));
        int max = max(node.config().getOrDefault("maxIterations", values.size()));
        if (values.isEmpty() || values.size() > 1000) {
            throw new IllegalArgumentException("循环 items 必须包含 1 到 1000 项");
        }
        if (max < values.size()) throw new IllegalArgumentException("循环 maxIterations 不能小于 items 数量");
        if (text(node.config().get("bodyStart")).isBlank()
                || text(node.config().get("bodyEnd")).isBlank()) {
            throw new IllegalArgumentException("循环必须配置 bodyStart 和 bodyEnd");
        }
    }

    private List<Object> items(Object value) {
        if (!(value instanceof List<?> list)) throw new IllegalArgumentException("循环 items 必须是数组");
        return new ArrayList<>(list);
    }

    private int max(Object value) {
        try {
            int result = value instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(value));
            if (result < 1 || result > 1000) throw new IllegalArgumentException("循环 maxIterations 必须在 1 到 1000 之间");
            return result;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("循环 maxIterations 必须是整数", e);
        }
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
