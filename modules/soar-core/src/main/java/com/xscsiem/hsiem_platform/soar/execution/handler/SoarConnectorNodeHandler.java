package com.xscsiem.hsiem_platform.soar.execution.handler;

import com.xscsiem.hsiem_platform.soar.PlaybookGraph;
import com.xscsiem.hsiem_platform.soar.SoarExecutionContext;
import com.xscsiem.hsiem_platform.soar.SoarNodeHandler;
import com.xscsiem.hsiem_platform.soar.SoarNodeResult;
import com.xscsiem.hsiem_platform.soar.device.ConnectorAuditSanitizer;
import com.xscsiem.hsiem_platform.soar.device.SoarConnectorActionInvocation;
import com.xscsiem.hsiem_platform.soar.device.SoarConnectorRegistry;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** Adapter that keeps connector concerns outside the durable execution kernel. */
@Component
public class SoarConnectorNodeHandler implements SoarNodeHandler {

    private final SoarConnectorActionInvocation actions;
    private final SoarConnectorRegistry connectors;

    public SoarConnectorNodeHandler(SoarConnectorActionInvocation actions,
                                    SoarConnectorRegistry connectors) {
        this.actions = actions;
        this.connectors = connectors;
    }

    @Override
    public String type() {
        return "connector";
    }

    @Override
    public SoarNodeResult execute(SoarExecutionContext context, Map<String, Object> resolvedConfig) {
        String runtimeKey = text(resolvedConfig.get("runtimeKey"));
        String action = text(resolvedConfig.get("action"));
        Map<String, Object> parameters = toMap(resolvedConfig.get("parameters"));
        Duration timeout = Duration.ofMillis(number(resolvedConfig.getOrDefault("timeoutMs", 10_000)));
        Map<String, Object> output = actions.execute(context, runtimeKey, action, parameters, timeout);
        return SoarNodeResult.advance("next", output);
    }

    @Override
    public void validate(String entryType, PlaybookGraph.Node node) {
        String runtimeKey = text(node.config().get("runtimeKey"));
        if (runtimeKey.isBlank()) throw new IllegalArgumentException("Connector 节点缺少 runtimeKey");
        com.xscsiem.hsiem_platform.soar.device.SoarConnector connector = connectors.require(runtimeKey);
        String action = text(node.config().get("action"));
        if (action.isBlank()) throw new IllegalArgumentException("Connector 节点缺少 action");
        if (!connector.capabilities().contains(action)) {
            throw new IllegalArgumentException("Connector " + runtimeKey + " 不支持动作 " + action);
        }
        number(node.config().getOrDefault("timeoutMs", 10_000));
    }

    @Override
    public int defaultMaxAttempts() {
        return 3;
    }

    @Override
    public Map<String, Object> auditSafeConfig(Map<String, Object> resolvedConfig) {
        @SuppressWarnings("unchecked")
        Map<String, Object> sanitized = (Map<String, Object>) ConnectorAuditSanitizer.sanitize(resolvedConfig);
        if (sanitized.get("parameters") instanceof Map<?, ?> values && values.containsKey("body")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> parameters = (Map<String, Object>) values;
            parameters.put("body", "[REDACTED]");
        }
        return sanitized;
    }

    private long number(Object value) {
        long result;
        try {
            result = value instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(value));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Connector timeoutMs 必须是数字", e);
        }
        if (result < 1 || result > 120_000) throw new IllegalArgumentException("Connector timeoutMs 必须在 1 到 120000 之间");
        return result;
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
