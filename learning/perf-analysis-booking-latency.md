# Performance Analysis Report — `POST /bookings` latency

**Status:** root causes identified; the low-risk, high-leverage changes implemented,
deployed (`booking-service:1.0.12`), and directionally validated with a probe. One
structural change (lock held across a network call) is designed but **not** applied —
see §8.
**Bottleneck ID:** #2 in `learning/bottleneck-faced-resolved.md`.
**Companion:** `learning/perf-analysis-login-bottleneck.md` (#1), whose fix this run
builds on top of.

---

## 1. Executive summary

In the mixed 60-vuser load test, `POST /api/v1/bookings` showed **p95 2.0 s, p99 3.0 s,
max 8.7 s**, with a nominal 20.8 % error rate. Two thirds of that "error" rate was **not
the booking code**: 665 × `401` from vusers with no JWT (the login-timeout cascade,
bottleneck #1) and 1,827 × `409` "toy not available" (expected — the plan re-books a few
hot toys). Real server errors: 2 × `500` (~0.06 %).

The latency itself came from **how booking-service calls toy-service**, not from booking
logic:

1. Two sequential Feign calls per booking (`getToy`, `checkAvailability`) to a
   CPU-saturated toy-service, over a **non-pooled HTTP client** (a fresh TCP connection
   per call).
2. **No Feign timeouts** — a slow toy-service response could hang a booking for up to
   Feign's 60 s default read timeout, *while it holds the pessimistic overlap lock and a
   DB connection*.
3. **HikariCP pool of 10**, exhausted during the concurrent-booking + login burst.

### Changes made (all committed, low-risk)

| change | effect |
|---|---|
| Added `feign-hc5` → pooled Apache HttpClient 5 for all Feign clients | connection reuse instead of per-call TCP setup |
| `spring.cloud.openfeign.client.config.*` connect-timeout 1 s / read-timeout 3 s | fail fast; a slow dependency can't hang the lock for 60 s |
| `spring.cloud.openfeign.httpclient.time-to-live: 30s` | also the bottleneck-#3 fix — recycles pinned keep-alive connections |
| HikariCP `maximum-pool-size` 10 → 30 | headroom under concurrent bookings |

### Result (booking probe, 30 concurrent, isolated)

| | before (1.0.11) | after (1.0.12) |
|---|---:|---:|
| cold round p95 | ~8.0 s | ~3.4 s |
| warm round p95 | ~1.3–1.8 s | ~1.7–2.4 s (within noise) |

Cold-path latency more than halved — consistent with removing per-call connection setup
and bounding the timeouts. The warm-path difference is inside the probe's noise (see §7 —
the probe can't cleanly isolate this). **The real acceptance test is a re-run of
`ToyRentalMixed.jmx`.**

---

## 2. Context

Same system, test plan, window, and environment as
`learning/perf-analysis-login-bottleneck.md` §2. `POST /bookings` is the heaviest
write path:

```
create() @Transactional:
  1. toyServiceClient.getToy(toyId)              -- Feign -> toy-service
  2. toyServiceClient.checkAvailability(...)     -- Feign -> toy-service (reads Couchbase)
  3. bookingRepository.saveAndFlush(PENDING)     -- acquires the DB connection here
  4. bookingRepository.findOverlapping(...)      -- SELECT ... FOR UPDATE (pessimistic)
  5. paymentService.createPendingPayments():
       razorpayClient.createOrder(...)           -- Feign -> WireMock, INSIDE the lock
       INSERT payments x2
  6. return -> COMMIT (releases the lock)
```

---

## 3. Symptom

```
path                     n     err%   mean   p50    p90    p95    p99    max
POST /api/v1/bookings    3202   20.8   632    438   1601   2025   3008   8730
```

p95 2 s at only ~3.5 rps of bookings is high for what should be a couple of lookups plus
inserts.

---

## 4. Investigation

### Step 1 — Decompose the "20.8 % errors" (same technique as #1 Step 2)

```
code=409 (toy not available)  : 1827   success=true  (expected — plan books hot toys)
code=201 (created)            :  708
code=401 (unauthorised)       :  665   <- login-timeout cascade (bottleneck #1), not booking
code=500                      :    2   <- the only real failures
```

Real error rate ≈ 0.06 %. **This is a latency problem, not a failure problem.** The 401s
disappear once #1 is fixed; the 409s are the test design.

### Step 2 — Latency falls over the run

Mean RT per 60 s bucket: `1411 → 823 → 779 → … → 460 ms`. It tracks the login herd
clearing (bottleneck #1) — while 60 logins are eating the CPU and the connection pool,
bookings queue behind them. So part of booking latency was **#1 bleeding into #2** through
the shared pod and the shared 10-connection pool.

### Step 3 — Read the call path, cost each hop

- `getToy` + `checkAvailability`: two **sequential** Feign calls. toy-service p95 for its
  own browse traffic in this window was ~1.8 s (it's CPU-saturated and missing a composite
  index — see the "also observed" note in `bottleneck-faced-resolved.md`). Two serial
  calls into that = most of the booking p95.
- **Feign client**: `booking-service/pom.xml` had only `spring-cloud-starter-openfeign` —
  no `feign-hc5`/OkHttp. That means the default `HttpURLConnection`: a new TCP connection
  per call, no pooling, no reuse. Every booking pays connection setup twice.
- **Feign timeouts**: nothing in `spring.cloud.openfeign.client.config`. Defaults are
  ~10 s connect / 60 s read. A toy-service stall doesn't fail the booking quickly — it
  holds it (and its lock, and its DB connection) open.
- **The lock window**: `findOverlapping` is `@Lock(PESSIMISTIC_WRITE)`; the lock is held
  until COMMIT, and `createPendingPayments` — which makes the Razorpay/WireMock call — runs
  *inside* that window. WireMock answers in ~10 ms here, so this is a ~10–15 ms hold today,
  not the dominant cost — but it's the wrong shape and gets dangerous the moment that call
  is slow or real (see §8).
- **Pool**: `maximum-pool-size: 10` (`application.yml`). CLAUDE.md lists "HikariCP pool
  too small" as an intentional bottleneck; its documented target is 30.

### Root cause statement

> `POST /bookings` latency is dominated by **two serial Feign calls to a CPU-saturated
> toy-service over an unpooled HTTP client with no timeouts**. A small (10) connection
> pool and the bottleneck-#1 login herd sharing the same pod amplified it. The pessimistic
> overlap lock is also held across the Razorpay call — currently cheap (WireMock ~10 ms)
> but structurally wrong.

---

## 5. Fix (implemented)

`booking-service`, deployed as image `1.0.12`:

| # | Change | File |
|---|---|---|
| 1 | `feign-hc5` dependency → Spring Cloud OpenFeign auto-switches Feign to a pooled Apache HttpClient 5 | `pom.xml` |
| 2 | `spring.cloud.openfeign.httpclient`: `max-connections: 200`, `max-connections-per-route: 50`, `time-to-live: 30s` | `application.yml` |
| 3 | `spring.cloud.openfeign.client.config.{default,toy-service}`: `connect-timeout: 1000`, `read-timeout: 3000` | `application.yml` |
| 4 | HikariCP `maximum-pool-size` 10 → 30 | `application.yml` |

Verified in the running pod: `feign-hc5-13.2.1.jar` + `httpclient5` on the classpath;
`hikaricp_connections_max` = 30.

Not changed here: the Razorpay circuit breaker (resilience4j `razorpay` instance is
configured but deliberately not wired — CLAUDE.md's intentional bottleneck #5) and the
transaction structure (§8).

---

## 6. Validation

`loadtest/booking_probe.py` — authenticate once as a `cust-lt-*` user, then N concurrent
`POST /bookings`, each for a random toy from `loadtest/data/toy_ids.csv`; report latency
percentiles + a response-code histogram over several rounds.

**Before** (1.0.11 — has the #1 fix, not #2), 30 concurrent:

```
round 1 (cold): mean 7244  p95 7974  max 7976
round 2 (warm): mean 1002  p95 1753
round 3 (warm): mean  777  p95 1287
```

**After** (1.0.12), 30 concurrent:

```
round 1 (cold): mean 2822  p95 3433  max 3459
round 2 (warm): mean 1490  p95 2379
round 3 (warm): mean 1532  p95 2004
round 4 (warm): mean 1290  p95 1693
```

Cold p95 ~8.0 s → ~3.4 s. Warm rounds are within run-to-run noise of each other — see §7.

---

## 7. Why this measurement is weak (and what would be strong)

The probe can't cleanly isolate the Feign-pooling win:

- **~90 % of probe requests are `409`** — the toys table already has bookings from earlier
  JMeter runs for the P3D–P10D window, so most random toys collide. The 409 path is
  `getToy` + `checkAvailability` + return; it never reaches the lock or the payment insert,
  so pool size and the lock window don't show up.
- **`kubectl port-forward` pins to one booking pod** (see #1's report §7), so this is a
  single-pod number and the 30 "concurrent" bookings all land on one JVM.
- **toy-service load is uncontrolled** — it's been probed all day and has no composite
  index; its own latency varies round to round and feeds straight into the booking number.
- **30 samples per round.** Noisy at the tail.

The strong test is a **re-run of `loadtest/ToyRentalMixed.jmx`** with the #1 fixes (login
ramp-up) applied, comparing `POST /bookings` p95/p99 and, in Grafana, booking-service
`hikaricp_connections_pending` and the toy-service Feign call duration.

---

## 8. Not applied — move the lock off the network path

`findOverlapping`'s `PESSIMISTIC_WRITE` lock is held from step 4 until COMMIT, and the
Razorpay call in step 5 runs inside it. Today WireMock answers in ~10 ms so it's a
~10–15 ms hold, but:

- if the Razorpay call is ever slow, every concurrent booking for the same toy serialises
  behind it while holding a DB connection;
- CLAUDE.md's #5 (add a circuit breaker to this call) implies it *will* get failure
  handling, which means variable latency.

**Design (deferred):** take the lock, re-check overlap, insert the booking, **COMMIT**
(release the lock and connection); then in a second short transaction create the Razorpay
order and the pending-payment rows. On Razorpay failure, compensate: mark the booking
`FAILED` / roll it back explicitly.

**Trade-off that needs a decision first:** ~57 % of bookings in the test are `409`
conflicts. Today the Razorpay order is created *after* the overlap check, so a conflict
wastes nothing. If the order moves before the lock, every conflict creates (and abandons)
an order. The clean answer is the two-transaction split above (order *after* a successful
commit), which is a real refactor with a compensation path — not something to land
untested the evening before a shutdown. Do it with the JMeter plan available to verify.

---

## 9. Follow-ups

1. **Re-run `ToyRentalMixed.jmx`** with #1's ramp-up — the real acceptance for #1 and #2.
2. **Composite index on `toys`** (`CREATE INDEX idx_toys_browse ON toys (category,
   age_group, is_active, status)`) — cuts toy-service CPU, which is upstream of every
   booking. Lowest-risk single change with the widest effect. See
   `learning/composite-index-load-testing.md`.
3. The two-transaction split in §8.
4. Grafana panels: booking-service `hikaricp_connections_pending` /
   `hikaricp_connections_acquire_seconds`, and Feign `feign.Client` timer by client.

---

## 10. Reproduce

```bash
python loadtest/booking_probe.py --concurrency 30 --rounds 4        # RT + code histogram
curl -s localhost:8082/actuator/prometheus | grep hikaricp_connections_max   # -> 30
# revert: image tag back to 1.0.11 in k8s/services/booking-service/booking-service.yaml,
# remove feign-hc5 from pom.xml, restore hikari maximum-pool-size: 10 and drop the
# spring.cloud.openfeign block from application.yml.
```
