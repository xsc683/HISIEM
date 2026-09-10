package com.xscsiem.hsiem_platform.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
        if (requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("Agent 请求超时必须为正数");
        }
        this.requestTimeout = requestTimeout;
    }

    /** GET /api/v1/investigations/{id} — 调查概览(头部)。 */
    public JsonNode getInvestigation(String investigationId) {
        return send(request("GET", path(investigationId), null, null), "调查概览");
    }

    /** GET /api/v1/investigations/{id}/workspace — 只读工作台读模型。 */
    public JsonNode getWorkspace(String investigationId) {
        return send(request("GET", path(investigationId) + "/workspace", null, null), "调查工作台");
    }

    /** POST /api/v1/investigations/{id}/cancel — 取消仍在可取消状态的调查。 */
    public JsonNode cancel(String investigationId, String actor) {
        return send(request("POST", path(investigationId) + "/cancel", actor, "{}"), "取消调查");
    }

    /** GET /api/v1/investigations/lookup — 某来源告警的活动/最近调查(告警再进入)。 */
    public JsonNode lookupForAlert(String provider, String resourceType, String addressId) {
        String query = "?provider=" + enc(provider)
                + "&resource_type=" + enc(resourceType)
                + "&address_id=" + enc(addressId);
        return send(request("GET", "/lookup" + query, null, null), "告警调查查询");
    }

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
        if (!bearerToken.isBlank()) {
            builder.header("Authorization", "Bearer " + bearerToken);
        }
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
