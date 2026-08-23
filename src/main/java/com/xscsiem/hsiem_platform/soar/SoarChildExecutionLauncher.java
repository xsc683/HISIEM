package com.xscsiem.hsiem_platform.soar;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 创建可独立租约、审批和恢复的子 Playbook 执行，父流程只持久化等待引用。 */
@Component
public class SoarChildExecutionLauncher {

    private final SoarPlaybookCatalog catalog;
    private final SoarExecutionStore store;

    public SoarChildExecutionLauncher(SoarPlaybookCatalog catalog, SoarExecutionStore store) {
        this.catalog = catalog;
        this.store = store;
    }

    public SoarExecution launch(SoarExecution parent, SoarPlaybook.Node node,
                                Map<String, Object> input) {
        String playbookId = required(input, "playbookId");
        String identity = parent.id() + ":" + node.id();
        String id = "soar-child-" + UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8));
        SoarExecution existing = store.find(id);
        if (existing != null) return existing;
        int depth = number(parent.context().get("subplaybookDepth"), 0) + 1;
        if (depth > 5) throw new IllegalStateException("子 Playbook 嵌套超过 5 层");
        SoarPlaybook playbook = catalog.resolve(parent.tenantId(), playbookId, identity);
        if (!playbook.resourceTypes().contains(parent.resourceType())) {
            throw new IllegalArgumentException("子 Playbook 不支持父执行资源类型");
        }
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("executionId", id);
        context.put("playbookId", playbook.id());
        context.put("tenantId", parent.tenantId());
        context.put("resourceType", parent.resourceType());
        context.put("resourceId", parent.resourceId());
        context.put("resource", parent.context().getOrDefault("resource", Map.of()));
        context.put("nodes", new LinkedHashMap<>());
        context.put("variables", input.get("input") instanceof Map<?, ?> values
                ? new LinkedHashMap<>((Map<String, Object>) values) : new LinkedHashMap<>());
        context.put("subplaybookDepth", depth);
        if ("alert".equals(parent.resourceType())) context.put("alertId", parent.resourceId());
        if ("case".equals(parent.resourceType())) context.put("caseId", parent.resourceId());
        if (!SoarExpression.matches(playbook.when(), context)) {
            throw new IllegalArgumentException("资源不满足子 Playbook 条件");
        }
        SoarGraph graph = SoarGraph.compile(playbook);
        Instant now = Instant.now();
        SoarExecution child = new SoarExecution(id, playbook.id(), playbook.version(),
                parent.resourceType(), parent.resourceId(), "queued", parent.actor(), 0,
                graph.entrypoint(), List.of(graph.entrypoint()), playbook, context,
                "subplaybook", identity, null, null, null, null, now,
                null, null, false, false, 0, now, now, null, 0,
                parent.tenantId(), parent.id(), node.id(), List.of());
        store.create(child);
        store.appendEvent(id, "execution.created", null, parent.actor(), Map.of(
                "triggerType", "subplaybook", "parentExecutionId", parent.id(),
                "parentNodeId", node.id(), "playbook", playbook.id()));
        return store.find(id);
    }

    private static String required(Map<String, Object> input, String key) {
        Object value = input.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new IllegalArgumentException("subplaybook 缺少 with." + key);
        }
        return String.valueOf(value);
    }

    private static int number(Object value, int fallback) {
        if (value instanceof Number number) return number.intValue();
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception ignored) {
            return fallback;
        }
    }
}
