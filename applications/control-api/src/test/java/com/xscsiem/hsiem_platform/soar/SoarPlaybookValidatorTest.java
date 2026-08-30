package com.xscsiem.hsiem_platform.soar;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SoarPlaybookValidatorTest {

    private final SoarDictionary dictionary = new SoarDictionary();
    private final SoarPlaybookValidator validator = new SoarPlaybookValidator(new SoarNodeHandlerRegistry(List.of(
            new SoarStartNodeHandler(), new SoarEndNodeHandler(),
            new SoarConditionNodeHandler(new SoarConditionEvaluator(), dictionary),
            new SoarBusinessNodeHandler(null, dictionary), new SoarHumanNodeHandler(),
            new SoarWaitNodeHandler())));

    @Test
    void acceptsTypedConditionAndTwoExplicitBranches() {
        PlaybookGraph graph = new PlaybookGraph(List.of(
                node("start", "start", Map.of()),
                node("condition", "condition", Map.of("mode", "AND", "conditions", List.of(
                        Map.of("field", "alert.risk_score", "operator", "gt", "value", 70)))),
                node("end", "end", Map.of())), List.of(
                edge("a", "start", "condition", "next"),
                edge("b", "condition", "end", "true"),
                edge("c", "condition", "end", "false")));

        assertDoesNotThrow(() -> validator.validate("alert", List.of("alert.created"), graph));
    }

    @Test
    void rejectsFieldOutsideLifecycleDictionary() {
        PlaybookGraph graph = new PlaybookGraph(List.of(
                node("start", "start", Map.of()),
                node("condition", "condition", Map.of("mode", "AND", "conditions", List.of(
                        Map.of("field", "event.original", "operator", "eq", "value", "x")))),
                node("end", "end", Map.of())), List.of(
                edge("a", "start", "condition", "next"),
                edge("b", "condition", "end", "true"),
                edge("c", "condition", "end", "false")));

        assertThrows(IllegalArgumentException.class,
                () -> validator.validate("alert", List.of("alert.created"), graph));
    }

    @Test
    void draftMayContainDisconnectedBusinessNodeButPublishedGraphMayNot() {
        PlaybookGraph graph = new PlaybookGraph(List.of(
                node("start", "start", Map.of()), node("end", "end", Map.of()),
                node("action", "business", Map.of())),
                List.of(edge("a", "start", "end", "next")));

        assertDoesNotThrow(() -> validator.validateDraft("alert", List.of("alert.created"), graph));
        assertThrows(IllegalArgumentException.class,
                () -> validator.validate("alert", List.of("alert.created"), graph));
    }

    @Test
    void rejectsCycles() {
        PlaybookGraph graph = new PlaybookGraph(List.of(
                node("start", "start", Map.of()),
                node("a", "wait", Map.of("amount", 1, "unit", "minutes")),
                node("b", "wait", Map.of("amount", 1, "unit", "minutes")),
                node("end", "end", Map.of())), List.of(
                edge("s", "start", "a", "next"), edge("ab", "a", "b", "next"),
                edge("ba", "b", "a", "next"), edge("be", "b", "end", "other")));

        assertThrows(IllegalArgumentException.class,
                () -> validator.validate("alert", List.of("alert.created"), graph));
    }

    private PlaybookGraph.Node node(String id, String type, Map<String, Object> config) {
        return new PlaybookGraph.Node(id, id, type, config, 0, 0);
    }

    private PlaybookGraph.Edge edge(String id, String source, String target, String branch) {
        return new PlaybookGraph.Edge(id, source, target, branch);
    }
}
