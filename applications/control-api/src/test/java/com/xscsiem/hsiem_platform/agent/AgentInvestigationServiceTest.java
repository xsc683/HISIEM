package com.xscsiem.hsiem_platform.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xscsiem.hsiem_platform.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentInvestigationServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final String ID = "11111111-1111-1111-1111-111111111111";
    private final HttpClient client = mock(HttpClient.class);

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void workspaceUsesServerSideTenantAndBearerWithoutLeakingToBody() throws Exception {
        HttpResponse<String> response = response(200, "{\"investigation\":{\"status\":\"RUNNING\"}}");
        when(client.send(any(HttpRequest.class),
                org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenReturn(response);
        TenantContext.set("tenant-a");

        JsonNode body = service("agent-secret").getWorkspace(ID);

        assertEquals("RUNNING", body.path("investigation").path("status").asText());
        var captor = org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
        verify(client).send(captor.capture(), any());
        HttpRequest request = captor.getValue();
        assertEquals("GET", request.method());
        assertEquals("https://agent.example/api/v1/investigations/" + ID + "/workspace",
                request.uri().toString());
        assertEquals("tenant-a", request.headers().firstValue("X-Tenant-ID").orElseThrow());
        assertEquals("Bearer agent-secret", request.headers().firstValue("Authorization").orElseThrow());
    }

    @Test
    void lookupEncodesQueryParameters() throws Exception {
        HttpResponse<String> response = response(200, "{\"active\":null,\"latest\":null}");
        when(client.send(any(HttpRequest.class),
                org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenReturn(response);
        TenantContext.set("tenant-a");

        service("").lookupForAlert("hisiem", "alert", "a 1/2");

        var captor = org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
        verify(client).send(captor.capture(), any());
        String uri = captor.getValue().uri().toString();
        assertTrue(uri.startsWith("https://agent.example/api/v1/investigations/lookup?"));
        assertTrue(uri.contains("provider=hisiem"));
        assertTrue(uri.contains("resource_type=alert"));
        assertTrue(uri.contains("address_id=a+1%2F2"), uri);
    }

    @Test
    void cancelPostsWithServerSideActor() throws Exception {
        HttpResponse<String> response = response(200, "{\"status\":\"CANCELLED\"}");
        when(client.send(any(HttpRequest.class),
                org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenReturn(response);
        TenantContext.set("tenant-a");

        JsonNode body = service("").cancel(ID, "analyst");

        assertEquals("CANCELLED", body.path("status").asText());
        var captor = org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
        verify(client).send(captor.capture(), any());
        HttpRequest request = captor.getValue();
        assertEquals("POST", request.method());
        assertEquals("https://agent.example/api/v1/investigations/" + ID + "/cancel", request.uri().toString());
        assertEquals("analyst", request.headers().firstValue("X-Actor-Subject").orElseThrow());
        assertTrue(request.headers().firstValue("Authorization").isEmpty());
    }

    @Test
    void mapsUpstreamStatusCodesWithoutLeakingBody() throws Exception {
        TenantContext.set("tenant-a");
        assertMaps(404, 404, "AGENT_INVESTIGATION_NOT_FOUND", "secret upstream detail");
        assertMaps(403, 403, "AGENT_FORBIDDEN", "secret upstream detail");
        assertMaps(409, 409, "AGENT_STATE_CONFLICT", "secret upstream detail");
        assertMaps(422, 502, "AGENT_REJECTED", "secret upstream detail");
        assertMaps(500, 503, "AGENT_UNAVAILABLE", "secret upstream detail");
    }

    @Test
    void unavailableAgentMapsToServiceUnavailable() throws Exception {
        when(client.send(any(HttpRequest.class),
                org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenThrow(new java.io.IOException("offline"));

        AgentLaunchException error = assertThrows(AgentLaunchException.class,
                () -> service("").getWorkspace(ID));
        assertEquals(503, error.status());
        assertEquals("AGENT_UNAVAILABLE", error.code());
    }

    private void assertMaps(int upstream, int expectedStatus, String expectedCode, String secret) throws Exception {
        HttpResponse<String> response = response(upstream, "{\"message\":\"" + secret + "\"}");
        when(client.send(any(HttpRequest.class),
                org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenReturn(response);

        AgentLaunchException error = assertThrows(AgentLaunchException.class,
                () -> service("agent-secret").getWorkspace(ID));
        assertEquals(expectedStatus, error.status());
        assertEquals(expectedCode, error.code());
        assertTrue(!error.getMessage().contains(secret));
    }

    private AgentInvestigationService service(String token) {
        return new AgentInvestigationService(MAPPER, client, "https://agent.example/",
                token, Duration.ofSeconds(2));
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse<String> response(int status, String body) {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        return response;
    }
}
