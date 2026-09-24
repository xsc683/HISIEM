# HISIEM — Interview Guide

**Purpose:** private review material. Every technical statement here is grounded in the
repository at `add_frame`. Where the implementation has a boundary, the boundary is stated
rather than smoothed over.

**How to use it:** §1–§2 are the two spoken introductions. §3–§4 are the architecture and
code map. §5–§24 are the topic-by-topic review. §25–§27 are trade-offs and limits. §28–§29
are question banks.

---

## 1. 30-second introduction

> HISIEM is a lightweight SIEM I built on Elastic Stack, Kafka and Flink, with a Spring
> Boot control plane. Logs are parsed by Logstash into an ECS-shaped schema, published to
> Kafka, and evaluated by a Flink job that runs four kinds of detection — single-event
> rules, event-time window aggregations, CEP attack chains, and statistical baselines.
> Alerts land in Elasticsearch and drive a SOAR runtime that executes response playbooks
> against PostgreSQL-backed execution state.
>
> The interesting part is not the detection content — it's the streaming correctness
> work: event-time watermarks with bounded out-of-orderness and idle-partition handling,
> deterministic alert identities so that at-least-once delivery converges instead of
> duplicating, and an explicit separation between detection configuration and detection
> runtime.

---

## 2. 3-minute introduction

**Problem.** A SIEM consumes a firehose and must answer *what matters and what happens
next*. Four things make that hard: heterogeneous ingestion, unreliable event ordering,
pipeline restarts, and turning an alert into an auditable action.

**Shape of the system.** Two planes:

- **Data plane** — Logstash → Kafka → Flink → Elasticsearch. Optimized for event-time
  streaming and convergence.
- **Control plane** — Spring Boot + PostgreSQL/Flyway. Optimized for transactional state:
  cases, rules, IAM, SOAR orchestration, operations.

They split because the correctness requirements differ. Streaming wants event time and
idempotent convergence; the control plane wants transactions and referential integrity.

**The pipeline.** Logstash parses into ECS. Unparseable records go to a raw index rather
than disappearing. Parsed events are written to Elasticsearch *and* published to Kafka.
Flink validates JSON and event-time validity, routing invalid records to a DLQ topic.
Valid events flow into a **single shared watermark** — because window, CEP and baseline
rules all consume the same timed stream, so there is exactly one notion of "current event
time" in the job. Alerts are written to Elasticsearch by an async sink, and only after a
successful write does the pipeline emit an `alert.created` lifecycle event to Kafka. The
SOAR runtime consumes that and executes playbooks with lease-backed, resumable execution
state in PostgreSQL.

**The three things I'd want to be asked about:**

1. **Event time.** Bounded out-of-orderness of 10 seconds absorbs reordering; a 60-second
   idleness timeout stops a quiet key from holding every window open; windows fire on
   `watermark >= window end`. There is **no allowed lateness** — I'll be explicit about
   what that costs.
2. **Convergence under replay.** An alert's Elasticsearch `_id` is
   `sha1(rule_id | entity | event_time)`, so replaying the same event produces the same
   document id and the write is an idempotent upsert. Lifecycle messages carry a
   deterministic `message_id` for the same reason.
3. **Honest delivery semantics.** Flink checkpoints in `EXACTLY_ONCE` mode; Kafka **sinks**
   are `AT_LEAST_ONCE`. I do **not** claim end-to-end exactly-once — I claim state
   consistency plus idempotent convergence, and I can explain why those are different.

---

## 3. System architecture

```text
DATA PLANE       Logstash → Kafka → Flink → Elasticsearch
CONTROL PLANE    Spring Boot → PostgreSQL
```

| Component | Responsibility |
|---|---|
| Logstash | Ingest, Grok/ECS parsing, date normalization. Nothing else. |
| Kafka | Standardized events (`siem-events`), parse DLQ (`siem-events-dlq`), lifecycle topics |
| Flink | **The detection engine** — event-time windows, CEP, baselines, suppression |
| Elasticsearch | Events, raw events, alerts, risk, compatibility read models |
| Kibana / Vue console | Analyst presentation and authoring |
| Spring Boot control plane | Ingest APIs, cases, auth, SOAR orchestration, operations |
| PostgreSQL | Control-plane transactional truth and execution state |

**Modules (12) and applications (3):**

```text
modules/
  platform-contracts/        cross-domain stable contracts
  platform-migrations/       shared Flyway migration resources (resource-only)
  iam/                       auth, session, tenant, control-plane storage
  agent-adapter/             HISIEM-SOC-Copilot outbound adapter
  security-ops/              alerts, cases, log search, ES gateway
  platform-operations/       ingest, notification, health, operational jobs
  platform-operations-adapters/
  detection-control/         rules, schedules, desired/observed runtime — no deployment
  detection-runtime/         transport-neutral runtime port contracts
  soar-core/                 transport-independent engine, SPI, node handlers
  soar-adapters/             Kafka lifecycle + HTTP connector adapters
  soar-worker-runtime/       Kafka consumer, health, worker loop

applications/
  control-api/               control API (does not perform physical detection deployment)
  detection-controller/      standalone reconciler (WebApplicationType.NONE)
  soar-worker/               standalone SOAR worker
```

**Why the module split matters.** `detection-control` and `detection-runtime` are separate
because *configuring detection* and *running detection* are different problems with
different failure modes and different deployment lifecycles. `soar-core` and
`soar-adapters` are separate because the execution engine must be transport-independent —
the engine decides node transitions; the adapters own Kafka and HTTP.

---

## 4. Repository / code map

The files worth opening first, in the order I'd walk someone through them:

