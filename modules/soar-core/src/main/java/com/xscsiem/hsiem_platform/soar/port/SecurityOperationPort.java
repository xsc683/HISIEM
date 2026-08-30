package com.xscsiem.hsiem_platform.soar.port;

import java.util.List;
import java.util.Map;

/**
 * Consumer-owned operations that SOAR needs from the security-operation context.
 *
 * <p>The contract deliberately contains no alert/case service types. Implementations
 * may be local service adapters today or transport adapters in another process later.
 */
public interface SecurityOperationPort {

    Map<String, Object> updateAlertStatus(String alertId, String status, String actor);

    Map<String, Object> updateAlertVerdict(String alertId, String verdict, String actor);

    Map<String, Object> createCaseFromAlert(String alertId, String title, String actor);

    Map<String, Object> addAlertsToCase(String caseId, List<String> alertIds, String actor);

    /**
     * Updates a case status. {@code verdict} is optional except where the target
     * status requires one (for example, resolving a case).
     */
    Map<String, Object> updateCaseStatus(String caseId, String status, String verdict, String actor);

    Map<String, Object> updateCaseOwner(String caseId, String owner, String actor);

    /**
     * Appends one evidence item. Implementations must treat the supplied map as
     * caller-owned data and copy it before retaining or merging it.
     */
    Map<String, Object> addCaseEvidence(String caseId, Map<String, Object> evidence, String actor);
}
