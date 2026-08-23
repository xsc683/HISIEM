package com.xscsiem.hsiem_platform.soar;

import com.xscsiem.hsiem_platform.alert.AlertService;
import com.xscsiem.hsiem_platform.control.ControlPlaneStore;
import com.xscsiem.hsiem_platform.investigation.CaseService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SoarServiceTest {

    @Test
    void startQueuesImmutableSnapshotWithoutExecutingInRequestThread() {
        SoarPlaybook playbook = new SoarPlaybook("alert-high-risk-test", "高危告警", "test", "1.0", true,
                List.of("alert"), null, List.of(
                step("acknowledge-alert", "确认", "alert.set_status", Map.of("status", "acknowledged")),
                step("approve-investigation", "审批", "approval", Map.of("requiredRole", "admin"))));
        SoarPlaybookRegistry registry = mock(SoarPlaybookRegistry.class);
        when(registry.get(playbook.id())).thenReturn(playbook);
        AlertService alerts = mock(AlertService.class);
        when(alerts.detail("alert-1")).thenReturn(Map.of("alert.risk_score", 90));
        CaseService cases = mock(CaseService.class);
        ControlPlaneStore control = mock(ControlPlaneStore.class);
        SoarExecutionStore store = mock(SoarExecutionStore.class);
        AtomicReference<SoarExecution> saved = new AtomicReference<>();
        when(store.create(any())).thenAnswer(invocation -> {
            saved.set(invocation.getArgument(0));
            return true;
        });
        when(store.find(any())).thenAnswer(invocation -> saved.get());
        when(store.listSteps(any())).thenReturn(List.of());
        SoarService service = new SoarService(registry, store, alerts, cases, control);

        SoarExecution queued = service.start(playbook.id(), "alert", "alert-1", "alice");

        assertEquals("queued", queued.status());
        assertEquals("acknowledge-alert", queued.frontier().getFirst());
        assertEquals(playbook, queued.playbookSnapshot());
        verify(alerts, never()).update(any(), any(), any(), any());
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
        assertFalse(SoarService.matches(
                new SoarPlaybook.Condition("resource.missing", "lt", 10), context));
    }

    private static SoarPlaybook.Step step(String id, String name, String action,
                                           Map<String, Object> parameters) {
        return new SoarPlaybook.Step(id, name, action, parameters, null);
    }
}