```text
flink/src/main/java/com/siem/DetectionJob.java            # the whole pipeline assembly
flink/src/main/java/com/siem/EventParsingProcessFunction.java  # parse gate + DLQ side output
flink/src/main/java/com/siem/AlertSuppressor.java         # processing-time suppression + stable _id
flink/src/main/java/com/siem/WindowAlertSuppressor.java   # sliding-window duplicate collapse
flink/src/main/java/com/siem/DetectionFunction.java       # single-event rule evaluation
flink/src/main/java/com/siem/WindowRuleFunction.java      # event-time window rule
flink/src/main/java/com/siem/BruteforceSuccessFunction.java # CEP pattern output
flink/src/main/java/com/siem/BaselineAnomalyFunction.java # rolling baseline anomaly
flink/src/main/java/com/siem/AlertElasticsearchIndexer.java # async idempotent ES write
flink/src/main/java/com/siem/AlertLifecycleEventMapper.java # lifecycle contract + message_id
flink/src/main/java/com/siem/RuntimeManifestVerifier.java # rules-dir provenance check
flink/src/main/java/com/siem/config/RuleConfigLoader.java # YAML → declarations
flink/src/main/java/com/siem/config/RuleBuilder.java      # declarations → Flink operators
flink/src/main/java/com/siem/config/RuntimeTuning.java    # checkpoint/ES tuning from env

modules/soar-core/  .../soar/SoarExecutionEngine.java     # node transitions
modules/soar-core/  .../soar/SoarLifecycleRuntime.java    # lifecycle → execution runtime
modules/detection-control/                                 # desired vs observed state
infra/rules/*.yaml                                         # detection as code
```

**`DetectionJob` is the single densest file** — it assembles the source, the parse gate,
the shared watermark, all four detection branches, suppression, the async ES sink and the
lifecycle sink. If someone reads one file, that is the one.

---

## 5. Ingestion pipeline

**What it is.** The path from a raw log line to a normalized, searchable, detection-ready
event.

**How this project implements it.** Logstash applies Grok patterns, ECS field mapping and
date parsing. Records that fail parsing are routed to `siem-events-raw-*` — they are
retained, not discarded. Parsed records go to `siem-events-*` in Elasticsearch and to the
Kafka `siem-events` topic. In Flink, `EventParsingProcessFunction` re-validates JSON
structure and event-time validity; failures go out of a **side output** to the
`siem-events-dlq` Kafka topic.

**Why it matters.** Two distinct failure policies: parse failure at ingestion is
*retained for forensics*; structural/event-time failure in the stream is *quarantined for
reprocessing*. Neither silently drops data, and both are observable.

**Limitation.** The DLQ has no automated replay tooling in this baseline — records are
preserved, not automatically reprocessed.

**Likely follow-up.** *"What happens to an event with a malformed timestamp?"* → It fails
event-time validity in `EventParsingProcessFunction` and goes to the DLQ side output; the
lifecycle sink is unaffected. It is not silently assigned `now()`.

---

## 6. Kafka

**What it is.** The durable fan-out and the DLQ carrier.

**How this project implements it.**

| Topic | Producer | Consumer | Purpose |
|---|---|---|---|
| `siem-events` | Logstash | Flink `KafkaSource` | Standardized events |
| `siem-events-dlq` | Flink (side output) | (operational) | Quarantined records |
| `siem-alert-lifecycle` | Flink `KafkaSink` | SOAR worker | `alert.created` lifecycle contract |

Flink's `KafkaSource` is configured with group id `siem-detection` (or
`siem-detection-<jobKey>` in managed mode) and
`OffsetsInitializer.committedOffsets(OffsetResetStrategy.EARLIEST)` — it resumes from
committed group offsets, and falls back to `earliest` only on a genuine first run.

**Why it matters.** Offset reset strategy is a real operational decision: `latest` would
silently skip events produced while the job was down. `earliest` on first run only is the
conservative choice.

**Limitation / trade-off.** `earliest` fallback on a reset consumer group can produce a
large replay if offsets are ever lost. The `OffsetResetStrategy` guards the *first-run*
case, not the lost-offsets case.

**Likely follow-up.** *"How do consumer offsets relate to checkpoints?"* → Flink commits
Kafka offsets as part of checkpoint completion, which is what makes source-side replay
consistent with restored operator state. See §15–§16.

---

## 7. Flink runtime

**What it is.** The detection engine's execution environment.

**How this project implements it.**

```java
env.setParallelism(2);
env.enableCheckpointing(tuning.checkpointIntervalMs(), CheckpointingMode.EXACTLY_ONCE);
env.getCheckpointConfig().setCheckpointTimeout(tuning.checkpointTimeoutMs());
env.getCheckpointConfig().setMinPauseBetweenCheckpoints(tuning.minPauseBetweenCheckpointsMs());
env.getCheckpointConfig().setMaxConcurrentCheckpoints(1);
env.getCheckpointConfig().setTolerableCheckpointFailureNumber(tuning.tolerableCheckpointFailures());
```

Restart strategy is exponential delay with 10 attempts. Parallelism is 2, matched to a
2-slot task manager, with the Kafka source split across 2 parallel consumers over 3
partitions. Every operator is assigned an explicit `uid(...)`, which is what makes state
restorable across job upgrades.

**Why it matters.** Four choices here are deliberate:

- `maxConcurrentCheckpoints = 1` plus a minimum pause prevents checkpoint storms under
  backpressure — a common cause of cascading job failure.
- `tolerableCheckpointFailureNumber` means one failed checkpoint does not kill the job.
- Explicit `uid`s mean operator state can be mapped after a topology change. Without them,
  Flink cannot restore state.
- Parallelism matched to slots means no idle task manager slots and no queueing.

**Limitation.** `tolerableCheckpointFailureNumber > 0` deliberately trades a small
correctness window for availability: the job keeps running on a failed checkpoint and
retries. That is a conscious trade, not an oversight.

**Likely follow-up.** *"Why `EXACTLY_ONCE` for checkpointing if your sinks are
at-least-once?"* → See §16 — they govern different things.

---

## 8. Event time

**What it is.** Using the timestamp *inside the event* — when the security event actually
occurred — rather than when the pipeline happened to see it.

**How this project implements it.**

```java
DataStream<Event> parsedTimed = parsed
    .assignTimestampsAndWatermarks(
        WatermarkStrategy.<Event>forBoundedOutOfOrderness(Duration.ofSeconds(10))
            .withTimestampAssigner((e, ts) -> e.getTimestampMillis())
            .withIdleness(Duration.ofSeconds(60)))
    .uid("window-watermark");
```

The timestamp assigner reads `Event.getTimestampMillis()` — extracted during parsing, not
assigned at ingestion.

