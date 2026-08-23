package com.xscsiem.hsiem_platform.soar;

import java.util.List;
import java.util.Map;

/** Immutable graph snapshot persisted with every playbook revision and execution. */
public record PlaybookGraph(List<Node> nodes, List<Edge> edges) {

    public PlaybookGraph {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        edges = edges == null ? List.of() : List.copyOf(edges);
    }

    public record Node(String id, String name, String type, Map<String, Object> config,
                       double x, double y, ExecutionPolicy policy) {
        public Node {
            config = config == null ? Map.of() : Map.copyOf(config);
            policy = policy == null ? ExecutionPolicy.defaults() : policy;
        }

        public Node(String id, String name, String type, Map<String, Object> config,
                    double x, double y) {
            this(id, name, type, config, x, y, ExecutionPolicy.defaults());
        }
    }

    /** maxAttempts=0 delegates the default to the node handler. */
    public record ExecutionPolicy(int maxAttempts, long initialDelaySeconds,
                                  double backoffMultiplier, long maxDelaySeconds) {
        public static ExecutionPolicy defaults() {
            return new ExecutionPolicy(0, 2, 2.0, 60);
        }
    }

    public record Edge(String id, String source, String target, String branch) {
    }
}
