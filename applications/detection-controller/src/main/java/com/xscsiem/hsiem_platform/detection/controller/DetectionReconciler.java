package com.xscsiem.hsiem_platform.detection.controller;

import com.xscsiem.hsiem_platform.detection.runtime.DetectionGroupLease;
import com.xscsiem.hsiem_platform.detection.runtime.DetectionRuntimeTarget;
import com.xscsiem.hsiem_platform.detection.runtime.FlinkRuntimePort;
import com.xscsiem.hsiem_platform.detection.runtime.RuntimeObservation;
import com.xscsiem.hsiem_platform.rules.runtime.DetectionRuntimeService;
import com.xscsiem.hsiem_platform.rules.runtime.ObservationFence;
import com.xscsiem.hsiem_platform.rules.runtime.RuntimeDiff;
import com.xscsiem.hsiem_platform.rules.runtime.RuntimeJobState;
import com.xscsiem.hsiem_platform.rules.runtime.RuntimeManifest;
import com.xscsiem.hsiem_platform.rules.runtime.RuntimeManifestCodec;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import org.springframework.stereotype.Service;

/**
 * One fenced reconciliation attempt. The adapter is the only component allowed to perform an
 * external action; observed rows are written exclusively through DetectionRuntimeService.observe.
 */
@Service
public class DetectionReconciler {

    private final DetectionControllerRepositoryPort repository;
    private final FlinkRuntimePort port;
    private final DetectionRuntimeService runtime;
    private final RuntimeManifestCodec codec;

    public DetectionReconciler(
            DetectionControllerRepositoryPort repository,
            FlinkRuntimePort port,
            DetectionRuntimeService runtime) {
        this(repository, port, runtime, new RuntimeManifestCodec());
    }

    /** Binary/source compatibility for integrations compiled against the former concrete port. */
    public DetectionReconciler(
            DetectionControllerRepository repository,
            FlinkRuntimePort port,
            DetectionRuntimeService runtime,
            RuntimeManifestCodec codec) {
        this((DetectionControllerRepositoryPort) repository, port, runtime, codec);
    }

