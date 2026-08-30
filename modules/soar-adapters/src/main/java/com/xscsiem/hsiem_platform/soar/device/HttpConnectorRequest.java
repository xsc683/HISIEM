package com.xscsiem.hsiem_platform.soar.device;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

public record HttpConnectorRequest(URI uri, String method, Map<String, String> headers,
                                   String body, Duration timeout) {
    public HttpConnectorRequest {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }
}
