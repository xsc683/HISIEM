package com.siem;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 正负夹具:事件动作判定(CEP 条件)与 OCSF severity 映射。
 */
public class EventConditionsTest {

    private static Event event(String action) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("event.action", action);
        fields.put("source.ip", "1.2.3.4");
        fields.put("user.name", "alice");
        return new Event("{}", fields, 0L);
    }

    @Test
    void failurePredicateMatchesOnlyAuthFailure() {
        // 正样本:认证失败命中
        assertTrue(EventConditions.isAuthenticationFailure(event("authentication_failure")));
        // 负样本:成功登录/其他动作不命中
        assertFalse(EventConditions.isAuthenticationFailure(event("authentication_success")));
        assertFalse(EventConditions.isAuthenticationFailure(event("other")));
    }

    @Test
    void successPredicateMatchesOnlyAuthSuccess() {
        // 正样本:成功登录命中(CEP 攻击链的"随后成功"条件)
        assertTrue(EventConditions.isAuthenticationSuccess(event("authentication_success")));
        // 负样本
        assertFalse(EventConditions.isAuthenticationSuccess(event("authentication_failure")));
        assertFalse(EventConditions.isAuthenticationSuccess(event("other")));
    }

    @Test
    void ocsfSeverityMapping() {
        assertEquals(1, Ocsf.severityId("info"));
        assertEquals(2, Ocsf.severityId("low"));
        assertEquals(3, Ocsf.severityId("medium"));
        assertEquals(4, Ocsf.severityId("high"));
        assertEquals(5, Ocsf.severityId("critical"));
        assertEquals(0, Ocsf.severityId("unknown"));
        assertEquals(0, Ocsf.severityId(null));
    }
}
