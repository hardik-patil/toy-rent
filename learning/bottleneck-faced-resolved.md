# Bottlenecks Faced & Resolved

Running log of performance bottlenecks found via JMeter load tests against the local
Kubernetes deployment, with the evidence that identified each one and the fix applied.
One section per bottleneck. Template: **Symptom → Evidence → Root cause → Fix → Verify.**

Related: `learning/composite-index-load-testing.md`, `learning/prometheus-percentile-metrics.md`,
`learning/jmeter-fundamentals.md`. Intentional bottlenecks list is in `CLAUDE.md`
("Performance Engineering — Bottlenecks to Find").

---

## Test run being analysed

| | |
|---|---|
| Plan | `loadtest/ToyRentalMixed.jmx` (mixed: catalogue browse + toy detail/availability/calendar + create booking; one login per vuser via a Once Only Controller) |
| Results | `loadtest/results/ToyRentalMixed.jtl` |
| Load | 60 vusers, flat, `LoopController.loops = -1`, scheduler on |
| Window analysed | 2026-09-03 06:14:34 – 06:35:28 UTC (Grafana dashboard `ad57mh4`), 78,915 samples |
| Target | `toy-service` / `booking-service`, 2 replicas each, hit directly on `localhost:8081/8082` via `kubectl port-forward` (api-gateway bypassed) |
| Pod limits | toy-service / booking-service: `cpu=1000m`, `memory=1Gi` (`CLAUDE.md` K8s table) |

### How the JTL was analysed

`elapsed`/`Latency`/`Connect`/`responseCode`/`success` columns, grouped by normalised URL
path (collapsing `toy-bulk-<n>` → `toy-{id}`), filtered to the Grafana window, then:
percentiles per path, response-code histogram for the suspect paths, and mean RT bucketed
into 60s slices to see saturation vs. warm-up. Ad hoc script kept in the session scratch,
not committed — the queries are simple enough to redo.

### Headline numbers (in-window)

| path | n | err% | mean | p90 | p95 | p99 | max |
|---|---:|---:|---:|---:|---:|---:|---:|
| `GET /api/v1/toys` (BROWSE) | 38,577 | 0.0 | 917 | 1554 | 1864 | 2577 | 4746 |
| `GET /api/v1/toys/toy-{id}` (+ `/availability`, `/availability/calendar`) | ~3,200 ea | 0.0 | ~770 | ~1400 | ~1670 | ~2340 | ~5100 |
| `POST /api/v1/bookings` | 3,202 | 20.8* | 632 | 1601 | 2025 | 3008 | 8730 |
| `POST /api/v1/customers/login` | 60 | 20.0* | **7219** | **10144** | **10148** | **10149** | 10149 |
| `POST /api/v1/payments/webhook` | 708 | 100 | 8 | 13 | 18 | 34 | 155 |

\* the error rates are mostly **not real failures** — see each section.

---

## Bottleneck #1 — `POST /customers/login`: p99 at the 10 s client timeout  ✅ RESOLVED — deployed & validated

> Full analysis + before/after: **`learning/perf-analysis-login-bottleneck.md`**.
> Deployed as `booking-service:1.0.11` + live `BCRYPT_STRENGTH=8` + `loadtest/seed_loadtest_customers.sql`.
> Isolated 60-way login p95 ~7.3 s → ~3.3 s (2.2×), 0 timeouts. Residual is a capacity limit — see that doc's §7–8.

