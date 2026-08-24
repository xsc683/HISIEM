package com.xscsiem.hsiem_platform.soar.device;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SoarConnectorTest {

    @Test
    void registryRejectsDuplicateRuntimeKeysAndExposesCapabilities() {
        SoarConnector connector = new StubConnector("edr", Set.of("isolate"));
        SoarConnectorRegistry registry = new SoarConnectorRegistry(List.of(connector));
        assertEquals(Set.of("edr"), registry.runtimeKeys());
        assertEquals(Set.of("isolate"), registry.require("edr").capabilities());
        assertThrows(IllegalArgumentException.class, () -> registry.require("missing"));
        assertThrows(IllegalStateException.class, () -> new SoarConnectorRegistry(List.of(
                connector, new StubConnector("edr", Set.of()))));
    }

    @Test
    void sanitizerRedactsNestedSecretsWithoutChangingSafeValues() {
        Object value = ConnectorAuditSanitizer.sanitize(Map.of(
                "token", "secret-value",
                "nested", List.of(Map.of("password", "p", "status", "ok"))));
        assertEquals(Map.of("token", "[REDACTED]",
                "nested", List.of(Map.of("password", "[REDACTED]", "status", "ok"))), value);
    }

    @Test
    void genericHttpConnectorRejectsPrivateDestinationsBeforeSending() {
        ObjectMapper objectMapper = new ObjectMapper();
        GenericHttpConnector connector = new GenericHttpConnector(
                new GenericHttpRequestAdapter(objectMapper), new GenericHttpResponseInterpreter(objectMapper),
                new HttpConnectorTransport());
        ConnectorInvocation invocation = new ConnectorInvocation("default", "exec-1", "node-1", "GET",
                Map.of("url", "http://127.0.0.1:8080/private"), "key-1", Duration.ofSeconds(1));
        assertThrows(IllegalArgumentException.class, () -> connector.execute(invocation));
    }

    @Test
    void genericHttpAdapterAlwaysForwardsStableIdempotencyKey() {
        GenericHttpRequestAdapter adapter = new GenericHttpRequestAdapter(new ObjectMapper());
        ConnectorInvocation invocation = new ConnectorInvocation("default", "exec-1", "node-1", "POST",
                Map.of("url", "https://example.invalid/action", "body", Map.of("id", "a-1")),
                "soar:exec-1:node-1:1", Duration.ofSeconds(1));
        HttpConnectorRequest request = adapter.adapt(invocation);
        assertEquals("soar:exec-1:node-1:1", request.headers().get("Idempotency-Key"));
    }

    private record StubConnector(String runtimeKey, Set<String> capabilities) implements SoarConnector {
        @Override
        public ConnectorResult execute(ConnectorInvocation invocation) {
            return ConnectorResult.success(Map.of("ok", true));
        }
    }
}
