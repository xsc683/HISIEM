package com.xscsiem.hsiem_platform.soar;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
@Transactional
class SoarAdvancedFlowIntegrationTest {

    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory()).findAndRegisterModules();

    @Autowired
    private SoarExecutionStore store;

    @Autowired
    private SoarEngine engine;

    @Autowired
    private SoarPlaybookCatalog catalog;

    @Test
    void boundedLoopReexecutesBodyAndMapCollectsEveryItem() throws Exception {
        SoarPlaybook playbook = yaml.readValue("""
                formatVersion: "2"
                id: advanced-loop-map
                name: Advanced loop and map
                version: "1.0"
                resourceTypes: [alert]
                entrypoint: repeat
                nodes:
                  - id: repeat
                    name: bounded loop
                    type: loop
                    with:
                      maxIterations: 3
                      iterationVariable: iteration
                    transitions:
                      - target: body
                        on: success
                      - target: batch
                        on: complete
                  - id: body
                    name: loop body
                    type: action
                    action: context.set
                    with:
                      values:
                        lastIteration: ${variables.iteration}
                    transitions:
                      - target: repeat
                  - id: batch
                    name: map items
                    type: map
                    with:
                      items: [alpha, beta, gamma]
                      action: context.set
                      arguments:
                        values:
                          mapped: ${item}
                      concurrency: 2
                      maxItems: 10
                    transitions:
                      - target: done
                  - id: done
                    name: done
                    type: end
                    result: succeeded
                """, SoarPlaybook.class);
        SoarPlaybookRegistry.validate(playbook, null);
        String id = create(playbook, null, null);
        SoarExecution claimed = store.claimNext("worker-a", Instant.now(), Instant.now().plusSeconds(30));
        engine.process(claimed, "worker-a");

        SoarExecution result = store.find(id);
        assertEquals("succeeded", result.status());
        assertEquals(3L, store.listEvents(id).stream()
                .filter(event -> "node.loop_iteration".equals(event.eventType())).count());
        SoarStepExecution map = store.findStep(id, "batch");
        assertEquals(3, ((Number) map.output().get("succeeded")).intValue());
        assertEquals(3, ((List<?>) map.output().get("results")).size());
    }

    @Test
    void parentReleasesLeaseWhileChildRunsAndThenResumes() throws Exception {
        SoarPlaybook child = yaml.readValue("""
                formatVersion: "2"
                id: reusable-child-flow
                name: Reusable child
                version: "1.0"
                resourceTypes: [alert]
                entrypoint: enrich
                nodes:
                  - id: enrich
                    name: enrich
                    type: action
                    action: context.set
                    with:
                      values: {child: complete}
                    transitions:
                      - target: done
                  - id: done
                    name: done
                    type: end
                    result: succeeded
                """, SoarPlaybook.class);
        SoarPlaybookRevision draft = catalog.createDraft("default", child, Map.of(), "author");
        catalog.submit("default", child.id(), draft.revision(), "author");
        catalog.review("default", child.id(), draft.revision(), true, "ok", "reviewer");
        catalog.publish("default", child.id(), draft.revision(), 100, "publisher");

        SoarPlaybook parent = yaml.readValue("""
                formatVersion: "2"
                id: parent-flow-test
                name: Parent
                version: "1.0"
                resourceTypes: [alert]
                entrypoint: child
                nodes:
                  - id: child
                    name: child
                    type: subplaybook
                    with:
                      playbookId: reusable-child-flow
                      input: {source: parent}
                    transitions:
                      - target: done
                  - id: done
                    name: done
                    type: end
                    result: succeeded
                """, SoarPlaybook.class);
        SoarPlaybookRegistry.validate(parent, null);
        String parentId = create(parent, null, null);

        SoarExecution first = store.claimNext("parent-worker", Instant.now(), Instant.now().plusSeconds(30));
        engine.process(first, "parent-worker");
        SoarExecution waiting = store.find(parentId);
        assertEquals("queued", waiting.status());
        SoarExecution childExecution = store.find("soar-child-" + UUID.nameUUIDFromBytes(
                (parentId + ":child").getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertNotNull(childExecution);

        SoarExecution childClaim = store.claimNext("child-worker", Instant.now(), Instant.now().plusSeconds(30));
        assertEquals(childExecution.id(), childClaim.id());
        engine.process(childClaim, "child-worker");
        assertEquals("succeeded", store.find(childExecution.id()).status());

        Instant later = Instant.now().plusSeconds(2);
        SoarExecution parentClaim = store.claimNext("parent-worker-2", later, later.plusSeconds(30));
        assertEquals(parentId, parentClaim.id());
        engine.process(parentClaim, "parent-worker-2");
        assertEquals("succeeded", store.find(parentId).status());
    }

    private String create(SoarPlaybook playbook, String parentId, String parentNode) {
        String id = "soar-test-" + UUID.randomUUID();
        SoarGraph graph = SoarGraph.compile(playbook);
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("executionId", id);
        context.put("playbookId", playbook.id());
        context.put("tenantId", "default");
        context.put("resourceType", "alert");
        context.put("resourceId", "alert-test");
        context.put("resource", Map.of("alert.risk_score", 90));
        context.put("variables", new LinkedHashMap<>());
        context.put("nodes", new LinkedHashMap<>());
        context.put("alertId", "alert-test");
        Instant now = Instant.now();
        store.create(new SoarExecution(id, playbook.id(), playbook.version(), "alert", "alert-test",
                "queued", "tester", 0, graph.entrypoint(), List.of(graph.entrypoint()), playbook,
                context, "manual", null, null, null, null, null, now, null, null,
                false, false, 0, now, now, null, 0, "default", parentId, parentNode, List.of()));
        return id;
    }
}
