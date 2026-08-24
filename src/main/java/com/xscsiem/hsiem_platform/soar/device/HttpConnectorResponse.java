package com.xscsiem.hsiem_platform.soar.device;

import java.util.List;
import java.util.Map;

public record HttpConnectorResponse(int status, Map<String, List<String>> headers, String body) {
    public HttpConnectorResponse {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }
}
