# ToyRental — Performance SLOs & KPIs

Owner view (BA / Product). These are the targets a load/stress/soak run is measured
against, and the map from "which SLO broke" to "which bottleneck". Companion:
[PLAN.md](PLAN.md) (how to run), [../learning/jmeter-fundamentals.md](../learning/jmeter-fundamentals.md).

> **Environment reality:** load generator and the cluster share one machine, single node,
> no metrics-server. Treat the absolute targets below as the *product intent*. The
> trustworthy signal on this box is **(a)** before/after the same change on the same
> hardware and **(b)** the *shape* of degradation (graceful vs cliff), not whether p95 is
> literally 480 or 620 ms. Re-baseline the numbers on real infra before quoting them.

---

## 1. Scale assumptions (what "normal" and "peak" mean)

Premium kids' toy rental, Navi Mumbai, solo/small operation. No real telemetry yet — these
are the PO's planning figures; revise once live.

| Assumption | Value | Basis |
|---|---|---|
| Registered customers | ~2,000 | Single-city niche, year 1 |
| Daily active users | 60–100 | ~5% DAU/registered |
| Catalogue views / day | ~5,000 | ~50 views per browsing session, evening-weighted |
| Bookings / day | 30–50 | ~1% view→booking conversion |
| Concurrent users — **expected peak** | **40** | Evening 19:00–22:00 band |
| Concurrent users — **campaign / festival peak** | **120** | Diwali/Raksha Bandhan gifting spike, ~3× |
| Month-end report | 1 run / month | Batch, off-hours |
| Traffic mix (steady state) | 80% browse / 12% detail+availability / 8% booking | Scenario S3 |

---

## 2. Critical user journeys, ranked by business impact

| # | Journey | Why it matters | Endpoints |
|---|---|---|---|
| J1 | **Browse catalogue** | Top of funnel, highest volume; slow browse = lost discovery | `GET /api/v1/toys` (filtered) |
| J2 | **Toy detail + availability** | Consideration step; "is it free for my dates" | `GET /api/v1/toys/{id}`, `GET .../availability`, `.../availability/calendar` |
| J3 | **Create booking + pay** | **The revenue moment.** Any failure or timeout here is direct lost income | `POST /api/v1/bookings` → `POST /api/v1/payments/webhook` |
| J4 | **Login / register** | Gate to J3; BCrypt makes it intentionally non-trivial | `POST /api/v1/customers/login`, `/register` |
| J5 | **My bookings / receipt** | Post-purchase trust; PDF receipt | `GET /api/v1/customers/me/bookings`, `GET /api/v1/bookings/{id}/receipt` |
| J6 | **Admin daily ops** | Time-boxed each morning — deliveries/pickups list must load fast | `GET /api/v1/admin/bookings/today/deliveries`, `.../pickups`, `.../overdue` |
| J7 | **Month-end report** | Monthly batch; late/failed = no financial close | `POST /api/v1/admin/reports/trigger` → `monthly.report.generated` |
| A1 | **Availability freshness** (async) | After a booking confirms, the toy must stop showing as bookable within seconds | Kafka `booking.confirmed` → Couchbase `avail::toy-*` |
| A2 | **Notification latency** (async) | Confirmation WhatsApp should feel immediate | Kafka `booking.confirmed` → `notifications` row `SENT` |

---

## 3. SLIs — what we actually measure

| SLI | Definition | Source |
|---|---|---|
| **Latency p95 / p99** | Server-side response time per journey label, 1-min windows | Grafana `histogram_quantile(0.95, sum(rate(http_server_requests_seconds_bucket[1m])) by (le,uri))`; JMeter dashboard p95 as the user-facing cross-check |
| **Success rate** | `1 − (5xx + unexpected-4xx) / total`. A **legit 409** on J3 (real date conflict) is **not** a failure | JMeter Error% (with the S3 assertions) + `sum(rate(http_server_requests_seconds_count{status=~"5.."}[1m]))` |
| **Sustained throughput** | Max req/s a journey holds while still inside its latency + success SLO | JMeter "Transactions/s"; throughput-vs-active-threads plateau |
| **Async freshness (A1/A2)** | Wall-clock from `booking.confirmed` produced → effect visible (Couchbase updated / notification `SENT`) | `kafka-lag-exporter` consumer-group lag; app log timestamps |
| **Batch duration (J7)** | Trigger → `monthly.report.generated` published | `pdf.generation.duration` Timer + Kafka event timestamps |
| **Conflict rate (context, not an SLO)** | `rate(booking_conflict_total[1m])` — expected under booking contention; watch it doesn't dominate | Prometheus |

---

## 4. SLO targets

Two tiers per journey: **Expected** (40 concurrent — must hold) and **Peak** (120 concurrent
— should hold; controlled degradation acceptable, errors not). Warm-up (first 60 s) excluded
from every measurement.

