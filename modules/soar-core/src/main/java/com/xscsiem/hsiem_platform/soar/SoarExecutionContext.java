package com.xscsiem.hsiem_platform.soar;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable view presented to a node handler for one durable node attempt. */
public record SoarExecutionContext(
        SoarExecution execution,
        SoarTriggerEnvelope trigger,
        PlaybookGraph.Node node,
        SoarExecution.NodeRun nodeRun,
        Map<String, Object> payload,
        Map<String, Map<String, Object>> nodeOutputs,
        Map<String, Object> variables) {

    public SoarExecutionContext {
        payload = payload == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(payload));
        nodeOutputs = nodeOutputs == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(nodeOutputs));
        variables = variables == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(variables));
    }

    public Map<String, Object> templateVariables() {
        Map<String, Object> result = new LinkedHashMap<>(payload);
        Map<String, Object> nodes = new LinkedHashMap<>();
        nodeOutputs.forEach((id, output) -> nodes.put(id, Map.of("output", output)));
        result.put("nodes", nodes);
        result.put("variables", variables);
        result.put("execution", executionValue());
        result.put("trigger", trigger.templateValue());
        return result;
    }

    public Map<String, Object> persistedInput(Map<String, Object> resolvedConfig) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("eventType", execution.eventType());
        input.put("objectId", execution.objectId());
        input.put("triggerMessageId", execution.triggerMessageId());
        input.put("config", resolvedConfig);
        return input;
    }

    private Map<String, Object> executionValue() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", execution.id());
        value.put("tenantId", execution.tenantId());
        value.put("playbookId", execution.playbookId());
        value.put("playbookRevision", execution.playbookRevision());
        value.put("objectType", execution.objectType());
        value.put("objectId", execution.objectId());
        value.put("eventType", execution.eventType());
        if (nodeRun != null) {
            value.put("nodeRunId", nodeRun.id());
            value.put("attempt", nodeRun.attempt());
            value.put("idempotencyKey", nodeRun.idempotencyKey());
        }
        return value;
    }
}