    public DetectionReconciler(
            DetectionControllerRepositoryPort repository,
            FlinkRuntimePort port,
            DetectionRuntimeService runtime,
            RuntimeManifestCodec codec) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.port = Objects.requireNonNull(port, "port must not be null");
        this.runtime = Objects.requireNonNull(runtime, "runtime must not be null");
        this.codec = Objects.requireNonNull(codec, "codec must not be null");
    }

    public void reconcile(DetectionGroupLease lease) {
        reconcile(lease, () -> repository.isCurrent(lease));
    }

    /**
     * Worker supplies a heartbeat health gate in addition to the DB fence. A false gate prevents
     * all later phase, observe, release, and failure writes for this attempt.
     */
    public void reconcile(DetectionGroupLease lease, BooleanSupplier canContinue) {
        Objects.requireNonNull(lease, "lease must not be null");
        Objects.requireNonNull(canContinue, "canContinue must not be null");
        try {
            RuntimeManifest expected = validateExpected(lease);
            DetectionRuntimeTarget target = new DetectionRuntimeTarget(lease);
            ensureCurrent(lease, canContinue);

            RuntimeObservation inspected = port.inspect(target);
            ensureCurrent(lease, canContinue);
            RuntimeManifest actual = toManifest(target, inspected);
            ensureCurrent(lease, canContinue);

            RuntimeObservation verified = inspected;
            if (expected.members().isEmpty()) {
                boolean alreadyStopped =
                        "STOPPED".equalsIgnoreCase(inspected.runtimeState())
                                && inspected.members().isEmpty();
                if (!alreadyStopped) {
                    // Empty desired state is an explicit stop, even when an adapter reports
                    // UNKNOWN.
                    requirePhase(lease, ReconcileState.APPLYING, canContinue);
                    ensureCurrent(lease, canContinue);
                    port.stop(target, inspected);
                    ensureCurrent(lease, canContinue);
                    requirePhase(lease, ReconcileState.VERIFYING, canContinue);
                    ensureCurrent(lease, canContinue);
                    verified = port.inspect(target);
                    ensureCurrent(lease, canContinue);
                }
            } else {
                RuntimeDiff diff = RuntimeDiff.compare(expected, actual);
                boolean stateMatches = "RUNNING".equalsIgnoreCase(inspected.runtimeState());
                if (!diff.isEmpty() || !stateMatches) {
                    requirePhase(lease, ReconcileState.APPLYING, canContinue);
                    ensureCurrent(lease, canContinue);
                    port.apply(target, inspected);
                    ensureCurrent(lease, canContinue);
                    requirePhase(lease, ReconcileState.VERIFYING, canContinue);
                    ensureCurrent(lease, canContinue);
                    verified = port.inspect(target);
                    ensureCurrent(lease, canContinue);
                }
            }

            // Never persist or release from an optimistic adapter return.  The final inspect must
            // prove both the requested state and the exact expected members.
            RuntimeManifest observed = toManifest(target, verified);
            requireExactVerification(expected, verified, observed);
            ensureCurrent(lease, canContinue);
            runtime.observeFenced(
                    observed,
                    RuntimeJobState.from(verified.runtimeState()),
                    verified.errorCode(),
                    verified.errorMessage(),
                    new ObservationFence(
                            lease.tenantId(),
                            lease.groupKey(),
                            lease.targetCluster(),
                            lease.owner(),
                            lease.fencingToken(),
                            lease.desiredGeneration()));
            ensureCurrent(lease, canContinue);
            if (!repository.release(lease)) {
                throw new StaleLeaseException("lease was fenced before release");
            }
        } catch (StaleLeaseException stale) {
            // Stale work must stop without a success or failure write.
            return;
        } catch (DetectionRuntimeService.StaleObservationException stale) {
            // The transactional observer owns the final fence; stale work must not be marked as
            // a reconciliation failure after the lease has been stolen or expired.
            return;
        } catch (RuntimeException failure) {
            if (Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt();
                throw failure;
            }
            // fail() itself is fenced, so a concurrent generation change or lease loss is safe.
            repository.fail(lease, failure);
        }
    }

    public void reconcileOne(DetectionGroupLease lease) {
        reconcile(lease);
    }

    private RuntimeManifest validateExpected(DetectionGroupLease lease) {
        RuntimeManifest expected = codec.decode(lease.expectedJson());
        codec.validateSchemaVersion(expected);
        if (!lease.tenantId().equals(expected.tenantId())
                || !lease.groupKey().equals(expected.jobGroupKey())
                || !lease.targetCluster().equals(expected.targetCluster())
                || lease.desiredGeneration() != expected.generation()) {
            throw new IllegalArgumentException(
                    "expected manifest scope or generation does not match lease");
        }
        String calculated = codec.specHash(expected);
        if (!calculated.equalsIgnoreCase(lease.expectedHash())) {
            throw new IllegalArgumentException("expected manifest hash does not match lease");
        }
        return expected;
    }

    private RuntimeManifest toManifest(
            DetectionRuntimeTarget target, RuntimeObservation observation) {
        if (observation == null) {
            observation = RuntimeObservation.unknown(target);
        }
        if (!target.tenantId().equals(observation.tenantId())
                || !target.groupKey().equals(observation.groupKey())
                || !target.targetCluster().equals(observation.targetCluster())) {
            throw new IllegalArgumentException("runtime observation is outside target scope");
        }
        List<RuntimeManifest.Member> members =
                observation.members().stream()
                        .map(
                                member ->
                                        new RuntimeManifest.Member(
                                                member.ruleKey(),
                                                member.revision(),
                                                member.planHash()))
                        .toList();
        return new RuntimeManifest(
                RuntimeManifest.SCHEMA_VERSION,
                observation.tenantId(),
                observation.targetCluster(),
                observation.groupKey(),
                observation.generation(),
                observation.jobId(),
                observation.jobKey(),
                members);
    }

    private void requireExactVerification(
            RuntimeManifest expected, RuntimeObservation observation, RuntimeManifest actual) {
        if (observation == null) {
            throw new IllegalStateException("runtime verification returned no observation");
        }
        if (expected.members().isEmpty()) {
            if (!"STOPPED".equalsIgnoreCase(observation.runtimeState())
                    || !observation.members().isEmpty()
                    || expected.generation() != observation.generation()) {
                throw new IllegalStateException("runtime stop verification was not exact");
            }
            return;
        }
        if (!"RUNNING".equalsIgnoreCase(observation.runtimeState())
                || !RuntimeDiff.compare(expected, actual).isEmpty()) {
            throw new IllegalStateException("runtime apply verification was not exact");
        }
    }

    private void requirePhase(
            DetectionGroupLease lease, ReconcileState phase, BooleanSupplier canContinue) {
        ensureCurrent(lease, canContinue);
        if (!repository.transitionPhase(lease, phase)) {
            throw new StaleLeaseException("lease was fenced before phase " + phase);
        }
    }

    private void ensureCurrent(DetectionGroupLease lease, BooleanSupplier canContinue) {
        if (!canContinue.getAsBoolean()) {
            throw new StaleLeaseException("lease is no longer current");
        }
    }

    static final class StaleLeaseException extends RuntimeException {
        StaleLeaseException(String message) {
            super(message);
        }
    }
}
