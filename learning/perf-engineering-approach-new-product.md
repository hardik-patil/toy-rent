# Interview Answer — "A new product is being built. As a performance engineer, what's your approach?"

A structured answer, grounded in the ToyRental project as the worked example. The
one-minute version is first; the rest is the detail to draw on when the interviewer digs in.

---

## The 60-second answer

> "I treat performance as a discipline that runs across the whole SDLC, not a test phase at
> the end. It starts in **design** — I turn business expectations into measurable NFRs and
> SLOs, build a workload model, and review the architecture for the obvious performance
> risks. Then I build **realistic tests** — the right scenarios, real data volumes, real
> traffic mix with think-time — and run them as a progression: baseline, load, stress,
> scalability, soak, spike. For each run I **correlate client-side and server-side
> metrics** to locate the bottleneck to a specific resource or layer, fix **one thing**,
> and prove it with an identical before/after run. Finally I close the loop into
> **production** — the same SLOs become live monitoring and capacity planning. The
> principle underneath all of it is *measure, don't guess*, and *model reality, not a
> convenient synthetic*."

---

## The process, start to finish (the sequence)

This is the chronological version — "how do you start, and what happens next", step by
step, with the artifact each step produces and when you can move on.

### Step 0 — Get in early
**Trigger:** a new product is in design / architecture, before code is "done".
**Do:** ask for the PRD, the architecture diagram, and whatever traffic expectations exist.
Read them and **write down every question you can't answer yet** — that list is your first
deliverable and it drives Step 1.

### Step 1 — Establish performance requirements (NFRs → SLOs)
**With:** PO / BA (for volume and impact) and the architect (for feasibility).
**Do:** agree expected + peak concurrency and request volume; rank the critical user
journeys by business impact; set a per-journey SLO — latency percentile (p95/p99),
success-rate target, *at a stated load* — plus an error budget so pass/fail is objective.
If the product is new and has no data, record explicit planning assumptions.
**Artifact:** an SLO document (this project: `loadtest/SLOs.md`).
**Exit:** the PO has signed off on the targets.

### Step 2 — Build the workload model
**Do:** define the traffic *mix* (e.g. 80% browse / 12% detail / 8% booking), the arrival
pattern and peak windows, think-time per journey, session shape, and the data profile
(row volumes and cardinality that match production).
**Artifact:** a one-page workload model (feeds the scenario weights and the data seeding).
**Exit:** the mix and volumes are agreed as "representative".

