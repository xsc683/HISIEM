# Module boundaries and process roles

This repository remains a monorepo. The first migration establishes dependency
boundaries without turning every CRUD package into a network service.

## Code modules

| Module | Existing package boundary | Owns |
|---|---|---|
| `platform-contracts` | `tenant`, shared event/identity DTOs | stable cross-context contracts only |
| `platform-migrations` | `db/migration` resources only | one physical Flyway migration artifact shared by both applications |
| `iam` | `auth`, `tenant` | authentication, sessions, RBAC and tenant membership |
| `security-ops` | `alert`, `investigation`, `logsearch`, `search` | analyst queries, alerts and cases |
| `detection-control` | `rules` | YAML validation, immutable revisions, plans and desired deployments; no physical runtime adapter |
| `detection-runtime` | `detection.runtime` | transport-neutral lease/target/observation/port contracts, immutable artifact builder, stable job-name codec, and opt-in Flink process adapter |
| `detection-controller` | `detection.controller` | independent non-web claim, lease/fencing, reconciliation, conditional adapter wiring and adapter health process |
| `soar-core` | SOAR model, engine and handlers | transport-neutral playbook execution, lease, retry, approval and connector SPI; defines the consumer-owned `SecurityOperationPort`; no Kafka, Actuator health or JDK HTTP imports |
| `soar-adapters` | SOAR Kafka/HTTP and security-operation adapters | lifecycle publisher, Kafka properties and record mapper, generic HTTP connector implementations, and the local `SecurityOperationPort` implementation |
| `soar-worker-runtime` | SOAR worker runtime | Kafka consumer, Kafka health indicator and leased scheduled SOAR worker |
| `platform-operations` | `health`, `notify`, `control`, `settings` | operational jobs, health and configuration; no process/Docker/WSL adapter code |
| `platform-operations-adapters` | optional process adapter implementations | WSL/Docker process adapters for existing non-Detection operations; explicitly included by `control-api` for compatibility, can be disabled with `app.operations.process-adapters=disabled`, and is a candidate for a future operations worker |
| `agent-adapter` | `agent` | typed outbound integration with HISIEM-Agent |

The two executable Spring Boot applications are composition roots. Moving a package
into a Maven module is behavior-preserving; core modules must not depend on
Spring MVC or controller classes. `SecurityOperationPort` is defined by its
consumer in `soar-core` (`com.xscsiem.hsiem_platform.soar.port`) and exposes typed
alert/case operations without leaking `AlertService` or `CaseService`. The
`soar-adapters` module currently supplies `LocalSecurityOperationAdapter`, which
injects those in-process services and owns service-specific case detail/evidence
merging. It can later be replaced by an HTTP adapter without changing the SOAR
execution engine. This port does not make alerts or cases independent services;
they remain in-process security-operation capabilities until a measured scaling
or failure-domain requirement justifies another deployment boundary.

`soar-core` contains no Kafka consumer/client, Actuator health, JDK HTTP client,
or scheduled worker loop. Kafka record/header conversion happens in
`soar-adapters`; the Kafka consumer, health indicator and SOAR lease poller are
in `soar-worker-runtime`. `platform-migrations` is resource-only and owns the
single physical `db/migration` tree used by both applications.

## Process roles

The default `HsiemPlatformApplication` keeps the existing control API behavior
for development. `SoarWorkerApplication` is a separate non-servlet process role:

```text
control-api
  ├── HTTP controllers + control-plane operations + detection-control + soar-core + soar-adapters
  └── platform-operations-adapters (non-Detection operations; enabled by default, disable when needed)
  (no physical Detection deployment authority)
detection-controller
  ├── durable detection group claims and fencing
  ├── detection-runtime port contracts + disabled/process adapter
  └── DetectionRuntimeService observation bridge
soar-worker
  ├── SOAR Kafka consumer
  ├── leased SOAR execution worker
  └── connector runtime
```

The worker uses `WebApplicationType.NONE`, explicitly enables SOAR runtime and
consumer properties, and sets `app.operations.runtime-enabled=false`. The
control API defaults that one switch to true. Every non-SOAR scheduled wrapper
(background recovery, case aggregation/mirror/outbox, and notification scan)
is conditional on that switch; the CaseService mirror operation remains an
ordinary callable business method.

## Managed detection runtime

`RuleService` remains the Git/YAML authoring boundary. `ManagedDetectionService`
registers an immutable content-hashed `RuleRevision`, compiles the restricted
`DetectionPlan` IR, and writes `RuleDeployment` desired state with a monotonic
per-rule generation. `DetectionRuntimeService` owns the tenant-scoped job-group
placement, current `RuleJobAssignment`, canonical expected `RuntimeManifest`,
and observed `RuleRuntimeStatus`; its JDBC repository keeps those writes in the
same desired-state transaction. Placement is deterministic from tenant, target
cluster, plan input source family (default `siem-events`), category, and the
configured positive bucket count.

The canonical detection compilation path is `Rule YAML → RuleRevision →
DetectionPlan → FlinkArtifactCompiler → RuleDecl → DetectionJob`. A semantic
revision change with the same plan hash updates logical provenance only; it does
not advance physical job-group generation or trigger a Flink apply.

This phase is an **observed-state foundation plus the Phase 5A controller core and the Phase 5B single-cluster process adapter**. The API records desired state and returns `PENDING`; it has no physical deployment permission. The independent `detection-controller` process claims durable V18 leases, fences stale work, reconciles through `FlinkRuntimePort`, and calls `observe` only after a final exact verification inspect. Its default disabled adapter performs no physical Flink/Docker operation and reports `UNKNOWN`. When explicitly enabled, the process adapter materializes immutable job-group artifacts, submits structured Flink jobs through argument vectors, and derives observed members from the real job list and local artifact. It is not a production HA or multi-cluster deployment solution. Runtime observations are accepted and updated only for their exact tenant + target-cluster + job-group scope.

Runtime manifest JSON and its spec hash are produced by
`RuntimeManifestCodec`; member order is canonical by `ruleKey`, and volatile
observed `jobId`/`jobKey` fields are excluded from the SHA-256 spec hash. Runtime
persistence and the V17/V18 migrations live in `detection-control` and
`platform-migrations` respectively; V19 owns the lifecycle outbox. The Flink
module remains independent.

This intentionally avoids splitting alert, case, planner, tool, or connector
CRUD into independent services. Those calls are tightly coupled and should
remain in-process until measured scaling or failure-domain requirements justify
another deployment boundary.

## Build and run

From the repository root, build and test the complete Maven reactor with:

```bash
./mvnw test
```

The control API, detection controller, and SOAR worker have independent entrypoints:

```bash
./mvnw -pl applications/control-api spring-boot:run
./mvnw -pl applications/detection-controller spring-boot:run
./mvnw -pl applications/soar-worker spring-boot:run
```

Do not add `applications/control-api` as a dependency of the worker. Both apps
consume `platform-migrations`, whose `classpath:db/migration` location remains
unchanged. The complete reactor can be tested with:

```bash
./mvnw test
./mvnw -f flink/pom.xml test
```

The Flink job remains separately runnable:

```bash
docker exec siem-flink-jobmanager flink run -d /opt/flink/detection-job-1.0.jar
```
