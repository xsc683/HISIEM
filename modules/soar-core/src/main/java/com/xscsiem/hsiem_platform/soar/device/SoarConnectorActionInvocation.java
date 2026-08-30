package com.xscsiem.hsiem_platform.soar.device;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xscsiem.hsiem_platform.soar.SoarExecutionContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/** Executes connector actions once per durable node visit. */
@Service
public class SoarConnectorActionInvocation {

    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() { };
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final SoarConnectorRegistry registry;

    public SoarConnectorActionInvocation(JdbcTemplate jdbc, ObjectMapper objectMapper,
                                         SoarConnectorRegistry registry) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.registry = registry;
    }

    @Transactional
    public Map<String, Object> execute(SoarExecutionContext context, String runtimeKey,
                                       String action, Map<String, Object> parameters,
                                       java.time.Duration timeout) {
        String key = context.nodeRun().idempotencyKey();
        List<String> existing = jdbc.queryForList(
                "SELECT result_json FROM soar_action_receipt WHERE idempotency_key = ?", String.class, key);
        if (!existing.isEmpty()) return read(existing.getFirst());
        ConnectorResult result = registry.require(runtimeKey).execute(new ConnectorInvocation(
                context.execution().tenantId(), context.execution().id(), context.node().id(), action,
                parameters, key, timeout));
        if (!result.success()) throw new IllegalStateException("Connector 返回失败: " + runtimeKey + "/" + action);
        @SuppressWarnings("unchecked")
        Map<String, Object> sanitized = (Map<String, Object>) ConnectorAuditSanitizer.sanitize(result.output());
        Map<String, Object> output = new LinkedHashMap<>(sanitized);
        if (result.externalRequestId() != null && !result.externalRequestId().isBlank()) {
            output.put("externalRequestId", result.externalRequestId());
        }
        jdbc.update("INSERT INTO soar_action_receipt (idempotency_key, tenant_id, execution_id, "
                        + "node_id, action_id, result_json) VALUES (?, ?, ?, ?, ?, ?)",
                key, context.execution().tenantId(), context.execution().id(), context.node().id(),
                runtimeKey + ":" + action, json(output));
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
