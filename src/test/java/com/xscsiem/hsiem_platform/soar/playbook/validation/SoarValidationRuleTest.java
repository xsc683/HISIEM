package com.xscsiem.hsiem_platform.soar.playbook.validation;

import com.xscsiem.hsiem_platform.soar.PlaybookGraph;
import com.xscsiem.hsiem_platform.soar.SoarEndNodeHandler;
import com.xscsiem.hsiem_platform.soar.SoarNodeHandlerRegistry;
import com.xscsiem.hsiem_platform.soar.execution.handler.SoarParallelNodeHandler;
import com.xscsiem.hsiem_platform.soar.SoarStartNodeHandler;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;

class SoarValidationRuleTest {

    private final SoarNodeHandlerRegistry handlers = new SoarNodeHandlerRegistry(
            List.of(new SoarStartNodeHandler(), new SoarEndNodeHandler()));

    @Test
    void edgePortRejectsUppercaseThatRuntimeRouterWouldNotMatch() {
        PlaybookGraph graph = graph(Map.of(), "NEXT");
        assertThrows(IllegalArgumentException.class,
                () -> new EdgePortValidationRule().validate(context(graph)));
    }

    @Test
    void variableRuleRejectsUnknownNodeReference() {
        PlaybookGraph graph = graph(Map.of("value", "${nodes.missing.output.id}"), "next");
        assertThrows(IllegalArgumentException.class,
                () -> new VariableReferenceValidationRule().validate(context(graph)));
    }

    @Test
    void parallelHandlerRejectsUppercaseBranchThatRuntimeRouterWouldNotMatch() {
        PlaybookGraph.Node node = new PlaybookGraph.Node("parallel", "parallel", "parallel",
                Map.of("branches", List.of("LEFT", "right"), "joinNode", "join"), 0, 0);
        assertThrows(IllegalArgumentException.class,
                () -> new SoarParallelNodeHandler().validate("alert", node));
    }

    private SoarValidationContext context(PlaybookGraph graph) {
        return new SoarValidationContext("alert", List.of("alert.created"), graph, handlers);
    }

    private PlaybookGraph graph(Map<String, Object> config, String branch) {
        return new PlaybookGraph(List.of(
                new PlaybookGraph.Node("start", "start", "start", config, 0, 0),
                new PlaybookGraph.Node("end", "end", "end", Map.of(), 0, 0)),
                List.of(new PlaybookGraph.Edge("edge", "start", "end", branch)));
    }
}
