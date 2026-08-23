package com.xscsiem.hsiem_platform.soar;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 8 个模拟实例竞争 1000 个数据库租约，验证无重复领取和队列收敛。 */
@SpringBootTest
class SoarHorizontalClaimLoadTest {

    @Autowired
    private SoarExecutionStore store;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void eightWorkersClaimThousandExecutionsExactlyOnce() throws Exception {
        String prefix = "soar-load-" + java.util.UUID.randomUUID() + "-";
        SoarPlaybook playbook = new SoarPlaybook("load-flow", "load", "", "1.0", true,
                List.of("alert"), null, List.of(new SoarPlaybook.Step(
                "noop", "noop", "context.set", Map.of("values", Map.of("ok", true)), null)));
        Instant now = Instant.now();
        for (int index = 0; index < 1000; index++) {
            String id = prefix + index;
            Map<String, Object> context = Map.of("executionId", id, "tenantId", "default");
            assertTrue(store.create(new SoarExecution(id, playbook.id(), playbook.version(),
                    "alert", "load-resource", "queued", "load-test", 0, "noop", List.of("noop"),
                    playbook, context, "load", null, null, null, null, null, now, null, null,
                    false, false, 0, now, now, null, 0, "default", null, null, List.of())));
        }

        ExecutorService workers = Executors.newFixedThreadPool(8);
        CountDownLatch finished = new CountDownLatch(8);
        Set<String> claimedIds = ConcurrentHashMap.newKeySet();
        AtomicInteger duplicateClaims = new AtomicInteger();
        try {
            for (int worker = 0; worker < 8; worker++) {
                String owner = "load-worker-" + worker;
                workers.submit(() -> {
                    try {
                        while (true) {
                            Instant claimAt = Instant.now();
                            SoarExecution execution = store.claimNext(owner, claimAt, claimAt.plusSeconds(30));
                            if (execution == null) return;
                            if (!execution.id().startsWith(prefix)) {
                                store.release(execution.id(), owner, execution.frontier(), execution.currentNode(),
                                        execution.context(), execution.nodesExecuted(), claimAt, "load-test-skip");
                                continue;
                            }
                            if (!claimedIds.add(execution.id())) duplicateClaims.incrementAndGet();
                            store.finishExecution(execution.id(), owner, "succeeded", null,
                                    execution.context(), execution.nodesExecuted());
                        }
                    } finally {
                        finished.countDown();
                    }
                });
            }
            assertTrue(finished.await(30, TimeUnit.SECONDS), "1000 个租约应在 30 秒内收敛");
            assertEquals(1000, claimedIds.size());
            assertEquals(0, duplicateClaims.get());
            Integer succeeded = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM soar_executions WHERE id LIKE ? AND status = 'succeeded'
                    """, Integer.class, prefix + "%");
            assertEquals(1000, succeeded);
        } finally {
            workers.shutdownNow();
            jdbc.update("DELETE FROM soar_executions WHERE id LIKE ?", prefix + "%");
        }
    }
}
