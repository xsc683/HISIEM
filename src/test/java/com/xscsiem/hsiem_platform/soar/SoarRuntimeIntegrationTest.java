package com.xscsiem.hsiem_platform.soar;

import com.xscsiem.hsiem_platform.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Transactional
@Import(SoarRuntimeIntegrationTest.RetryHandlerConfiguration.class)
class SoarRuntimeIntegrationTest {

    @Autowired private SoarService service;
    @Autowired private SoarStore store;
    @Autowired private SoarLifecycleRuntime runtime;
    @Autowired private SoarExecutionEngine engine;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private FlakyNodeHandler flaky;

    @Test
    void lifecycleMessageCreatesOneExecutionAndHumanApprovalResumesCorrectBranch() {
        SoarPlaybook created = service.createPlaybook("高危告警人工复核", "contract test",
                "alert", List.of("alert.created"), "admin");
        PlaybookGraph graph = new PlaybookGraph(List.of(
                node("start", "开始", "start", Map.of()),
                node("condition", "是否严重", "condition", Map.of("mode", "AND", "conditions", List.of(
                        Map.of("field", "alert.severity", "operator", "eq", "value", "critical")))),
                node("human", "人工复核", "human",
                        Map.of("prompt", "请复核 ${alert.id} / ${trigger.messageId} / ${execution.id}")),
                node("end", "结束", "end", Map.of())), List.of(
                edge("s-c", "start", "condition", "next"),
                edge("c-h", "condition", "human", "true"),
                edge("c-e", "condition", "end", "false"),
                edge("h-a", "human", "end", "approve"),
                edge("h-r", "human", "end", "reject")));
        SoarPlaybook draft = service.updatePlaybook(created.id(), created.name(), created.description(),
                "alert", created.eventTypes(), graph, created.revision(), "admin");
        SoarPlaybook published = service.publishPlaybook(created.id(), draft.revision(), "admin");
        assertTrue(published.enabled());

        LifecycleEvent event = new LifecycleEvent("message-one", "alert.created", Instant.now(),
                "test", "default", Map.of("id", "alert-1", "severity", "critical"), null);
        assertEquals(1, runtime.accept(event));
        assertEquals(0, runtime.accept(event));

        processNext();
        processNext();
        processNext();
        SoarExecution waiting = store.listExecutions(TenantContext.id(), null, 10).getFirst();
        assertEquals("waiting_human", waiting.status());
        assertEquals("message-one", waiting.triggerEnvelope().messageId());
        SoarApproval approval = store.listApprovals(TenantContext.id(), "pending", 10).getFirst();
        assertTrue(approval.prompt().contains("alert-1"));
        assertTrue(approval.prompt().contains("message-one"));

        service.decideApproval(approval.id(), true, "verified", "analyst");
        processNext();
        SoarExecution completed = store.getExecution(TenantContext.id(), waiting.id());
        assertEquals("success", completed.status());
        assertEquals(4, completed.nodeRuns().size());
        assertEquals(4L, completed.nodeRuns().stream().map(SoarExecution.NodeRun::id).distinct().count());
    }

    @Test
    void waitNodeDoesNotBusyLoopAndResumesAfterDueTime() {
        SoarPlaybook created = service.createPlaybook("延迟处置", "wait test",
                "case", List.of("case.updated"), "admin");
        PlaybookGraph graph = new PlaybookGraph(List.of(
                node("start", "开始", "start", Map.of()),
                node("wait", "等待", "wait", Map.of("amount", 1, "unit", "minutes")),
                node("end", "结束", "end", Map.of())), List.of(
                edge("s-w", "start", "wait", "next"), edge("w-e", "wait", "end", "next")));
        SoarPlaybook draft = service.updatePlaybook(created.id(), created.name(), created.description(),
                "case", created.eventTypes(), graph, created.revision(), "admin");
        service.publishPlaybook(created.id(), draft.revision(), "admin");
        runtime.accept(new LifecycleEvent("wait-message", "case.updated", Instant.now(), "test", "default",
                null, Map.of("id", "case-1", "status", "open")));

        processNext();
        processNext();
        SoarExecution waiting = store.listExecutions(TenantContext.id(), "waiting", 10).getFirst();
        assertTrue(waiting.nextRunAt().isAfter(Instant.now().plusSeconds(50)));
        assertFalse(store.claimDue("early", Duration.ofSeconds(30), 1).stream()
                .anyMatch(item -> item.id().equals(waiting.id())));
    }