| Journey | Metric | Expected (40 VU) | Peak (120 VU) |
|---|---|---|---|
| **J1 Browse** | p95 / p99 latency | ≤ 500 ms / ≤ 1000 ms | ≤ 1200 ms / ≤ 2500 ms |
| | success rate | ≥ 99.5% | ≥ 99.0% |
| **J2 Detail + availability** | p95 (detail) | ≤ 400 ms | ≤ 900 ms |
| | p95 (availability check) | ≤ 300 ms | ≤ 700 ms |
| | success rate | ≥ 99.5% | ≥ 99.0% |
| **J3 Create booking** (`POST /bookings`, full call incl. lock + Feign + WireMock order) | p95 / p99 | ≤ 1200 ms / ≤ 2500 ms | ≤ 3000 ms / ≤ 5000 ms |
| | success rate (legit 409 excluded) | ≥ 99.0% | ≥ 98.0% |
| **J3 Payment webhook** | p95 | ≤ 800 ms | ≤ 1800 ms |
| **J4 Login** | p95 | ≤ 600 ms | ≤ 1200 ms |
| | success rate | ≥ 99.9% | ≥ 99.5% |
| **J5 My bookings** | p95 | ≤ 500 ms | ≤ 1100 ms |
| **J5 Receipt PDF** | p95 | ≤ 1500 ms | ≤ 3000 ms |
| **J6 Admin daily lists** | p95 | ≤ 800 ms | ≤ 2000 ms |
| **J7 Month-end report** | trigger → generated | ≤ 30 s (1 month of data) | n/a (off-peak) |
| **A1 Availability freshness** | p95 confirm → Couchbase updated | ≤ 5 s | ≤ 15 s |
| **A2 Notification latency** | p95 confirm → `SENT` | ≤ 10 s | ≤ 30 s |

**Aggregate gate for a "steady-state PASS":** at Expected load, every J1–J6 p95 within target
**AND** overall success ≥ 99.5% **AND** (for soak) no upward drift in p95 / heap-after-GC /
Kafka lag across the run.

### Error budget

99.5% steady-state success ⇒ **0.5% budget**. Per 20-min S3 load run at ~18 rps ≈ 21,600
requests ⇒ ~108 failures tolerated. Blow the budget on two consecutive runs of the same
build → that build does not ship / the regression is P1.

---

## 5. Load levels for the test matrix

| Level | Profile | Purpose | SLO expectation |
|---|---|---|---|
| **Smoke** | 2–5 VU, 1–2 min | script/env sanity | all green, ignore latency |
| **Expected** | 40 VU, S3 mix, 20 min, think 1–3 s | the contract | all Expected-tier targets hold |
| **Peak** | 120 VU, S3 mix, 30 min | festival spike | Peak-tier targets; success must hold even if latency degrades |
| **Stress** | step 25→50→100→200→400, no think | find the knee & first resource to exhaust | SLOs *will* break — record *where* and *how* (graceful vs cliff) |
| **Soak** | 40 VU (~70% of expected), 2–4 h | leaks, pool churn, GC drift, lag build-up | Expected targets **plus** flat trend lines |
| **Spike** | 5 → 150 for 90 s → 5 | burst tolerance & recovery | errors < 5% during burst; p95 back to baseline ≤ 2 min after |

---

## 6. SLO breach → bottleneck decoder

The reason these SLOs exist: each breach *shape* points at one of CLAUDE.md's seeded
bottlenecks. Do not pre-fix — prove it, fix it, re-run the identical test, diff.

| SLO that breaks | Signature to confirm | Bottleneck | Fix |
|---|---|---|---|
| **J1 p95** climbs *faster than linear* with load; Postgres shows `BitmapAnd` of two single-column index scans; buffer-cache hit ratio fine but CPU/query high | run `EXPLAIN ANALYZE` on the browse query at 50k rows | **#1** missing composite `idx_toys_browse` | add the composite index (Flyway `V*`) |
| **J1 / J2 p95** cliffs right after a Couchbase restart, recovers after ~1 min | correlate with `couchbase-0` restart time; cache-miss counter spike | **#2** no cache warming on startup | warm cache on `ApplicationReadyEvent` |
| **J3 p95** steps up in plateaus; `hikaricp_connections_pending > 0` sustained; logs "connection is not available" | Grafana Hikari panel during J3 stress | **#3** HikariCP pool too small / lock held too long | raise pool; shorten the `SELECT … FOR UPDATE` critical section |
| **A1 / A2 freshness** SLO misses; `kafka-lag-exporter` lag on `booking.confirmed` grows and never drains | Grafana Kafka lag panel during a booking burst | **#4** 1 partition per topic | bump to 6 partitions; raise consumer concurrency |
| **J3 success rate** collapses when WireMock/Razorpay returns 5xx; threads pile up in `payments/*`; retry storm | fault-inject the WireMock stub (503/slow), watch thread state | **#5** no circuit breaker on the Razorpay call | add Resilience4j CB → fast-fail + recovery |
| **Any journey p95** shows sawtooth spikes during **soak**, correlated with `jvm_gc_pause_seconds`; heap runs near ceiling | JFR / GC log over the soak window | **#6** JVM default heap (256m) | `-Xmx512m -XX:+UseG1GC`, confirm via JFR |
| **J1 p95** fine but **throughput plateaus** far below target while node CPU is pegged | `container_cpu_usage_seconds_total` by pod vs node capacity | not app — **node ceiling** (documented) | scale replicas manually / raise Docker Desktop resources / move load gen off-box |

---

## 7. Reporting — what a run writes down

Per matrix cell, one line in the run log:

```
date | git SHA | scenario+level | THREADS/RAMP/DURATION
p95 per journey (client) | overall success % | sustained rps
server: J1 p95, Hikari pending max, GC pause rate, Kafka lag max, node CPU
verdict: PASS / FAIL (which SLO) | hypothesis: bottleneck #_
```

A run is only useful next to another run. The deliverable of a bottleneck investigation is
the **before/after diff of two identical runs**, not a single dashboard.
