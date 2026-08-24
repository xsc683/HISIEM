package com.xscsiem.hsiem_platform.soar.device;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record ConnectorResult(boolean success, Map<String, Object> output, String externalRequestId) {

    public ConnectorResult {
        output = output == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(output));
    }

    public static ConnectorResult success(Map<String, Object> output) {
        return new ConnectorResult(true, output, null);
    }
}
