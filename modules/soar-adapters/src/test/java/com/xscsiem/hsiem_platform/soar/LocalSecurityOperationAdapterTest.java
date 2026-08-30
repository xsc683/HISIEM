package com.xscsiem.hsiem_platform.soar;

import com.xscsiem.hsiem_platform.alert.AlertService;
import com.xscsiem.hsiem_platform.investigation.CaseService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LocalSecurityOperationAdapterTest {

    private static final String ACTOR = "soar:execution-1";

    @Test
    void delegatesTypedAlertAndCaseOperationsToLocalServices() {
        AlertService alerts = mock(AlertService.class);
        CaseService cases = mock(CaseService.class);
        LocalSecurityOperationAdapter adapter = new LocalSecurityOperationAdapter(alerts, cases);
        Map<String, Object> result = Map.of("ok", true);

        when(alerts.update("alert-1", "acknowledged", null, ACTOR)).thenReturn(result);
        when(alerts.update("alert-1", null, "true_positive", ACTOR)).thenReturn(result);
        when(cases.createFromAlert("alert-1", "Investigate", ACTOR)).thenReturn(result);
        when(cases.addAlerts("case-1", List.of("alert-1"), ACTOR)).thenReturn(result);
        when(cases.updateStatus("case-1", "resolved", "false_positive", ACTOR)).thenReturn(result);

        assertSame(result, adapter.updateAlertStatus("alert-1", "acknowledged", ACTOR));
        assertSame(result, adapter.updateAlertVerdict("alert-1", "true_positive", ACTOR));
        assertSame(result, adapter.createCaseFromAlert("alert-1", "Investigate", ACTOR));
        assertSame(result, adapter.addAlertsToCase("case-1", List.of("alert-1"), ACTOR));
        assertSame(result, adapter.updateCaseStatus("case-1", "resolved", "false_positive", ACTOR));

        verify(alerts).update("alert-1", "acknowledged", null, ACTOR);
        verify(alerts).update("alert-1", null, "true_positive", ACTOR);
        verify(cases).createFromAlert("alert-1", "Investigate", ACTOR);
        verify(cases).addAlerts("case-1", List.of("alert-1"), ACTOR);
        verify(cases).updateStatus("case-1", "resolved", "false_positive", ACTOR);
    }

    @Test
    void mergesExistingEvidenceAndPreservesItWhenUpdatingOwner() {
        AlertService alerts = mock(AlertService.class);
        CaseService cases = mock(CaseService.class);
        LocalSecurityOperationAdapter adapter = new LocalSecurityOperationAdapter(alerts, cases);
        Map<String, Object> existingItem = new LinkedHashMap<>();
        existingItem.put("type", "ip");
        existingItem.put("value", "192.0.2.1");
        List<Map<String, Object>> existingEvidence = new ArrayList<>(List.of(existingItem));
        Map<String, Object> current = new LinkedHashMap<>();
        current.put("case.owner", "old-owner");
        current.put("evidence", existingEvidence);
        Map<String, Object> result = Map.of("updated", true);
        when(cases.detail("case-1")).thenReturn(current);
        when(cases.updateMetadata(eq("case-1"), eq("new-owner"), anyList(), eq(ACTOR)))
                .thenReturn(result);

        assertSame(result, adapter.updateCaseOwner("case-1", "new-owner", ACTOR));

        var evidence = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(cases).updateMetadata(eq("case-1"), eq("new-owner"), evidence.capture(), eq(ACTOR));
        List<?> copied = evidence.getValue();
        assertEquals(List.of(existingItem), copied);
        assertNotSame(existingEvidence, copied);
        assertNotSame(existingItem, copied.getFirst());
        assertThrows(UnsupportedOperationException.class,
                () -> ((Map) copied.getFirst()).put("value", "changed"));

        existingItem.put("value", "mutated-after-call");
        existingEvidence.add(Map.of("type", "user", "value", "bob"));
        assertEquals("192.0.2.1", ((Map<?, ?>) copied.getFirst()).get("value"));
        assertEquals(1, copied.size());
    }

    @Test
    void appendsSoarEvidenceWithoutSharingInputOrExistingCollections() {
        AlertService alerts = mock(AlertService.class);
        CaseService cases = mock(CaseService.class);
        LocalSecurityOperationAdapter adapter = new LocalSecurityOperationAdapter(alerts, cases);
        Map<String, Object> oldItem = new LinkedHashMap<>();
        oldItem.put("type", "command");
        oldItem.put("value", "whoami");
        List<Map<String, Object>> existingEvidence = new ArrayList<>(List.of(oldItem));
        Map<String, Object> current = new LinkedHashMap<>();
        current.put("case.owner", "alice");
        current.put("evidence", existingEvidence);
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("type", "ip");
        input.put("value", "198.51.100.10");
        Map<String, Object> result = Map.of("updated", true);
        when(cases.detail("case-1")).thenReturn(current);
        when(cases.updateMetadata(eq("case-1"), eq("alice"), anyList(), eq(ACTOR)))
                .thenReturn(result);

        assertSame(result, adapter.addCaseEvidence("case-1", input, ACTOR));

        var evidence = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(cases).updateMetadata(eq("case-1"), eq("alice"), evidence.capture(), eq(ACTOR));
        List<?> merged = evidence.getValue();
        assertEquals(2, merged.size());
        assertEquals("whoami", ((Map<?, ?>) merged.getFirst()).get("value"));
        assertEquals("ip", ((Map<?, ?>) merged.get(1)).get("type"));
        assertEquals("198.51.100.10", ((Map<?, ?>) merged.get(1)).get("value"));
        assertEquals("soar", ((Map<?, ?>) merged.get(1)).get("source"));
        assertNotSame(input, merged.get(1));
        assertThrows(UnsupportedOperationException.class,
                () -> ((Map) merged.get(1)).put("value", "changed"));

        input.put("value", "mutated-after-call");
        oldItem.put("value", "old-mutated-after-call");
        existingEvidence.clear();
        assertEquals("198.51.100.10", ((Map<?, ?>) merged.get(1)).get("value"));
        assertEquals("whoami", ((Map<?, ?>) merged.getFirst()).get("value"));
        assertEquals(2, merged.size());
    }
}
