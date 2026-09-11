package com.xscsiem.hsiem_platform.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xscsiem.hsiem_platform.tenant.TenantContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * HISIEM 到 SOC Copilot 调查工作台的服务端读取/取消代理。
 *
 * <p>浏览器只调用 HISIEM API；Copilot 地址与可选服务凭据只存在服务端配置中。租户/操作人来自
 * HISIEM 已完成校验的服务端上下文，浏览器不能通过请求体/头伪造。代理只回传 Copilot 的有界
 * JSON DTO，绝不回传服务凭据或上游原始错误正文。</p>
 */
@Service
public class AgentInvestigationService {

    private final ObjectMapper mapper;
    private final HttpClient client;
    private final URI investigationsUri;
    private final String bearerToken;
    private final Duration requestTimeout;

    @Autowired
    public AgentInvestigationService(
            ObjectMapper mapper,
            @Value("${app.agent.base-url:http://127.0.0.1:8000}") String agentBaseUrl,
            @Value("${app.agent.bearer-token:}") String bearerToken,
            @Value("${app.agent.timeout:PT10S}") Duration requestTimeout) {
        this(mapper, HttpClient.newBuilder()
                        .version(HttpClient.Version.HTTP_1_1)
                        .connectTimeout(requestTimeout).build(),
                agentBaseUrl, bearerToken, requestTimeout);
    }

    AgentInvestigationService(ObjectMapper mapper, HttpClient client, String agentBaseUrl,
                              String bearerToken, Duration requestTimeout) {
        this.mapper = mapper;
        this.client = client;
        this.investigationsUri = endpoint(agentBaseUrl, "/api/v1/investigations");
        this.bearerToken = bearerToken == null ? "" : bearerToken.trim();
        if (this.bearerToken.isBlank()) {
            // 集成运行时 Copilot 会校验服务凭据；空凭据必须让进程启动失败，绝不静默发匿名请求。
            throw new IllegalStateException(
                    "Copilot 服务凭据未配置：app.agent.bearer-token / HISIEM_AGENT_BEARER_TOKEN 必须为非空");
        }
        if (requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("Agent 请求超时必须为正数");
        }
        this.requestTimeout = requestTimeout;
    }

    /** GET /api/v1/investigations/{id} — 调查概览(头部)。 */
    public JsonNode getInvestigation(String investigationId, String actor) {
        return send(request("GET", path(investigationId), actor, null), "调查概览");
    }

    /** GET /api/v1/investigations/{id}/workspace — 只读工作台读模型。 */
    public JsonNode getWorkspace(String investigationId, String actor) {
        return send(request("GET", path(investigationId) + "/workspace", actor, null), "调查工作台");
    }

    /** POST /api/v1/investigations/{id}/cancel — 取消仍在可取消状态的调查。 */
    public JsonNode cancel(String investigationId, String actor) {
        return send(request("POST", path(investigationId) + "/cancel", actor, "{}"), "取消调查");
    }

    /** GET /api/v1/investigations/lookup — 某来源告警的活动/最近调查(告警再进入)。 */
    public JsonNode lookupForAlert(String provider, String resourceType, String addressId, String actor) {
        String query = "?provider=" + enc(provider)
                + "&resource_type=" + enc(resourceType)
                + "&address_id=" + enc(addressId);
        return send(request("GET", "/lookup" + query, actor, null), "告警调查查询");
    }

    /**
     * POST /api/v1/investigations/{id}/response-proposals — 派生并持久化一条类型化响应提案。
     *
     * <p>浏览器只能提交有界的动作契约；租户/操作人一律由服务端上下文派生，请求体不接受这两项，
     * 因此浏览器无法把提案归到别的租户或冒充他人。此处仅做传输层转发，不代替 Copilot 的
     * 策略/审批判定。</p>
     */
    public JsonNode createResponseProposal(String investigationId, String actor, CreateProposal body) {
        if (body == null || body.actionKey() == null || body.actionKey().isBlank()) {
            throw new IllegalArgumentException("响应提案必须携带 action_key");
        }
        ObjectNode node = mapper.createObjectNode();
        node.put("action_key", body.actionKey().trim());
        if (body.target() != null) {
            ObjectNode target = node.putObject("target");
            target.put("provider", body.target().provider());
            target.put("resource_type", body.target().resourceType());
            target.put("address_id", body.target().addressId());
            if (body.target().businessId() != null) {
                target.put("business_id", body.target().businessId());
            }
        }
        ArrayNode evidence = node.putArray("evidence_ids");
        if (body.evidenceIds() != null) {
            for (String id : body.evidenceIds()) {
                if (id != null && !id.isBlank()) {
                    evidence.add(id.trim());
                }
            }
        }
        ObjectNode parameters = node.putObject("parameters");
        if (body.parameters() != null) {
            body.parameters().forEach((key, value) -> {
                if (key != null && value != null) {
                    parameters.put(key, value);
                }
            });
        }
        node.put("reason", body.reason() == null ? "" : body.reason().trim());
        return send(request("POST", path(investigationId) + "/response-proposals", actor, node.toString()),
                "响应提案");
    }

