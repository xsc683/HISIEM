package com.xscsiem.hsiem_platform.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xscsiem.hsiem_platform.alert.AlertController;
import com.xscsiem.hsiem_platform.alert.AlertService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class AgentInvestigationControllerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ID = "11111111-1111-1111-1111-111111111111";

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void workspaceForwardsServerDerivedActorToService() throws Exception {
        AgentInvestigationService service = mock(AgentInvestigationService.class);
        JsonNode expected = MAPPER.readTree("{\"investigation\":{\"status\":\"COMPLETED\"}}");
        when(service.getWorkspace(ID, "analyst")).thenReturn(expected);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("analyst", "token"));
        AgentInvestigationController controller = new AgentInvestigationController(service);

        assertEquals(expected, MAPPER.readTree(controller.workspace(ID)));
        verify(service).getWorkspace(ID, "analyst");
    }

    @Test
    void cancelUsesAuthenticatedOperator() throws Exception {
        AgentInvestigationService service = mock(AgentInvestigationService.class);
        when(service.cancel(eq(ID), eq("analyst"))).thenReturn(MAPPER.readTree("{\"status\":\"CANCELLED\"}"));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("analyst", "token"));
        AgentInvestigationController controller = new AgentInvestigationController(service);

        assertEquals("CANCELLED", MAPPER.readTree(controller.cancel(ID)).path("status").asText());
        verify(service).cancel(ID, "analyst");
    }

    @Test
    void alertLookupUsesAlertProviderAddressIdAndAuthenticatedActor() throws Exception {
        AgentInvestigationService service = mock(AgentInvestigationService.class);
        when(service.lookupForAlert("hisiem", "alert", "alert-doc-9", "analyst"))
                .thenReturn(MAPPER.readTree("{\"active\":null,\"latest\":{\"investigation_id\":\"x\"}}"));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("analyst", "token"));
        AlertController controller = new AlertController(mock(AlertService.class),
                mock(AgentLaunchService.class), service);

        assertEquals("x", MAPPER.readTree(controller.agentInvestigation("alert-doc-9"))
                .path("latest").path("investigation_id").asText());
        verify(service).lookupForAlert("hisiem", "alert", "alert-doc-9", "analyst");
    }

    @Test
    void createResponseProposalUsesServerDerivedOperator() throws Exception {
        AgentInvestigationService service = mock(AgentInvestigationService.class);
        String body = "{\"action_key\":\"START_SOAR_PLAYBOOK\",\"evidence_ids\":[\"ev-1\"],"
                + "\"parameters\":{\"playbook_id\":\"pb-9\"},\"reason\":\"contain\"}";
        when(service.createResponseProposal(ID, "analyst", body))
                .thenReturn(MAPPER.readTree("{\"proposal\":{\"status\":\"WAITING_APPROVAL\"}}"));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("analyst", "token"));
        AgentInvestigationController controller = new AgentInvestigationController(service);

        assertEquals("WAITING_APPROVAL",
                MAPPER.readTree(controller.createResponseProposal(ID, body))
                        .path("proposal").path("status").asText());
        verify(service).createResponseProposal(ID, "analyst", body);
    }

    @Test
    void approveAndRejectRoutesSelectDecisionExplicitly() throws Exception {
        AgentInvestigationService service = mock(AgentInvestigationService.class);
        var input = new AgentInvestigationService.ApprovalDecisionInput(2, "hash", null);
        when(service.decideResponseApproval("req-1", "operator", true, input))
                .thenReturn(MAPPER.readTree("{\"status\":\"APPROVED\",\"execution_queued\":true}"));
        when(service.decideResponseApproval("req-1", "operator", false, input))
                .thenReturn(MAPPER.readTree("{\"status\":\"REJECTED\",\"execution_queued\":false}"));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("operator", "token"));
        AgentInvestigationController controller = new AgentInvestigationController(service);

        assertEquals("APPROVED", MAPPER.readTree(controller.approveResponse("req-1", input))
                .path("status").asText());
        assertEquals("REJECTED", MAPPER.readTree(controller.rejectResponse("req-1", input))
                .path("status").asText());
        // Decision is chosen by the route, never by the request body.
        verify(service).decideResponseApproval("req-1", "operator", true, input);
        verify(service).decideResponseApproval("req-1", "operator", false, input);
    }
}
