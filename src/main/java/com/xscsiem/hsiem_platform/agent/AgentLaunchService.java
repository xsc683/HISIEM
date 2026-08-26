package com.xscsiem.hsiem_platform.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xscsiem.hsiem_platform.tenant.TenantContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * HISIEM 到 HISIEM-Agent 的服务端启动代理。
 *
 * <p>浏览器只调用 HISIEM API；Agent 地址和可选服务凭据只存在服务端配置中。
 * 传给 Agent 的内容保持为有界的资源引用，Agent 后续通过 provider 自己 hydrate 权威数据。</p>
 */
@Service
public class AgentLaunchService {

    private final ObjectMapper mapper;
    private final HttpClient client;
    private final URI agentRunsUri;
    private final String agentUiBaseUrl;
    private final String bearerToken;
    private final Duration requestTimeout;

    @Autowired
    public AgentLaunchService(
            ObjectMapper mapper,
            @Value("${app.agent.base-url:http://127.0.0.1:8000}") String agentBaseUrl,
            @Value("${app.agent.ui-base-url:http://127.0.0.1:8000}") String agentUiBaseUrl,
            @Value("${app.agent.bearer-token:}") String bearerToken,
            @Value("${app.agent.timeout:PT10S}") Duration requestTimeout) {
        this(mapper, HttpClient.newBuilder().connectTimeout(requestTimeout).build(),
                agentBaseUrl, agentUiBaseUrl, bearerToken, requestTimeout);
    }

    AgentLaunchService(ObjectMapper mapper, HttpClient client, String agentBaseUrl,
                       String agentUiBaseUrl, String bearerToken, Duration requestTimeout) {
        this.mapper = mapper;
        this.client = client;
        this.agentRunsUri = endpoint(agentBaseUrl, "/api/v1/runs");
        this.agentUiBaseUrl = trimTrailingSlash(agentUiBaseUrl);
        this.bearerToken = bearerToken == null ? "" : bearerToken.trim();
        if (requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("Agent 请求超时必须为正数");
        }
        this.requestTimeout = requestTimeout;
    }

    public AgentLaunchResponse launch(String taskType, String resourceType, String resourceId,
                                      String requestedBy) {
        validate(taskType, resourceType, resourceId, requestedBy);

        Map<String, Object> subject = new LinkedHashMap<>();
        subject.put("provider", "hisiem");
        subject.put("resource_type", resourceType);
        subject.put("resource_id", resourceId.trim());

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("launch_source", "hisiem");
        metadata.put("launch_resource_type", resourceType);
        metadata.put("launch_resource_id", resourceId.trim());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("task_type", taskType);
        payload.put("prompt", promptFor(resourceType));
        payload.put("subject", subject);
        payload.put("requested_by", requestedBy.trim());
        payload.put("metadata", metadata);

        final String body;
        try {
            body = mapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new AgentLaunchException(502, "AGENT_REQUEST_INVALID",
                    "Agent 启动请求无法序列化", e);
        }

        HttpRequest.Builder request = HttpRequest.newBuilder(agentRunsUri)
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .header("X-Tenant-ID", TenantContext.id())
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (!bearerToken.isBlank()) {
            request.header("Authorization", "Bearer " + bearerToken);
        }

        final HttpResponse<String> response;
        try {
            response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unavailable(e);
        } catch (IOException | RuntimeException e) {
            throw unavailable(e);
        }

        if (response.statusCode() / 100 != 2) {
            if (response.statusCode() >= 400 && response.statusCode() < 500) {
                throw new AgentLaunchException(502, "AGENT_REJECTED", "Agent 拒绝创建任务");
            }
            throw unavailable(null);
        }

        return parseLaunchResponse(response.body());
    }

    private AgentLaunchResponse parseLaunchResponse(String body) {
        try {
            JsonNode json = mapper.readTree(body);
            UUID runId = UUID.fromString(json.path("run_id").asText());
            return new AgentLaunchResponse(runId.toString(),
                    agentUiBaseUrl + "/ui/runs/" + runId);
        } catch (Exception e) {
            throw new AgentLaunchException(502, "AGENT_INVALID_RESPONSE",
                    "Agent 返回的任务标识无效", e);
        }
    }

    private static void validate(String taskType, String resourceType, String resourceId,
                                 String requestedBy) {
        if (!("alert_investigation".equals(taskType) || "case_investigation".equals(taskType))) {
            throw new IllegalArgumentException("Agent 启动任务类型非法");
        }
        if (!("alert".equals(resourceType) || "case".equals(resourceType))) {
            throw new IllegalArgumentException("Agent 资源类型非法");
        }
        if (resourceId == null || resourceId.isBlank()) {
            throw new IllegalArgumentException("Agent 资源 ID 不能为空");
        }
        if (requestedBy == null || requestedBy.isBlank()) {
            throw new IllegalArgumentException("Agent 启动人不能为空");
        }
        String expectedTask = "alert".equals(resourceType)
                ? "alert_investigation" : "case_investigation";
        if (!expectedTask.equals(taskType)) {
            throw new IllegalArgumentException("Agent 任务类型与资源类型不匹配");
        }
    }

    private static String promptFor(String resourceType) {
        return "alert".equals(resourceType)
                ? "Investigate this HISIEM alert and return evidence-backed findings."
                : "Investigate this HISIEM case and return evidence-backed findings.";
    }

    private static AgentLaunchException unavailable(Throwable cause) {
        return new AgentLaunchException(503, "AGENT_UNAVAILABLE", "Agent 服务暂不可用", cause);
    }

    private static URI endpoint(String baseUrl, String path) {
        return URI.create(trimTrailingSlash(baseUrl) + path);
    }

    private static String trimTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Agent 地址不能为空");
        }
        return value.replaceFirst("/+$", "");
    }
}
