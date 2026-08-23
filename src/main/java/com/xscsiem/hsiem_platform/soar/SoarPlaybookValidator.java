package com.xscsiem.hsiem_platform.soar;

import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class SoarPlaybookValidator {

    private static final int MAX_NODES = 50;

    private final SoarNodeHandlerRegistry handlers;

    public SoarPlaybookValidator(SoarNodeHandlerRegistry handlers) {
        this.handlers = handlers;
    }

    /** Drafts may be incomplete, but they cannot contain unknown node types or broken references. */
    public void validateDraft(String entryType, List<String> eventTypes, PlaybookGraph graph) {
        validateHeader(entryType, eventTypes);
        if (graph == null || graph.nodes().size() < 2 || graph.nodes().size() > MAX_NODES) {
            throw new IllegalArgumentException("流程图节点数必须在 2 到 " + MAX_NODES + " 之间");
        }
        Map<String, PlaybookGraph.Node> nodes = new HashMap<>();
        for (PlaybookGraph.Node node : graph.nodes()) {
            requireText(node.id(), "节点 ID");
            requireText(node.name(), "节点名称");
            handlers.require(node.type());
            if (nodes.put(node.id(), node) != null) {
                throw new IllegalArgumentException("节点 ID 重复: " + node.id());
            }
        }
        if (graph.nodes().stream().filter(node -> "start".equals(node.type())).count() != 1
                || graph.nodes().stream().filter(node -> "end".equals(node.type())).count() != 1) {
            throw new IllegalArgumentException("草稿也必须且只能包含一个开始节点和一个结束节点");
        }
        Set<String> edgeIds = new HashSet<>();
        for (PlaybookGraph.Edge edge : graph.edges()) {
            requireText(edge.id(), "连线 ID");
            if (!edgeIds.add(edge.id())) throw new IllegalArgumentException("连线 ID 重复: " + edge.id());
            if (!nodes.containsKey(edge.source()) || !nodes.containsKey(edge.target())) {
                throw new IllegalArgumentException("连线引用了不存在的节点: " + edge.id());
            }
            if (edge.source().equals(edge.target())) {
                throw new IllegalArgumentException("节点不能连接自身: " + edge.source());
            }
        }
    }

    public void validate(String entryType, List<String> eventTypes, PlaybookGraph graph) {
        validateHeader(entryType, eventTypes);
        validateDraft(entryType, eventTypes, graph);
        validateCompleteGraph(entryType, graph);
    }

    private void validateHeader(String entryType, List<String> eventTypes) {
        if (!Set.of("alert", "case").contains(entryType)) {
            throw new IllegalArgumentException("entryType 仅支持 alert 或 case");
        }
        if (eventTypes == null || eventTypes.isEmpty()) {
            throw new IllegalArgumentException("至少选择一个生命周期事件");
        }
        Set<String> allowedEvents = Set.of(entryType + ".created", entryType + ".updated");
        if (eventTypes.stream().anyMatch(item -> !allowedEvents.contains(item))) {
            throw new IllegalArgumentException("生命周期事件必须与入口对象类型一致");
        }
    }

    private void validateCompleteGraph(String entryType, PlaybookGraph graph) {
        Map<String, PlaybookGraph.Node> nodes = new HashMap<>();
        for (PlaybookGraph.Node node : graph.nodes()) {
            requireText(node.id(), "节点 ID");
            requireText(node.name(), "节点名称");
            SoarNodeHandler handler = handlers.require(node.type());
            if (nodes.put(node.id(), node) != null) {
                throw new IllegalArgumentException("节点 ID 重复: " + node.id());
            }
            SoarRetryPolicy.resolve(node, handler);
            handler.validate(entryType, node);
        }

        List<PlaybookGraph.Node> starts = graph.nodes().stream().filter(node -> "start".equals(node.type())).toList();
        List<PlaybookGraph.Node> ends = graph.nodes().stream().filter(node -> "end".equals(node.type())).toList();
        if (starts.size() != 1 || ends.size() != 1) {
            throw new IllegalArgumentException("流程必须且只能包含一个开始节点和一个结束节点");
        }

        Map<String, List<PlaybookGraph.Edge>> outgoing = new HashMap<>();
        Map<String, List<PlaybookGraph.Edge>> incoming = new HashMap<>();
        Set<String> edgeIds = new HashSet<>();
        Set<String> routes = new HashSet<>();
        for (PlaybookGraph.Edge edge : graph.edges()) {
            requireText(edge.id(), "连线 ID");
            if (!edgeIds.add(edge.id())) {
                throw new IllegalArgumentException("连线 ID 重复: " + edge.id());
            }
            if (!nodes.containsKey(edge.source()) || !nodes.containsKey(edge.target())) {
                throw new IllegalArgumentException("连线引用了不存在的节点: " + edge.id());
            }
            if (edge.source().equals(edge.target())) {
                throw new IllegalArgumentException("节点不能连接自身: " + edge.source());
            }
            String routeKey = edge.source() + "\u0000" + normalize(edge.branch());
            if (!routes.add(routeKey)) {
                throw new IllegalArgumentException("同一节点的分支标签不能重复: " + edge.source());
            }
            outgoing.computeIfAbsent(edge.source(), ignored -> new ArrayList<>()).add(edge);
            incoming.computeIfAbsent(edge.target(), ignored -> new ArrayList<>()).add(edge);
        }

        for (PlaybookGraph.Node node : graph.nodes()) {
            List<PlaybookGraph.Edge> outs = outgoing.getOrDefault(node.id(), List.of());
            List<PlaybookGraph.Edge> ins = incoming.getOrDefault(node.id(), List.of());
            SoarNodeHandler handler = handlers.require(node.type());
            if (!handler.acceptsIncoming()) {
                requireSize(ins, 0, node.name() + "不能有入线");
            } else if (handler.requiresIncoming() && ins.isEmpty()) {
                throw new IllegalArgumentException(node.name() + "必须有入线");
            }
            requireBranches(outs, handler.outgoingBranches(), node.name());
        }

        Set<String> reachable = walk(starts.getFirst().id(), outgoing, true);
        if (reachable.size() != nodes.size()) {
            throw new IllegalArgumentException("存在从开始节点不可达的孤立节点");
        }
        Set<String> canReachEnd = walk(ends.getFirst().id(), incoming, false);
        if (canReachEnd.size() != nodes.size()) {
            throw new IllegalArgumentException("存在无法到达结束节点的分支");
        }
        assertAcyclic(starts.getFirst().id(), outgoing, nodes.size());
    }

    private void requireBranches(List<PlaybookGraph.Edge> edges, Set<String> expected, String label) {
        Set<String> actual = new HashSet<>();
        for (PlaybookGraph.Edge edge : edges) actual.add(normalize(edge.branch()));
        if (!actual.equals(expected) || edges.size() != expected.size()) {
            throw new IllegalArgumentException(label + "必须且只能包含分支 " + expected);
        }
    }

    private void requireSize(List<?> values, int size, String message) {
        if (values.size() != size) throw new IllegalArgumentException(message);
    }

    private Set<String> walk(String origin, Map<String, List<PlaybookGraph.Edge>> adjacency, boolean forward) {
        Set<String> visited = new HashSet<>();
        ArrayDeque<String> queue = new ArrayDeque<>();
        queue.add(origin);
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            if (!visited.add(current)) continue;
            for (PlaybookGraph.Edge edge : adjacency.getOrDefault(current, List.of())) {
                queue.add(forward ? edge.target() : edge.source());
            }
        }
        return visited;
    }

    private void assertAcyclic(String start, Map<String, List<PlaybookGraph.Edge>> outgoing, int nodeCount) {
        Map<String, Integer> incomingCount = new HashMap<>();
        Set<String> all = new HashSet<>();
        all.add(start);
        for (List<PlaybookGraph.Edge> edges : outgoing.values()) {
            for (PlaybookGraph.Edge edge : edges) {
                all.add(edge.source());
                all.add(edge.target());
                incomingCount.merge(edge.target(), 1, Integer::sum);
            }
        }
        ArrayDeque<String> queue = new ArrayDeque<>();
        all.stream().filter(id -> incomingCount.getOrDefault(id, 0) == 0).forEach(queue::add);
        int visited = 0;
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            visited++;
            for (PlaybookGraph.Edge edge : outgoing.getOrDefault(current, List.of())) {
                int next = incomingCount.merge(edge.target(), -1, Integer::sum);
                if (next == 0) queue.add(edge.target());
            }
        }
        if (visited != nodeCount) throw new IllegalArgumentException("流程图不允许出现循环");
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }

    private void requireText(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + "不能为空");
    }
}
