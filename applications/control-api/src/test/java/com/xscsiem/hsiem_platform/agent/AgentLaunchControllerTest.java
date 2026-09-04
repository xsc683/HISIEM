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
                .thenReturn(new AgentLaunchResponse("run-7", "https://agent/ui/runs/run-7"));
        AlertController controller = new AlertController(mock(AlertService.class), launch);

        AgentLaunchResponse result =
                controller.investigateWithAgent(
                        "alert-doc-7", new UsernamePasswordAuthenticationToken("analyst", "token"));

        assertEquals("run-7", result.runId());
        verify(launch).launch("alert_investigation", "alert", "alert-doc-7", "analyst");
    }

    @Test
    void caseLaunchUsesCaseProviderIdAndAuthenticatedUser() {
        AgentLaunchService launch = mock(AgentLaunchService.class);
        when(launch.launch("case_investigation", "case", "case-42", "admin"))
                .thenReturn(new AgentLaunchResponse("run-42", "https://agent/ui/runs/run-42"));
        CaseController controller = new CaseController(mock(CaseService.class), launch);

        AgentLaunchResponse result =
                controller.investigateWithAgent(
                        "case-42", new UsernamePasswordAuthenticationToken("admin", "token"));

        assertEquals("run-42", result.runId());
        verify(launch).launch("case_investigation", "case", "case-42", "admin");
    }
}
