package com.xscsiem.hsiem_platform.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P2 响应工作台 BFF 的 RBAC 边界。
 *
 * <p>审计角色只读：不得创建响应提案，也不得批准/拒绝。分析角色可创建并决策。决策由路由
 * 决定(approve/reject 各自独立的 URL)，不由请求体决定。服务层被 mock，只验证 HTTP 边界。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class AgentResponseBffSecurityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ID = "11111111-1111-1111-1111-111111111111";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private AgentInvestigationService service;

    @Test
    void auditCannotCreateResponseProposal() throws Exception {
        mvc.perform(post("/api/agent-investigations/{id}/response-proposals", ID)
                        .with(user("auditor").roles("AUDIT"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actionKey\":\"START_SOAR_PLAYBOOK\",\"reason\":\"x\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void auditCannotApproveOrReject() throws Exception {
        mvc.perform(post("/api/agent-investigations/response-approvals/{id}/approve", "req-1")
                        .with(user("auditor").roles("AUDIT"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"expectedContentHash\":\"h\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/agent-investigations/response-approvals/{id}/reject", "req-1")
                        .with(user("auditor").roles("AUDIT"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"expectedContentHash\":\"h\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void auditCanReadWorkspace() throws Exception {
        when(service.getWorkspace(eq(ID), any()))
                .thenReturn(MAPPER.readTree("{\"investigation\":{\"status\":\"COMPLETED\"}}"));
        mvc.perform(get("/api/agent-investigations/{id}/workspace", ID)
                        .with(user("auditor").roles("AUDIT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.investigation.status").value("COMPLETED"));
    }

    @Test
    void analystCanCreateProposal() throws Exception {
        when(service.createResponseProposal(eq(ID), eq("analyst"), any()))
                .thenReturn(MAPPER.readTree("{\"proposal\":{\"status\":\"WAITING_APPROVAL\"}}"));
        mvc.perform(post("/api/agent-investigations/{id}/response-proposals", ID)
                        .with(user("analyst").roles("ANALYST"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actionKey\":\"START_SOAR_PLAYBOOK\",\"reason\":\"x\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.proposal.status").value("WAITING_APPROVAL"));
        verify(service).createResponseProposal(eq(ID), eq("analyst"), any());
    }

    @Test
    void approveRoutePassesApproveTrue() throws Exception {
        when(service.decideResponseApproval(eq("req-1"), eq("operator"), eq(true), any()))
                .thenReturn(MAPPER.readTree("{\"status\":\"APPROVED\",\"execution_queued\":true}"));
        mvc.perform(post("/api/agent-investigations/response-approvals/{id}/approve", "req-1")
                        .with(user("operator").roles("ANALYST"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":2,\"expectedContentHash\":\"hash\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
        verify(service).decideResponseApproval(eq("req-1"), eq("operator"), eq(true), any());
    }

    @Test
    void rejectRoutePassesApproveFalse() throws Exception {
        when(service.decideResponseApproval(eq("req-2"), eq("operator"), eq(false), any()))
                .thenReturn(MAPPER.readTree("{\"status\":\"REJECTED\",\"execution_queued\":false}"));
        mvc.perform(post("/api/agent-investigations/response-approvals/{id}/reject", "req-2")
                        .with(user("operator").roles("ANALYST"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":2,\"expectedContentHash\":\"hash\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));
        verify(service).decideResponseApproval(eq("req-2"), eq("operator"), eq(false), any());
    }
}