**Why it matters.** With processing time, a log source that buffers and flushes in bursts
would shift every window boundary by the buffer delay, and a replay would produce entirely
different windows. With event time, the same input produces the same windows regardless of
when it is processed. **This is the property that makes detection reproducible**, and it is
also what makes the deterministic alert `_id` meaningful — it includes event time, so the
id is stable across replays.

**Limitation.** Event time is only as good as the source clock. The pipeline trusts the
event's own timestamp; it does not reconcile against source clock skew.

**Likely follow-up.** *"What if the source clock is wrong?"* → Windows would be misplaced
for that source. There is no per-source clock-skew correction in this baseline.

---

## 9. Watermark

**What it is.** A monotonic signal that "no event with timestamp earlier than W is expected
to arrive." It is the trigger for event-time windows.

**How this project implements it.** `forBoundedOutOfOrderness(Duration.ofSeconds(10))`:
the watermark is `max_observed_event_time - 10s`. Windows fire when the watermark passes
the window end.

**Why it matters.** The watermark is the contract between "the stream is unordered" and
"windows can be evaluated." A watermark that advances too eagerly drops legitimate late
data; one that advances too slowly delays every window. Ten seconds is the chosen
compromise.

**Limitation / the trap.** **The watermark is not a completeness guarantee — it is a
bounded-lateness *assumption*.** Exceeding the bound is not an error state; it is data that
simply misses its window.

**Likely follow-up.** → §10 and §29-Q1.

---

## 10. Bounded out-of-orderness

**What it is.** The maximum amount of reordering the job agrees to absorb.

**How this project implements it.** 10 seconds. An event whose timestamp is within 10
seconds of the newest observed timestamp still lands in its correct window. An event
outside that bound arrives after its window may already have fired.

**Why it matters.** This is the single number that governs the false-negative/false-latency
trade-off for windowed detection. An SSH brute-force rule with a 5-minute window and a
10-second disorder bound tolerates normal network and log-shipping jitter while still
firing promptly.

**Limitation.** The bound is a fixed constant, not adaptive. A source with genuinely
delayed shipping (a batch uploader) can systematically exceed it.

**Likely follow-up.** *"How would you make it adaptive?"* → Per-source watermark strategies
or a measured p99 shipping delay per source, rather than one global constant. Note this is
a design proposal, not something implemented here.

---

## 11. Idleness

**What it is.** What happens to the watermark of a partition or key that has gone silent.

**How this project implements it.** `.withIdleness(Duration.ofSeconds(60))` — after 60
seconds without events, that source is marked idle and excluded from watermark
computation, so it cannot hold back the global watermark.

**Why it matters.** This is the concrete case that makes idleness necessary here. A
brute-force rule keys on `source.ip`. Once an attacker is blocked, that source goes silent.
Without idleness handling, the partition's watermark stalls at the last event, and **every**
window in the job stalls with it — including windows for completely unrelated keys. The job
would appear healthy and simply stop alerting.

**Limitation.** Sixty seconds is a latency floor for any key that legitimately goes quiet
mid-window.

**Likely follow-up.** *"Why is idleness a correctness issue and not just a latency issue?"*
→ Because a stalled watermark is a global stall: one silent source starves every window in
the job. That is a silent-failure mode, which is worse than a slow one.

---

## 12. Windows

**What it is.** Grouping events by key over a time interval for aggregation.

**How this project implements it.** Two window types, selected per rule:

```java
// slidingMinutes > 0 → sliding
keyed.window(SlidingEventTimeWindows.of(
        Duration.ofMinutes(wr.getWindowMinutes()),
        Duration.ofMinutes(wr.getSlidingMinutes())))
    .process(new WindowRuleFunction(wr));

// otherwise → tumbling
keyed.window(TumblingEventTimeWindows.of(Duration.ofMinutes(wr.getWindowMinutes())))
    .process(new WindowRuleFunction(wr));
```

`rule-ssh-brute-force-001` is sliding: a 5-minute window with a 1-minute step, threshold 5,
keyed on `source.ip`. Baseline rules use `TumblingEventTimeWindows.of(Duration.ofHours(...))`.

**Why it matters.** Tumbling windows have boundary blind spots. Five failures at 12:04 and
five at 12:06 fall into two separate 5-minute tumbling windows and never reach the
threshold of 5 — even though ten failures occurred in a 2-minute span. A sliding window
with a 1-minute step catches this because a window starting at 12:02 contains all ten.

**Limitation / trade-off.** Sliding windows evaluate the same events in multiple windows,
so one active attack produces multiple window hits. That is exactly why the window
suppressor exists (§14) — sliding coverage is preserved while duplicate *alert documents*
are collapsed.

**Likely follow-up.** *"What's the cost of a 1-minute step?"* → Five window evaluations
per event for a 5-minute window (window/step ratio), so increased state and CPU in exchange
for boundary coverage.

---

## 13. CEP

**What it is.** Pattern matching across a sequence of events over time — "A happened, then
B, within N minutes."

**How this project implements it.** `CEP.pattern(keyedStream, pattern)` where the pattern is
built from the rule declaration:

```java
p = p.next(step.name).where(cond);        // strict contiguity
p = p.followedBy(step.name).where(cond);  // relaxed contiguity
p = p.within(Duration.ofMinutes(cep.withinMinutes));
```

The shipped rule `rule-ssh-bruteforce-success-001` models the attack chain that window
rules cannot express: *N authentication failures followed by a successful login from the
same source IP within a bounded window*. A count-based rule cannot distinguish "brute force
happened" from "brute force succeeded" — that is a sequence, not a count.

**Why it matters.** Multi-stage attack detection is where CEP earns its place. The output
is handled by `BruteforceSuccessFunction`, which emits a critical alert carrying the full
chain.

**Trade-off: `next` vs `followedBy`.** `next` requires strict adjacency — no unrelated
event may occur between the two steps. `followedBy` allows intervening events. Strict
contiguity gives higher precision but misses the pattern whenever an unrelated event
interleaves, which in a real log stream is common. The rule declaration chooses per step.