### Step 3 — Architecture & code risk review
**Do:** walk each critical-path call chain and look for the usual suspects — synchronous
fan-out, N+1 queries, missing/!composite indexes, unbounded list endpoints,
connection-pool sizing, cache strategy and cold-start behaviour, single-partition queues,
retry-without-backoff, JVM heap/GC defaults, and whether services are stateless enough to
scale horizontally.
**Artifact:** a risk register — each row: *risk · expected symptom under load · the test
that would prove it · proposed fix · now or deferred*.
**Exit:** risks logged and prioritised (you don't fix them yet — a test proves the cost first).

### Step 4 — Test plan
**Do:** map scenarios 1:1 to the critical journeys; pick the test types you'll run and say
what each answers (baseline, load, stress, scalability, soak, spike); define the test
environment and call out where it differs from production; specify data setup and tooling;
set entry/exit criteria per test.
**Artifact:** the test plan + a run matrix (scenario × type × load level).
**Exit:** plan reviewed by the eng lead.

### Step 5 — Environment & observability prep
**Do:** stand up a production-like environment (or document the deltas honestly); seed
representative data volume; build the dashboards for every SLI (latency percentiles per
journey) and for each resource (CPU, heap/GC, DB pool active/pending, DB, queue lag,
per-pod CPU). **Verify the monitoring works before you generate load.**
**Exit:** you can see every SLI and resource metric on a dashboard with the system idle.

### Step 6 — Script and validate
**Do:** build the scripts; parameterise threads/ramp/duration/host; correlate dynamic
values (extract the token, feed it downstream); assert on every response; separate
*expected* failures (a legitimate 409) from *real* ones; add think-time. Validate at 1
user in the GUI, then smoke at low concurrency headless. Peer-review the script.
**Artifact:** versioned test scripts + data files.
**Exit:** smoke run is green and the correlation/assertions demonstrably work.

### Step 7 — Baseline
**Do:** a single-user, warm run of each scenario.
**Purpose:** establishes the performance floor *and* confirms scripts + environment +
monitoring all work end to end.
**Artifact:** baseline numbers in the run log.

### Step 8 — Execute the progression
**Do:** run smallest-first — load → stress → scalability → soak → spike. Discard the
warm-up window. **Change one variable per run.** Keep a run log (date, build SHA, profile,
key numbers, verdict, hypothesis).

### Step 9 — Analyse and locate the bottleneck
**Do:** read results in order — error rate first, then p95 (not average), then throughput
vs concurrency (a plateau while threads climb = saturation). Correlate client-side and
server-side. Apply the resource-saturation method: for each resource check utilisation /
saturation / errors; the first to saturate is the bottleneck, the rest are symptoms.
Confirm the *mechanism*, don't just correlate (`EXPLAIN ANALYZE`, a thread dump, a GC log).
**Artifact:** a finding — "bottleneck is X at layer Y, evidence Z, it maps to risk-register item N".

### Step 10 — Fix and re-test
**Do:** apply **one** fix. Re-run the **identical** test. The before/after diff is the
result. Re-check the whole SLO set — a fix often moves the bottleneck downstream. Iterate
Steps 8–10 until the SLOs hold at expected load, with headroom toward peak.

### Step 11 — Report and go/no-go
**Artifact:** a per-journey SLO pass/fail table, headroom vs peak load, residual risks, an
error-budget statement, and a clear recommendation (ship / ship-with-caveats / hold).
**To:** PO and eng lead.

### Step 12 — Productionise and close the loop
**Do:** the SLOs become live monitoring with alerting on error-budget burn; the
scalability runs become a capacity model ("N replicas ⇒ X throughput at SLO") with scaling
triggers; the scenarios go into a CI regression suite. Post-launch, compare real
production traffic to the Step 2 model and re-baseline the assumptions.

---

## Reference: each phase in depth

### 1. Design / requirements — shift left

Before any code is "done", I get answers to:

- **Who uses it, how much?** Expected and peak concurrent users, requests/day, growth
  curve, seasonality. If there's no data (new product), I set explicit planning
  assumptions with the PO and write them down — they're revised once live, but you can't
  test against "fast".
- **What are the critical journeys?** Rank by business impact. For ToyRental: browse
  (funnel top), booking+pay (the revenue moment), month-end report (financial close).
- **Turn that into SLOs.** Per journey: latency percentile (p95/p99, not average),
  success-rate target, at a stated load. Plus an error budget so "pass/fail" is
  objective. → this project's `loadtest/SLOs.md`.
- **Workload model.** The traffic *mix* (ToyRental: 80% browse / 12% detail / 8%
  booking), arrival pattern, think-time, session shape. A test without this is measuring
  a fiction.
- **Architecture review for performance risk.** Synchronous call chains, N+1 queries,
  missing indexes, unbounded lists, connection-pool sizing, cache strategy, single points
  of contention (locks, single-partition queues), retry-without-backoff, GC/heap config.
  Most bottlenecks are *predictable from the design* — I flag them early even if we defer
  the fix until a test proves the cost.

### 2. Test strategy & environment

- **Scenarios** map 1:1 to the critical journeys, scripted so each is reusable/composable
  (a mixed test is the journeys weighted by the workload model).
- **Test types, each answering a different question:**
  | Type | Question |
  |---|---|
  | Baseline | single-user, warm — what's the floor? |
  | Load | do we meet SLOs at expected peak? |
  | Stress | where's the knee, and is degradation graceful or a cliff? What saturates first? |
  | Scalability | does adding replicas / CPU / heap actually buy throughput? Linearly? |
  | Soak | leaks, pool churn, GC drift, queue lag over hours |
  | Spike | burst tolerance and recovery time |
- **Environment:** as production-like as possible, and I'm explicit about where it isn't.
  If the load generator shares hardware with the system under test, absolute numbers are
  only valid *relative to another run on the same box* — I say so rather than quoting
  them as capacity.
- **Test data:** production-representative *volume and cardinality*. ToyRental's missing
  composite index is invisible at 8 seed rows and obvious at 50k — the data volume is
  part of the test, not a detail.

### 3. Scripting & tooling

- Parameterise everything (threads, ramp, duration, target host) so one script walks the
  whole matrix without edits.
- Correlate dynamic values properly (extract the JWT from login, feed it downstream).
- **Assert on every response** — under load a `500` returns faster than a healthy `200`;
  without assertions an outage looks like a latency *improvement*.
- Distinguish *expected* failure from *real* failure (a booking `409` on a genuine date
  conflict is correct behaviour, not an error).
- Run headless for measurement; GUI only for authoring. Never load-test through GUI
  listeners.
- Add think-time — without it you built a stress test, whatever you meant to build.

### 4. Execution

Run in progression, smallest first: smoke → baseline → load → stress → scalability →
soak → spike. Discard warm-up (cold JIT, empty caches). Change **one variable per run**.
Keep a run log: date, build SHA, profile, key numbers, verdict, hypothesis.

### 5. Analysis — locating the bottleneck

- Read results in order: **error rate first**, then **p95** (not average), then
  **throughput vs concurrency** — a plateau while threads keep climbing is saturation.
- **Correlate client and server.** Client p95 high but server p95 fine ⇒ network / load
  generator / connection exhaustion. Both high ⇒ real server work.
- **Resource method (USE / saturation):** for each resource — CPU, memory/GC, DB
  connection pool, DB itself, thread pools, queues, downstream calls — check utilisation,
  saturation (queue depth, pending), errors. The first one to saturate is the bottleneck;
  the rest are symptoms.
- Go layer by layer: load balancer → app threads → connection pool → database → cache →
  message queue → downstream services.
- Confirm the mechanism, don't just correlate: e.g. `EXPLAIN ANALYZE` to *see* the
  `BitmapAnd`, a thread dump to *see* threads parked on the pool, a GC log to *see* the
  pause.

### 6. Fix & regression

- Fix one thing. Re-run the **identical** test. The **before/after diff** is the
  deliverable — a single run in isolation proves little.
- Re-check the whole SLO set — a fix can move the bottleneck downstream (bigger pool ⇒
  now the DB is the limit).
- Add the scenario to a regression suite so the fix stays fixed.

### 7. Production & feedback loop

- The SLOs become **live monitoring** (same percentiles, same journeys) with alerting on
  budget burn.
- **Capacity planning:** from the scalability runs, "N replicas ⇒ X throughput at SLO" →
  headroom and scaling triggers.
- Feed real production traffic shape back into the workload model; re-baseline the
  planning assumptions.

---

## Worked example — ToyRental's seeded bottlenecks

The project intentionally ships six known bottlenecks (CLAUDE.md, "Performance
Engineering"). The approach above finds each:

| Design smell flagged in review | Test that proves the cost | Fix, verified by before/after |
|---|---|---|
| No composite index on `toys(category, age_group, is_active, status)` | Browse load at 50k rows — p95 rises faster than linear; `EXPLAIN` shows `BitmapAnd` of two single-column scans | add composite index; re-run identical browse test |
| No Couchbase cache warming on startup | Restart Couchbase, hit browse immediately — latency cliff for ~1 min as every request falls through to Postgres | warm cache on `ApplicationReadyEvent` |
| HikariCP pool = 10 | Booking stress — p95 steps up in plateaus, `hikaricp_connections_pending > 0` | raise pool / shorten the `SELECT … FOR UPDATE` critical section |
| Kafka topics at 1 partition | Burst of bookings — `booking.confirmed` consumer lag grows and never drains; availability-freshness SLO missed | 6 partitions, higher consumer concurrency |
| No circuit breaker on the Razorpay (WireMock) call | Fault-inject the stub to 503 — threads pile up in `payments/*`, retry storm, cascading timeout | Resilience4j CB — fast-fail + recovery |
| JVM default heap (256m) | Soak — p95 sawtooths in lockstep with GC pauses, heap near ceiling | `-Xmx512m -XX:+UseG1GC`, confirm via JFR |

---

## What the interviewer is checking for (and the pitfalls)

- **Shift-left** — do you engage at design, or just run scripts at the end? (The weak
  answer starts at "write JMeter scripts".)
- **Targets from the business** — SLOs derived from user journeys and impact, not
  arbitrary round numbers.
- **Realistic workload** — mix, think-time, pacing, data cardinality. Not "10,000 threads
  hitting one endpoint with no pacing".
- **You know the test types apart** — and *why* you'd pick stress vs soak vs spike.
- **Client + server correlation** — a candidate who only reads the JMeter summary is
  missing half the picture.
- **Systematic bottleneck method** — resource saturation / layer-by-layer, not "I'd look
  around".
- **Controlled change** — one variable, identical before/after, regression suite.
- **Close the loop** — production observability and capacity planning, not "job done when
  the test passes".
- **Communication** — you can give a go/no-go with a risk statement and an error-budget
  argument, not just a wall of graphs.
