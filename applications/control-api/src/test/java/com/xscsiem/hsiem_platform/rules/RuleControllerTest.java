package com.xscsiem.hsiem_platform.rules;

import com.xscsiem.hsiem_platform.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 自定义检测规则写 API：操作者透传、201 语义和待部署标记。 */
class RuleControllerTest {

    private RuleService service;
    private RuleController controller;

    @BeforeEach
    void setUp() {
        service = mock(RuleService.class);
        controller = new RuleController(service);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("rule-admin", "n/a"));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void create_marksYamlAsPendingDeployment_andUsesAuthenticatedActor() {
        Map<String, Object> request = new LinkedHashMap<>(Map.of("id", "rule-ui-api"));
        Map<String, Object> saved = new LinkedHashMap<>(request);
        when(service.create(eq(request), eq("rule-admin"))).thenReturn(saved);

        var response = controller.create(request);

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertTrue(Boolean.TRUE.equals(response.getBody().get("redeployRequired")));
        assertEquals(false, response.getBody().get("deployed"));
        verify(service).create(request, "rule-admin");
    }

    @Test
    void update_keepsStablePathId_andMarksPendingDeployment() {
        Map<String, Object> request = new LinkedHashMap<>(Map.of("id", "rule-ui-api"));
        when(service.update(eq("rule-ui-api"), eq(request), eq("rule-admin")))
                .thenReturn(new LinkedHashMap<>(request));

        Map<String, Object> response = controller.update("rule-ui-api", request);

        assertEquals("rule-ui-api", response.get("id"));
        assertEquals(true, response.get("redeployRequired"));
        verify(service).update("rule-ui-api", request, "rule-admin");
    }

    @Test
    void bulkDeployOnlyWritesDesiredStateAndDoesNotCallPhysicalDeployer() {
        ManagedDetectionService managed = mock(ManagedDetectionService.class);
        controller = new RuleController(service, managed);
        when(service.list()).thenReturn(List.of(Map.of("id", "rule-ui-api", "enabled", true)));
        when(managed.deployAll("default",
                List.of(Map.of("id", "rule-ui-api", "enabled", true)), "rule-admin"))
                .thenReturn(List.of(Map.of("ruleKey", "rule-ui-api", "status", "PENDING")));
        TenantContext.set("default");

        var response = controller.deploy();

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertEquals("PENDING", response.getBody().get("status"));
        assertEquals(List.of(Map.of("ruleKey", "rule-ui-api", "status", "PENDING")),
                response.getBody().get("pendingSummaries"));
        verify(managed).deployAll("default",
                List.of(Map.of("id", "rule-ui-api", "enabled", true)), "rule-admin");
    }

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }
}
