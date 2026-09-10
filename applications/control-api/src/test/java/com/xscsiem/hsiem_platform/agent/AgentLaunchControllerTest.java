package com.xscsiem.hsiem_platform.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xscsiem.hsiem_platform.alert.AlertController;
import com.xscsiem.hsiem_platform.alert.AlertService;
import com.xscsiem.hsiem_platform.investigation.CaseController;
import com.xscsiem.hsiem_platform.investigation.CaseService;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

class AgentLaunchControllerTest {

    @Test
    void alertLaunchUsesAlertProviderIdAndAuthenticatedUser() {
        AgentLaunchService launch = mock(AgentLaunchService.class);
        when(launch.launch("alert_investigation", "alert", "alert-doc-7", "analyst"))
                .thenReturn(new AgentLaunchResponse("inv-7", "/copilot/investigations/inv-7"));
        AlertController controller = new AlertController(mock(AlertService.class), launch,
                mock(AgentInvestigationService.class));

        AgentLaunchResponse result =
                controller.investigateWithAgent(
                        "alert-doc-7", new UsernamePasswordAuthenticationToken("analyst", "token"));

        assertEquals("inv-7", result.investigationId());
        verify(launch).launch("alert_investigation", "alert", "alert-doc-7", "analyst");
    }

    @Test
    void caseLaunchUsesCaseProviderIdAndAuthenticatedUser() {
        AgentLaunchService launch = mock(AgentLaunchService.class);
        when(launch.launch("case_investigation", "case", "case-42", "admin"))
                .thenReturn(new AgentLaunchResponse("inv-42", "/copilot/investigations/inv-42"));
        CaseController controller = new CaseController(mock(CaseService.class), launch);

        AgentLaunchResponse result =
                controller.investigateWithAgent(
                        "case-42", new UsernamePasswordAuthenticationToken("admin", "token"));

        assertEquals("inv-42", result.investigationId());
        verify(launch).launch("case_investigation", "case", "case-42", "admin");
    }
}