**Likely follow-up.** *"What is the state cost of CEP?"* → The pattern keeps partial-match
state per key for the duration of `within(...)`. Long windows × high-cardinality keys = the
largest state in the job. Since the state is checkpointed, that directly affects checkpoint
size and duration.

---

## 14. Alert suppression

**What it is.** Collapsing repeated detections of the same thing into one alert.

**Two mechanisms, different time semantics — this is the subtle part:**

**(a) `AlertSuppressor` — single-event rules, processing time.**

```java
keyBy(AlertSuppressor::suppressionKey)   // rule_id + entity (source.ip, else user.name)
  .process(new AlertSuppressor(Duration.ofMinutes(suppressionMinutes)))
```

It uses a **processing-time (wall-clock)** window, evaluated in `onTimer` at window close.
The suppression key is `rule_id + entity`. On window close it emits one alert carrying the
**final count**, but keeps the **first** alert's original `@timestamp` so the deterministic
`_id` stays stable and the Elasticsearch write updates the same document.

**(b) `WindowAlertSuppressor` — window rules.**

A sliding window produces multiple overlapping hits for one active attack. This suppressor
keys by rule plus entity and applies `alertSuppressionMinutes` to collapse them, so sliding
coverage does not create duplicate alert documents.

**Why processing time for (a).** Suppression is a *notification rate limit*, not a
detection semantic. "At most one alert per rule per entity per hour" is a statement about
operator experience, which is measured in wall-clock time.

**Trade-off — and be honest about it.** Processing-time suppression is **not
replay-deterministic**. Replaying the same event stream at a different rate can produce a
different number of suppressed windows and therefore a different alert count. This is a
deliberate consequence of choosing wall-clock semantics, and it is a genuine difference
from the event-time windows used for detection.

**Likely follow-up.** *"Why not event time?"* → Because the requirement is operational
rate-limiting, not detection correctness. But if audit-grade reproducibility of suppression
were required, event-time suppression plus a stable per-window identity would be the
change.

---

## 15. Checkpointing

**What it is.** Periodic consistent snapshots of Flink operator state, used to restore a
job after failure.

**How this project implements it.** `CheckpointingMode.EXACTLY_ONCE`, bounded timeout,
minimum pause between checkpoints, `maxConcurrentCheckpoints = 1`, tolerable failure count,
exponential-delay restart with 10 attempts. State includes window contents, CEP partial
matches, suppressor state and baseline statistics.

**Why it matters.** Restoring this state consistently is what prevents a restart from
double-counting a window or resetting a baseline. The tolerances are what keep the job
alive under load: exclusive checkpoints and a minimum pause avoid checkpoint storms; a
tolerable-failure count prevents a transient storage blip from killing the job.

**The exact scope of the guarantee.**

> `EXACTLY_ONCE` here describes **Flink's internal state consistency and the source offset
> commit protocol**. It does *not* extend to non-transactional external sinks.

**Likely follow-up.** → §16 and §29-Q2.

---

## 16. Delivery semantics

**What it is.** What happens to an output record when a failure occurs between production
and consumption.

**How this project implements it — stated precisely:**

| Stage | Guarantee | Mechanism |
|---|---|---|
| Flink operator state | Checkpointed `EXACTLY_ONCE` | Consistent restore of window/CEP/suppressor/baseline state |
| Kafka **source** offsets | Committed with checkpoints | Consistent with restored state; first run → `earliest` |
| Kafka **sinks** (DLQ, lifecycle) | `DeliveryGuarantee.AT_LEAST_ONCE` | Duplicates possible on failure/restore |
| Elasticsearch alert writes | **Idempotent, not transactional** | Deterministic `_id` + `_update` upsert |
| Lifecycle events | At-least-once, deterministic `message_id` | Downstream can deduplicate |

**Why it matters — the honest framing.**

> **The platform does not claim end-to-end exactly-once.** It claims Flink state
> consistency plus idempotent convergence at the sinks.

This is the correct engineering answer, and it is stronger than the slogans. `EXACTLY_ONCE`
checkpointing and at-least-once sink delivery are not contradictory — the first governs
*when state is committed relative to source offsets*, the second governs *what happens to a
record already handed to a sink*. What makes the combination safe is the third row: the
sink write is idempotent because the document id is derived from the alert's own identity.

**Limitation.** Idempotence is a property of *this* sink. Any new sink added to the pipeline
must independently establish its own guarantee; it does not inherit one.

---

## 17. Duplicate-delivery convergence

**What it is.** The mechanism that makes at-least-once delivery produce a user-visible
"no duplicate alerts" outcome.

**How this project implements it.**

```java
/** sha1(rule_id + entity + event time). Replaying the same event yields the same _id,
    so the ES write becomes an idempotent overwrite instead of a duplicate alert. */
static String alertId(String element) {
    // entity = alert.entity, else source.ip, else user.name, else "unknown"
    return sha1Hex(ruleId + "|" + entity + "|" + ts);
}
```

and the write is an update, not an index:

```java
POST /siem-alerts/_update/<id>
```

A non-2xx response completes the future exceptionally — so no lifecycle event is emitted for
an alert that was not stored.

The lifecycle envelope then carries a deterministic message id:

```java
envelope.put("message_id", messageId("alert.created", "default", "alert", alertId, occurredAt));
```

**Why it matters.** This is the compensation for at-least-once delivery. Instead of trying to
prevent duplicates in the transport, the system makes duplicates *harmless*: the same
logical alert always maps to the same document, so a replay converges.

Note the interaction with suppression: the suppressor keeps the **first** alert's
`@timestamp` when it emits the final count. That is not cosmetic — it is what keeps `_id`
stable across suppression updates, so the updated count overwrites the original document.

**Limitation.** Convergence depends entirely on the id inputs staying deterministic. If
event time became processing time, or the entity fallback order changed, replay would start
producing new documents. This is a fragile-in-the-good-sense property: it is correct, but it
must be *maintained*.

**Likely follow-up.** → §29-Q5.

---

## 18. Elasticsearch

**What it is.** The search and read-model store for events, raw events and alerts.

**How this project implements it.**

