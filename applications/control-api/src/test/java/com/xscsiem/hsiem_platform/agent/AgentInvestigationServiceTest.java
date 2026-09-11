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
    void workspaceUsesServerSideTenantActorAndBearerWithoutLeakingToBody() throws Exception {
        HttpResponse<String> response = response(200, "{\"investigation\":{\"status\":\"RUNNING\"}}");
        when(client.send(any(HttpRequest.class),
                org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenReturn(response);
        TenantContext.set("tenant-a");

        JsonNode body = service("agent-secret").getWorkspace(ID, "analyst");

        assertEquals("RUNNING", body.path("investigation").path("status").asText());
        var captor = org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
        verify(client).send(captor.capture(), any());
        HttpRequest request = captor.getValue();
        assertEquals("GET", request.method());
        assertEquals("https://agent.example/api/v1/investigations/" + ID + "/workspace",
                request.uri().toString());
        assertEquals("tenant-a", request.headers().firstValue("X-Tenant-ID").orElseThrow());
        assertEquals("analyst", request.headers().firstValue("X-Actor-Subject").orElseThrow());
        assertEquals("Bearer agent-secret", request.headers().firstValue("Authorization").orElseThrow());
    }

    @Test
    void lookupEncodesQueryParameters() throws Exception {
        HttpResponse<String> response = response(200, "{\"active\":null,\"latest\":null}");
        when(client.send(any(HttpRequest.class),
                org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenReturn(response);
        TenantContext.set("tenant-a");

        service("agent-secret").lookupForAlert("hisiem", "alert", "a 1/2", "analyst");

        var captor = org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
        verify(client).send(captor.capture(), any());
        HttpRequest request = captor.getValue();
        String uri = request.uri().toString();
        assertTrue(uri.startsWith("https://agent.example/api/v1/investigations/lookup?"));
        assertTrue(uri.contains("provider=hisiem"));
        assertTrue(uri.contains("resource_type=alert"));
        assertTrue(uri.contains("address_id=a+1%2F2"), uri);
        assertEquals("analyst", request.headers().firstValue("X-Actor-Subject").orElseThrow());
        assertEquals("Bearer agent-secret", request.headers().firstValue("Authorization").orElseThrow());
    }

    @Test
    void cancelPostsWithServerSideActorAndBearer() throws Exception {
        HttpResponse<String> response = response(200, "{\"status\":\"CANCELLED\"}");
        when(client.send(any(HttpRequest.class),
                org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenReturn(response);
        TenantContext.set("tenant-a");

        JsonNode body = service("agent-secret").cancel(ID, "analyst");

        assertEquals("CANCELLED", body.path("status").asText());
        var captor = org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
        verify(client).send(captor.capture(), any());
        HttpRequest request = captor.getValue();
        assertEquals("POST", request.method());
        assertEquals("https://agent.example/api/v1/investigations/" + ID + "/cancel", request.uri().toString());
        assertEquals("analyst", request.headers().firstValue("X-Actor-Subject").orElseThrow());
        assertEquals("Bearer agent-secret", request.headers().firstValue("Authorization").orElseThrow());
    }

    @Test
    void blankServiceTokenFailsClosed() {
        // 空/空白服务凭据必须在构造期失败：绝不静默发送匿名服务端请求。
        assertThrows(IllegalStateException.class, () -> service(""));
        assertThrows(IllegalStateException.class, () -> service("   "));
        assertThrows(IllegalStateException.class, () -> service(null));
    }

    @Test
    void createResponseProposalSendsBoundedContractWithServerSideIdentity() throws Exception {
        HttpResponse<String> response = response(201, "{\"proposal\":{\"status\":\"WAITING_APPROVAL\"}}");
        when(client.send(any(HttpRequest.class),
                org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenReturn(response);
        TenantContext.set("tenant-a");

        var proposal = new AgentInvestigationService.CreateProposal(
                "START_SOAR_PLAYBOOK",
                new AgentInvestigationService.ResponseTarget("hisiem", "alert", "alert-1", null),
                java.util.List.of("ev-1", "ev-2"),
                java.util.Map.of("playbook_id", "pb-9"),
                "contain");

        JsonNode body = service("agent-secret").createResponseProposal(ID, "analyst", proposal);

        assertEquals("WAITING_APPROVAL", body.path("proposal").path("status").asText());
        var captor = org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
        verify(client).send(captor.capture(), any());
        HttpRequest request = captor.getValue();
        assertEquals("POST", request.method());
        assertEquals("https://agent.example/api/v1/investigations/" + ID + "/response-proposals",
                request.uri().toString());
        assertEquals("tenant-a", request.headers().firstValue("X-Tenant-ID").orElseThrow());
        assertEquals("analyst", request.headers().firstValue("X-Actor-Subject").orElseThrow());
        assertEquals("Bearer agent-secret", request.headers().firstValue("Authorization").orElseThrow());
        String sent = bodyOf(request);
        assertTrue(sent.contains("\"action_key\":\"START_SOAR_PLAYBOOK\""), sent);
        assertTrue(sent.contains("\"playbook_id\":\"pb-9\""), sent);
        assertTrue(sent.contains("\"evidence_ids\":[\"ev-1\",\"ev-2\"]"), sent);
        // 浏览器身份不可注入：租户/操作人只走服务端头，绝不出现在请求体。
        assertTrue(!sent.contains("tenant"), sent);
        assertTrue(!sent.contains("actor"), sent);
    }

    @Test
    void decideResponseApprovalBindsRouteDecisionNotBody() throws Exception {
        HttpResponse<String> response = response(200, "{\"status\":\"APPROVED\",\"execution_queued\":true}");
        when(client.send(any(HttpRequest.class),
                org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenReturn(response);
        TenantContext.set("tenant-a");

        var input = new AgentInvestigationService.ApprovalDecisionInput(3, "hash-abc", "looks good");
        service("agent-secret").decideResponseApproval("req-1", "operator", true, input);

        var captor = org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
        verify(client).send(captor.capture(), any());
        HttpRequest request = captor.getValue();
        assertEquals("POST", request.method());
        assertEquals("https://agent.example/api/v1/investigations/response-approvals/req-1/approve",
                request.uri().toString());
        assertEquals("operator", request.headers().firstValue("X-Actor-Subject").orElseThrow());
        String sent = bodyOf(request);
        assertTrue(sent.contains("\"decision\":\"APPROVE\""), sent);
        assertTrue(sent.contains("\"expected_revision\":3"), sent);
        assertTrue(sent.contains("\"expected_content_hash\":\"hash-abc\""), sent);
    }

    @Test
    void rejectResponseApprovalTargetsRejectPathAndDecision() throws Exception {
        HttpResponse<String> response = response(200, "{\"status\":\"REJECTED\",\"execution_queued\":false}");
        when(client.send(any(HttpRequest.class),
                org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenReturn(response);
        TenantContext.set("tenant-a");

        var input = new AgentInvestigationService.ApprovalDecisionInput(1, "hash-xyz", null);
        service("agent-secret").decideResponseApproval("req-2", "operator", false, input);

        var captor = org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
        verify(client).send(captor.capture(), any());
        HttpRequest request = captor.getValue();
        assertTrue(request.uri().toString().endsWith("/response-approvals/req-2/reject"));
        assertTrue(bodyOf(request).contains("\"decision\":\"REJECT\""));
    }

    @Test
    void createResponseProposalRequiresActionKey() {
        TenantContext.set("tenant-a");
        assertThrows(IllegalArgumentException.class,
                () -> service("agent-secret").createResponseProposal(ID, "analyst",
                        new AgentInvestigationService.CreateProposal("  ", null, java.util.List.of(), java.util.Map.of(), "x")));
        assertThrows(IllegalArgumentException.class,
                () -> service("agent-secret").createResponseProposal(ID, "analyst", null));
    }

    private static String bodyOf(HttpRequest request) throws Exception {
        var chunks = new java.io.ByteArrayOutputStream();
        var done = new java.util.concurrent.CompletableFuture<Void>();
        request.bodyPublisher().orElseThrow().subscribe(
                new java.util.concurrent.Flow.Subscriber<java.nio.ByteBuffer>() {
                    @Override
                    public void onSubscribe(java.util.concurrent.Flow.Subscription subscription) {
                        subscription.request(Long.MAX_VALUE);
                    }

                    @Override
                    public void onNext(java.nio.ByteBuffer item) {
                        byte[] bytes = new byte[item.remaining()];
                        item.get(bytes);
                        chunks.writeBytes(bytes);
                    }

                    @Override
                    public void onError(Throwable throwable) {
                        done.completeExceptionally(throwable);
                    }

                    @Override
                    public void onComplete() {
                        done.complete(null);
                    }
                });
        done.get();
        return chunks.toString(java.nio.charset.StandardCharsets.UTF_8);
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
                () -> service("agent-secret").getWorkspace(ID, "analyst"));
        assertEquals(503, error.status());
        assertEquals("AGENT_UNAVAILABLE", error.code());
    }

    private void assertMaps(int upstream, int expectedStatus, String expectedCode, String secret) throws Exception {
        HttpResponse<String> response = response(upstream, "{\"message\":\"" + secret + "\"}");
        when(client.send(any(HttpRequest.class),
                org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenReturn(response);

        AgentLaunchException error = assertThrows(AgentLaunchException.class,
                () -> service("agent-secret").getWorkspace(ID, "analyst"));
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
