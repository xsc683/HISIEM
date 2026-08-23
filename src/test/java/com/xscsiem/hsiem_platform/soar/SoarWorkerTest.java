package com.xscsiem.hsiem_platform.soar;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SoarWorkerTest {

    @Test
    void pollDispatchesMultipleExecutionsUpToConfiguredConcurrency() throws Exception {
        SoarExecutionStore store = mock(SoarExecutionStore.class);
        SoarEngine engine = mock(SoarEngine.class);
        SoarExecution first = mock(SoarExecution.class);
        SoarExecution second = mock(SoarExecution.class);
        when(store.claimNext(anyString(), any(), any())).thenReturn(first, second);
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            started.countDown();
            release.await(2, TimeUnit.SECONDS);
            return null;
        }).when(engine).process(any(), anyString());
        SoarWorker worker = new SoarWorker(store, engine, Duration.ofSeconds(45), true, 10, 2);
        try {
            worker.poll();
            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertEquals(2, worker.inFlight());
            release.countDown();
            for (int index = 0; index < 20 && worker.inFlight() != 0; index++) {
                Thread.sleep(25);
            }
            assertEquals(0, worker.inFlight());
        } finally {
            release.countDown();
            worker.close();
        }
    }
}
