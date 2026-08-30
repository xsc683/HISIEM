package com.xscsiem.hsiem_platform.soar;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Makes internal business actions idempotent across lease expiry and node retries.
 * The receipt participates in the same PostgreSQL transaction as control-plane writes.
 */
@Service
public class SoarBusinessActionInvocation {

    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() { };

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final SoarBusinessActionExecutor executor;

    public SoarBusinessActionInvocation(JdbcTemplate jdbc, ObjectMapper objectMapper,
                                        SoarBusinessActionExecutor executor) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.executor = executor;
    }

    @Transactional
    public Map<String, Object> execute(SoarExecutionContext context, String action,
                                       Map<String, Object> parameters) {
        String key = context.nodeRun().idempotencyKey();
        List<String> existing = jdbc.queryForList(
                "SELECT result_json FROM soar_action_receipt WHERE idempotency_key = ?",
                String.class, key);
        if (!existing.isEmpty()) return map(existing.getFirst());

        Map<String, Object> output = executor.execute(context.execution(), action, parameters);
        jdbc.update("INSERT INTO soar_action_receipt (idempotency_key, tenant_id, execution_id, "
                        + "node_id, action_id, result_json) VALUES (?, ?, ?, ?, ?, ?)",
                key, context.execution().tenantId(), context.execution().id(), context.node().id(),
                action, json(output));
        return output;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("SOAR 业务动作结果无法序列化", e);
        }
    }

    private Map<String, Object> map(String value) {
        try {
            return objectMapper.readValue(value, OBJECT_MAP);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("SOAR 业务动作回执格式错误", e);
        }
    }
}
