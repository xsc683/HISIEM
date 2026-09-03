package com.xscsiem.hsiem_platform.soar;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xscsiem.hsiem_platform.soar.persistence.SoarMapper;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Makes internal business actions idempotent across lease expiry and node retries. The receipt
 * participates in the same PostgreSQL transaction as control-plane writes.
 */
@Service
public class SoarBusinessActionInvocation {

    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() {};

    private final SoarMapper mapper;
    private final ObjectMapper objectMapper;
    private final SoarBusinessActionExecutor executor;

    public SoarBusinessActionInvocation(
            SoarMapper mapper, ObjectMapper objectMapper, SoarBusinessActionExecutor executor) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
        this.executor = executor;
    }

    @Transactional
    public Map<String, Object> execute(
            SoarExecutionContext context, String action, Map<String, Object> parameters) {
        String key = context.nodeRun().idempotencyKey();
        String existing = mapper.selectReceipt(key);
        if (existing != null) return map(existing);

        Map<String, Object> output = executor.execute(context.execution(), action, parameters);
        mapper.insertReceipt(
                key,
                context.execution().tenantId(),
                context.execution().id(),
                context.node().id(),
                action,
                json(output));
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
