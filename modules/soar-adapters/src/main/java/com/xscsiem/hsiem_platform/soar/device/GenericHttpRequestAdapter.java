package com.xscsiem.hsiem_platform.soar.device;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class GenericHttpRequestAdapter implements ConnectorRequestAdapter {

    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");
    private final ObjectMapper objectMapper;

    public GenericHttpRequestAdapter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String runtimeKey() {
        return "http";
    }

    @Override
    public Set<String> capabilities() {
        return METHODS;
    }

    @Override
    public HttpConnectorRequest adapt(ConnectorInvocation invocation) {
        String url = text(invocation.parameters().get("url"));
        String method = text(invocation.action()).toUpperCase(Locale.ROOT);
        if (url.isBlank()) throw new IllegalArgumentException("HTTP Connector 缺少 url");
        if (!METHODS.contains(method)) throw new IllegalArgumentException("HTTP method 不受支持: " + method);
        Map<String, String> headers = new LinkedHashMap<>();
        if (invocation.parameters().get("headers") instanceof Map<?, ?> values) {
            values.forEach((key, value) -> headers.put(String.valueOf(key), String.valueOf(value)));
        }
        if (headers.keySet().stream().noneMatch(name -> "Idempotency-Key".equalsIgnoreCase(name))) {
            headers.put("Idempotency-Key", invocation.idempotencyKey());
        }
        return new HttpConnectorRequest(URI.create(url), method, headers,
                body(invocation.parameters().get("body")), invocation.timeout());
    }

    private String body(Object value) {
        if (value == null) return null;
        if (value instanceof String text) return text;
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("HTTP body 无法序列化", e);
        }
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