    @Test
    void retryCreatesAttemptHistoryAndKeepsOneIdempotencyKeyForTheLogicalVisit() {
        flaky.reset();
        SoarPlaybook created = service.createPlaybook("可恢复节点", "retry test",
                "alert", List.of("alert.updated"), "admin");
        PlaybookGraph graph = new PlaybookGraph(List.of(
                node("start", "开始", "start", Map.of()),
                node("flaky", "短暂失败", "flaky", Map.of()),
                node("end", "结束", "end", Map.of())), List.of(
                edge("s-f", "start", "flaky", "next"), edge("f-e", "flaky", "end", "next")));
        SoarPlaybook draft = service.updatePlaybook(created.id(), created.name(), created.description(),
                "alert", created.eventTypes(), graph, created.revision(), "admin");
        service.publishPlaybook(created.id(), draft.revision(), "admin");
        runtime.accept(new LifecycleEvent("retry-message", "alert.updated", Instant.now(), "test", "default",
                Map.of("id", "alert-retry"), null));

        processNext();
        processNext();
        SoarExecution summary = store.listExecutions(TenantContext.id(), "pending", 10).getFirst();
        SoarExecution scheduled = store.getExecution(TenantContext.id(), summary.id());
        assertEquals(1, scheduled.nodeRuns().stream()
                .filter(run -> "flaky".equals(run.nodeId()) && "retrying".equals(run.status())).count());

        jdbc.update("UPDATE soar_execution SET next_run_at = CURRENT_TIMESTAMP WHERE id = ?", scheduled.id());
        processNext();
        processNext();

        SoarExecution completed = store.getExecution(TenantContext.id(), scheduled.id());
        assertEquals("success", completed.status());
        List<SoarExecution.NodeRun> attempts = completed.nodeRuns().stream()
                .filter(run -> "flaky".equals(run.nodeId())).toList();
        assertEquals(List.of(1, 2), attempts.stream().map(SoarExecution.NodeRun::attempt).toList());
        assertEquals(1, attempts.stream().map(SoarExecution.NodeRun::idempotencyKey).distinct().count());
    }

    @Test
    void expiredWorkerIsFencedAfterAnotherWorkerReclaimsExecution() {
        SoarPlaybook created = service.createPlaybook("租约隔离", "fencing test",
                "alert", List.of("alert.created"), "admin");
        SoarPlaybook published = service.publishPlaybook(created.id(), created.revision(), "admin");
        runtime.accept(new LifecycleEvent("fencing-message", "alert.created", Instant.now(), "test", "default",
                Map.of("id", "alert-fencing"), null));

        SoarExecution stale = store.claimDue("worker-old", Duration.ofSeconds(30), 1).getFirst();
        jdbc.update("UPDATE soar_execution SET lease_expires_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(1)), stale.id());
        SoarExecution current = store.claimDue("worker-new", Duration.ofSeconds(30), 1).getFirst();

        assertTrue(current.fencingToken() > stale.fencingToken());
        assertFalse(store.renewLease(stale, Duration.ofSeconds(30)));
        engine.process(stale);
        assertTrue(store.getExecution(TenantContext.id(), stale.id()).nodeRuns().isEmpty());

        engine.process(current);
        SoarExecution advanced = store.getExecution(TenantContext.id(), current.id());
        assertEquals("pending", advanced.status());
        assertEquals(1, advanced.nodeRuns().size());
        assertEquals("success", advanced.nodeRuns().getFirst().status());
        assertTrue(published.enabled());
    }

    private void processNext() {
        SoarExecution next = store.claimDue("test-worker", Duration.ofSeconds(30), 1).getFirst();
        engine.process(next);
    }

    private PlaybookGraph.Node node(String id, String name, String type, Map<String, Object> config) {
        return new PlaybookGraph.Node(id, name, type, config, 0, 0);
    }

    private PlaybookGraph.Edge edge(String id, String source, String target, String branch) {
        return new PlaybookGraph.Edge(id, source, target, branch);
    }

    @TestConfiguration
    static class RetryHandlerConfiguration {
        @Bean
        FlakyNodeHandler flakyNodeHandler() {
            return new FlakyNodeHandler();
        }
    }

    static class FlakyNodeHandler implements SoarNodeHandler {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public String type() {
            return "flaky";
        }

        @Override
        public SoarNodeResult execute(SoarExecutionContext context, Map<String, Object> resolvedConfig) {
            if (calls.incrementAndGet() == 1) throw new IllegalStateException("temporary dependency failure");
            return SoarNodeResult.advance("next", Map.of("recovered", true));
        }

        @Override
        public int defaultMaxAttempts() {
            return 2;
        }

        void reset() {
            calls.set(0);
        }
    }
}
