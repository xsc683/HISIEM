package com.xscsiem.hsiem_platform.lifecycle;

import java.util.Map;

/**
 * Stable boundary for publishing alert and case lifecycle events.
 *
 * <p>Security operations depend on this contract rather than on the SOAR
 * Kafka implementation, keeping the module dependency graph acyclic.</p>
 */
public interface LifecycleEventPort {

    void publishAlert(String eventType, Map<String, Object> source, String tenantId);

    void publishCase(String eventType, Map<String, Object> source, String tenantId);
}
