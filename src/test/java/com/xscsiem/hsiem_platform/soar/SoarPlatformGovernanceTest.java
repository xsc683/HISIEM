package com.xscsiem.hsiem_platform.soar;

import com.xscsiem.hsiem_platform.tenant.TenantService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Transactional
class SoarPlatformGovernanceTest {

    @Autowired
    private SoarPlaybookCatalog catalog;

    @Autowired
    private TenantService tenants;

    @Autowired
    private SoarConnectorGuard guard;

    @Test
    void revisionRequiresFourEyesAndRoutesStableCanaryTraffic() {
        SoarPlaybook current = catalog.resolve("default", "alert-high-risk-triage", "baseline");
        SoarPlaybook candidate = new SoarPlaybook(current.formatVersion(), current.id(), current.name(),
                current.description(), "2.1.0", current.enabled(), current.resourceTypes(), current.when(),
                current.entrypoint(), current.defaults(), current.triggers(), current.nodes(), current.steps());
        SoarPlaybookRevision draft = catalog.createDraft("default", candidate,
                Map.of("risk-gate", Map.of("x", 120, "y", 80)), "author");
        catalog.submit("default", candidate.id(), draft.revision(), "author");
        assertThrows(RuntimeException.class, () -> catalog.review("default", candidate.id(),
                draft.revision(), true, "self approval", "author"));
        catalog.review("default", candidate.id(), draft.revision(), true, "reviewed", "reviewer");
        catalog.publish("default", candidate.id(), draft.revision(), 25, "release-manager");

        List<SoarPlaybookRevision> active = catalog.revisions("default", candidate.id()).stream()
                .filter(item -> "published".equals(item.state())).toList();
        assertEquals(2, active.size());
        assertEquals(100, active.stream().mapToInt(SoarPlaybookRevision::rolloutPercentage).sum());
        HashSet<String> selected = new HashSet<>();
        for (int index = 0; index < 500; index++) {
            selected.add(catalog.resolve("default", candidate.id(), "resource-" + index).version());
        }
        assertEquals(java.util.Set.of(current.version(), "2.1.0"), selected);
    }

    @Test
    void tenantMembershipCannotBeSelectedByHeaderAlone() {
        tenants.create("blue-team", "Blue Team", "admin");
        assertEquals("blue-team", tenants.requireMembership("admin", "blue-team"));
        assertThrows(RuntimeException.class, () -> tenants.requireMembership("missing-user", "blue-team"));
    }

    @Test
    void connectorGuardEnforcesDistributedQuotaAndCircuitBreaker() {
        SoarConnector limited = connector("quota-test", new SoarConnector.Limits(2, 10, 2, 5, 60));
        SoarConnectorGuard.Permit first = guard.acquire("default", limited, Duration.ofSeconds(2));
        guard.success(first, "lookup", "exec-1", 1);
        SoarConnectorGuard.Permit second = guard.acquire("default", limited, Duration.ofSeconds(2));
        guard.success(second, "lookup", "exec-2", 1);
        SoarConnectorGuard.ConnectorRejectedException quota = assertThrows(
                SoarConnectorGuard.ConnectorRejectedException.class,
                () -> guard.acquire("default", limited, Duration.ofSeconds(2)));
        assertEquals("RATE_LIMIT", quota.code());

        SoarConnector breaker = connector("breaker-test", new SoarConnector.Limits(100, 1000, 2, 2, 60));
        for (int index = 0; index < 2; index++) {
            SoarConnectorGuard.Permit permit = guard.acquire("default", breaker, Duration.ofSeconds(2));
            guard.failure(permit, breaker, "lookup", "exec-f" + index, 1, "IO_ERROR");
        }
        SoarConnectorGuard.ConnectorRejectedException open = assertThrows(
                SoarConnectorGuard.ConnectorRejectedException.class,
                () -> guard.acquire("default", breaker, Duration.ofSeconds(2)));
        assertEquals("CIRCUIT_OPEN", open.code());
        assertTrue(guard.status("default").stream()
                .anyMatch(row -> "breaker-test".equals(row.get("connectorId"))));
    }

    private static SoarConnector connector(String id, SoarConnector.Limits limits) {
        return new SoarConnector(id, id, "test", true, "https://example.com", null,
                false, false, new SoarConnector.Auth("none", null, null, null),
                null, limits, Map.of("lookup", new SoarConnector.Action("GET", "/", 2,
                List.of(), 4096)));
    }
}
