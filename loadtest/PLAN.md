# ToyRental — Performance Test Plan

The guided plan for practising JMeter scripting and bottleneck analysis on this stack.
You build and run; this doc is the map. Companion reading:
[SLOs.md](SLOs.md) (the targets each run is graded against, and the SLO-breach →
bottleneck decoder), [README.md](README.md) (install/run),
[../learning/jmeter-fundamentals.md](../learning/jmeter-fundamentals.md) (JMeter concepts),
[../learning/composite-index-load-testing.md](../learning/composite-index-load-testing.md)
(the worked example for bottleneck #1).

The test matrix in §4 below uses starting thread counts; **SLOs.md §4 has the actual
pass/fail targets** (p95, success rate, freshness) per user journey at Expected and Peak
load.

---

## 1. What we're doing and what we're not

**Goal:** get fluent at (a) writing a correct JMeter plan — correlation, parameterisation,
assertions, non-GUI runs — and (b) reading client + server metrics together to name a
bottleneck, fix it, and prove the fix with a before/after diff.

**Not the goal:** producing "official" capacity numbers. See the environment caveats next.

### Environment caveats — read before trusting any number

| Constraint | Consequence |
|---|---|
| Load generator (JMeter) runs on the **same laptop** as Docker Desktop + the cluster | Above modest concurrency, JMeter competes with the pods for CPU. Absolute throughput/latency are only meaningful **relative to another run on the same box**. Always compare, never quote in isolation. |
| Single-node cluster, `node CPU ~100% of a 600% cap` at idle-ish steady state (see STARTUP.md) | Headroom is small. A stress run *will* saturate the node — that's fine, just know that "the service degraded" may partly be "the node ran out of CPU". Watch node CPU alongside service metrics. |
| No `metrics-server` | The HPAs show `<unknown>/60%` and **cannot autoscale on CPU**. Scalability tests (S*/scalability below) require **manually** scaling replicas (`kubectl scale deployment ... --replicas=N`) — or install metrics-server first (`--kubelet-insecure-tls`). |
| New Relic agent now connects (real key) | Small constant overhead per JVM. Consistent across runs, so before/after diffs stay valid. |
| Logical date is Couchbase-driven | Booking `startDate` must be `>= LogicalDateService.getCurrentDate()` (a `@FutureOrPresent` bean-validation check), not just `>= today`. Check `logical-date::current` before building S2 data. |

---

## 2. Test types — definitions used in this plan

| Type | Question it answers | Shape |
|---|---|---|
| **Load** | Does the system meet its SLOs at expected peak traffic? | Fixed concurrency (or fixed rps) at the *target* level, with think-time, held for 10–20 min. |
| **Stress** | Where does it break, and *how* — graceful degradation or cliff? What's the first resource to exhaust? | Ramp concurrency up in steps past the target until error rate climbs or latency runs away. Little/no think-time. |
| **Scalability** | Does adding replicas (horizontal) or CPU/heap (vertical) actually buy more throughput? Linear? | Run the *same* load at 1, 2, 4 replicas (or default vs tuned heap). Plot throughput & p95 vs capacity. |
| **Soak / endurance** | Do leaks, pool churn, GC drift, or Kafka lag build up over time? | Moderate load (≈70% of target) held for 1–4 h. Watch trend lines, not point values. |
| **Spike** | Does a sudden burst cause errors, and does it recover afterwards? | Idle → instant jump to high concurrency for 1–2 min → back to idle. Check recovery time. |

---

## 3. Scenarios

Three scenarios, increasing in scripting difficulty. Do them in order.

### S1 — Catalogue browse (read-heavy)  · *plan exists: `catalogue-browse.jmx`*

- **Endpoint:** `GET /api/v1/toys?category=${category}&ageGroup=${ageGroup}` on `:8081`.
- **Auth:** none (public).
- **Data:** `data/browse_params.csv` (fix the CSV path bug first — see README). Widen the
  pool after seeding.
- **Prep:** run `seed_toys_bulk.sql` (50k rows) — the bottleneck is invisible at 8 rows.
- **Assertions:** response code `200`; body contains `"content"`.
- **Targets the bottleneck:** #1 missing composite index `idx_toys_browse`.
- **Scripting practice:** CSV Data Set Config, property-driven thread group, HTML
  dashboard, before/after diff.

### S2 — Booking creation flow (write-heavy)  · *to build → `plans/booking-flow.jmx`*

The critical path from `CLAUDE.md`'s Booking Flow. Per iteration:

1. `POST /api/v1/customers/login` (`:8082`, body `{"phone":"9821012345","password":"password"}`)
   → JSON Extractor `$.accessToken` → `token`. **Do once per thread** (Once Only
   Controller / setUp Thread Group), not every loop.
2. `GET /api/v1/toys/{toyId}/availability?from=&to=` (`:8081`) — pick `toyId` from a CSV
   of real ids (`SELECT id FROM toys WHERE status='AVAILABLE' LIMIT 500`).
3. `POST /api/v1/bookings` (`:8082`, bearer `${token}`) with body:
   ```json
   {"toyId":"${toyId}","startDate":"${startDate}","endDate":"${endDate}",
    "rentalType":"WEEKLY","deliveryFlat":"B-204","deliveryBuilding":"Neelkanth Heights",
    "deliveryArea":"Kharghar","deliveryCity":"Navi Mumbai","deliveryPincode":"410210"}
   ```
   `startDate` = logical current date or later (compute with `${__timeShift(...)}`);
   `endDate` = `startDate + 7d` for WEEKLY.
4. `POST /api/v1/payments/initiate` (`:8082`, bearer) body `{"bookingId":"${bookingId}"}`
   → extract `razorpayOrderId`.
5. `POST /api/v1/payments/webhook` (`:8082`, **no auth**) body
   `{"razorpayOrderId":"${orderId}","razorpayPaymentId":"pay_load_${__UUID}","razorpaySignature":"sig_load"}`.

- **Assertions:** login `200`, availability `200`, **booking create `201`**, payment
  initiate `200`, webhook `200`; booking body contains `"status":"PENDING"`.
- **Expected conflicts:** two threads booking the same `toyId` for overlapping dates →
  `409 TOY_NOT_AVAILABLE`. That's **correct behaviour** — assert it separately (tag those
  as expected, not errors) via a Response Assertion with "Or" logic or a JSR223 check.
