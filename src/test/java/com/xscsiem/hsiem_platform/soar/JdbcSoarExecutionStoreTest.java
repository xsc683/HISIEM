package com.xscsiem.hsiem_platform.soar;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Transactional
class JdbcSoarExecutionStoreTest {

    @Autowired
    private SoarExecutionStore store;

    @Test
    void persistsStepsAndResolvesApprovalOnce() {
        String id = "soar-test-" + UUID.randomUUID();
        SoarPlaybook.Step approval = new SoarPlaybook.Step("approve-step", "审批", "approval",
                Map.of("requiredRole", "admin"), null);
        SoarPlaybook.Node approvalNode = new SoarPlaybook.Node(approval.id(), approval.name(),
                "approval", null, approval.parameters(), null, null, null,
                null, null, null, null, List.of());
        SoarPlaybook playbook = new SoarPlaybook("store-playbook", "Store Test", "", "1.0", true,
                List.of("alert"), null, List.of(approval));
        Instant now = Instant.now();
        store.create(new SoarExecution(id, playbook.id(), playbook.version(), "alert", "a-1",
                "queued", "alice", 0, playbook, Map.of("alertId", "a-1"), null, null,
                null, null, now, now, null, 0, List.of()));

        Instant claimTime = Instant.now();
        assertEquals(id, store.claimNext("worker-1", claimTime, claimTime.plusSeconds(45)).id());
        store.startNode(id, 0, approvalNode, 1, approval.parameters());
        store.waitForApproval(id, "worker-1", approval.id(), "批准处置",
                List.of(approval.id()), Map.of("alertId", "a-1"), 1);
        assertEquals("waiting_approval", store.find(id).status());
        assertTrue(store.resolveApproval(id, approval.id(), true, "admin",
                List.of("end"), Map.of("alertId", "a-1"), true));
        assertEquals("queued", store.find(id).status());
        assertEquals(1, store.find(id).currentStep());
        assertEquals(List.of("end"), store.find(id).frontier());
        assertEquals("succeeded", store.listSteps(id).getFirst().status());
    }
}
