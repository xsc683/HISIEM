package com.xscsiem.hsiem_platform.investigation;

import com.xscsiem.hsiem_platform.alert.AlertService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 调查台案件(story-07)校验逻辑(纯逻辑,不触 ES):
 * 手动聚合 ≥2 条、结案必带 verdict、状态/verdict 枚举。
 */
class CaseServiceTest {

    private CaseService svc;

    @BeforeEach
    void setUp() {
        // ES 地址无效,但校验在请求前抛出,故可安全测试
        svc = new CaseService("http://127.0.0.1:1", new AlertService("http://127.0.0.1:1"));
    }

    @Test
    void create_lessThanTwoAlerts_rejected() {
        assertThrows(IllegalArgumentException.class,
                () -> svc.create(List.of("a"), "t", "alice"));
    }

    @Test
    void create_nullAlertIds_rejected() {
        assertThrows(IllegalArgumentException.class,
                () -> svc.create(null, "t", "alice"));
    }

    @Test
    void updateStatus_invalidStatus_rejected() {
        assertThrows(IllegalArgumentException.class,
                () -> svc.updateStatus("x", "bogus", null, "alice"));
    }

    @Test
    void resolveWithoutVerdict_rejected() {
        assertThrows(IllegalArgumentException.class,
                () -> svc.updateStatus("x", "resolved", null, "alice"));
        assertThrows(IllegalArgumentException.class,
                () -> svc.updateStatus("x", "resolved", "yes", "alice"));
    }

    @Test
    void statusTransition_rejectsReverseMove() {
        assertThrows(IllegalArgumentException.class,
                () -> CaseService.validateStatusTransition("investigating", "open"));
    }

    @Test
    void statusTransition_allowsResolveAndReopen() {
        assertDoesNotThrow(() -> CaseService.validateStatusTransition("open", "investigating"));
        assertDoesNotThrow(() -> CaseService.validateStatusTransition("investigating", "resolved"));
        assertDoesNotThrow(() -> CaseService.validateStatusTransition("resolved", "open"));
    }

    @Test
    void alertJoin_rejectsAlreadyAssignedAlert() {
        assertDoesNotThrow(() -> CaseService.validateAlertCanJoinCase("open", null, "a"));
        assertThrows(IllegalArgumentException.class,
                () -> CaseService.validateAlertCanJoinCase("open", "case-1", "a"));
        assertThrows(IllegalArgumentException.class,
                () -> CaseService.validateAlertCanJoinCase("closed", null, "a"));
    }
}
