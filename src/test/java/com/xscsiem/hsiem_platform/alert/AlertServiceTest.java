package com.xscsiem.hsiem_platform.alert;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 告警三线(story-04)校验逻辑(纯逻辑,不触 ES):
 * 状态/verdict 枚举校验、批量 close 前置 verdict、空 ids。
 */
class AlertServiceTest {

    private AlertService svc;

    @BeforeEach
    void setUp() {
        // ES 地址无效,但校验在请求前抛出,故可安全测试
        svc = new AlertService("http://127.0.0.1:1");
    }

    @Test
    void update_invalidStatus_rejected() {
        assertThrows(IllegalArgumentException.class,
                () -> svc.update("x", "bogus", null, "alice"));
        assertThrows(IllegalArgumentException.class,
                () -> svc.update("x", null, "TP", "alice"));
    }

    @Test
    void update_neitherStatusNorVerdict_rejected() {
        assertThrows(IllegalArgumentException.class,
                () -> svc.update("x", null, null, "alice"));
    }

    @Test
    void batch_emptyIds_rejected() {
        assertThrows(IllegalArgumentException.class,
                () -> svc.batch(List.of(), "open", null, "alice"));
        assertThrows(IllegalArgumentException.class,
                () -> svc.batch(null, "open", null, "alice"));
    }

    @Test
    void batch_invalidVerdict_rejected() {
        assertThrows(IllegalArgumentException.class,
                () -> svc.batch(List.of("a"), "acknowledged", "yes", "alice"));
    }

    @Test
    void statusTransition_rejectsReverseMoveAndClosingWithoutVerdict() {
        assertThrows(IllegalArgumentException.class,
                () -> AlertService.validateStatusTransition("acknowledged", "open", null, null));
        assertThrows(IllegalArgumentException.class,
                () -> AlertService.validateStatusTransition("investigating", "closed", null, null));
    }

    @Test
    void statusTransition_allowsTriageAndReopenPaths() {
        assertDoesNotThrow(() -> AlertService.validateStatusTransition(
                "open", "acknowledged", null, null));
        assertDoesNotThrow(() -> AlertService.validateStatusTransition(
                "open", "closed", null, "false_positive"));
        assertDoesNotThrow(() -> AlertService.validateStatusTransition(
                "closed", "open", "false_positive", null));
    }
}
