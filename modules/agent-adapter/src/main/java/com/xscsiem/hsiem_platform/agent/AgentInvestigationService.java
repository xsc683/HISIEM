package com.xscsiem.hsiem_platform.agent;

import com.fasterxml.jackson.annotation.JsonProperty;
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
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    /** 浏览器可提交的响应提案字段白名单：越界字段一律显式失败，绝不静默丢弃。 */
    private static final Set<String> PROPOSAL_FIELDS =
            Set.of("action_key", "evidence_ids", "parameters", "reason");

    /**
     * {@code parameters} 内部同样必须有界：唯一可执行的 START_SOAR_PLAYBOOK 只接受 playbook_id。
     *
     * <p>只白名单顶层字段是不够的 —— 调用方可以把 target/provider 塞进 parameters 里；若那里
     * 被静默过滤或静默透传，浏览器都会看到一次“成功”的请求，却无法知道自己的输入被改写或丢弃。</p>
     */
    private static final Set<String> PROPOSAL_PARAMETER_FIELDS = Set.of("playbook_id");

    /**
     * POST /api/v1/investigations/{id}/response-proposals — 派生并持久化一条类型化响应提案。
     *
     * <p>浏览器只能提交有界的动作契约：动作/证据/参数/理由。没有 target —— 执行目标由 Copilot
     * 从调查持久化的 {@code source_alert_ref} 派生，浏览器无法自行指定作用对象；也没有
     * tenant_id/actor，这两者一律取自服务端上下文，因此浏览器既不能把提案归到别的租户，也不能
     * 冒充他人。越界字段返回 400 而不是被丢弃后“看起来提交成功”。此处仅做传输层转发，不代替
     * Copilot 的策略/审批判定。</p>
     */
    public JsonNode createResponseProposal(String investigationId, String actor, String rawBody) {
        JsonNode body = parseBoundedProposal(rawBody);
        String actionKey = body.path("action_key").asText("");
        if (actionKey.isBlank()) {
            throw new IllegalArgumentException("响应提案必须携带 action_key");
        }
        ObjectNode node = mapper.createObjectNode();
        node.put("action_key", actionKey.trim());
        ArrayNode evidence = node.putArray("evidence_ids");
        JsonNode evidenceIds = body.path("evidence_ids");
        if (!evidenceIds.isMissingNode() && !evidenceIds.isNull() && !evidenceIds.isArray()) {
            throw new IllegalArgumentException("响应提案的 evidence_ids 必须是数组");
        }
        if (evidenceIds.isArray()) {
            for (JsonNode item : evidenceIds) {
                if (!item.isTextual() || item.asText().isBlank()) {
                    throw new IllegalArgumentException("响应提案的 evidence_ids 只能是非空字符串");
                }
                evidence.add(item.asText().trim());
            }
        }
        if (evidence.isEmpty()) {
            throw new IllegalArgumentException("响应提案至少需要引用一条证据");
        }

        ObjectNode parameters = node.putObject("parameters");
        JsonNode rawParameters = body.path("parameters");
        if (!rawParameters.isMissingNode() && !rawParameters.isNull() && !rawParameters.isObject()) {
            throw new IllegalArgumentException("响应提案的 parameters 必须是 JSON 对象");
        }
        if (rawParameters.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = rawParameters.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                String name = entry.getKey();
                if (name == null || !PROPOSAL_PARAMETER_FIELDS.contains(name)) {
                    throw new IllegalArgumentException("响应提案不接受的参数：" + name);
                }
                JsonNode value = entry.getValue();
                if (value == null || !value.isValueNode() || value.isNull()) {
                    throw new IllegalArgumentException("响应提案的参数 " + name + " 必须是标量值");
                }
                parameters.put(name, value.asText());
            }
        }

        String reason = body.path("reason").asText("").trim();
        if (reason.isEmpty()) {
            throw new IllegalArgumentException("响应提案必须携带响应理由");
        }
        node.put("reason", reason);
        return send(request("POST", path(investigationId) + "/response-proposals", actor, node.toString()),
                "响应提案");
    }

    /** 解析并校验提案正文：必须是对象，且只能出现白名单字段。 */
    private JsonNode parseBoundedProposal(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            throw new IllegalArgumentException("响应提案正文不能为空");
        }
        JsonNode body;
        try {
            body = mapper.readTree(rawBody);
        } catch (IOException e) {
            throw new IllegalArgumentException("响应提案正文不是合法 JSON");
        }
        if (body == null || !body.isObject()) {
            throw new IllegalArgumentException("响应提案正文必须是 JSON 对象");
        }
        List<String> unknown = new ArrayList<>();
        body.fieldNames().forEachRemaining(name -> {
            if (!PROPOSAL_FIELDS.contains(name)) {
                unknown.add(name);
            }
        });
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("响应提案不接受字段：" + String.join(", ", unknown));
        }
        return body;
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

    /**
     * 一次人类审批的绑定契约：精确版本 + 精确内容指纹，另可附有界理由。
     *
     * <p>字段名与浏览器/Copilot 契约一致(snake_case)：{@code expected_revision} 是用户在界面上
     * 看到并确认的那一版内容，缺省或错版会被 Copilot 以 409 拒绝，而不是退回“按最新版执行”。</p>
     */
    public record ApprovalDecisionInput(
            @JsonProperty("expected_revision") long expectedRevision,
            @JsonProperty("expected_content_hash") String expectedContentHash,
            @JsonProperty("reason") String reason) { }

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
