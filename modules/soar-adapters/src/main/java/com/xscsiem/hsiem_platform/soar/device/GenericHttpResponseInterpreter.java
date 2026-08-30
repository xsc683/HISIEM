package com.xscsiem.hsiem_platform.soar.device;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class GenericHttpResponseInterpreter implements ConnectorResponseInterpreter {

    private static final TypeReference<Object> JSON_VALUE = new TypeReference<>() { };
    private final ObjectMapper objectMapper;

    public GenericHttpResponseInterpreter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public ConnectorResult interpret(HttpConnectorResponse response) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("status", response.status());
        output.put("body", parse(response.body()));
        output.put("success", response.status() < 400);
        String requestId = response.headers().entrySet().stream()
                .filter(entry -> "x-request-id".equalsIgnoreCase(entry.getKey()))
                .flatMap(entry -> entry.getValue().stream()).findFirst().orElse(null);
        return new ConnectorResult(response.status() < 400, output, requestId);
    }

    private Object parse(String value) {
        if (value == null || value.isBlank()) return "";
        try {
            return objectMapper.readValue(value, JSON_VALUE);
        } catch (JsonProcessingException ignored) {
            return value;
        }
    }
}
