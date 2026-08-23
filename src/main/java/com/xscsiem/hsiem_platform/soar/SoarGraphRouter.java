package com.xscsiem.hsiem_platform.soar;

import org.springframework.stereotype.Component;

@Component
public class SoarGraphRouter {

    public String next(PlaybookGraph graph, String source, String branch) {
        return graph.edges().stream()
                .filter(edge -> edge.source().equals(source) && branch.equals(edge.branch()))
                .map(PlaybookGraph.Edge::target).findFirst()
                .orElseThrow(() -> new IllegalStateException("节点 " + source + " 缺少 " + branch + " 分支"));
    }
}
