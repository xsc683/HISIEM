package com.xscsiem.hsiem_platform.agent;

import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 调查工作台 BFF：浏览器只访问 HISIEM，HISIEM 再以服务端身份代理到 SOC Copilot。
 *
 * <p>租户/操作人取自 HISIEM 已完成校验的上下文({@code TenantContext} + Spring Security principal)，
 * 浏览器不能通过请求体/头覆盖。响应只回传 Copilot 的有界 JSON DTO，绝不回传服务凭据。</p>
 */
@RestController
@RequestMapping("/api/agent-investigations")
public class AgentInvestigationController {

    private final AgentInvestigationService service;

    public AgentInvestigationController(AgentInvestigationService service) {
        this.service = service;
    }

    /**
     * 调查概览(头部)。
     *
     * <p>返回原始 JSON 文本而非 Jackson 节点：控制面 HTTP 序列化由 Jackson 3 完成，而本模块
     * 解析上游响应使用 Jackson 2 的 {@code JsonNode}；直接返回节点会被 Jackson 3 当作普通
     * POJO 序列化(输出 containerNode/nodeType 等)。返回 {@code toString()} 得到的合法 JSON 文本
     * 并显式声明 {@code application/json}，保证浏览器收到的是真实 JSON。</p>
     */
    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST', 'AUDIT')")
    public String investigation(@PathVariable String id) {
        return service.getInvestigation(id, operator()).toString();
    }

    /** 只读工作台读模型(Overview/Evidence/Investigation/Timeline)。 */
    @GetMapping(value = "/{id}/workspace", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST', 'AUDIT')")
    public String workspace(@PathVariable String id) {
        return service.getWorkspace(id, operator()).toString();
    }

    /** 取消仍在可取消状态的调查；后端权威判定可取消性。 */
    @PostMapping(value = "/{id}/cancel", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST')")
    public String cancel(@PathVariable String id) {
        return service.cancel(id, operator()).toString();
    }

    /**
     * 派生一条类型化响应提案(CREATED/WAITING_APPROVAL/DENIED)。
     *
     * <p>仅持有审计只读角色的用户不得创建：发起响应属于分析/管理职责。租户与操作人由服务端
     * 上下文派生，浏览器请求体无法覆盖；正文只接受有界字段(action_key/evidence_ids/parameters/
     * reason)，出现 target/tenant_id/actor 等越界字段时返回 400，绝不静默丢弃。</p>
     */
    @PostMapping(value = "/{id}/response-proposals", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST')")
    public String createResponseProposal(@PathVariable String id, @RequestBody String body) {
        return service.createResponseProposal(id, operator(), body).toString();
    }

    /** 批准一条待审批的响应提案；真正的副作用由 Copilot 的持久化队列异步执行。 */
    @PostMapping(value = "/response-approvals/{approvalRequestId}/approve",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST')")
    public String approveResponse(
            @PathVariable String approvalRequestId,
            @RequestBody(required = false) AgentInvestigationService.ApprovalDecisionInput body) {
        return service.decideResponseApproval(approvalRequestId, operator(), true, body).toString();
    }

    /** 拒绝一条待审批的响应提案；不会产生任何执行命令。 */
    @PostMapping(value = "/response-approvals/{approvalRequestId}/reject",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST')")
    public String rejectResponse(
            @PathVariable String approvalRequestId,
            @RequestBody(required = false) AgentInvestigationService.ApprovalDecisionInput body) {
        return service.decideResponseApproval(approvalRequestId, operator(), false, body).toString();
    }

    private static String operator() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null || authentication.getName() == null
                ? "system"
                : authentication.getName();
    }
}