- **Targets the bottlenecks:** #3 HikariCP pool exhaustion (booking-service pool=30, plus
  the pessimistic `SELECT ... FOR UPDATE`), #5 no circuit breaker on the WireMock Razorpay
  call, and Kafka consumer lag (#4) on `booking.confirmed` downstream.
- **Scripting practice:** multi-step correlation, per-thread auth, dynamic dates,
  distinguishing "expected 409" from "real error", transaction controllers.

### S3 — Mixed realistic traffic (steady state)  · *to build → `plans/mixed.jmx`*

One Thread Group, a **Throughput Controller** (or weighted `Random Controller`) splitting:

| Weight | Action |
|---|---|
| 80% | S1 browse |
| 12% | `GET /api/v1/toys/{toyId}` detail + `GET .../availability/calendar` |
| 8%  | full S2 booking flow |

- Think-time **on** (Uniform Random 1–3s). This is the load/soak/spike workhorse.
- **Scripting practice:** controllers, weighted mix, module reuse across plans.

---

## 4. The test matrix

Numbers are **starting points for this laptop** — adjust to keep JMeter itself under
~1.5 cores (watch `docker stats` / node CPU). Walk each row via `-J` properties, one run
per cell, fresh `.jtl` + dashboard each time.

| # | Scenario | Type | Threads / profile | Ramp | Duration | Think-time | Pass criteria (tune) |
|---|---|---|---|---|---|---|---|
| 1 | S1 | Load | 50 | 30s | 15 min | 1–3s | p95 < 400ms, error < 1%, throughput stable |
| 2 | S1 | Stress | step 25→50→100→200→400 (Stepping/Concurrency TG) | per step | 3 min/step | none | find the knee: where p95 > 2s or error > 5% |
| 3 | S1 | Scalability | fixed 100, no think-time | 60s | 10 min | none | run at toy-service `replicas=1,2,4` (manual scale). Throughput should rise ~linearly; if flat, bottleneck is downstream (DB), not the service |
| 4 | S1 | Before/after index | matrix row 1 settings | — | — | — | rerun identical plan after `idx_toys_browse` migration; expect lower p95 & CPU per request |
| 5 | S2 | Load | 20 | 30s | 15 min | 1–2s | booking-create p95 < 800ms, real-error < 1% (409 conflicts excluded), no Hikari pending |
| 6 | S2 | Stress | step 10→20→40→80 | per step | 3 min/step | none | first thing to saturate? watch Hikari `pending`, DB active connections, node CPU |
| 7 | S2 | Circuit-breaker | 40, no think | 20s | 10 min | none | make WireMock fail/slow (`scripts/` or edit stub delay); with CB off = retry storm & thread pile-up; add Resilience4j CB, rerun, expect fast-fail |
| 8 | S3 | Load | 60 | 60s | 20 min | 1–3s | all endpoints meet their p95; error < 1% |
| 9 | S3 | Soak | 40 | 60s | 2–4 h | 1–3s | no upward drift in p95 / heap-after-GC / Kafka lag; RSS stable |
| 10 | S3 | Spike | 5 → 150 for 90s → 5 | instant | 12 min total | 1–3s | errors during spike < 5%; p95 back to baseline within 2 min of drop |

---

## 5. What to watch — client + server, side by side

**Client (JMeter HTML dashboard):**
- Error % first. Then p95 (not average). Then throughput vs active threads — when
  throughput stops rising while threads keep climbing, that's saturation.
- *Response Times Over Time* + *Active Threads Over Time* on one screen = where it broke.
- *Connect Time* rising ⇒ connection exhaustion (client sockets or server accept queue),
  not CPU.

**Server (Grafana / Prometheus at `:3000` / `:9090`):**

| Signal | Query / source | Tells you |
|---|---|---|
| HTTP p95 per endpoint | `histogram_quantile(0.95, sum(rate(http_server_requests_seconds_bucket[1m])) by (le, uri))` | server-side latency, excludes client/network — see [../learning/prometheus-percentile-metrics.md](../learning/prometheus-percentile-metrics.md) |
| HikariCP | `hikaricp_connections_active`, `hikaricp_connections_pending` | pending > 0 sustained = pool #3 exhausted |
| JVM heap / GC | `jvm_memory_used_bytes{area="heap"}`, `rate(jvm_gc_pause_seconds_sum[1m])` | GC pressure #6; heap-after-GC trending up over a soak = leak |
| Kafka consumer lag | `kafka-lag-exporter` metrics | notification/toy-service consumers falling behind #4 |
| Postgres | `postgres-exporter`: `pg_stat_activity_count`, `pg_stat_database_blks_hit` ratio | connection ceiling, buffer-cache hit rate (S1 index) |
| Couchbase | `couchbase` scrape job | availability cache hit/miss (`toy.availability.cache.*`) — bottleneck #2 |
| Node CPU | `sum(rate(container_cpu_usage_seconds_total{namespace="toy-rental"}[2m])) by (pod)` | is the *node* the limit rather than the app |

**Deep-dive when a run points at the JVM:** thread dump / JFR — see
[../learning/heapdump-jfr.md](../learning/heapdump-jfr.md).

---

## 6. Bottleneck hypotheses (from CLAUDE.md — do NOT pre-fix)

| # | Seeded bottleneck | Exposed by | Expected signature | Fix (after proving) |
|---|---|---|---|---|
| 1 | Missing composite index on `toys(category, age_group, is_active, status)` | S1 load/stress @ 50k rows | `BitmapAnd` of two single-col index scans in `EXPLAIN ANALYZE`; CPU per request higher than it should be; p95 climbs with concurrency faster than linear | add `idx_toys_browse` via Flyway `V*`, rerun matrix row 4 |
| 2 | No Couchbase cache warming on startup | restart Couchbase, then immediately hit S1/S3 | cache-miss spike, all threads fall through to Postgres, latency cliff for ~first minute | warm cache on `ApplicationReadyEvent` |
| 3 | HikariCP pool too small (was 10; booking pool now 30) | S2 stress (row 6) | `hikaricp_connections_pending` > 0, booking p95 steps up in plateaus, `connection is not available` in logs | raise pool, or shorten the `FOR UPDATE` critical section |
| 4 | Kafka 1 partition/topic initially | S2/S3 burst of bookings (row 5, 8, spike) | consumer lag on `booking.confirmed` grows and doesn't drain; notification delay | bump partitions to 6, scale consumer concurrency |
| 5 | No circuit breaker on WireMock Razorpay call | S2 row 7 (make the stub 503/slow) | threads pile up in `payments/initiate`, retry storm, cascading timeout | add Resilience4j CB, expect fast-fail + recovery |
| 6 | JVM default heap (256m) | S3 soak (row 9) | frequent GC pauses in `jvm_gc_pause_seconds`, sawtooth heap near ceiling, p95 spikes correlated with GC | `-Xmx512m -XX:+UseG1GC`, confirm via JFR |

Method every time: **reproduce → measure → apply one fix → rerun the identical plan →
diff the two dashboards.** One variable per iteration.

---

## 7. Suggested session flow for today

1. **Install & smoke** — `winget install Apache.JMeter`; open `catalogue-browse.jmx` in
   the GUI; fix the CSV path; run 1 thread / 5 loops with View Results Tree; confirm green
   200s and that `${category}` is substituting.
2. **Seed** — load `seed_toys_bulk.sql` (50k rows). Verify: `SELECT count(*) FROM toys;`
3. **Baseline EXPLAIN** — `EXPLAIN ANALYZE` the browse query at 50k rows; save the plan
   text (this is your "before").
4. **S1 load (matrix row 1)** — non-GUI, save `.jtl` + dashboard. Read it against the
   Grafana panels. Write down p95, error %, throughput, node CPU, buffer-cache hit ratio.
5. **S1 stress (row 2)** — Stepping Thread Group. Find the knee. Note which server metric
   moved first.
6. **Fix #1** — add the composite index (Flyway migration), redeploy toy-service, rerun
   row 1 **unchanged**. Diff. That diff is the deliverable.
7. If time remains, start **scripting S2** (`plans/booking-flow.jmx`) — the correlation
   chain is the real practice; running it can wait for the next session.

Record each run in a short log (date, matrix row, git SHA, key numbers, what you
concluded) — `results/` is git-ignored, so keep the log somewhere tracked or in your
notes.
