package com.xscsiem.hsiem_platform.soar;

import com.xscsiem.hsiem_platform.soar.port.SecurityOperationPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SoarBusinessActionExecutorTest {

    private static final String ACTOR = "soar:execution-1";

    @Test
    void routesEveryBusinessActionThroughTypedPortWithExecutionActor() {
        SecurityOperationPort port = mock(SecurityOperationPort.class);
        SoarBusinessActionExecutor executor = new SoarBusinessActionExecutor(port);
        SoarExecution alert = execution("alert-1", "alert");
        SoarExecution caseExecution = execution("case-1", "case");
        Map<String, Object> result = Map.of("result", "ok");

        when(port.updateAlertStatus("alert-1", "acknowledged", ACTOR)).thenReturn(result);
        when(port.updateAlertVerdict("alert-1", "true_positive", ACTOR)).thenReturn(result);
        when(port.createCaseFromAlert("alert-1", "Investigate", ACTOR)).thenReturn(result);
        when(port.addAlertsToCase("case-2", java.util.List.of("alert-1"), ACTOR)).thenReturn(result);
        when(port.updateCaseStatus("case-1", "investigating", null, ACTOR)).thenReturn(result);
        when(port.updateCaseStatus("case-1", "resolved", "false_positive", ACTOR)).thenReturn(result);
        when(port.addAlertsToCase("case-1", java.util.List.of("alert-2"), ACTOR)).thenReturn(result);
        when(port.updateCaseOwner("case-1", "alice", ACTOR)).thenReturn(result);
        when(port.addCaseEvidence("case-1", Map.of("type", "ip", "value", "192.0.2.1"), ACTOR))
                .thenReturn(result);

        assertSame(result, executor.execute(alert, "alert.update_status", Map.of("status", "acknowledged")));
        assertSame(result, executor.execute(alert, "alert.update_verdict", Map.of("verdict", "true_positive")));
        assertSame(result, executor.execute(alert, "alert.create_case", Map.of("title", "Investigate")));
        assertSame(result, executor.execute(execution("alert-1", "alert"), "alert.add_to_case",
                Map.of("case_id", "case-2")));
        assertSame(result, executor.execute(caseExecution, "case.update_status",
                Map.of("status", "investigating")));
        assertSame(result, executor.execute(caseExecution, "case.close",
                Map.of("verdict", "false_positive")));
        assertSame(result, executor.execute(caseExecution, "case.add_alert",
                Map.of("alert_id", "alert-2")));
        assertSame(result, executor.execute(caseExecution, "case.update_owner",
                Map.of("owner", "alice")));
        assertSame(result, executor.execute(caseExecution, "case.add_evidence",
                Map.of("type", "ip", "value", "192.0.2.1")));

        verify(port).updateAlertStatus("alert-1", "acknowledged", ACTOR);
        verify(port).updateAlertVerdict("alert-1", "true_positive", ACTOR);
        verify(port).createCaseFromAlert("alert-1", "Investigate", ACTOR);
        verify(port).addAlertsToCase("case-2", java.util.List.of("alert-1"), ACTOR);
        verify(port).updateCaseStatus("case-1", "investigating", null, ACTOR);
        verify(port).updateCaseStatus("case-1", "resolved", "false_positive", ACTOR);
        verify(port).addAlertsToCase("case-1", java.util.List.of("alert-2"), ACTOR);
        verify(port).updateCaseOwner("case-1", "alice", ACTOR);
        verify(port).addCaseEvidence("case-1", Map.of("type", "ip", "value", "192.0.2.1"), ACTOR);
    }

    @Test
    void passesAnImmutableEvidenceCopyToThePort() {
        SecurityOperationPort port = mock(SecurityOperationPort.class);
        SoarBusinessActionExecutor executor = new SoarBusinessActionExecutor(port);
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", "ip");
        parameters.put("value", "192.0.2.1");

        executor.execute(execution("case-1", "case"), "case.add_evidence", parameters);

        var evidence = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(port).addCaseEvidence(org.mockito.ArgumentMatchers.eq("case-1"), evidence.capture(),
                org.mockito.ArgumentMatchers.eq(ACTOR));
        assertThrows(UnsupportedOperationException.class,
                () -> evidence.getValue().put("source", "caller"));
        parameters.put("value", "changed");
        assertEquals("192.0.2.1", evidence.getValue().get("value"));
    }

    @ParameterizedTest
    @CsvSource({
            "alert.update_status, status",
            "alert.update_verdict, verdict",
            "alert.create_case, title",
            "alert.add_to_case, case_id",
            "case.update_status, status",
            "case.close, verdict",
            "case.add_alert, alert_id",
            "case.update_owner, owner",
            "case.add_evidence, type",
            "case.add_evidence, value"
    })
    void rejectsMissingRequiredActionParameters(String action, String missingKey) {
        SecurityOperationPort port = mock(SecurityOperationPort.class);
        SoarBusinessActionExecutor executor = new SoarBusinessActionExecutor(port);
        Map<String, Object> parameters = validParameters(action);
        parameters.remove(missingKey);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> executor.execute(execution("case-1", "case"), action, parameters));

        assertTrue(error.getMessage().contains(missingKey));
        verifyNoInteractions(port);
    }

    @Test
    void rejectsUnknownAction() {
        SecurityOperationPort port = mock(SecurityOperationPort.class);
        SoarBusinessActionExecutor executor = new SoarBusinessActionExecutor(port);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> executor.execute(execution("case-1", "case"), "case.unknown", Map.of()));

        assertTrue(error.getMessage().contains("case.unknown"));
        verifyNoInteractions(port);
    }

    private static Map<String, Object> validParameters(String action) {
        Map<String, Object> parameters = new HashMap<>();
        switch (action) {
            case "alert.update_status", "case.update_status" -> parameters.put("status", "investigating");
            case "alert.update_verdict" -> parameters.put("verdict", "true_positive");
            case "alert.create_case" -> parameters.put("title", "Case title");
            case "alert.add_to_case" -> parameters.put("case_id", "case-2");
            case "case.close" -> parameters.put("verdict", "true_positive");
            case "case.add_alert" -> parameters.put("alert_id", "alert-2");
            case "case.update_owner" -> parameters.put("owner", "alice");
            case "case.add_evidence" -> {
                parameters.put("type", "ip");
                parameters.put("value", "192.0.2.1");
            }
            default -> throw new AssertionError("unexpected action: " + action);
        }
        return parameters;
    }

    private static SoarExecution execution(String objectId, String objectType) {
        return new SoarExecution(
                "execution-1", "tenant-1", "playbook-1", "Playbook", 1L, null,
                objectType, objectId, "alert.created", "lifecycle", null, null, Map.of(),
                "running", null, null, null, null, false, null, null, 0L,
                null, null, null, null, java.util.List.of());
    }
}
