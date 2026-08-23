package com.xscsiem.hsiem_platform.soar;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SoarWorkerTest {

    @Test
    void renewsLeaseWhileNodeHandlerIsStillRunning() {
        SoarStore store = mock(SoarStore.class);
        SoarExecutionEngine engine = mock(SoarExecutionEngine.class);
        SoarExecution execution = mock(SoarExecution.class);
        Duration lease = Duration.ofMillis(120);
        when(execution.id()).thenReturn("exec-slow");
        when(execution.leaseOwner()).thenReturn("worker-test");
        when(execution.fencingToken()).thenReturn(7L);
        when(store.claimDue(anyString(), eq(lease), eq(1)))
                .thenReturn(List.of(execution), List.of());
        when(store.renewLease(execution, lease)).thenReturn(true);
        doAnswer(invocation -> {
            Thread.sleep(220);
            return null;
        }).when(engine).process(execution);

        SoarWorker worker = new SoarWorker(store, engine, lease, 1);
        try {
            worker.poll();
            verify(store, atLeastOnce()).renewLease(execution, lease);
        } finally {
            worker.close();
        }
    }
}
