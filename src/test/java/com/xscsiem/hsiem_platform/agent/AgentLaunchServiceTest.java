package com.xscsiem.hsiem_platform.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xscsiem.hsiem_platform.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentLaunchServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private final HttpClient client = mock(HttpClient.class);

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void launchSendsBoundedReferenceWithServerSideTenantAndUser() throws Exception {
        HttpResponse<String> response = response(202,
                "{\"run_id\":\"11111111-1111-1111-1111-111111111111\"}");
        when(client.send(any(HttpRequest.class),
                org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenReturn(response);
        TenantContext.set("tenant-a");

        AgentLaunchService service = service("agent-secret");
        AgentLaunchResponse result = service.launch(
                "alert_investigation", "alert", "alert-doc-42", "analyst");

        assertEquals("11111111-1111-1111-1111-111111111111", result.runId());
        assertEquals("https://agent.example/ui/runs/11111111-1111-1111-1111-111111111111",
                result.redirectUrl());

        var requestCaptor = org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
        verify(client).send(requestCaptor.capture(), any());
        HttpRequest request = requestCaptor.getValue();
        assertEquals("https://agent.example/api/v1/runs", request.uri().toString());
        assertEquals("tenant-a", request.headers().firstValue("X-Tenant-ID").orElseThrow());
        assertEquals("Bearer agent-secret", request.headers().firstValue("Authorization").orElseThrow());

        JsonNode payload = MAPPER.readTree(body(request));
        assertEquals("alert_investigation", payload.path("task_type").asText());
        assertEquals("analyst", payload.path("requested_by").asText());
        assertEquals("hisiem", payload.path("subject").path("provider").asText());
        assertEquals("alert", payload.path("subject").path("resource_type").asText());
        assertEquals("alert-doc-42", payload.path("subject").path("resource_id").asText());
        assertEquals("hisiem", payload.path("metadata").path("launch_source").asText());
        assertEquals(0, payload.path("subject").size() - 3);
        assertTrue(!payload.has("alert_data"));
    }

    @Test
    void launchCaseUsesCaseInvestigationContract() throws Exception {
        HttpResponse<String> response = response(202,
                "{\"run_id\":\"22222222-2222-2222-2222-222222222222\"}");
        when(client.send(any(HttpRequest.class),
                org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenReturn(response);
        TenantContext.set("tenant-b");

        AgentLaunchService service = service("");
        service.launch("case_investigation", "case", "case-20260827-1", "admin");

        var requestCaptor = org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
        verify(client).send(requestCaptor.capture(), any());
        HttpRequest request = requestCaptor.getValue();
        JsonNode payload = MAPPER.readTree(body(request));
        assertEquals("case_investigation", payload.path("task_type").asText());
        assertEquals("case", payload.path("subject").path("resource_type").asText());
        assertTrue(request.headers().firstValue("Authorization").isEmpty());
    }

    @Test
    void launchMapsAgentErrorsWithoutForwardingUpstreamBody() throws Exception {
        HttpResponse<String> response = response(429,
                "{\"message\":\"secret upstream detail\"}");
        when(client.send(any(HttpRequest.class),
                org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenReturn(response);

        AgentLaunchException error = assertThrows(AgentLaunchException.class,
                () -> service("").launch("alert_investigation", "alert", "a-1", "analyst"));

        assertEquals(502, error.status());
        assertEquals("AGENT_REJECTED", error.code());
        assertTrue(!error.getMessage().contains("secret upstream detail"));
    }

    @Test
    void launchMapsAgentServerErrorToServiceUnavailable() throws Exception {
        HttpResponse<String> response = response(503, "{\"message\":\"upstream failure\"}");
        when(client.send(any(HttpRequest.class),
                org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenReturn(response);

        AgentLaunchException error = assertThrows(AgentLaunchException.class,
                () -> service("").launch("case_investigation", "case", "case-1", "analyst"));

        assertEquals(503, error.status());
        assertEquals("AGENT_UNAVAILABLE", error.code());
    }

    @Test
    void invalidTypeIsRejectedBeforeHttpCall() {
        AgentLaunchService service = service("");

        assertThrows(IllegalArgumentException.class,
                () -> service.launch("threat_hunt", "alert", "a-1", "analyst"));
        assertThrows(IllegalArgumentException.class,
                () -> service.launch("alert_investigation", "case", "c-1", "analyst"));
    }

    @Test
    void unavailableAgentMapsToServiceUnavailable() throws Exception {
        when(client.send(any(HttpRequest.class),
                org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenThrow(new java.io.IOException("offline"));

        AgentLaunchException error = assertThrows(AgentLaunchException.class,
                () -> service("").launch("alert_investigation", "alert", "a-1", "analyst"));

        assertEquals(503, error.status());
        assertEquals("AGENT_UNAVAILABLE", error.code());
    }

    private AgentLaunchService service(String token) {
        return new AgentLaunchService(MAPPER, client, "https://agent.example/",
                "https://agent.example/", token, Duration.ofSeconds(2));
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse<String> response(int status, String body) {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        return response;
    }

    private static String body(HttpRequest request) throws Exception {
        CompletableFuture<String> completed = new CompletableFuture<>();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<>() {
            private final StringBuilder value = new StringBuilder();
            private Flow.Subscription subscription;

            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                this.subscription = subscription;
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(ByteBuffer item) {
                value.append(StandardCharsets.UTF_8.decode(item));
            }

            @Override
            public void onError(Throwable throwable) {
                completed.completeExceptionally(throwable);
            }

            @Override
            public void onComplete() {
                completed.complete(value.toString());
            }
        });
        return completed.get(1, TimeUnit.SECONDS);
    }
}