    /**
     * POST /api/v1/investigations/response-approvals/{id}/approve|reject — 记录一次人类审批决策。
     *
     * <p>决策种类由本方法的 {@code approve} 参数(源自 BFF 路由)决定，绝不取自信任请求体，
     * 避免请求体与路由不一致造成“看似拒绝实为批准”。{@code expected_revision}/{@code
     * expected_content_hash} 是浏览器看到的契约版本，绑定到该提案的精确内容；不一致时 Copilot
     * 拒绝(409)，绝不误执行。</p>
     */
    public JsonNode decideResponseApproval(String approvalRequestId, String actor, boolean approve,
                                           ApprovalDecisionInput body) {
        ObjectNode node = mapper.createObjectNode();
        node.put("decision", approve ? "APPROVE" : "REJECT");
        if (body != null) {
            node.put("expected_revision", body.expectedRevision());
            node.put("expected_content_hash",
                    body.expectedContentHash() == null ? "" : body.expectedContentHash().trim());
            if (body.reason() != null && !body.reason().isBlank()) {
                node.put("reason", body.reason().trim());
            }
        } else {
            node.put("expected_revision", 0);
            node.put("expected_content_hash", "");
        }
        String suffix = approve ? "/approve" : "/reject";
        return send(request("POST", "/response-approvals/" + enc(approvalRequestId) + suffix, actor,
                node.toString()), "响应审批");
    }

    /** 浏览器可提交的响应提案契约(仅动作/目标/证据/参数/理由;无租户、无操作人)。 */
    public record CreateProposal(String actionKey, ResponseTarget target, List<String> evidenceIds,
                                 Map<String, String> parameters, String reason) { }

    public record ResponseTarget(String provider, String resourceType, String addressId, String businessId) { }

    /** 一次人类审批的绑定契约。 */
    public record ApprovalDecisionInput(long expectedRevision, String expectedContentHash, String reason) { }

    private static String path(String investigationId) {
        return "/" + enc(investigationId);
    }

    private HttpRequest request(String method, String pathAndQuery, String actor, String body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(investigationsUri + pathAndQuery))
                .timeout(requestTimeout)
                .header("X-Tenant-ID", TenantContext.id())
                .header("Accept", "application/json");
        if (actor != null && !actor.isBlank()) {
            builder.header("X-Actor-Subject", actor.trim());
        }
        // 构造期已强制 bearerToken 非空：每个服务端请求都携带服务凭据，绝无匿名调用分支。
        builder.header("Authorization", "Bearer " + bearerToken);
        if (body != null) {
            builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }
        return builder.build();
    }

    private JsonNode send(HttpRequest request, String action) {
        final HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AgentLaunchException(503, "AGENT_UNAVAILABLE", "Agent 服务暂不可用", e);
        } catch (IOException | RuntimeException e) {
            throw new AgentLaunchException(503, "AGENT_UNAVAILABLE", "Agent 服务暂不可用", e);
        }

        int status = response.statusCode();
        if (status / 100 == 2) {
            return parse(response.body(), action);
        }
        // 上游错误归一化：绝不把原始响应正文/凭据透传给浏览器。
        if (status == 404) {
            throw new AgentLaunchException(404, "AGENT_INVESTIGATION_NOT_FOUND", "调查不存在");
        }
        if (status == 403) {
            throw new AgentLaunchException(403, "AGENT_FORBIDDEN", "无权访问该调查");
        }
        if (status == 409) {
            throw new AgentLaunchException(409, "AGENT_STATE_CONFLICT", "调查当前状态不允许该操作");
        }
        if (status >= 400 && status < 500) {
            throw new AgentLaunchException(502, "AGENT_REJECTED", "Agent 拒绝该请求");
        }
        throw new AgentLaunchException(503, "AGENT_UNAVAILABLE", "Agent 服务暂不可用");
    }

    private JsonNode parse(String body, String action) {
        if (body == null || body.isBlank()) {
            throw new AgentLaunchException(502, "AGENT_INVALID_RESPONSE", "Agent 返回的" + action + "为空");
        }
        try {
            return mapper.readTree(body);
        } catch (Exception e) {
            throw new AgentLaunchException(502, "AGENT_INVALID_RESPONSE", "Agent 返回的" + action + "无效", e);
        }
    }

    private static String enc(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static URI endpoint(String baseUrl, String path) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("Agent 地址不能为空");
        }
        return URI.create(baseUrl.replaceFirst("/+$", "") + path);
    }
}