| Index | Content |
|---|---|
| `siem-events-raw-*` | Records that failed Logstash parsing (retained for forensics) |
| `siem-events-*` | Parsed, ECS-shaped events |
| `siem-alerts` | Alerts, written via `_update` with the deterministic id |

Alert writes go through `AlertElasticsearchIndexer`, a `RichAsyncFunction` using Java's
`HttpClient` with a 5-second connect timeout and a 20-second request timeout, wrapped in
`AsyncDataStream.unorderedWait(..., 30, TimeUnit.SECONDS, ...)`. Security-aware
(`SIEM_ES_USERNAME` / `SIEM_ES_PASSWORD` basic auth when configured).

**Why it matters.** Two deliberate choices:

- **Async, not blocking.** A synchronous sink would make Elasticsearch latency part of the
  pipeline's throughput. Async with `unorderedWait` decouples them — at the cost of
  ordering, which is safe here precisely because writes are idempotent by id.
- **`unorderedWait` is only safe because of §17.** If writes were non-idempotent, out-of-order
  completion would matter.

**Limitation.** `unorderedWait` plus retries means an alert may be re-sent; that is exactly
the duplicate case §17 handles. Also, a persistently failing Elasticsearch will
backpressure the async operator (bounded buffered requests) and eventually stall detection.

---

## 19. PostgreSQL

**What it is.** The control plane's transactional store.

**How this project implements it.** PostgreSQL 16.4, schema managed by **Flyway** (19
migrations), accessed via MyBatis. It holds case state, lifecycle outbox rows, SOAR
execution state, leases, attempts and audit records.

**Why it matters.** This is where the *transactional* guarantees live, and they are a
different kind from the data plane's. A case update is a transaction with referential
integrity; an alert write is an idempotent upsert into a search index. Putting both in one
store would force one of them to compromise.

**Limitation.** Two stores means no single distributed transaction across them — see §20.

**Likely follow-up.** *"Why not put everything in PostgreSQL?"* → Then you lose
Elasticsearch's log search and aggregation, which is the analyst-facing capability. The
split is a deliberate allocation of concerns, not an accident of tooling.

---

## 20. Cross-store consistency

**What it is.** A case is a single business object whose parts live in Elasticsearch
(alert/event search) and PostgreSQL (case state). Keeping them coherent without a
distributed transaction is the problem.

**How this project implements it.** An **outbox**: state changes that must produce an
external message write the message into an outbox table *inside the same database
transaction* as the state change. A publisher then delivers from the outbox, with **lease
ownership, lease expiry, reclaim and dead-letter** semantics on the outbox rows. Deterministic
`message_id` values make re-delivery idempotent.

**Why it matters.** The outbox pattern is the standard answer to "dual write" — and the
interesting part is not the write, it is the *failure* handling. A naive outbox leaks
messages when a publisher dies mid-flight. Leases with expiry plus reclaim mean an
abandoned message is retried by another publisher rather than stuck forever; a fencing token
prevents a resumed owner from double-publishing after its lease was reclaimed.

**Limitation.** Consistency is **eventually** consistent and bounded by outbox publish
latency and retry policy. There is no cross-store snapshot isolation, so a reader can
observe a case in an intermediate state between the two stores.

**Likely follow-up.** *"Why not 2PC?"* → Two-phase commit across Elasticsearch (which does
not participate in XA) is not available; and 2PC trades availability for atomicity, which is
the wrong trade for an alerting pipeline. The outbox plus idempotent consumers gives
at-least-once with convergence, which is what the domain actually needs.

---

## 21. Detection-control vs. detection-runtime

**What it is.** A deliberate separation between *what detection should be running* and *the
thing that runs it*.

**How this project implements it.**

- **`detection-control`** — rule definitions, schedules, and desired/observed runtime state.
  It has **no physical deployment responsibility**.
- **`detection-runtime`** — transport-neutral runtime **port contracts**: an interface the
  runtime must satisfy, with no assumption about deployment.
- **`applications/detection-controller`** — a standalone reconciler process
  (`WebApplicationType.NONE`, adapter disabled by default) that reconciles desired against
  observed.
- **The Flink job** — the actual runtime, deployed separately.
- **`applications/control-api`** explicitly *does not* perform physical detection deployment.

The managed path additionally maintains immutable runtime artifacts, claim/lease/fencing on
runtime ownership, and *real observed state* rather than assumed state.

**Why it matters.** This is the difference between "we have rules in a database" and
"detection is a reconciled control loop." Three consequences:

1. The control plane never needs to know Flink exists.
2. The runtime can be replaced without changing rule semantics.
3. Desired state and observed state are separate facts, so "the rule is enabled in the
   database" never gets confused with "the rule is running."

**Limitation.** The managed-detection path has documented Phase 5B boundaries (see
`docs/design/managed-detection-runtime.md`) — the adapter model is deliberately narrow.

**Likely follow-up.** *"Why `WebApplicationType.NONE` for the controller?"* → It is a
reconciler, not a service. It has no API surface, so giving it a web server would only add
attack surface and startup cost. Its liveness is expressed through health and observed
state, not HTTP.

---

## 22. SOAR architecture

**What it is.** The deterministic response execution engine: alerts become playbook
executions, which advance through a graph of nodes.

**How this project implements it.**

| Layer | Responsibility |
|---|---|
| `soar-core` | Transport-independent engine: `SoarExecutionEngine`, `SoarGraphRouter`, node handlers, SPI (`SoarConnector`, `SecurityOperationPort`), validation rules |
| `soar-adapters` | Kafka lifecycle consumption and HTTP connector adapters |
| `soar-worker-runtime` | Kafka consumer, health, worker loop |

Node handlers cover business actions, conditions, connectors, human approval, parallel
branches, loops, joins and end nodes — i.e. the execution graph is a real workflow model, not
a linear script. Validation rules check graph topology, node types, edge ports, conditions
and device actions before execution.

**Retry/backoff is not hand-rolled around the whole thing.** The engine advances node by
node; each node handler is invoked for one node; the execution state records where it is.
Pausing at human approval leaves state in PostgreSQL, and resumption reads that state —
which is the difference between a workflow and a script in memory.

Leases: execution advancement is lease-protected (`SoarLeaseLostException` exists as an
explicit failure mode). A worker that lost its lease must not advance the execution.

