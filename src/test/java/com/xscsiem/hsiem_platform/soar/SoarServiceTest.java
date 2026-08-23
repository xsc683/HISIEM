package com.xscsiem.hsiem_platform.soar;

import com.xscsiem.hsiem_platform.alert.AlertService;
import com.xscsiem.hsiem_platform.control.ControlPlaneStore;
import com.xscsiem.hsiem_platform.investigation.CaseService;
import com.xscsiem.hsiem_platform.notify.NotificationService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SoarServiceTest {

    @Test
    void startPausesForApprovalAndContinuesFromSnapshot() {
        SoarPlaybook playbook = new SoarPlaybook("alert-high-risk-test", "高危告警", "test", "1.0", true,
                List.of("alert"), null, List.of(
                step("acknowledge-alert", "确认", "alert.set_status", Map.of("status", "acknowledged")),
                step("approve-investigation", "审批", "approval", Map.of("requiredRole", "admin", "message", "请审批 ${alertId}")),
                step("start-investigation", "调查", "alert.set_status", Map.of("status", "investigating"))));
        SoarPlaybookRegistry registry = mock(SoarPlaybookRegistry.class);
        when(registry.get(playbook.id())).thenReturn(playbook);
        AlertService alerts = mock(AlertService.class);
        when(alerts.detail("alert-1")).thenReturn(Map.of("alert.risk_score", 90));
        when(alerts.update(eq("alert-1"), any(), any(), eq("alice")))
                .thenAnswer(invocation -> Map.of("alert.status", invocation.getArgument(1) == null ? "acknowledged" : invocation.getArgument(1)));
        CaseService cases = mock(CaseService.class);
        NotificationService notifications = mock(NotificationService.class);
        ControlPlaneStore control = mock(ControlPlaneStore.class);
        FakeStore store = new FakeStore();
        SoarService service = new SoarService(registry, store, alerts, cases, notifications, control);

        SoarExecution waiting = service.start(playbook.id(), "alert", "alert-1", "alice");
        assertEquals("waiting_approval", waiting.status());
        assertEquals("approve-investigation", waiting.approvalStepId());
        assertEquals(List.of("succeeded", "waiting_approval"), waiting.steps().stream().map(SoarStepExecution::status).toList());

        SoarExecution completed = service.approve(waiting.id(), true, "admin", "admin");
        assertEquals("succeeded", completed.status());
        assertEquals(3, completed.steps().size());
        assertTrue(completed.steps().stream().allMatch(step -> "succeeded".equals(step.status())));
        verify(alerts).update("alert-1", "acknowledged", null, "alice");
        verify(alerts).update("alert-1", "investigating", null, "alice");
    }

    @Test
    void conditionAndInterpolationUnderstandDottedEcsKeys() {
        Map<String, Object> context = Map.of(
                "alertId", "a-1",
                "resource", Map.of("alert.risk_score", 73, "alert.rule_name", "SSH 暴力破解"));
        SoarPlaybook.Condition condition = new SoarPlaybook.Condition("resource.alert.risk_score", "gte", 70);

        assertTrue(SoarService.matches(condition, context));
        assertEquals("告警 SSH 暴力破解 / a-1", SoarService.resolveMap(
                Map.of("message", "告警 ${resource.alert.rule_name} / ${alertId}"), context).get("message"));
    }

    private static SoarPlaybook.Step step(String id, String name, String action, Map<String, Object> parameters) {
        return new SoarPlaybook.Step(id, name, action, parameters, null);
    }

    private static final class FakeStore implements SoarExecutionStore {
        private final Map<String, SoarExecution> executions = new LinkedHashMap<>();
        private final Map<String, Map<String, SoarStepExecution>> steps = new LinkedHashMap<>();

        @Override
        public void create(SoarExecution execution) {
            executions.put(execution.id(), execution);
            steps.put(execution.id(), new LinkedHashMap<>());
        }

        @Override
        public SoarExecution find(String id) {
            return executions.get(id);
        }

        @Override
        public List<SoarExecution> list(int size) {
            return executions.values().stream().limit(size).toList();
        }

        @Override
        public boolean claimQueued(String id) {
            SoarExecution current = find(id);
            if (current == null || !"queued".equals(current.status())) return false;
            save(current, "running", current.currentStep(), null, null, current.approvedBy(), null, null);
            return true;
        }

        @Override
        public SoarStepExecution findStep(String executionId, String stepId) {
            return steps.get(executionId).get(stepId);
        }

        @Override
        public List<SoarStepExecution> listSteps(String executionId) {
            return new ArrayList<>(steps.get(executionId).values());
        }

        @Override
        public void startStep(String executionId, int index, SoarPlaybook.Step step, Map<String, Object> input) {
            steps.get(executionId).put(step.id(), new SoarStepExecution(executionId, step.id(), index,
                    step.name(), step.action(), "running", input, Map.of(), null, Instant.now(), null));
        }

        @Override
        public void finishStep(String executionId, String stepId, String status, Map<String, Object> output, String error) {
            SoarStepExecution current = findStep(executionId, stepId);
            steps.get(executionId).put(stepId, new SoarStepExecution(executionId, stepId, current.stepIndex(),
                    current.stepName(), current.action(), status, current.input(), output, error,
                    current.startedAt(), Instant.now()));
        }

        @Override
        public void advance(String executionId, int nextStep) {
            SoarExecution current = find(executionId);
            save(current, current.status(), nextStep, current.approvalStepId(), current.approvalMessage(),
                    current.approvedBy(), current.error(), current.finishedAt());
        }

        @Override
        public void waitForApproval(String executionId, int stepIndex, String stepId, String message) {
            finishStep(executionId, stepId, "waiting_approval", Map.of(), null);
            SoarExecution current = find(executionId);
            save(current, "waiting_approval", stepIndex, stepId, message, current.approvedBy(), null, null);
        }

        @Override
        public boolean resolveApproval(String executionId, String stepId, boolean approved, String actor) {
            SoarExecution current = find(executionId);
            if (!"waiting_approval".equals(current.status())) return false;
            finishStep(executionId, stepId, approved ? "succeeded" : "rejected",
                    Map.of("actor", actor), null);
            save(current, approved ? "queued" : "rejected", current.currentStep() + (approved ? 1 : 0),
                    null, null, actor, null, approved ? null : Instant.now());
            return true;
        }

        @Override
        public void finishExecution(String executionId, String status, String error) {
            SoarExecution current = find(executionId);
            save(current, status, current.currentStep(), null, null, current.approvedBy(), error, Instant.now());
        }

        @Override
        public boolean prepareRetry(String executionId) {
            SoarExecution current = find(executionId);
            if (current == null || !"failed".equals(current.status())) return false;
            save(current, "queued", current.currentStep(), null, null, current.approvedBy(), null, null);
            return true;
        }

        @Override
        public int recoverStale(Instant cutoff) {
            return 0;
        }

        private void save(SoarExecution current, String status, int currentStep, String approvalStep,
                          String approvalMessage, String approvedBy, String error, Instant finishedAt) {
            executions.put(current.id(), new SoarExecution(current.id(), current.playbookId(), current.playbookVersion(),
                    current.resourceType(), current.resourceId(), status, current.actor(), currentStep,
                    current.playbookSnapshot(), current.context(), approvalStep, approvalMessage, approvedBy,
                    error, current.createdAt(), Instant.now(), finishedAt, current.version() + 1, List.of()));
        }
    }
}
