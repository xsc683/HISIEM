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
    void workspaceDelegatesToService() throws Exception {
        AgentInvestigationService service = mock(AgentInvestigationService.class);
        JsonNode expected = MAPPER.readTree("{\"investigation\":{\"status\":\"COMPLETED\"}}");
        when(service.getWorkspace(ID)).thenReturn(expected);
        AgentInvestigationController controller = new AgentInvestigationController(service);

        assertEquals(expected, MAPPER.readTree(controller.workspace(ID)));
        verify(service).getWorkspace(ID);
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
    void alertLookupUsesAlertProviderAndAddressId() throws Exception {
        AgentInvestigationService service = mock(AgentInvestigationService.class);
        when(service.lookupForAlert("hisiem", "alert", "alert-doc-9"))
                .thenReturn(MAPPER.readTree("{\"active\":null,\"latest\":{\"investigation_id\":\"x\"}}"));
        AlertController controller = new AlertController(mock(AlertService.class),
                mock(AgentLaunchService.class), service);

        assertEquals("x", MAPPER.readTree(controller.agentInvestigation("alert-doc-9"))
                .path("latest").path("investigation_id").asText());
        verify(service).lookupForAlert("hisiem", "alert", "alert-doc-9");
    }
}