**Why it matters.** "Respond to an alert" is a distributed workflow with concurrency,
pauses and failures. The lease and the explicit `SoarLeaseLostException` are what make the
engine safe under a multi-worker deployment: two workers cannot both advance the same
execution.

**Limitation.** Playbook authoring is validated structurally and at publish time, but the
playbook library is a demonstration set.

**Likely follow-up.** *"What happens if a worker dies mid-node?"* → The lease expires, the
execution is reclaimable by another worker, and the engine resumes from persisted state at
that node. Node-level idempotency is the responsibility of the connector/action, and that is
the honest boundary.

---

## 23. Failure modes

Walk these confidently; each maps to a concrete mechanism.

| Failure | Behaviour | Mechanism |
|---|---|---|
| Malformed log line at ingestion | Retained in `siem-events-raw-*` | Logstash failure routing |
| Invalid JSON / bad event time in stream | Quarantined to `siem-events-dlq` | `EventParsingProcessFunction` side output |
| A source goes silent mid-window | Other windows still fire | `withIdleness(60s)` — §11 |
| An event arrives after its window fired | Misses that window | No allowed lateness — §8, §10 |
| Flink job restarts | State restored, offsets consistent | `EXACTLY_ONCE` checkpoints — §15 |
| Kafka sink failure after checkpoint | Possible duplicate lifecycle event | `AT_LEAST_ONCE` + deterministic `message_id` — §16, §17 |
| Elasticsearch write returns non-2xx | Exception, **no lifecycle event emitted** | `AlertElasticsearchIndexer` completes exceptionally — §17 |
| Same event replayed | Overwrites the same alert document | Deterministic `_id` + `_update` — §17 |
| Elasticsearch persistently down | Backpressure stalls the async operator | Bounded `esMaxBufferedRequests` |
| Checkpoint store blip | Job keeps running | `tolerableCheckpointFailureNumber` — §7 |
| Checkpoint storm under load | Prevented | `maxConcurrentCheckpoints=1` + min pause — §7 |
| SOAR worker dies mid-execution | Lease expires, another worker reclaims | Lease + fencing; `SoarLeaseLostException` |
| Outbox publisher dies mid-publish | Message reclaimed and retried | Lease expiry + reclaim on outbox rows — §20 |
| Two workers advance one execution | Prevented | Leases — §22 |

---

## 24. Key code paths

**Path A — an event becomes an alert.**

```text
Kafka siem-events
  → KafkaSource (committed offsets, earliest fallback)
  → EventParsingProcessFunction   [invalid → DLQ side output]
  → assignTimestampsAndWatermarks (10s bounded OOO + 60s idleness)
  → keyBy(entity) → window / CEP / baseline operator
  → alert JSON
  → AlertSuppressor | WindowAlertSuppressor   [suppression window]
  → union of all branches
  → AlertElasticsearchIndexer (async, _update with sha1 id)   [non-2xx → exception]
  → AlertLifecycleEventMapper (deterministic message_id)
  → Kafka siem-alert-lifecycle
```

**Path B — an alert becomes an executed response.**

```text
Kafka siem-alert-lifecycle
  → soar-worker-runtime consumer
  → LifecycleEvent validation (message_id required)
  → SoarLifecycleRuntime → SoarExecution graph
  → SoarExecutionEngine advances node by node (lease-protected)
  → node handlers: business action | condition | connector | human approval | parallel | loop | join
  → PostgreSQL: execution state, attempts, audit
```

**Path C — detection configuration changes.**

```text
rule definition (control-plane state)
  → detection-control: desired state
  → applications/detection-controller reconciles against observed state
  → runtime artifact (immutable) + manifest
  → Flink job startup: RuntimeManifestVerifier.verify(rulesDir, arguments, decls)
  → RuleConfigLoader → RuleBuilder → operators
```

The manifest check in Path C is the detail worth calling out: **the job does not silently
run a different rule set than the one that was verified.** A mismatch is a startup failure.

---

## 25. Architecture trade-offs

| Decision | Chosen | Alternative | Why |
|---|---|---|---|
| Two planes | Elasticsearch + PostgreSQL | One store | Different correctness models: search/aggregation vs. transactions |
| Consistency | Outbox + idempotent consumers | 2PC / distributed transaction | Elasticsearch does not participate in XA; availability matters more than atomicity here |
| Delivery | At-least-once sinks + deterministic ids | Kafka transactions | Convergence is cheaper and sufficient than exactly-once plumbing |
| Alert identity | `sha1(rule, entity, event_time)` | Random UUID | Replay convergence |
| Watermark | Fixed 10s bound | Adaptive per source | Simpler, predictable; same value applies everywhere |
| Window | Sliding 5m/1m for brute force | Tumbling only | Boundary blind spots are a real false-negative source |
| Suppression (single-event) | Processing time | Event time | Rate-limiting is an operator-experience concern |
| Detection config | Separate control/runtime | Direct deploy | Config changes decoupled from runtime replacement |
| CEP contiguity | Per-step `next`/`followedBy` | Always `next` | Precision vs. real-world interleaving |
| ES sink | Async `unorderedWait` | Synchronous | Decouples throughput; ordering is unnecessary because writes are idempotent |
| Checkpointing | Exclusive + tolerable failure | Aggressive | Avoid storms; survive a transient blip |
| Concurrency | Leases + fencing | Assume single worker | Makes multi-worker deployment safe by construction |

---

## 26. Current limitations

Stated without hedging. None of these is hidden; each is a scope boundary.

**Streaming / event time**

- **No allowed lateness and no late-event side output.** An event arriving after its window
  fired contributes nothing to that window and is not separately captured. Mitigated by a
  small disorder bound, not eliminated.
- **Suppression for single-event rules is processing-time**, so suppression counts are not
  replay-deterministic (§14).
- Event time trusts the source clock; no skew reconciliation.
- Watermark bound is a fixed global constant, not adaptive per source.

**Delivery / consistency**

- Kafka sinks are at-least-once; duplication is *converged*, not prevented.
- Cross-store consistency is eventual, bounded by outbox publish latency; no cross-store
  snapshot isolation.
