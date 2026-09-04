package com.xscsiem.hsiem_platform.investigation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xscsiem.hsiem_platform.alert.AlertService;
import com.xscsiem.hsiem_platform.control.CaseStore;
import com.xscsiem.hsiem_platform.onboarding.ConflictException;
import com.xscsiem.hsiem_platform.search.ElasticsearchGateway;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 调查台案件(story-07)校验逻辑(纯逻辑,不触 ES): 手动聚合 ≥2 条、结案必带 verdict、状态/verdict 枚举。 */
class CaseServiceTest {

    private CaseService svc;

    @BeforeEach
    void setUp() {
        // ES 地址无效,但校验在请求前抛出,故可安全测试
        svc = new CaseService("http://127.0.0.1:1", new AlertService("http://127.0.0.1:1"));
    }

    @Test
    void create_lessThanTwoAlerts_rejected() {
        assertThrows(IllegalArgumentException.class, () -> svc.create(List.of("a"), "t", "alice"));
    }

    @Test
    void create_nullAlertIds_rejected() {
        assertThrows(IllegalArgumentException.class, () -> svc.create(null, "t", "alice"));
    }

    @Test
    void updateStatus_invalidStatus_rejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> svc.updateStatus("x", "bogus", null, "alice"));
    }

    @Test
    void list_invalidStatus_rejected() {
        assertThrows(IllegalArgumentException.class, () -> svc.list("bogus", null, 50));
    }

    @Test
    void list_outOfRangeSize_rejected() {
        assertThrows(IllegalArgumentException.class, () -> svc.list(null, null, 0));
        assertThrows(IllegalArgumentException.class, () -> svc.list(null, null, 201));
        assertThrows(IllegalArgumentException.class, () -> svc.list(null, null, -5));
    }

    @Test
    void resolveWithoutVerdict_rejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> svc.updateStatus("x", "resolved", null, "alice"));
        assertThrows(
                IllegalArgumentException.class,
                () -> svc.updateStatus("x", "resolved", "yes", "alice"));
    }

    @Test
    void statusTransition_rejectsReverseMove() {
        assertThrows(
                IllegalArgumentException.class,
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
        assertThrows(
                IllegalArgumentException.class,
                () -> CaseService.validateAlertCanJoinCase("open", "case-1", "a"));
        assertThrows(
                IllegalArgumentException.class,
                () -> CaseService.validateAlertCanJoinCase("closed", null, "a"));
    }

    @Test
    void summary_usesDatabaseFullCountsAndRecentRows() {
        CaseStore control = mock(CaseStore.class);
        Map<String, Object> newest = Map.of("case.id", "case-new", "case.status", "open");
        when(control.listCases(null, null, 7)).thenReturn(List.of(newest));
        when(control.caseStatusCounts())
                .thenReturn(Map.of("open", 4L, "investigating", 3L, "resolved", 13L));
        CaseService service =
                new CaseService(
                        "http://unused",
                        mock(AlertService.class),
                        control,
                        mock(ElasticsearchGateway.class));

        Map<String, Object> summary = service.summary();

        assertEquals(20L, summary.get("total"));
        assertEquals(List.of(newest), summary.get("recent"));
    }

    @Test
    void legacyEsList_buildsCleanObjectNodeDsl() {
        ElasticsearchGateway gateway = mock(ElasticsearchGateway.class);
        when(gateway.request(eq("POST"), eq("/siem-cases/_search"), any()))
                .thenReturn(
                        new ElasticsearchGateway.Response(
                                200, Map.of("hits", Map.of("hits", List.of()))));
        // control=null + gateway 的构造走 legacy ES 查询路径(数据面先建案、控制面尚未启用时的兼容回退)。
        CaseService service =
                new CaseService("http://unused", mock(AlertService.class), null, gateway);

        service.list("open", "ip:10.0.0.1", 50);

        var captor = ArgumentCaptor.forClass(String.class);
        verify(gateway).request(eq("POST"), eq("/siem-cases/_search"), captor.capture());
        assertEquals(
                "{\"size\":50,\"query\":{\"bool\":{\"must\":[{\"term\":{\"case.status\":\"open\"}},"
                        + "{\"nested\":{\"path\":\"entities\",\"query\":{\"bool\":{\"must\":"
                        + "[{\"term\":{\"entities.type\":\"ip\"}},{\"term\":{\"entities.value\":\"10.0.0.1\"}}]}}}}]}},"
                        + "\"sort\":[{\"case.updated_at\":{\"order\":\"desc\"}}]}",
                captor.getValue());
    }

    @Test
    void updateStatus_writesOnlyPgAndOutbox_noSyncEsMirror() {
        CaseStore control = mock(CaseStore.class);
        ElasticsearchGateway gateway = mock(ElasticsearchGateway.class);
        Map<String, Object> current = new LinkedHashMap<>();
        current.put("case.id", "case-9");
        current.put("case.status", "open");
        current.put("_control_version", 3L);
        current.put("alert_ids", List.of());
        when(control.findCase("case-9")).thenReturn(current);
        Map<String, Object> updated = new LinkedHashMap<>(current);
        updated.put("case.status", "investigating");
        when(control.updateCase(eq("case-9"), eq(3L), any(), any())).thenReturn(updated);
        CaseService service =
                new CaseService("http://unused", mock(AlertService.class), control, gateway);

        Map<String, Object> result = service.updateStatus("case-9", "investigating", null, "alice");

        assertEquals("investigating", result.get("case.status"));
        // 正常写路径唯一 ES 镜像机制 = store 事务内 outbox;不再有任何同步 ES 调用。
        verify(control).updateCase(eq("case-9"), eq(3L), any(), any());
        verify(control, never()).enqueueCaseMirror(any(), any(), any());
        verify(gateway, never()).request(any(), any(), any());
    }

    @Test
    void updateStatus_versionConflict_surfacesAsConcurrentUpdate() {
        CaseStore control = mock(CaseStore.class);
        ElasticsearchGateway gateway = mock(ElasticsearchGateway.class);
        Map<String, Object> current = new LinkedHashMap<>();
        current.put("case.id", "case-9");
        current.put("case.status", "open");
        current.put("_control_version", 3L);
        current.put("alert_ids", List.of());
        when(control.findCase("case-9")).thenReturn(current);
        when(control.updateCase(eq("case-9"), eq(3L), any(), any()))
                .thenThrow(new IllegalStateException("版本冲突"));
        CaseService service =
                new CaseService("http://unused", mock(AlertService.class), control, gateway);

        // PG _control_version 乐观锁冲突映射回 409 语义(ConflictException)。
        assertThrows(
                ConflictException.class,
                () -> service.updateStatus("case-9", "investigating", null, "alice"));
        verify(gateway, never()).request(any(), any(), any());
    }
}
