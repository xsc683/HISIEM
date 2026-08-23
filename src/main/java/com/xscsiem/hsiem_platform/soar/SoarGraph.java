package com.xscsiem.hsiem_platform.soar;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 把 V1/V2 Playbook 统一编译成无运行时状态的有向图。 */
public final class SoarGraph {

    private final String entrypoint;
    private final Map<String, SoarPlaybook.Node> nodes;
    private final Map<String, Set<String>> inbound;

    private SoarGraph(String entrypoint, Map<String, SoarPlaybook.Node> nodes,
                      Map<String, Set<String>> inbound) {
        this.entrypoint = entrypoint;
        this.nodes = Collections.unmodifiableMap(nodes);
        this.inbound = Collections.unmodifiableMap(inbound);
    }

    public static SoarGraph compile(SoarPlaybook playbook) {
        List<SoarPlaybook.Node> source = playbook.isGraph()
                ? playbook.nodes() : compileLegacy(playbook.steps());
        Map<String, SoarPlaybook.Node> nodes = new LinkedHashMap<>();
        for (SoarPlaybook.Node node : source) {
            if (node != null) nodes.put(node.id(), node);
        }
        String entrypoint = playbook.isGraph() ? playbook.entrypoint()
                : source.isEmpty() ? null : source.get(0).id();
        Map<String, Set<String>> inbound = new HashMap<>();
        nodes.keySet().forEach(id -> inbound.put(id, new LinkedHashSet<>()));
        for (SoarPlaybook.Node node : nodes.values()) {
            transitions(node).forEach(edge -> {
                if (inbound.containsKey(edge.target())) inbound.get(edge.target()).add(node.id());
            });
        }
        return new SoarGraph(entrypoint, nodes, inbound);
    }

    public String entrypoint() {
        return entrypoint;
    }

    public SoarPlaybook.Node node(String id) {
        return nodes.get(id);
    }

    public List<SoarPlaybook.Node> nodes() {
        return new ArrayList<>(nodes.values());
    }

    public Set<String> inbound(String id) {
        return inbound.getOrDefault(id, Set.of());
    }

    public static List<SoarPlaybook.Transition> transitions(SoarPlaybook.Node node) {
        return node.transitions() == null ? List.of() : node.transitions();
    }

    public static Map<String, Object> parameters(SoarPlaybook.Node node) {
        return node.parameters() == null ? Map.of() : node.parameters();
    }

    private static List<SoarPlaybook.Node> compileLegacy(List<SoarPlaybook.Step> steps) {
        if (steps == null) return List.of();
        List<SoarPlaybook.Node> nodes = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            SoarPlaybook.Step step = steps.get(i);
            List<SoarPlaybook.Transition> transitions = i + 1 < steps.size()
                    ? List.of(new SoarPlaybook.Transition(steps.get(i + 1).id(), "success", null))
                    : List.of();
            nodes.add(new SoarPlaybook.Node(step.id(), step.name(),
                    "approval".equals(step.action()) ? "approval" : "action",
                    step.action(), step.parameters(), step.when(), true, "any",
                    null, null, null, null, transitions));
        }
        return nodes;
    }
}
