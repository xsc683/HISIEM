package com.xscsiem.hsiem_platform.soar.device;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xscsiem.hsiem_platform.soar.SoarExecutionContext;
import com.xscsiem.hsiem_platform.soar.persistence.SoarMapper;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Executes connector actions once per durable node visit. */
@Service
public class SoarConnectorActionInvocation {

    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() {};
    private final SoarMapper mapper;
    private final ObjectMapper objectMapper;
    private final SoarConnectorRegistry registry;

    public SoarConnectorActionInvocation(
            SoarMapper mapper, ObjectMapper objectMapper, SoarConnectorRegistry registry) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
        this.registry = registry;
    }

    @Transactional
    public Map<String, Object> execute(
            SoarExecutionContext context,
            String runtimeKey,
            String action,
            Map<String, Object> parameters,
            java.time.Duration timeout) {
        String key = context.nodeRun().idempotencyKey();
        String existing = mapper.selectReceipt(key);
        if (existing != null) return read(existing);
        ConnectorResult result =
                registry.require(runtimeKey)
                        .execute(
                                new ConnectorInvocation(
                                        context.execution().tenantId(),
                                        context.execution().id(),
                                        context.node().id(),
                                        action,
                                        parameters,
                                        key,
                                        timeout));
        if (!result.success())
            throw new IllegalStateException("Connector 返回失败: " + runtimeKey + "/" + action);
        @SuppressWarnings("unchecked")
        Map<String, Object> sanitized =
                (Map<String, Object>) ConnectorAuditSanitizer.sanitize(result.output());
        Map<String, Object> output = new java.util.LinkedHashMap<>(sanitized);
        if (result.externalRequestId() != null && !result.externalRequestId().isBlank()) {
            output.put("externalRequestId", result.externalRequestId());
        }
        mapper.insertReceipt(
                key,
                context.execution().tenantId(),
                context.execution().id(),
                context.node().id(),
                runtimeKey + ":" + action,
                json(output));
        return output;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Connector 结果无法序列化", e);
        }
    }

    private Map<String, Object> read(String value) {
        try {
            return objectMapper.readValue(value, OBJECT_MAP);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Connector 回执格式错误", e);
        }
    }
}
