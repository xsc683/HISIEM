# Managed detection runtime — Phase 5A and 5B

## Scope

Phase 5A provides the independent detection controller foundation. `detection-control` remains the desired-state and observed-state library used by the control API; `detection-controller` is a separate `WebApplicationType.NONE` Spring Boot process that claims groups and reconciles them through the transport-neutral contracts in `detection-runtime`.

Phase 5B adds the opt-in process adapter for a configured Flink JobManager container. The controller materializes a tenant/group-scoped immutable artifact from the exact assignment, plan, and revision rows, submits the Flink job with a structured identity, and accepts observed members only from the real Flink job list plus the local artifact manifest. The expected manifest is never copied into observed state.

The control API has no Detection process/deployment command authority and does not invoke Docker/WSL commands or the Flink CLI. Its deploy endpoints only persist desired state and return `202 PENDING`; a controller claim is the only path from Detection desired state to a runtime port. Existing non-Detection Logstash and criticality operations remain available through `platform-operations-adapters` for compatibility, are enabled by default, and can be disabled with `app.operations.process-adapters=disabled`.

## Durable state and fencing

V18 adds controller-only fields to `detection_job_group`:

- `reconcile_state`: `PENDING`, `INSPECTING`, `APPLYING`, `VERIFYING`, `IDLE`, or `FAILED`;
- `reconcile_available_at` and `reconcile_attempts` for due polling and bounded exponential backoff;
- `controller_lease_owner`, `controller_lease_until`, and monotonic `controller_fencing_token`;
- `last_reconciled_at`.

`claimDue` uses one transaction with `FOR UPDATE SKIP LOCKED`, claims only due rows with no live lease unless a newly desired `PENDING` generation supersedes that lease, increments the fencing token and attempt, and snapshots the desired generation and expected manifest. Heartbeat, phase, failure, and release updates require owner + token + desired generation. Stale updates return `false`; runtime `status`, `job_id`, and `job_key` are not changed by the controller repository.

## Reconciliation contract

`DetectionReconciler` validates manifest JSON, exact tenant/group/cluster scope, generation, and SHA-256 hash. It inspects before deciding:

- a non-empty expected manifest is applied only when `RuntimeDiff` or job state is not matching;
- an empty expected manifest invokes `stop` unless the inspected runtime is already `STOPPED` with no members;
- every external call and every observe boundary checks the lease; apply/stop is followed by a VERIFYING inspect;
- only `DetectionRuntimeService.observe` writes the unique observed state;
- a generation or fencing change abandons the attempt and never releases it as successful;
- adapter failures enter `FAILED` with capped exponential backoff.

The worker claims at most 100 groups per poll, but claims and fully processes one lease at a time so every claimed lease has an active heartbeat. Each lease is renewed at one third of its duration, and the heartbeat executor shuts down on `PreDestroy`.

## Adapter mode, immutable artifacts, and job identity

The default `app.detection.runtime-adapter=disabled` enables `DisabledFlinkRuntimePort`. It performs no physical deployment and reports `UNKNOWN`; this remains the safe development default. Setting `app.detection.runtime-adapter=process` enables exactly one `ProcessFlinkRuntimeAdapter`; the conditional configuration and disabled adapter are mutually exclusive. The process adapter accepts only its configured `app.detection.cluster-id`, which must also be in `app.detection.allowed-clusters` when a whitelist is supplied.

`DetectionArtifactBuilder` stores artifacts below:

```text
<app.detection.artifact-root>/<jobKey>/<generation>-<manifestHash>/
  runtime-manifest.json
  artifact-metadata.json
  0001-<safe-rule-slug>.yaml
```

The manifest file is the exact UTF-8 bytes of `RuntimeManifestCodec.canonicalSpecJson(expected)`, and its raw SHA-256 must equal the expected hash. The builder queries assignment joined to plan and revision using the exact tenant and group predicates, validates every rule revision, plan hash, generation, and member set, writes to a temporary directory, then atomically moves it into place. Existing directories are fully revalidated before reuse; malformed rule keys cannot escape the artifact root. Artifact directories are immutable and should be retained for at least the duration of the longest rollback/savepoint window plus investigation retention.

The stable key is a length-delimited SHA-256 identity of tenant, cluster, and group, rendered as `dg-<24 lowercase hex>`. A managed job name is:

```text
SIEM-DETECTION-dg-<24hex>-g<generation>-m<64hex>
```

The name contains no raw tenant or group strings. The process adapter calls Docker/Flink only through argument vectors (`docker exec ... flink list -a`, `cancel -s`, `run -d`), never through shell concatenation. It reads the observed manifest from the local artifact named by the parsed job identity, rejects missing/corrupt/scope-mismatched artifacts as `UNKNOWN`, rejects duplicate active jobs for one key, and stops only jobs carrying the target key. Updates use the savepoint returned by cancel; a failed replacement attempts rollback with the old artifact and that same savepoint, preserving the original failure if rollback also fails.

Flink managed launches receive `rulesDir`, `jobKey`, `generation`, and `manifestHash` as typed arguments. At startup it hashes the raw `runtime-manifest.json`, validates schema/scope/generation, and requires the manifest rule-key set to equal the unique IDs actually loaded by `RuleConfigLoader`. Managed Kafka groups and checkpoint/savepoint paths are isolated by job key. Legacy launches remain supported only through an explicit compatibility path and log that manifest verification was skipped.

## Operations and limitations

Enable the process adapter only in the independent controller process, after granting it permission to invoke Docker for the configured container, read the local artifact root, and write the configured savepoint location. Do not enable it in `control-api`. The adapter is a single-cluster process implementation: production HA, multi-cluster orchestration, distributed artifact storage/locking, and a fully automated disaster-recovery policy are not claimed by Phase 5B.

Run the controller separately from the control API:

```bash
./mvnw -pl applications/detection-controller spring-boot:run
```

Both applications consume the sole migration tree in `modules/platform-migrations`.