- The DLQ has no automated replay tooling.

**Operational**

- Parallelism is fixed at 2, matched to a 2-slot task manager — a baseline configuration,
  not an autoscaled one.
- The rule set is a demonstration set (6 rules), not production detection content.

**Production hardening not closed**

- TLS, authentication and least-privilege across ES/Kafka are documented gates
  (`docs/design/security-rbac.md`), not shipped defaults.
- High availability, multi-node deployment and failover are outside the current baseline.
- No formal SLO/error-budget process; operational verification is documented and scripted
  (`infra/validate-deployment.sh`, health scans) rather than automated in CI.

---

## 27. What production hardening would require

Ordered by risk, not effort.

1. **Security defaults.** TLS everywhere, mutual auth for Kafka, scoped Elasticsearch roles,
   secret management instead of environment variables. Currently a documented gate.
2. **High availability.** Multiple Flink task managers and a highly available JobManager;
   Kafka and Elasticsearch replication factors appropriate to the deployment; connection
   pooling and failover for PostgreSQL.
3. **Backpressure and capacity.** Derived parallelism from measured partition and event
   volume rather than a fixed 2; tuned async sink buffer limits; explicit alerting on Kafka
   consumer lag and Flink backpressure.
4. **DLQ operations.** Automated replay tooling, DLQ depth alerting and a documented
   remediation runbook — currently records are preserved but manually handled.
5. **Late-data handling.** If the domain needs it: allowed lateness with a side output, and a
   decision about whether late events re-fire or are reported separately.
6. **Detection content pipeline.** Rule testing/regression harness, rule versioning and
   staged rollout, coverage reporting against a threat model.
7. **Cross-store reconciliation.** A periodic reconciler that detects and repairs divergence
   between Elasticsearch read models and PostgreSQL state.
8. **Observability SLOs.** Define availability, latency and lag objectives with error budgets,
   and wire DLQ depth, consumer lag and checkpoint duration into alerting.
9. **Schema evolution.** Explicit event-schema versioning with compatibility rules and a
   migration path for stored events.
10. **Disaster recovery.** Tested restore for Elasticsearch snapshots and PostgreSQL backups,
    with a documented RPO/RTO.

---

## 28. Likely interview questions

**Opening**

1. *Walk me through what happens to a log line from arrival to alert.* → §4 Path A.
2. *Why two storage systems?* → §20, §25.
3. *What's the hardest part of this system?* → Event-time correctness plus convergence under
   replay; both are invisible when working and silent when broken.

**Streaming**

4. *What is a watermark, really?* → A monotonic completeness assumption, not a guarantee. §9.
5. *Why bounded out-of-orderness rather than allowed lateness?* → §29-Q1.
6. *What breaks if a source stops sending events?* → §11: a global window stall, a silent
   failure mode.
7. *Why sliding instead of tumbling windows for brute force?* → §12: boundary blind spots.
8. *When would you use CEP instead of a window?* → §13: sequences, not counts.
9. *How much state does CEP keep?* → Per-key partial matches for `within(...)`; likely the
   largest state in the job, so it drives checkpoint size. §13.

**Reliability**

10. *Is this system exactly-once?* → §29-Q2. No end-to-end claim; state consistency plus
    idempotent convergence.
11. *How do you prevent duplicate alerts on restart?* → §17: deterministic `_id` + upsert.
12. *What happens if Elasticsearch is down?* → §23: exceptions, bounded async buffer,
    backpressure, and no lifecycle event for an unstored alert.
13. *How do you keep a case consistent across two stores?* → §20: outbox with leases and
    fencing, eventually consistent.
14. *Why not 2PC?* → §20: no XA participant in Elasticsearch, wrong availability trade.

**Design / operations**

15. *How does a rule change get deployed?* → §24 Path C, including the manifest verification.
16. *Why is detection-control separate from detection-runtime?* → §21.
17. *What stops two workers from advancing the same execution?* → §22: leases and fencing.
18. *How do you know the pipeline is healthy?* → Health scans, end-to-end smoke tests and
    deployment validation (`docs/operations.md`, `infra/validate-deployment.sh`); gaps noted
    in §26.
19. *What would you change if you started over?* → Adaptive per-source watermark bounds and
    a late-event side output; automated DLQ replay; derived parallelism.

---

## 29. Deep follow-up questions

### Q1. "Why is a watermark not the same as allowed lateness?"

**The distinction.** A watermark is an *assertion about expected completeness*; allowed
lateness is a *retention policy for the window's state after that assertion was made*.

- The **watermark** answers: "when may I evaluate this window?" With bounded
  out-of-orderness of 10s, the watermark is `max_event_time - 10s`, and the window fires
  when the watermark passes the window end.
- **Allowed lateness** answers a different question: "after the window fired, for how much
  longer do I keep its state so a late event can *update* the already-emitted result?"

Once a window fires, Flink keeps no state for it by default. `allowedLateness(x)` retains
that window state for a further `x`, and a late event arriving within `x` causes the window
to **re-fire** with a corrected result. Late elements beyond that are dropped — or, if a
side output is configured, captured separately. **Neither is configured here.**

**Concretely in this project.** With a 5-minute sliding window and a 10-second disorder bound:
the window result is emitted as soon as the watermark passes its end. If an event with an
in-window timestamp arrives afterwards, it does not update that result and is not routed
anywhere. That is the precise, observable consequence of the current configuration.

**The three-way framing that shows you understand it:**

| Setting | Question it answers | Effect |
|---|---|---|
| Bounded out-of-orderness | How much reordering is *absorbed*? | Shifts the watermark; events inside the bound still land correctly |
| Idleness | When is a *silent* source removed from watermark computation? | Prevents one quiet key from stalling every window |
| Allowed lateness | How long is *fired window state retained* for correction? | Enables re-fire on late data; costs state and duplicate downstream emissions |

They are orthogonal. Increasing out-of-orderness does not give you allowed-lateness
behaviour — it only delays the watermark, so fewer events arrive "late" in the first place.
The trade-off differs too: out-of-orderness adds **latency**; allowed lateness adds **state
and duplicate emissions**.

