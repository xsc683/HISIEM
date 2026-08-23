package com.xscsiem.hsiem_platform.soar;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.xscsiem.hsiem_platform.control.ControlPlaneStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
@Transactional
class SoarEngineIntegrationTest {

    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory()).findAndRegisterModules();

    @Autowired
    private SoarExecutionStore store;

    @Autowired
    private SoarEngine engine;

    @Autowired
    private SoarService service;

    @Test
    void graphFansOutJoinsWaitsForApprovalAndResumesFromDatabase() throws Exception {
        SoarPlaybook playbook = yaml.readValue("""
                        formatVersion: "2"
                        id: graph-runtime-test
                        name: Graph runtime test
                        version: "2.0"
                        resourceTypes: [alert]
                        entrypoint: fan-out
                        nodes:
                          - id: fan-out
                            name: fan out
                            type: decision
                            transitions:
                              - target: branch-a
                              - target: branch-b
                          - id: branch-a
                            name: branch A
                            type: action
                            action: context.set
                            with:
                              values: {a: complete}
                            transitions:
                              - target: approve
                          - id: branch-b
                            name: branch B
                            type: action
                            action: context.set
                            with:
                              values: {b: complete}
                            transitions:
                              - target: approve
                          - id: approve
                            name: approve joined branches
                            type: approval
                            join: all
                            with:
                              requiredRole: admin
                              message: approve both branches
                            transitions:
                              - target: completed
                                on: approved
                              - target: rejected
                                on: rejected
                          - id: completed
                            name: completed
                            type: end
                            result: succeeded
                          - id: rejected
                            name: rejected
                            type: end
                            result: rejected
                        """, SoarPlaybook.class);
        SoarPlaybookRegistry.validate(playbook, null);
        String id = "soar-graph-" + UUID.randomUUID();
        Instant now = Instant.now();
        Map<String, Object> context = Map.of(
                "executionId", id, "playbookId", playbook.id(), "alertId", "alert-1",
                "resource", Map.of("alert.risk_score", 99), "nodes", Map.of(), "variables", Map.of());
        assertTrue(store.create(new SoarExecution(id, playbook.id(), playbook.version(),
                "alert", "alert-1", "queued", "admin", 0, playbook, context,
                null, null, null, null, now, now, null, 0, List.of())));

        SoarExecution firstClaim = store.claimNext("worker-a", now.plusSeconds(1), now.plusSeconds(46));
        assertNotNull(firstClaim);
        engine.process(firstClaim, "worker-a");

        SoarExecution waiting = store.find(id);
        assertEquals("waiting_approval", waiting.status());
        assertEquals("approve", waiting.approvalStepId());
        assertEquals(List.of("branch-a", "branch-b", "fan-out"), store.listSteps(id).stream()
                .filter(step -> "succeeded".equals(step.status()))
                .map(SoarStepExecution::stepId).sorted().toList());
        assertEquals("complete", SoarExpression.lookup(waiting.context(), "variables.a"));
        assertEquals("complete", SoarExpression.lookup(waiting.context(), "variables.b"));

        SoarExecution resumed = service.approve(id, true, "admin", "admin");
        assertEquals("queued", resumed.status());
        SoarExecution secondClaim = store.claimNext("worker-b", Instant.now().plusSeconds(1),
                Instant.now().plusSeconds(46));
        assertNotNull(secondClaim);
        engine.process(secondClaim, "worker-b");

        assertEquals("succeeded", store.find(id).status());
        assertTrue(store.listEvents(id).stream()
                .anyMatch(event -> "execution.succeeded".equals(event.eventType())));
    }

    @Test
    void failedActionIsPersistedRetriedAndThenTerminatesAtAttemptLimit() throws Exception {
        SoarPlaybook playbook = yaml.readValue("""
                formatVersion: "2"
                id: retry-runtime-test
                name: Retry runtime test
                version: "2.0"
                resourceTypes: [alert]
                defaults:
                  timeoutSeconds: 2
                  retry:
                    maxAttempts: 2
                    delaySeconds: 0
                    backoffMultiplier: 2.0
                entrypoint: external-lookup
                nodes:
                  - id: external-lookup
                    name: unavailable connector
                    type: action
                    action: connector.call
                    with:
                      connector: connector-does-not-exist
                      operation: lookup-ip
                      arguments: {ip: 203.0.113.10}
                    transitions:
                      - target: completed
                        on: success
                  - id: completed
                    name: completed
                    type: end
                    result: succeeded
                """, SoarPlaybook.class);
        SoarPlaybookRegistry.validate(playbook, null);
        String id = "soar-retry-" + UUID.randomUUID();
        Instant now = Instant.now();
        assertTrue(store.create(new SoarExecution(id, playbook.id(), playbook.version(),
                "alert", "alert-2", "queued", "admin", 0, playbook,
                Map.of("executionId", id, "playbookId", playbook.id(), "alertId", "alert-2",
                        "resource", Map.of(), "nodes", Map.of(), "variables", Map.of()),
                null, null, null, null, now, now, null, 0, List.of())));

        SoarExecution first = store.claimNext("worker-retry-1", now.plusSeconds(1), now.plusSeconds(46));
        engine.process(first, "worker-retry-1");
        assertEquals("queued", store.find(id).status());
        assertEquals("retrying", store.findStep(id, "external-lookup").status());

        SoarExecution second = store.claimNext("worker-retry-2", Instant.now().plusSeconds(1),
                Instant.now().plusSeconds(46));
        engine.process(second, "worker-retry-2");
        assertEquals("failed", store.find(id).status());
        assertEquals(2, store.findStep(id, "external-lookup").attempt());
        assertTrue(store.listEvents(id).stream()
                .anyMatch(event -> "node.retry_scheduled".equals(event.eventType())));

        SoarExecution manuallyRetried = service.retry(id, "admin");
        assertEquals("queued", manuallyRetried.status());
        assertEquals(List.of("external-lookup"), manuallyRetried.frontier());
        assertEquals("retrying", store.findStep(id, "external-lookup").status());
        assertEquals(0, store.findStep(id, "external-lookup").attempt());
    }

    @Test
    void persistedFinalFailureIsRoutedAfterCrashWithoutRepeatingAction() throws Exception {
        SoarPlaybook playbook = yaml.readValue("""
                formatVersion: "2"
                id: failure-recovery-test
                name: Failure recovery test
                version: "2.0"
                resourceTypes: [alert]
                entrypoint: protected-action
                nodes:
                  - id: protected-action
                    name: action already failed
                    type: action
                    action: context.set
                    retry: {maxAttempts: 1}
                    with:
                      values: {mustNotRunAgain: true}
                    transitions:
                      - target: failed-end
                        on: failure
                  - id: failed-end
                    name: failed end
                    type: end
                    result: failed
                """, SoarPlaybook.class);
        SoarPlaybookRegistry.validate(playbook, null);
        String id = "soar-crash-" + UUID.randomUUID();
        Instant now = Instant.now();
        assertTrue(store.create(execution(id, playbook, now)));
        SoarExecution claimed = store.claimNext("worker-crash", now.plusSeconds(1), now.plusSeconds(46));
        SoarPlaybook.Node node = SoarGraph.compile(playbook).node("protected-action");
        store.startNode(id, 0, node, 1, Map.of());
        store.finishNode(id, node.id(), "failed", Map.of(), "remote system rejected request");

        engine.process(claimed, "worker-crash");

        assertEquals("failed", store.find(id).status());
        assertEquals(1, store.findStep(id, node.id()).attempt());
        assertTrue(store.listEvents(id).stream()
                .anyMatch(event -> "node.failure_route_recovered".equals(event.eventType())));
    }

    @Test
    void longActionRenewsLeaseWhileWaiting() throws Exception {
        SoarPlaybook playbook = yaml.readValue("""
                formatVersion: "2"
                id: heartbeat-runtime-test
                name: Heartbeat runtime test
                version: "2.0"
                resourceTypes: [alert]
                entrypoint: slow-action
                nodes:
                  - id: slow-action
                    name: slow action
                    type: action
                    action: context.set
                    timeoutSeconds: 3
                    with:
                      values: {slow: true}
                    transitions:
                      - target: completed
                  - id: completed
                    name: completed
                    type: end
                    result: succeeded
                """, SoarPlaybook.class);
        SoarPlaybookRegistry.validate(playbook, null);
        SoarExecutionStore observedStore = mock(SoarExecutionStore.class, delegatesTo(store));
        SoarActionExecutor actions = mock(SoarActionExecutor.class);
        when(actions.execute(any(), any(), any(), any())).thenAnswer(invocation -> {
            Thread.sleep(1200);
            return Map.of("values", Map.of("slow", true));
        });
        SoarEngine localEngine = new SoarEngine(observedStore, actions,
                mock(ControlPlaneStore.class), Duration.ofMillis(300), 50, 2);
        try {
            String id = "soar-heartbeat-" + UUID.randomUUID();
            Instant now = Instant.now();
            assertTrue(observedStore.create(execution(id, playbook, now)));
            SoarExecution claimed = observedStore.claimNext("worker-heartbeat",
                    now.plusSeconds(1), now.plusMillis(300));
            localEngine.process(claimed, "worker-heartbeat");

            assertEquals("succeeded", observedStore.find(id).status());
            verify(observedStore, atLeast(2)).heartbeat(any(), any(), any());
        } finally {
            localEngine.close();
        }
    }

    private static SoarExecution execution(String id, SoarPlaybook playbook, Instant now) {
        return new SoarExecution(id, playbook.id(), playbook.version(),
                "alert", "alert-test", "queued", "admin", 0, playbook,
                Map.of("executionId", id, "playbookId", playbook.id(), "alertId", "alert-test",
                        "resource", Map.of(), "nodes", Map.of(), "variables", Map.of()),
                null, null, null, null, now, now, null, 0, List.of());
    }
}
