package com.xscsiem.hsiem_platform.soar;

import com.xscsiem.hsiem_platform.onboarding.ConflictException;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    @Test
    void parallelBranchesAreDurableChildrenAndJoinReleasesParentOnce() {
        SoarPlaybook created = service.createPlaybook("持久并行", "fan-out/join test",
                "alert", List.of("alert.created"), "admin");
        PlaybookGraph graph = new PlaybookGraph(List.of(
                node("start", "开始", "start", Map.of()),
                node("parallel", "并行", "parallel", Map.of("branches", List.of("left", "right"),
                        "joinNode", "join")),
                node("left", "左分支", "wait", Map.of("amount", 1, "unit", "minutes")),
                node("right", "右分支", "wait", Map.of("amount", 1, "unit", "minutes")),
                node("join", "汇合", "join", Map.of()),
                node("end", "结束", "end", Map.of())), List.of(
                edge("s-p", "start", "parallel", "next"),
                edge("p-l", "parallel", "left", "left"), edge("p-r", "parallel", "right", "right"),
                edge("l-j", "left", "join", "next"), edge("r-j", "right", "join", "next"),
                edge("j-e", "join", "end", "next")));
        SoarPlaybook draft = service.updatePlaybook(created.id(), created.name(), created.description(),
                "alert", created.eventTypes(), graph, created.revision(), "admin");
        service.publishPlaybook(created.id(), draft.revision(), "admin");
        runtime.accept(new LifecycleEvent("parallel-message", "alert.created", Instant.now(), "test", "default",
                Map.of("id", "alert-parallel"), null));

        processNext();
        processNext();
        SoarExecution parent = store.listExecutions(TenantContext.id(), "waiting", 20).stream()
                .filter(item -> item.triggerMessageId().equals("parallel-message")).findFirst().orElseThrow();
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM soar_parallel_branch WHERE group_id IN "
                + "(SELECT id FROM soar_parallel_group WHERE parent_execution_id = ?)", Integer.class, parent.id()));
        jdbc.update("UPDATE soar_execution SET next_run_at = CURRENT_TIMESTAMP WHERE parallel_parent_id = ?", parent.id());
        // Each branch waits once, resumes once, then arrives at the join.
        for (int i = 0; i < 12; i++) {
            List<SoarExecution> due = store.claimDue("parallel-test", Duration.ofSeconds(30), 1);
            if (due.isEmpty()) break;
            engine.process(due.getFirst());
            jdbc.update("UPDATE soar_execution SET next_run_at = CURRENT_TIMESTAMP WHERE parallel_parent_id = ?",
                    parent.id());
        }
        jdbc.update("UPDATE soar_execution SET next_run_at = CURRENT_TIMESTAMP WHERE id = ?", parent.id());
        List<SoarExecution> parentDue = store.claimDue("parallel-test-final", Duration.ofSeconds(30), 1);
        if (!parentDue.isEmpty()) engine.process(parentDue.getFirst());
        SoarExecution completed = store.getExecution(TenantContext.id(), parent.id());
        assertEquals("success", completed.status());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM soar_parallel_group WHERE parent_execution_id = ? "
                + "AND status = 'released'", Integer.class, parent.id()));
    }

    @Test
    void manualTriggerIsIdempotentWhenTheClientReusesRequestId() {
        SoarPlaybook created = service.createPlaybook("手动触发", "manual test",
                "alert", List.of("alert.created"), "admin");
        SoarPlaybook published = service.publishPlaybook(created.id(), created.revision(), "admin");
        SoarExecution first = service.triggerExecution(published.id(), "manual-request-1", "alert", "alert-manual",
                "alert.created", Map.of("severity", "high"), "analyst");
        SoarExecution second = service.triggerExecution(published.id(), "manual-request-1", "alert", "alert-manual",
                "alert.created", Map.of("severity", "high"), "analyst");
        assertEquals(first.id(), second.id());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM soar_execution WHERE playbook_id = ? "
                + "AND trigger_message_id = ?", Integer.class, published.id(), "manual-request-1"));
        assertEquals("manual:analyst", first.triggerEnvelope().producer());
        assertEquals("MANUAL", first.triggerType());
        assertEquals(Map.of("alert", Map.of("id", "alert-manual", "severity", "high")),
                first.payloadSnapshot());
    }

    @Test
    void cancellingParallelParentPropagatesToAllDurableChildren() {
        SoarPlaybook created = service.createPlaybook("取消并行", "cancel propagation",
                "alert", List.of("alert.created"), "admin");
        PlaybookGraph graph = new PlaybookGraph(List.of(
                node("start", "开始", "start", Map.of()),
                node("parallel", "并行", "parallel", Map.of("branches", List.of("a", "b"), "joinNode", "join")),
                node("a", "A", "wait", Map.of("amount", 1, "unit", "minutes")),
                node("b", "B", "wait", Map.of("amount", 1, "unit", "minutes")),
                node("join", "汇合", "join", Map.of()), node("end", "结束", "end", Map.of())), List.of(
                edge("s-p", "start", "parallel", "next"), edge("p-a", "parallel", "a", "a"),
                edge("p-b", "parallel", "b", "b"), edge("a-j", "a", "join", "next"),
                edge("b-j", "b", "join", "next"), edge("j-e", "join", "end", "next")));
        SoarPlaybook draft = service.updatePlaybook(created.id(), created.name(), created.description(),
                "alert", created.eventTypes(), graph, created.revision(), "admin");
        service.publishPlaybook(created.id(), draft.revision(), "admin");
        runtime.accept(new LifecycleEvent("cancel-parallel", "alert.created", Instant.now(), "test", "default",
                Map.of("id", "alert-cancel"), null));
        processNext();
        processNext();
        SoarExecution parent = store.listExecutions(TenantContext.id(), "waiting", 20).stream()
                .filter(item -> item.triggerMessageId().equals("cancel-parallel")).findFirst().orElseThrow();
        String internalChild = jdbc.queryForObject("SELECT id FROM soar_execution WHERE parallel_parent_id = ? "
                + "ORDER BY id FETCH FIRST 1 ROWS ONLY", String.class, parent.id());
        assertThrows(ConflictException.class,
                () -> service.cancelExecution(internalChild, "analyst"));
        service.cancelExecution(parent.id(), "analyst");
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM soar_execution WHERE parallel_parent_id = ? "
                + "AND status = 'cancelled'", Integer.class, parent.id()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM soar_parallel_group WHERE parent_execution_id = ? "
                + "AND status = 'cancelled'", Integer.class, parent.id()));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM soar_parallel_branch WHERE group_id IN "
                + "(SELECT id FROM soar_parallel_group WHERE parent_execution_id = ?) AND status = 'cancelled'",
                Integer.class, parent.id()));
    }

    @Test
    void loopUsesOneDurableChildAndStopsAtConfiguredBound() {
        SoarPlaybook created = service.createPlaybook("有界循环", "loop test",
                "alert", List.of("alert.created"), "admin");
        PlaybookGraph graph = new PlaybookGraph(List.of(
                node("start", "开始", "start", Map.of()),
                node("loop", "循环", "loop", Map.of("bodyStart", "body", "bodyEnd", "loop-end",
                        "items", List.of("one", "two", "three"), "maxIterations", 3)),
                node("body", "循环体", "wait", Map.of("amount", 1, "unit", "minutes")),
                node("loop-end", "循环结束", "loop_end", Map.of()),
                node("after", "循环后", "wait", Map.of("amount", 1, "unit", "minutes")),
                node("end", "结束", "end", Map.of())), List.of(
                edge("s-l", "start", "loop", "next"), edge("l-b", "loop", "body", "next"),
                edge("b-e", "body", "loop-end", "next"), edge("e-a", "loop-end", "after", "next"),
                edge("a-end", "after", "end", "next")));
        SoarPlaybook draft = service.updatePlaybook(created.id(), created.name(), created.description(),
                "alert", created.eventTypes(), graph, created.revision(), "admin");
        service.publishPlaybook(created.id(), draft.revision(), "admin");
        runtime.accept(new LifecycleEvent("loop-message", "alert.created", Instant.now(), "test", "default",
                Map.of("id", "alert-loop"), null));
        processNext();
        processNext();
        SoarExecution parent = store.listExecutions(TenantContext.id(), "waiting", 20).stream()
                .filter(item -> item.triggerMessageId().equals("loop-message")).findFirst().orElseThrow();
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM soar_loop_state WHERE parent_execution_id = ?",
                Integer.class, parent.id()));
        for (int i = 0; i < 20; i++) {
            jdbc.update("UPDATE soar_execution SET next_run_at = CURRENT_TIMESTAMP WHERE id IN "
                    + "(SELECT child_execution_id FROM soar_loop_state WHERE parent_execution_id = ?)", parent.id());
            List<SoarExecution> due = store.claimDue("loop-test", Duration.ofSeconds(30), 1);
            if (due.isEmpty()) break;
            engine.process(due.getFirst());
            jdbc.update("UPDATE soar_execution SET next_run_at = CURRENT_TIMESTAMP WHERE id = ? "
                    + "AND current_node_id IS NOT NULL", parent.id());
            SoarExecution current = store.getExecution(TenantContext.id(), parent.id());
            if ("success".equals(current.status())) break;
        }
        SoarExecution completed = store.getExecution(TenantContext.id(), parent.id());
        assertEquals("success", completed.status());
        assertEquals(3, jdbc.queryForObject("SELECT iteration_index FROM soar_loop_state WHERE parent_execution_id = ?",
                Integer.class, parent.id()));
    }

    @Test
    void failedParallelBranchFailsParentAndCancelsSiblingInsteadOfWaitingForever() {
        SoarPlaybook created = service.createPlaybook("并行失败传播", "failure propagation",
                "alert", List.of("alert.created"), "admin");
        PlaybookGraph graph = new PlaybookGraph(List.of(
                node("start", "开始", "start", Map.of()),
                node("parallel", "并行", "parallel", Map.of("branches", List.of("bad", "slow"),
                        "joinNode", "join")),
                node("bad", "失败分支", "always_fail", Map.of()),
                node("slow", "慢分支", "wait", Map.of("amount", 1, "unit", "minutes")),
                node("join", "汇合", "join", Map.of()), node("end", "结束", "end", Map.of())), List.of(
                edge("s-p", "start", "parallel", "next"), edge("p-b", "parallel", "bad", "bad"),
                edge("p-s", "parallel", "slow", "slow"), edge("b-j", "bad", "join", "next"),
                edge("s-j", "slow", "join", "next"), edge("j-e", "join", "end", "next")));
        SoarPlaybook draft = service.updatePlaybook(created.id(), created.name(), created.description(),
                "alert", created.eventTypes(), graph, created.revision(), "admin");
        service.publishPlaybook(created.id(), draft.revision(), "admin");
        runtime.accept(new LifecycleEvent("parallel-failure", "alert.created", Instant.now(), "test", "default",
                Map.of("id", "alert-parallel-failure"), null));
        processNext();
        processNext();
        SoarExecution parent = store.listExecutions(TenantContext.id(), "waiting", 20).stream()
                .filter(item -> item.triggerMessageId().equals("parallel-failure")).findFirst().orElseThrow();
        List<SoarExecution> branches = store.claimDue("parallel-failure-test", Duration.ofSeconds(30), 10);
        SoarExecution failedBranch = branches.stream()
                .filter(item -> "bad".equals(item.currentNodeId())).findFirst().orElseThrow();
        engine.process(failedBranch);

        SoarExecution failedParent = store.getExecution(TenantContext.id(), parent.id());
        assertEquals("failed", failedParent.status());
        assertTrue(failedParent.error().contains("并行分支 bad 失败"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM soar_parallel_group "
                + "WHERE parent_execution_id = ? AND status = 'cancelled'", Integer.class, parent.id()));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM soar_parallel_branch WHERE group_id IN "
                + "(SELECT id FROM soar_parallel_group WHERE parent_execution_id = ?) AND status = 'cancelled'",
                Integer.class, parent.id()));
    }

    @Test
    void failedLoopBodyFailsParentInsteadOfLeavingLoopWaiting() {
        SoarPlaybook created = service.createPlaybook("循环失败传播", "loop failure propagation",
                "alert", List.of("alert.created"), "admin");
        PlaybookGraph graph = new PlaybookGraph(List.of(
                node("start", "开始", "start", Map.of()),
                node("loop", "循环", "loop", Map.of("bodyStart", "body", "bodyEnd", "loop-end",
                        "items", List.of("one", "two"), "maxIterations", 2)),
                node("body", "失败循环体", "always_fail", Map.of()),
                node("loop-end", "循环结束", "loop_end", Map.of()),
                node("end", "结束", "end", Map.of())), List.of(
                edge("s-l", "start", "loop", "next"), edge("l-b", "loop", "body", "next"),
                edge("b-e", "body", "loop-end", "next"), edge("e-end", "loop-end", "end", "next")));
        SoarPlaybook draft = service.updatePlaybook(created.id(), created.name(), created.description(),
                "alert", created.eventTypes(), graph, created.revision(), "admin");
        service.publishPlaybook(created.id(), draft.revision(), "admin");
        runtime.accept(new LifecycleEvent("loop-failure", "alert.created", Instant.now(), "test", "default",
                Map.of("id", "alert-loop-failure"), null));
        processNext();
        processNext();
        SoarExecution parent = store.listExecutions(TenantContext.id(), "waiting", 20).stream()
                .filter(item -> item.triggerMessageId().equals("loop-failure")).findFirst().orElseThrow();
        processNext();

        SoarExecution failedParent = store.getExecution(TenantContext.id(), parent.id());
        assertEquals("failed", failedParent.status());
        assertTrue(failedParent.error().contains("循环体失败"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM soar_loop_state "
                + "WHERE parent_execution_id = ? AND status = 'failed'", Integer.class, parent.id()));
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

        @Bean
        AlwaysFailNodeHandler alwaysFailNodeHandler() {
            return new AlwaysFailNodeHandler();
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

    static class AlwaysFailNodeHandler implements SoarNodeHandler {
        @Override
        public String type() {
            return "always_fail";
        }

        @Override
        public SoarNodeResult execute(SoarExecutionContext context, Map<String, Object> resolvedConfig) {
            throw new IllegalStateException("permanent branch failure");
        }
    }
}