### Symptom
Login mean **7.2 s**, p90–p99 pinned at **~10.1 s**, 12 of 60 samples fail as
`java.net.SocketTimeoutException` (JMeter's 10 s response timeout). No errors in the
booking-service log — the JVM is simply busy.

### Evidence
- Only **60 login samples** in a 15-minute window = exactly one per vuser, and **all 60
  land in the first 60 s bucket**. The plan logs in inside a `OnceOnlyController` at the
  start of each thread's first iteration, and ramp-up is short → 60 simultaneous logins.
- `POST /bookings` response-code histogram: 1,827×409 (toy not available — *expected*,
  `success=true`), 708×201, **665×401**, 2×500. The 401s are vusers whose login timed out:
  no JWT → every subsequent booking they attempt returns 401. So login latency
  **cascades** into ~21 % "errors" on bookings.
- All 60 vusers authenticate as the single `V6__seed_sample_customer.sql` row
  (`cust-0001`, phone `9821012345`, password `"password"`), whose hash is `$2a$10$…`
  (BCrypt cost 10).

### Root cause
Two compounding causes, both in booking-service:

1. **BCrypt cost 10 × 60 concurrent verifications on a ~1-vCPU pod.**
   `new BCryptPasswordEncoder()` uses Spring's default strength 10. BCrypt is deliberately
   CPU-hard and each `matches()` is single-threaded and un-parallelisable. 60 of them at
   once, competing for the pod's `cpu=1000m` (~1 core) *while the same pod also serves the
   booking mix*, serialise into a ~7 s tail; the slowest dozen cross JMeter's 10 s timeout.
   `SecurityConfig.passwordEncoder()`.

2. **`login()` was `@Transactional(readOnly = true)`.**
   The only DB work is one indexed `findByPhone` (~1 ms). Wrapping the method in a
   transaction made Spring borrow a HikariCP connection on entry and hold it until the
   method returned — i.e. across the whole ~7 s of BCrypt + RSA JWT signing, doing zero DB
   work. With `maximum-pool-size: 10` (still the unfixed intentional bottleneck #3) a burst
   of logins pins the pool and starves booking traffic on the same service.
   `CustomerService.login()`.

The thundering herd itself (all logins at t≈0) is partly a **test-plan artifact**, not a
system defect — real traffic wouldn't re-authenticate every user in the same second.

### Fix

| # | Change | File | Rationale |
|---|---|---|---|
| 1 | Removed `@Transactional(readOnly = true)` from `login()` | `booking-service/.../service/CustomerService.java` | The `findByPhone` runs in its own auto-commit connection borrowed/returned in ~1 ms; the connection is back in the pool during BCrypt + JWT. `Customer` has no lazy associations, so using the entity detached is safe. |
| 2 | `passwordEncoder()` bean now takes `security.bcrypt.strength` (default **10**, env `BCRYPT_STRENGTH`) | `booking-service/.../config/SecurityConfig.java` | Lets the load-test env match BCrypt cost to the CPU the pod actually has, without weakening the default for a real deployment. |
| 3 | Added `security.bcrypt.strength: ${BCRYPT_STRENGTH:10}` | `booking-service/src/main/resources/application.yml` | The knob. |
| 4 | `loadtest/seed_loadtest_customers.sql` — re-hash `cust-0001` at cost 8 + bulk-seed 200 `cust-lt-*` rows | `loadtest/` | **Required for #2/#3 to have any effect:** `BCryptPasswordEncoder.matches()` reads the cost from each stored hash's `$2a$NN$` prefix, *not* from the encoder's configured strength. Rows seeded at cost 10 stay cost 10 until re-hashed. |

**Cost 8 vs 10:** ~4× less CPU per verification (`2^8` vs `2^10` key-expansion rounds).
Still ~25 ms on a normal core — fine as a hashing deterrent, appropriate for a pod capped
near 1 vCPU. Do **not** ship < 10 to a real deployment.

### Deploy / run steps for the next test

```bash
# 1. rebuild + redeploy booking-service with the code change, and set the cost knob
#    (live env-var patch, same style as the New Relic toggle — not committed to the manifest)
kubectl set env deployment/booking-service -n toy-rental BCRYPT_STRENGTH=8
#    (or bake into k8s/services/booking-service/booking-service.yaml if it should stick)

# 2. re-hash the seeded customer(s) at the matching cost
kubectl cp loadtest/seed_loadtest_customers.sql infra/postgres-0:/tmp/seed_loadtest_customers.sql
kubectl exec -n infra postgres-0 -- psql -U bookinguser -d bookingdb -f /tmp/seed_loadtest_customers.sql

# 3. in the JMeter plan: add a ramp-up (>= 60 s) so the login herd spreads; optionally
#    point CUST_PHONE at a CSV of the cust-lt-* phones (999000000N) instead of one row.
```

### Verify (acceptance for the next run)
- `POST /customers/login` p95 **< 1 s**, p99 **< 2 s**, **0** socket timeouts.
- `POST /bookings` 401 count **→ 0** (no login-timeout cascade); remaining non-201s are the
  expected 409s.
- booking-service HikariCP `hikaricp_connections_pending` stays ~0 during the login window
  (Prometheus / Grafana).

### Notes
- If p95 is still high after this, the remaining cost is CPU contention with the browse
  mix on the same pod — options: raise booking-service `cpu` limit, or move login to its
  own deployment. Revisit only if the numbers above aren't met.
- `@Transactional` is still (correctly) on `register()` / `updateProfile()` /
  `updateAddress()` — those write.

---

## Bottleneck #2 — `POST /bookings`: p95 2 s, unpooled Feign to a saturated toy-service  ✅ RESOLVED (core changes) — deployed; JMeter re-run pending

> Full analysis + before/after: **`learning/perf-analysis-booking-latency.md`**.
> Deployed as `booking-service:1.0.12`.

**Symptom:** p95 2.0 s, p99 3.0 s, max 8.7 s; nominal 20.8 % errors — of which 665×401 is
the bottleneck-#1 login cascade and 1,827×409 is expected ("toy not available"). Real
failures: 2×500 (~0.06 %).

**Root cause:** two **sequential** Feign calls per booking (`getToy`, `checkAvailability`)
to a CPU-saturated toy-service over a **non-pooled** HTTP client (fresh TCP per call) with
**no timeouts** (Feign defaults ~10 s/60 s). A 10-connection Hikari pool and the #1 login
herd on the same pod amplified it. The `PESSIMISTIC_WRITE` overlap lock is also held across
the Razorpay/WireMock call (cheap today at ~10 ms, structurally wrong).

**Fixed:** added `feign-hc5` (pooled Apache HttpClient 5); Feign `connect-timeout: 1000` /
`read-timeout: 3000`; `httpclient.time-to-live: 30s` (also the #3 fix); Hikari
`maximum-pool-size` 10 → 30. Verified in-pod: HC5 jars present, `hikaricp_connections_max`
= 30.

**Result (booking probe, 30 concurrent, isolated):** cold p95 ~8.0 s → ~3.4 s; warm rounds
within probe noise (the probe is ~90 % 409s and single-pod via port-forward — see the
report §7). **Real acceptance = re-run `ToyRentalMixed.jmx`.**

**Not done:** the two-transaction split to move the Razorpay call out of the pessimistic
lock — designed in the report §8, needs the JMeter plan to verify + a compensation path;
deferred.

---

## Bottleneck #3 — one replica busy, the other idle  ✅ RESOLVED (app side) — rest is test-methodology, documented

> Full analysis: **`learning/perf-analysis-pod-load-imbalance.md`**.

**Symptom:** one `toy-service` pod carries the load, its replica ~idle (same for
booking-service).

**Root cause:** a ClusterIP `Service` is L4 — kube-proxy picks a backend pod **per TCP
connection**, at connect time. Every connection-holding layer then pins a request stream to
one pod: `kubectl port-forward` (binds one pod for its lifetime — measured 426 vs 0 logins
across the two booking pods), JMeter keep-alive (JTL `Connect` p95 = 0 mid-test), and
booking→toy Feign keep-alive. HPAs can't compensate — `cpu: <unknown>` because no
`metrics-server` is installed.

**Fixed (app side):** `spring.cloud.openfeign.httpclient.time-to-live: 30s` in
`booking-service:1.0.12` — recycles pooled connections so booking→toy calls re-balance
across toy-service replicas over time (without this, the #2 pooling change would have made
internal pinning *worse*).

**Documented, not code (for the next run):** stop load-testing through `kubectl
port-forward` (use NodePort/Ingress or an in-cluster JMeter Job); JMeter keep-alive off or
per-iteration connection reset; install `metrics-server` with `--kubelet-insecure-tls`
(attempted in-session, blocked by a guard on `kubectl apply -f <remote URL>` — run
manually); longer term, route the external path through an L7 proxy. #3 is a **multiplier**
on the #1/#2 fixes, not an independent latency source.

---

## Also observed (not a latency bottleneck, but blocks the test's intent)

- **`POST /api/v1/payments/webhook` — 100 % failure** (708 calls, all non-2xx, ~8 ms).
  708 bookings created, 708 webhook attempts, all fail → **no booking ever reaches
  CONFIRMED**, so the booking → payment → confirm → Kafka path isn't being exercised.
  Likely a signature/param mismatch in the JMeter webhook step. Fix before trusting any
  results past `POST /bookings`.
- **Browse params look unsubstituted**: requests go to
  `?category=category&ageGroup=ageGroup` (literally those strings). Confirm the bulk seed
  actually uses those values, or the catalogue-filter path isn't being tested as intended.
- **Composite index still missing on `toys`** — `browse` filters `active + category +
  age_group` over 50k rows with only single-column indexes, plus a `count(*)` per page.
  Most of BROWSE's p95 1.8 s at 43 rps. Documented fix:
  `CREATE INDEX idx_toys_browse ON toys (category, age_group, is_active, status);`
  (`CLAUDE.md` bottleneck #1, `learning/composite-index-load-testing.md`).
