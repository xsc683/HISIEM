package com.xscsiem.hsiem_platform.detection.controller;

import com.xscsiem.hsiem_platform.detection.runtime.DetectionGroupLease;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DetectionControllerWorkerTest {

    @Test
    void claimsNextLeaseOnlyAfterCurrentLeaseCompletes() throws Exception {
        DetectionControllerRepository repository = mock(DetectionControllerRepository.class);
        DetectionReconciler reconciler = mock(DetectionReconciler.class);
        ControllerPollState pollState = new ControllerPollState();
        ScheduledExecutorService heartbeatExecutor = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> heartbeat = mock(ScheduledFuture.class);
        doReturn(heartbeat).when(heartbeatExecutor).scheduleAtFixedRate(any(Runnable.class), anyLong(),
                anyLong(), any(TimeUnit.class));

        DetectionGroupLease first = lease("group-a", 1L);
        DetectionGroupLease second = lease("group-b", 1L);
        AtomicInteger claimCalls = new AtomicInteger();
        when(repository.claimDue(eq("worker-a"), eq(Duration.ofSeconds(30)), eq(1)))
                .thenAnswer(invocation -> switch (claimCalls.incrementAndGet()) {
                    case 1 -> List.of(first);
                    case 2 -> List.of(second);
                    default -> List.of();
                });

        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch allowFirstToFinish = new CountDownLatch(1);
        doAnswer(invocation -> {
            DetectionGroupLease current = invocation.getArgument(0, DetectionGroupLease.class);
            if (first.equals(current)) {
                firstStarted.countDown();
                assertTrue(allowFirstToFinish.await(5, TimeUnit.SECONDS));
            }
            return null;
        }).when(reconciler).reconcile(any(DetectionGroupLease.class), any(BooleanSupplier.class));

        DetectionControllerWorker worker = new DetectionControllerWorker(repository, reconciler,
                pollState, "worker-a", Duration.ofSeconds(30), 2, heartbeatExecutor);
        ExecutorService runner = Executors.newSingleThreadExecutor();
        try {
            var completion = runner.submit(worker::runOnce);
            assertTrue(firstStarted.await(5, TimeUnit.SECONDS));
            assertEquals(1, claimCalls.get(), "the next lease must not be pre-claimed");

            allowFirstToFinish.countDown();
            completion.get(5, TimeUnit.SECONDS);

            assertEquals(2, claimCalls.get());
            verify(repository, times(2)).claimDue("worker-a", Duration.ofSeconds(30), 1);
        } finally {
            allowFirstToFinish.countDown();
            runner.shutdownNow();
            worker.shutdown();
        }
    }

    private DetectionGroupLease lease(String group, long generation) {
        return new DetectionGroupLease("tenant-a", group, "cluster-a", generation,
                "{}", "hash-" + group, "worker-a", generation, 1);
    }
}
