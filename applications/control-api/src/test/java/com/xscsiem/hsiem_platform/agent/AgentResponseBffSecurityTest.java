package com.xscsiem.hsiem_platform.agent;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * P2 响应工作台 BFF 的 RBAC 边界。
 *
 * <p>审计角色只读：不得创建响应提案，也不得批准/拒绝。分析角色可创建并决策。决策由路由 决定(approve/reject 各自独立的 URL)，不由请求体决定。服务层被 mock，只验证
 * HTTP 边界。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AgentResponseBffSecurityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ID = "11111111-1111-1111-1111-111111111111";

    @Autowired private MockMvc mvc;

    @MockitoBean private AgentInvestigationService service;

    @Test
    void auditCannotCreateResponseProposal() throws Exception {
        mvc.perform(
                        post("/api/agent-investigations/{id}/response-proposals", ID)
                                .with(user("auditor").roles("AUDIT"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"action_key\":\"START_SOAR_PLAYBOOK\",\"reason\":\"x\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void auditCannotApproveOrReject() throws Exception {
        mvc.perform(
                        post("/api/agent-investigations/response-approvals/{id}/approve", "req-1")
                                .with(user("auditor").roles("AUDIT"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"expected_revision\":1,\"expected_content_hash\":\"h\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(
                        post("/api/agent-investigations/response-approvals/{id}/reject", "req-1")
                                .with(user("auditor").roles("AUDIT"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"expected_revision\":1,\"expected_content_hash\":\"h\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void auditCanReadWorkspace() throws Exception {
        when(service.getWorkspace(eq(ID), any()))
                .thenReturn(MAPPER.readTree("{\"investigation\":{\"status\":\"COMPLETED\"}}"));
        mvc.perform(
                        get("/api/agent-investigations/{id}/workspace", ID)
                                .with(user("auditor").roles("AUDIT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.investigation.status").value("COMPLETED"));
    }

    @Test
    void analystCanCreateProposal() throws Exception {
        when(service.createResponseProposal(eq(ID), eq("analyst"), any()))
                .thenReturn(MAPPER.readTree("{\"proposal\":{\"status\":\"WAITING_APPROVAL\"}}"));
        mvc.perform(
                        post("/api/agent-investigations/{id}/response-proposals", ID)
                                .with(user("analyst").roles("ANALYST"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"action_key\":\"START_SOAR_PLAYBOOK\",\"reason\":\"x\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.proposal.status").value("WAITING_APPROVAL"));
        verify(service).createResponseProposal(eq(ID), eq("analyst"), any());
    }

    @Test
    void proposalBodyReachesTheServiceVerbatimSoOutOfBoundsFieldsAreNotDropped() throws Exception {
        // BFF 不得在反序列化阶段把 target/tenant_id/actor 静默丢弃；正文原样交给服务层，
        // 由服务层的字段白名单显式拒绝(见 AgentInvestigationServiceTest)。
        when(service.createResponseProposal(eq(ID), eq("analyst"), any()))
                .thenReturn(MAPPER.readTree("{\"proposal\":{\"status\":\"WAITING_APPROVAL\"}}"));
        String body =
                "{\"action_key\":\"START_SOAR_PLAYBOOK\",\"reason\":\"x\","
                        + "\"target\":{\"provider\":\"hisiem\",\"resource_type\":\"alert\","
                        + "\"address_id\":\"alert-9\"},\"tenant_id\":\"tenant-b\",\"actor\":\"someone\"}";
        mvc.perform(
                        post("/api/agent-investigations/{id}/response-proposals", ID)
                                .with(user("analyst").roles("ANALYST"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isOk());

        var captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(service).createResponseProposal(eq(ID), eq("analyst"), captor.capture());
        for (String forbidden : new String[] {"target", "tenant_id", "actor", "address_id"}) {
            assertTrue(captor.getValue().contains(forbidden), captor.getValue());
        }
    }

    @Test
    void anOutOfBoundsProposalIsRejectedWith400InvalidArgument() throws Exception {
        // 服务层用 IllegalArgumentException 拒绝越界字段(见 AgentInvestigationServiceTest)；
        // 这里锁定它在 HTTP 层的可见结果：400 + code=INVALID_ARGUMENT，而不是 500 或静默成功。
        when(service.createResponseProposal(eq(ID), eq("analyst"), any()))
                .thenThrow(new IllegalArgumentException("响应提案不接受字段：target"));
        mvc.perform(
                        post("/api/agent-investigations/{id}/response-proposals", ID)
                                .with(user("analyst").roles("ANALYST"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"action_key\":\"START_SOAR_PLAYBOOK\",\"evidence_ids\":[\"ev-1\"],"
                                                + "\"parameters\":{\"playbook_id\":\"pb-9\"},\"reason\":\"x\","
                                                + "\"target\":{\"provider\":\"hisiem\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    @Test
    void approveRoutePassesApproveTrue() throws Exception {
        when(service.decideResponseApproval(eq("req-1"), eq("operator"), eq(true), any()))
                .thenReturn(MAPPER.readTree("{\"status\":\"APPROVED\",\"execution_queued\":true}"));
        mvc.perform(
                        post("/api/agent-investigations/response-approvals/{id}/approve", "req-1")
                                .with(user("operator").roles("ANALYST"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"expected_revision\":2,\"expected_content_hash\":\"hash\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
        verify(service).decideResponseApproval(eq("req-1"), eq("operator"), eq(true), any());
    }

    @Test
    void rejectRoutePassesApproveFalse() throws Exception {
        when(service.decideResponseApproval(eq("req-2"), eq("operator"), eq(false), any()))
                .thenReturn(
                        MAPPER.readTree("{\"status\":\"REJECTED\",\"execution_queued\":false}"));
        mvc.perform(
                        post("/api/agent-investigations/response-approvals/{id}/reject", "req-2")
                                .with(user("operator").roles("ANALYST"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"expected_revision\":2,\"expected_content_hash\":\"hash\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));
        verify(service).decideResponseApproval(eq("req-2"), eq("operator"), eq(false), any());
    }
}
