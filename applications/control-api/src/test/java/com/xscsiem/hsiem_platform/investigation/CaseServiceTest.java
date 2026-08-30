package com.xscsiem.hsiem_platform.investigation;

import com.xscsiem.hsiem_platform.alert.AlertService;
import com.xscsiem.hsiem_platform.control.ControlPlaneStore;
import com.xscsiem.hsiem_platform.search.ElasticsearchGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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

    @Test
    void summary_usesDatabaseFullCountsAndRecentRows() {
        ControlPlaneStore control = mock(ControlPlaneStore.class);
        Map<String, Object> newest = Map.of("case.id", "case-new", "case.status", "open");
        when(control.listCases(null, null, 7)).thenReturn(List.of(newest));
        when(control.caseStatusCounts()).thenReturn(Map.of("open", 4L, "investigating", 3L, "resolved", 13L));
        CaseService service = new CaseService("http://unused", mock(AlertService.class), control,
                mock(ElasticsearchGateway.class));

        Map<String, Object> summary = service.summary();

        assertEquals(20L, summary.get("total"));
        assertEquals(List.of(newest), summary.get("recent"));
    }
}
