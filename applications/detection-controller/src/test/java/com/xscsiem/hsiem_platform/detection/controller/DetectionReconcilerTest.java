package com.xscsiem.hsiem_platform.detection.controller;

import com.xscsiem.hsiem_platform.detection.runtime.DetectionGroupLease;
import com.xscsiem.hsiem_platform.detection.runtime.DetectionRuntimeTarget;
import com.xscsiem.hsiem_platform.detection.runtime.FlinkRuntimePort;
import com.xscsiem.hsiem_platform.detection.runtime.RuntimeObservation;
import com.xscsiem.hsiem_platform.rules.runtime.DetectionRuntimeService;
import com.xscsiem.hsiem_platform.rules.runtime.RuntimeJobState;
import com.xscsiem.hsiem_platform.rules.runtime.RuntimeManifest;
import com.xscsiem.hsiem_platform.rules.runtime.RuntimeManifestCodec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class DetectionReconcilerTest {

    private DetectionControllerRepository repository;
    private FlinkRuntimePort port;
    private DetectionRuntimeService runtime;
    private RuntimeManifestCodec codec;
    private DetectionReconciler reconciler;

    @BeforeEach
    void setUp() {
        repository = mock(DetectionControllerRepository.class);
        port = mock(FlinkRuntimePort.class);
        runtime = mock(DetectionRuntimeService.class);
        codec = new RuntimeManifestCodec();
        reconciler = new DetectionReconciler(repository, port, runtime, codec);
        when(repository.isCurrent(any())).thenReturn(true);
        when(repository.transitionPhase(any(), any())).thenReturn(true);
        when(repository.release(any())).thenReturn(true);
    }

    @Test
    void matchingRuntimeIsObservedWithoutApply() {
        RuntimeManifest expected = manifest(List.of(new RuntimeManifest.Member("rule-a", 1, "plan-a")));
        DetectionGroupLease lease = lease(expected);
        DetectionRuntimeTarget target = new DetectionRuntimeTarget(lease);
        when(port.inspect(target)).thenReturn(RuntimeObservation.running(target, "job-1", "key-1",
                List.of(new RuntimeObservation.Member("rule-a", 1, "plan-a"))));

        reconciler.reconcile(lease);

        verify(port).inspect(target);
        verify(port, never()).apply(any(), any());
        verify(port, never()).stop(any(), any());
        verify(runtime).observe(any(RuntimeManifest.class), any(RuntimeJobState.class), any(), any());
        verify(repository).release(lease);
    }

    @Test
    void missingOrOutdatedRuntimeIsAppliedAndVerified() {
        RuntimeManifest expected = manifest(List.of(new RuntimeManifest.Member("rule-a", 2, "plan-new")));
        DetectionGroupLease lease = lease(expected);
        DetectionRuntimeTarget target = new DetectionRuntimeTarget(lease);
        RuntimeObservation missing = new RuntimeObservation("RUNNING", target, "job-1", "key-1",
                expected.generation(), List.of(), null, null);
        RuntimeObservation verified = RuntimeObservation.running(target, "job-2", "key-2",
                List.of(new RuntimeObservation.Member("rule-a", 2, "plan-new")));
        when(port.inspect(target)).thenReturn(missing, verified);
        when(port.apply(target, missing)).thenReturn(verified);

        reconciler.reconcile(lease);

        verify(port).apply(target, missing);
        verify(port, org.mockito.Mockito.times(2)).inspect(target);
        verify(runtime).observe(any(RuntimeManifest.class), any(RuntimeJobState.class), any(), any());
        verify(repository).release(lease);
    }

    @Test
    void emptyExpectedRuntimeIsStoppedAndVerified() {
        RuntimeManifest expected = manifest(List.of());
        DetectionGroupLease lease = lease(expected);
        DetectionRuntimeTarget target = new DetectionRuntimeTarget(lease);
        RuntimeObservation running = RuntimeObservation.running(target, "job-1", "key-1",
                List.of(new RuntimeObservation.Member("rule-a", 1, "plan-a")));
        RuntimeObservation stopped = RuntimeObservation.stopped(target, null, null);
        when(port.inspect(target)).thenReturn(running, stopped);
        when(port.stop(target, running)).thenReturn(stopped);

        reconciler.reconcile(lease);

        verify(port).stop(target, running);
        verify(port, org.mockito.Mockito.times(2)).inspect(target);
        verify(runtime).observe(any(RuntimeManifest.class), any(RuntimeJobState.class), any(), any());
    }

    @Test
    void emptyExpectedAlreadyStoppedDoesNotCallStop() {
        RuntimeManifest expected = manifest(List.of());
        DetectionGroupLease lease = lease(expected);
        DetectionRuntimeTarget target = new DetectionRuntimeTarget(lease);
        RuntimeObservation stopped = RuntimeObservation.stopped(target, null, null);
        when(port.inspect(target)).thenReturn(stopped);

        reconciler.reconcile(lease);

        verify(port).inspect(target);
        verify(port, never()).stop(any(), any());
        verify(port, never()).apply(any(), any());
        verify(runtime).observe(any(RuntimeManifest.class), any(RuntimeJobState.class), any(), any());
        verify(repository).release(lease);
    }

    @Test
    void portFailureIsFencedIntoBackoff() {
        RuntimeManifest expected = manifest(List.of(new RuntimeManifest.Member("rule-a", 1, "plan-a")));
        DetectionGroupLease lease = lease(expected);
        when(port.inspect(any())).thenThrow(new IllegalStateException("adapter down"));

        assertDoesNotThrow(() -> reconciler.reconcile(lease));

        verify(repository).fail(eq(lease), any(Throwable.class));
        verify(runtime, never()).observe(any(RuntimeManifest.class), any(RuntimeJobState.class), any(), any());
    }

    @Test
    void generationChangeDuringApplyDoesNotObserveOldResult() {
        RuntimeManifest expected = manifest(List.of(new RuntimeManifest.Member("rule-a", 1, "plan-a")));
        DetectionGroupLease lease = lease(expected);
        DetectionRuntimeTarget target = new DetectionRuntimeTarget(lease);
        RuntimeObservation missing = new RuntimeObservation("UNKNOWN", target, null, null, 0L,
                List.of(), "UNKNOWN", "not present");
        when(port.inspect(target)).thenReturn(missing);
        when(port.apply(target, missing)).thenReturn(missing);
        when(repository.isCurrent(lease)).thenReturn(true, true, true, true, true, false);

        reconciler.reconcile(lease);

        verify(runtime, never()).observe(any(RuntimeManifest.class), any(RuntimeJobState.class), any(), any());
        verify(repository, never()).release(any());
        verify(repository, never()).fail(any(), any(String.class));
    }

    private RuntimeManifest manifest(List<RuntimeManifest.Member> members) {
        return new RuntimeManifest(RuntimeManifest.SCHEMA_VERSION, "default", "cluster-a", "group-a",
                1L, members);
    }

    private DetectionGroupLease lease(RuntimeManifest manifest) {
        return new DetectionGroupLease("default", "group-a", "cluster-a", manifest.generation(),
                codec.encode(manifest), codec.specHash(manifest), "worker-a", 1L, 1);
    }
}
