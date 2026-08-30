package com.xscsiem.hsiem_platform.soar;

import com.xscsiem.hsiem_platform.alert.AlertService;
import com.xscsiem.hsiem_platform.investigation.CaseService;
import com.xscsiem.hsiem_platform.soar.port.SecurityOperationPort;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * In-process implementation of the SOAR security-operation port.
 *
 * <p>This adapter keeps service-specific case detail/evidence merging outside
 * {@code soar-core}. A future HTTP implementation can replace it without
 * changing the action executor.
 */
@Component
public class LocalSecurityOperationAdapter implements SecurityOperationPort {

    private final AlertService alerts;
    private final CaseService cases;

    public LocalSecurityOperationAdapter(AlertService alerts, CaseService cases) {
        this.alerts = alerts;
        this.cases = cases;
    }

    @Override
    public Map<String, Object> updateAlertStatus(String alertId, String status, String actor) {
        return alerts.update(alertId, status, null, actor);
    }

    @Override
    public Map<String, Object> updateAlertVerdict(String alertId, String verdict, String actor) {
        return alerts.update(alertId, null, verdict, actor);
    }

    @Override
    public Map<String, Object> createCaseFromAlert(String alertId, String title, String actor) {
        return cases.createFromAlert(alertId, title, actor);
    }

    @Override
    public Map<String, Object> addAlertsToCase(String caseId, List<String> alertIds, String actor) {
        return cases.addAlerts(caseId, List.copyOf(alertIds), actor);
    }

    @Override
    public Map<String, Object> updateCaseStatus(String caseId, String status, String verdict, String actor) {
        return cases.updateStatus(caseId, status, verdict, actor);
    }

    @Override
    public Map<String, Object> updateCaseOwner(String caseId, String owner, String actor) {
        Map<String, Object> current = cases.detail(caseId);
        Object currentEvidence = current == null ? null : current.get("evidence");
        return cases.updateMetadata(caseId, owner, copyEvidence(currentEvidence), actor);
    }

    @Override
    public Map<String, Object> addCaseEvidence(String caseId, Map<String, Object> evidence, String actor) {
        Map<String, Object> current = cases.detail(caseId);
        Object currentEvidence = current == null ? null : current.get("evidence");
        List<Map<String, Object>> merged = new ArrayList<>(copyEvidence(currentEvidence));

        Map<String, Object> item = new LinkedHashMap<>(copyMap(evidence == null ? Map.of() : evidence));
        item.put("source", "soar");
        merged.add(Collections.unmodifiableMap(item));

        String owner = string(current == null ? null : current.get("case.owner"));
        return cases.updateMetadata(caseId, owner, Collections.unmodifiableList(merged), actor);
    }

    private static List<Map<String, Object>> copyEvidence(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                result.add(Collections.unmodifiableMap(copyMap(map)));
            }
        }
        return Collections.unmodifiableList(result);
    }

    private static Map<String, Object> copyMap(Map<?, ?> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(String.valueOf(key), copyValue(value)));
        return copy;
    }

    private static Object copyValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return Collections.unmodifiableMap(copyMap(map));
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>();
            list.forEach(item -> copy.add(copyValue(item)));
            return Collections.unmodifiableList(copy);
        }
        if (value instanceof Set<?> set) {
            Set<Object> copy = new LinkedHashSet<>();
            set.forEach(item -> copy.add(copyValue(item)));
            return Collections.unmodifiableSet(copy);
        }
        return value;
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
