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
| `detection-control` | `rules` | YAML validation, immutable revisions, plans and desired deployments |
| `soar-core` | SOAR model, engine and handlers | transport-neutral playbook execution, lease, retry, approval and connector SPI; defines the consumer-owned `SecurityOperationPort`; no Kafka, Actuator health or JDK HTTP imports |
| `soar-adapters` | SOAR Kafka/HTTP and security-operation adapters | lifecycle publisher, Kafka properties and record mapper, generic HTTP connector implementations, and the local `SecurityOperationPort` implementation |
| `soar-worker-runtime` | SOAR worker runtime | Kafka consumer, Kafka health indicator and leased scheduled SOAR worker |
| `platform-operations` | `health`, `notify`, `control`, `settings` | operational jobs, health and configuration |
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
  └── HTTP controllers + control-plane operations + soar-core + soar-adapters
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

This phase is an **observed-state foundation**. The API records desired state and
returns `PENDING`; it never treats desired state as observed state. Only an
explicit `observe(RuntimeManifest, RuntimeJobState, ...)` call can move a rule to
`RUNNING`, `DISABLED`, `FAILED`, or `UNKNOWN`, and manifest comparison reports
missing, outdated (revision/generation/plan hash), and unexpected members.
`STOPPED` removes a rule from the expected running member set while retaining its
status row so a stopped observation can converge it to `DISABLED`. No physical
Flink/Docker controller or deployment loop is implemented in this phase; that is
the next phase's responsibility. Runtime observations are accepted and updated
only for their exact tenant + target-cluster + job-group scope.

Runtime manifest JSON and its spec hash are produced by
`RuntimeManifestCodec`; member order is canonical by `ruleKey`, and volatile
observed `jobId`/`jobKey` fields are excluded from the SHA-256 spec hash. Runtime
persistence and the V17 migration live in `detection-control` and
`platform-migrations` respectively; the Flink module remains independent.

This intentionally avoids splitting alert, case, planner, tool, or connector
CRUD into independent services. Those calls are tightly coupled and should
remain in-process until measured scaling or failure-domain requirements justify
another deployment boundary.

## Build and run

From the repository root, build and test the complete Maven reactor with:

```bash
./mvnw test
```

The control API and SOAR worker have independent entrypoints:

```bash
./mvnw -pl applications/control-api spring-boot:run
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
