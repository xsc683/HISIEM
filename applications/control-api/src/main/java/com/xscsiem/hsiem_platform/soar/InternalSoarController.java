package com.xscsiem.hsiem_platform.soar;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * SOC Copilot 等服务到服务的 SOAR 触发入口(仅此一个内部端点)。
 *
 * <p>认证/租户由 {@code /api/internal/**} 专用安全链完成({@code Authorization: Bearer} + {@code
 * X-Tenant-ID});本控制器不再解析凭据。真实执行复用 {@link SoarService#triggerExecution} 的持久化、幂等、租户隔离语义,不引入新的执行路径。
 *
 * <p>响应只包含有界字段:执行 id、状态、空 result 和截断的错误文本。绝不回显上游响应体、 令牌或平台凭据。
 */
@RestController
@RequestMapping("/api/internal/soar")
public class InternalSoarController {

    static final String ACTION_START_SOAR_PLAYBOOK = "START_SOAR_PLAYBOOK";
    private static final String ACTOR = "copilot";
    private static final int MAX_ERROR_CHARS = 500;

    private final SoarService service;

    public InternalSoarController(SoarService service) {
        this.service = service;
    }

    @PostMapping("/executions")
    public ResponseEntity<ExecutionResponse> trigger(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody ExecutionRequest request) {
        if (request == null || !ACTION_START_SOAR_PLAYBOOK.equals(trim(request.actionKey()))) {
            throw new IllegalArgumentException("不支持的 action_key");
        }
        String playbookId = require(request.playbookId(), "playbook_id");
        Target target = request.target();
        if (target == null) {
            throw new IllegalArgumentException("target 不能为空");
        }
        String resourceType = require(target.resourceType(), "target.resource_type");
        String addressId = require(target.addressId(), "target.address_id");
        // eventType 从 playbook 已订阅的事件里推导,保证落在 SoarService 的校验范围内。
        String eventType = resolveEventType(playbookId, resourceType);
        Map<String, Object> payload = targetPayload(target, resourceType, addressId);
        SoarExecution execution =
                service.triggerExecution(
                        playbookId,
                        trimToNull(idempotencyKey),
                        resourceType,
                        addressId,
                        eventType,
                        payload,
                        ACTOR);
        return ResponseEntity.ok(ExecutionResponse.from(execution));
    }

    @GetMapping("/executions/{executionId}")
    public ExecutionResponse get(@PathVariable String executionId) {
        return ExecutionResponse.from(service.getExecution(executionId));
    }

    /** 只传资源引用(provider/resource_type/address_id),不含任何机密。 */
    private Map<String, Object> targetPayload(
            Target target, String resourceType, String addressId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (target.provider() != null && !target.provider().isBlank()) {
            payload.put("provider", target.provider().trim());
        }
        payload.put("resource_type", resourceType);
        payload.put("address_id", addressId);
        return payload;
    }

    /**
     * 从 playbook 已配置的 eventTypes 中挑选一个:优先与 resource_type 同前缀的事件, 否则取第一个;两者都没有时退回 {@code
     * <resource_type>.created}。这样只要 playbook 订阅了该资源类型的事件就能触发,而不必硬编码某个事件名。
     */
    private String resolveEventType(String playbookId, String resourceType) {
        SoarPlaybook playbook = service.getPlaybook(playbookId);
        String type = resourceType.trim().toLowerCase();
        List<String> events = playbook.eventTypes();
        if (events != null) {
            String prefixed =
                    events.stream()
                            .filter(Objects::nonNull)
                            .filter(event -> event.startsWith(type + "."))
                            .findFirst()
                            .orElse(null);
            if (prefixed != null) {
                return prefixed;
            }
            String any = events.stream().filter(Objects::nonNull).findFirst().orElse(null);
            if (any != null) {
                return any;
            }
        }
        return type + ".created";
    }

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value.trim();
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }

    private static String trimToNull(String value) {
        String trimmed = trim(value);
        return trimmed == null || trimmed.isEmpty() ? null : trimmed;
    }

    private static String truncate(String value) {
        return value.length() <= MAX_ERROR_CHARS ? value : value.substring(0, MAX_ERROR_CHARS);
    }

    public record ExecutionRequest(
            @JsonProperty("action_key") String actionKey,
            @JsonProperty("playbook_id") String playbookId,
            Target target) {}

    public record Target(
            String provider,
            @JsonProperty("resource_type") String resourceType,
            @JsonProperty("address_id") String addressId) {}

    public record ExecutionResponse(
            @JsonProperty("execution_id") String executionId,
            String status,
            Map<String, Object> result,
            @JsonProperty("error_code") String errorCode,
            @JsonProperty("error_message") String errorMessage) {

        static ExecutionResponse from(SoarExecution execution) {
            String code = "failed".equals(execution.status()) ? "EXECUTION_FAILED" : null;
            String message =
                    execution.error() == null || execution.error().isBlank()
                            ? null
                            : truncate(execution.error());
            return new ExecutionResponse(
                    execution.id(), execution.status(), Map.of(), code, message);
        }
    }
}
