package com.xscsiem.hsiem_platform.soar.device;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Input to a connector. The idempotency key is stable across node retries. */
public record ConnectorInvocation(
        String tenantId,
        String executionId,
        String nodeId,
        String action,
        Map<String, Object> parameters,
        String idempotencyKey,
        Duration timeout) {

    public ConnectorInvocation {
        parameters = parameters == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(parameters));
        timeout = timeout == null ? Duration.ofSeconds(10) : timeout;
        if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofMinutes(2)) > 0) {
            throw new IllegalArgumentException("Connector timeout 必须在 1ms 到 120s 之间");
        }
    }
}
