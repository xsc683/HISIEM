# HISIEM — Architecture Diagram & Data Flow Diagram

Two canonical diagrams for the platform:

1. **[Architecture diagram](#1-architecture-diagram)** — *what the system is made of*
2. **[Data flow diagram](#2-data-flow-diagram)** — *what happens when a real log line flows through it*

Companion documents: [`architecture.md`](architecture.md) (data/control plane boundaries),
[`architecture-overview.md`](architecture-overview.md) (four-figure overview),
[`architecture-deep-dive.md`](architecture-deep-dive.md) (full technical walkthrough).

---

## 1. Architecture Diagram

Every component grouped by the responsibility plane that owns it, with the flows that cross
plane boundaries made explicit.

```mermaid
graph TB

    subgraph SRC["Log Sources"]
        SYSLOG["Syslog / Agent / Simulator"]
    end

    subgraph INGEST["Ingestion Plane"]
        LS["Logstash<br/>Grok + ECS + date parsing"]
    end

    subgraph DETECT["Detection Plane — Flink 2.1"]
        KSRC["KafkaSource<br/>siem-events"]
        PARSE["EventParsingProcessFunction<br/>JSON + event-time validation"]
        DLQS["DLQ Sink"]
        WATERMARK["Watermark<br/>bounded OOO 10s + idle 60s"]
        SINGLE["Single-event rules<br/>DetectionFunction"]
        WIN["Window rules<br/>WindowRuleFunction"]
        CEPH["CEP rules<br/>BruteforceSuccessFunction"]
        BASE["Baseline rules<br/>BaselineAnomalyFunction"]
        SUP1["AlertSuppressor<br/>processing-time window"]
        SUP2["WindowAlertSuppressor<br/>processing-time window"]
        ESIDX["AlertElasticsearchIndexer<br/>async, deterministic _id upsert"]
        LCM["AlertLifecycleEventMapper<br/>deterministic message_id"]
        LSK["Lifecycle Kafka Sink"]
    end

    subgraph BUS["Message Bus — Kafka"]
        T_EVENTS["siem-events"]
        T_DLQ["siem-events-dlq"]
        T_ALERT["siem-alert-lifecycle"]
        T_CASE["siem-case-lifecycle"]
    end

    subgraph STORE["Persistence Plane"]
        ES[("Elasticsearch<br/>search read model")]
        PG[("PostgreSQL<br/>control-plane truth")]
    end

    subgraph SOAR["SOAR Plane"]
        SKC["SoarKafkaConsumer"]
        SW["SoarWorker<br/>scheduled poll + lease claim"]
        ENG["SoarExecutionEngine<br/>lease + fencing token"]
        ROUTER["SoarGraphRouter"]
        HANDLERS["11 Node Handlers<br/>start / business / condition / connector<br/>human / wait / parallel / join<br/>loop / loop-end / end"]
        SPI["SPI<br/>SoarConnector / SecurityOperationPort"]
        ADAPTERS["soar-adapters<br/>Kafka lifecycle + HTTP connector"]
        LOD["LifecycleOutboxDispatcher"]
    end

    subgraph CTRL["Control Plane — Spring Boot"]
        CA["control-api"]
        ALERTAPI["AlertController"]
        CASEAPI["CaseController"]
        RULEAPI["RuleController"]
        LOGAPI["LogSearchController"]
        SOARAPI["SoarController / InternalSoarController"]
        BFF["AgentInvestigationController<br/>BFF to sibling system"]
        JOBS["Background jobs<br/>CaseAggregateJob<br/>CaseMirrorReconcileJob"]
        IAM["IAM<br/>auth / session / tenant / RBAC"]
        STORES["Control-plane stores<br/>ControlPlaneStore / CaseStore<br/>LifecycleOutboxStore"]
    end

    subgraph DETCTL["Detection Control Plane"]
        DCTL["detection-control<br/>rules + desired/observed state"]
        DRT["detection-runtime<br/>transport-neutral ports"]
        DCTRLR["detection-controller<br/>standalone reconciler"]
    end

    subgraph CONSOLE["Analyst Console Plane"]
        WEB["Vue Console<br/>overview / logs / alerts / cases<br/>rules / sources / SOAR / RBAC"]
    end

    subgraph SIBLING["Sibling System — separate repository"]
        COPI["HISIEM-SOC-Copilot<br/>AI investigation &amp; response decision layer"]
    end

    %% ---- Ingest ----
    SYSLOG --> LS
    LS -->|"parse failed: archive only"| ES
    LS -->|"parse ok"| ES
    LS -->|"parse ok"| T_EVENTS

    %% ---- Detection ----
    T_EVENTS --> KSRC
    KSRC --> PARSE
    PARSE -->|"invalid JSON / event time"| DLQS
    DLQS --> T_DLQ
    PARSE --> WATERMARK
    PARSE -->|"no watermark"| SINGLE
    WATERMARK --> WIN
    WATERMARK --> CEPH
    WATERMARK --> BASE

    SINGLE --> SUP1
    WIN --> SUP2
    SUP1 --> ESIDX
    SUP2 --> ESIDX
    CEPH --> ESIDX
    BASE --> ESIDX

    %% ---- Alert persistence and announcement ----
    ESIDX --> ES
    ESIDX -->|"only after ES 2xx"| LCM
    LCM --> LSK
    LSK --> T_ALERT

    %% ---- Control plane ----
    ES --> ALERTAPI
    ES --> LOGAPI
    ES --> CASEAPI
    CA --> ALERTAPI
    CA --> CASEAPI
    CA --> RULEAPI
    CA --> LOGAPI
    CA --> SOARAPI
    CA --> BFF
    CA --> JOBS
    CA --> IAM
    CA --> STORES
    STORES --> PG

    %% ---- Detection control ----
    RULEAPI --> DCTL
    DCTL --> DRT
    DCTRLR --> DCTL
    CA --> DCTL

    %% ---- Cross-store convergence ----
    JOBS -->|"enqueueCaseMirror"| STORES
    STORES -->|"case_mirror_outbox: lease + reclaim"| ES

    %% ---- SOAR ----
    T_ALERT --> SKC
    T_CASE --> SKC
    SKC --> SW
    SW --> ENG
    ENG --> ROUTER
    ROUTER --> HANDLERS
    HANDLERS --> SPI
    SPI --> ADAPTERS
    ENG --> PG
    STORES --> LOD
    LOD --> T_ALERT

    %% ---- Console ----
    WEB -->|"/api/**"| CA

    %% ---- Cross-system bridge ----
    WEB -->|"/api/agent-investigations/**"| BFF
    BFF -->|"service bearer + server-asserted tenant/actor"| COPI
    COPI -->|"proxied read model"| BFF
    SOARAPI -->|"execution state query"| COPI
```

### What this diagram solves

It answers **"which plane owns which responsibility, and where does a flow cross a plane
boundary?"** The crossing points are where the interesting engineering lives — each one is a
place where two different correctness models meet.

### Component responsibilities

| Plane | Owns | Notable non-responsibility |
|---|---|---|
| **Ingestion** | Parse, normalize to ECS, route by parse outcome | Does not detect |
| **Detection (Flink)** | Event-time windowing, CEP, baselines, suppression, alert identity | Does not store or manage cases |
| **Message Bus** | Durable fan-out, DLQ, lifecycle contract carrier | Does not compute |
| **Persistence** | PostgreSQL = transactional truth; Elasticsearch = rebuildable search read model | Neither alone is a complete truth |
| **SOAR** | Playbook execution: node-by-node advance under lease, with fencing | Does not decide *whether* to run — that is a durable command from the control plane |
| **Control Plane** | Cases, alerts surface, rules, log search, IAM/RBAC, ops APIs, the Copilot BFF | **Does not perform physical detection deployment** |
| **Detection Control** | Rule definitions, desired vs. observed runtime state, reconciliation | **No physical deployment responsibility** |
| **Console** | Analyst presentation and authoring | Holds no authority of its own |
| **Sibling System** | AI investigation, evidence, response *proposal*, human-authority workflow | **Does not detect and does not execute** |

### Where data comes from and goes

```text
IN    Log lines arrive at Logstash from syslog / agents / the simulator.
      Parsed events fan out to Elasticsearch (search) AND Kafka (streaming).
      Flink consumes Kafka, detects, and writes alerts back to Elasticsearch.

OUT   Alerts become lifecycle events on Kafka (only after a successful ES write).
      soar-worker consumes those, executes SOAR playbooks, and records execution
      state in PostgreSQL. The console reads from both stores.

CROSS The console never talks to the sibling system directly. It calls HISIEM's
      /api/agent-investigations/** BFF, which proxies server-side with a service
      credential and a tenant/actor taken from HISIEM's already-validated context.
```

### Where truth and authority live

```text
PostgreSQL                    = control-plane transactional truth
                                (cases, rules, IAM, SOAR execution state, outboxes)
Elasticsearch                 = rebuildable search read model
                                (events, raw events, alerts, case mirror, entity risk)
Flink operator state          = recoverable detection working state (checkpointed)
SOAR execution state          = execution truth, recorded in PostgreSQL
Alert document identity       = deterministic: sha1(rule_id | entity | event_time)
Sibling-system execution view = NOT authoritative here; HISIEM's observed state is
                                the final execution truth for the combined system
```

### Reliability boundaries

| # | Boundary | Where | What can go wrong |
|---|---|---|---|
| 1 | Parse boundary | Logstash → ES raw / ES events + Kafka | Unparseable records must be retained, never silently dropped |
| 2 | Event-time boundary | Kafka → Flink timestamp assignment | Source clock is trusted; skew is not reconciled |
| 3 | Sink boundary | Flink → Elasticsearch | Non-transactional write; convergence relies on deterministic `_id` |
| 4 | Announcement boundary | ES 2xx → Kafka lifecycle event | Ordering is load-bearing: downstream must never learn of an unstored alert |
| 5 | Cross-store boundary | PostgreSQL ↔ Elasticsearch | No distributed transaction; convergence via `case_mirror_outbox` with lease + reclaim |
| 6 | Execution boundary | Control plane → SOAR | Playbook advancement is lease-protected; **node-side-effect idempotency belongs to the connector** |
| 7 | Cross-system boundary | HISIEM BFF → sibling system | Tenant/actor are server-asserted; the browser can never override them |

---

## 2. Data Flow Diagram

One real log line, followed end to end.

```mermaid
sequenceDiagram
    autonumber
    participant SRC as Log Source
    participant LS as Logstash
    participant ES as Elasticsearch
    participant MQ as Kafka
    participant FL as Flink Detection Engine
    participant API as HISIEM Control API
    participant PG as PostgreSQL
    participant SOAR as SOAR Runtime
    participant WEB as Analyst Console
    participant CP as Sibling System (SOC Copilot)

    rect rgb(245,248,255)
        Note over SRC,MQ: Ingestion — two different failure policies
    end

    SRC->>LS: raw log line
    LS->>LS: Grok parse + ECS mapping + date parsing
    alt parse failed
        LS->>ES: write siem-events-raw-YYYY.MM.dd
        Note over LS,ES: archived for forensics, NOT published to Kafka
    else parse ok
        LS->>ES: write siem-events-*
        LS->>MQ: publish siem-events
    end

    rect rgb(245,255,245)
        Note over MQ,ES: Detection — event-time streaming
    end

    MQ->>FL: KafkaSource consumes siem-events
    FL->>FL: validate JSON and event-time validity
    alt invalid
        FL->>MQ: side output to siem-events-dlq
    else valid
        FL->>FL: assign timestamp, advance watermark (OOO 10s, idle 60s)
        Note over FL: one shared watermark feeds window / CEP / baseline
        FL->>FL: single-event rules (no watermark)
        FL->>FL: window rules over event time
        FL->>FL: CEP pattern match
        FL->>FL: baseline anomaly compare
        FL->>FL: suppress per rule + entity
        FL->>FL: compute _id = sha1(rule_id | entity | event_time)
    end

    rect rgb(255,250,240)
        Note over FL,MQ: Alert persistence then announcement
    end

    FL->>ES: POST /siem-alerts/_update/{_id}
    alt ES write failed
        ES--xFL: non-2xx
        Note over FL,ES: exception raised, NO lifecycle event emitted
    else ES write succeeded
        ES-->>FL: 2xx
        FL->>MQ: alert.created with deterministic message_id
        Note over FL,MQ: downstream never learns of an unstored alert
    end

    rect rgb(250,245,255)
        Note over API,PG: Case handling and cross-store convergence
    end

    WEB->>API: analyst opens alert
    API->>ES: query alert and related events
    ES-->>API: alert document
    API-->>WEB: alert detail
    API->>API: CaseAggregateJob may aggregate alerts into a case
    API->>PG: persist case fact (single transaction)
    API->>PG: enqueueCaseMirror into case_mirror_outbox
    Note over API,PG: fact and outbox row commit together
    PG->>ES: dispatcher claims batch under lease, writes case mirror
    Note over PG,ES: eventually consistent, no distributed transaction

    rect rgb(245,255,250)
        Note over MQ,PG: SOAR execution
    end

    MQ->>SOAR: alert.created / case lifecycle event
    SOAR->>SOAR: validate lifecycle contract (message_id required)
    SOAR->>PG: claim execution under lease, carry fencing token
    loop advance node by node
        SOAR->>SOAR: route to next node and run its handler
        alt human approval node
            SOAR->>PG: persist state, release worker
            Note over SOAR,PG: the work resumes from persisted state, not memory
        else ordinary node
            SOAR->>PG: update execution state
        end
    end
    SOAR->>PG: persist terminal execution state

    rect rgb(255,245,245)
        Note over WEB,CP: Cross-system bridge
    end

    WEB->>API: open AI investigation workbench
    API->>CP: proxy as service: bearer + server-asserted tenant/actor
    Note over API,CP: the browser never holds a Copilot credential
    CP-->>API: bounded JSON read model
    API-->>WEB: workspace projection
```

### What this diagram solves

It answers **"what actually happens, in order, and where can each step fail?"** The three
`alt` branches are the parts worth memorizing: parse failure, event-time validation failure,
and a failed alert write. Each one has a *different* policy, and mixing them up is the most
common way to misdescribe this pipeline.

### The three failure policies, side by side

| Failure | Destination | Policy |
|---|---|---|
| Logstash parse failure | ES `siem-events-raw-YYYY.MM.dd` | **Retained for forensics**; daily index, short retention, never enters Kafka or Flink |
| Flink validation failure (JSON / event time) | Kafka `siem-events-dlq` | **Quarantined** for reprocessing; no automated replay tooling today |
| Elasticsearch alert write failure | exception, and **no lifecycle event** | The announcement is suppressed so no downstream system sees a phantom alert |

### Load-bearing ordering

```text
1. The alert must be STORED before it is ANNOUNCED.
   ES 2xx → lifecycle event. A failed write produces an exception, not an event.

2. The case fact and its outbox row commit in the SAME transaction.
   Publication becomes a consequence of the commit, not a second independent action.

3. The SOAR execution state lands in PostgreSQL BEFORE the worker releases it.
   That is what makes a human-approval node resumable rather than blocking.
```

### Where the sibling system meets HISIEM

```text
Browser  →  HISIEM /api/agent-investigations/**  (Spring Security RBAC)
         →  AgentInvestigationService            (server-side proxy)
         →  Copilot API                          (service bearer credential)

Tenant and actor are taken from HISIEM's already-validated context.
A browser cannot override them through a request body or header, and Copilot
credentials are never returned to the client.
```

The reverse direction is equally deliberate: the execution state that matters for the
combined system is the one **HISIEM records**, not the one the sibling believes it submitted.