**If asked to fix it.** Two options: (a) `allowedLateness` plus a side output for
still-later elements, accepting that corrected window results must be emitted downstream —
which would require duplicate-tolerant handling, and here the deterministic alert `_id`
would actually absorb the re-fire cleanly; or (b) accept the bound and increase
out-of-orderness if measured shipping delay justifies it. Option (a) is the more correct
change if late data is a real, measured phenomenon rather than a theoretical one.

### Q2. "How can Flink checkpointing coexist with an at-least-once Kafka/output path?"

**Short answer.** They govern different things, and the gap between them is closed by
idempotent sinks rather than by the transport.

**What `CheckpointingMode.EXACTLY_ONCE` actually covers.**

- Flink takes a consistent snapshot of operator state, aligned at a barrier across the
  topology, so restored state matches a single point in the input stream.
- The Kafka source commits offsets **as part of checkpoint completion**, so on restore, the
  source resumes from exactly where the restored state expects — no gap and no replay
  relative to state.
- That combination is what Flink calls exactly-once *state consistency*. It is a property of
  Flink's internals plus source offset commit, both of which Flink controls.

**What it does not cover.** A sink outside Flink's transactional control. `EXACTLY_ONCE`
checkpoint mode does not make an arbitrary external write atomic with the checkpoint. To get
end-to-end exactly-once you need either a two-phase-commit sink participating in
checkpoints, or a transactional destination with idempotent producers plus a transactional
read.

**What this project does instead.** The sink guarantee is *chosen explicitly* as
`DeliveryGuarantee.AT_LEAST_ONCE`:

```java
.setDeliveryGuarantee(DeliveryGuarantee.AT_LEAST_ONCE)
```

and correctness is obtained downstream:

1. **Alert writes are idempotent by construction** — the document id is
   `sha1(rule_id | entity | event_time)`, so a replayed write updates the same document
   (§17). A duplicate delivery is a no-op, not a duplicate alert.
2. **Lifecycle events carry a deterministic `message_id`** derived from the alert id and
   occurrence time, so an at-least-once re-delivery is deduplicable by the consumer. The
   control plane's `lifecycle_outbox` is keyed by `message_id`.
3. **The lifecycle event is emitted only after a successful Elasticsearch write** — a
   non-2xx completes the async future exceptionally, so no downstream system learns about an
   unstored alert.

**The one-sentence answer.** *Flink guarantees that my operator state and my source offsets
are consistent with each other; it does not and cannot guarantee that a write to a
non-transactional sink happens exactly once. I chose at-least-once at the sink and made the
sink idempotent, so duplicates converge instead of accumulating. That's a stronger position
than claiming exactly-once I don't have — because I can point at the mechanism.*

**Anticipated follow-up: "Why not a two-phase-commit Kafka sink?"** → Flink's
`EXACTLY_ONCE` Kafka sink requires transactional producers and imposes read-committed
consumer configuration and commit-on-checkpoint latency. For the alert path it would add
latency and operational complexity to buy a guarantee the deterministic alert id already
provides. It would be worth revisiting for a sink that cannot be made idempotent.

**Anticipated follow-up: "Where would this break?"** → If a sink write were *not* idempotent
(a side effect with no natural key), at-least-once delivery would produce real duplicates
and the current design would be insufficient. That is the exact condition under which I would
move to a transactional sink.

### Q3. "What happens to a late event?"

It fails to contribute to its window if the window has already fired, and it is not captured
separately — there is no allowed-lateness buffer and no late-event side output. With a
10-second disorder bound and 60-second idleness, "late" in practice means *very* late
(tens of seconds or more of shipping delay). The honest framing: the system trades
late-data completeness for bounded state and simple downstream semantics. §10, §26, §29-Q1.

### Q4. "Why is the alert `_id` a hash instead of a UUID?"

Because a UUID is unique per *emission*, and what I need is uniqueness per *logical alert*.
`sha1(rule_id | entity | event_time)` is a pure function of the alert's identity, so the same
detection produces the same document id on every replay. That converts the Elasticsearch
write into an idempotent upsert and makes at-least-once delivery harmless. A UUID would
require an external deduplication step instead.

*Follow-up:* "Which field is the entity?" → `alert.entity` if declared, else `source.ip`,
else `user.name`, else `unknown` — a documented precedence order, because the id must be
computable for every rule category.

### Q5. "Why do you call it convergence rather than idempotence?"

They are related but the emphasis differs. Idempotence is the property of the write (same id
→ same document). Convergence is the property of the *system*: after any number of replays
and restarts, the observable state approaches one correct result. Convergence is what the
domain needs — "I replayed the stream and did not get duplicate alerts" — and idempotent
writes are the mechanism that produces it. It also correctly signals that this is *eventual*,
not transactional: during a replay, intermediate states are observable.

### Q6. "How does the system avoid duplicate alerts when a sliding window hits repeatedly?"

Two layers. The sliding window legitimately produces multiple overlapping hits for one
active attack — that is the price of boundary coverage. `WindowAlertSuppressor` keys by rule
plus entity and applies an alert-suppression window, collapsing those hits. And even if two
did escape, the deterministic `_id` would make the second write update the same document.
Belt and braces, deliberately. §12, §14, §17.

### Q7. "What is the single most fragile part of this design?"

The determinism of identity. Everything that makes at-least-once safe — the alert `_id` and
the lifecycle `message_id` — depends on those ids being pure functions of stable inputs. The
suppressor going out of its way to preserve the *first* alert's `@timestamp` is a good
illustration: it looks like a detail but it is load-bearing, because changing it would change
the document id on suppression updates. Any future change that makes an id input
non-deterministic (event time → processing time, entity precedence reordered) would silently
convert convergence back into duplication. That is the thing I would protect with a test.

### Q8. "Why is single-event suppression using processing time a defensible choice?"

Because the requirement it serves is operational, not analytical. "At most one SSH
auth-failure alert per source IP per hour" is a statement about analyst attention, and
analyst attention is measured in wall-clock time. Using event time would make suppression
behave differently depending on how fast the log source ships, which is not what an operator
means by "an hour." The honest cost, which I would state unprompted: suppression counts are
then not replay-deterministic, unlike the detection windows. §14.
