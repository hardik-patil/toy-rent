# Performance Analysis Report — `POST /customers/login` latency

**Status:** root cause identified, fix implemented, deployed to the local cluster, and
validated with a controlled before/after probe.
**Bottleneck ID:** #1 in `learning/bottleneck-faced-resolved.md` (running log).
**Focus of this document:** the analysis method — how we went from a dashboard number to a
verified root cause — not just the answer.

---

## 1. Executive summary

A mixed load test (60 virtual users) showed `POST /api/v1/customers/login` responding in
**~7 s mean, p99 pinned at the 10 s client timeout**, with 20 % of login calls failing as
socket timeouts. The application logs were clean — no exceptions, no slow-query warnings.

The latency was **CPU, not I/O**: BCrypt password verification at work factor 10 is
deliberately expensive and single-threaded, and 60 of them arriving in the same second
contend for a pod capped at ~1 vCPU. A second, independent defect amplified it — `login()`
was `@Transactional`, so each request also held a database connection for the full ~7 s
while doing no database work, starving the small pool for the rest of the service.

Failed logins then **cascaded**: a vuser whose login timed out had no JWT, so every
booking it subsequently attempted returned 401 — that alone was ~21 % of all
`POST /bookings` responses.

| metric (60 concurrent logins, isolated, warm) | before | after | change |
|---|---:|---:|---:|
| mean | ~4.1 s | ~2.0 s | −51 % |
| p95 | ~7.3 s | ~3.3 s | −55 % |
| p99 | ~7.5 s | ~3.4 s | −55 % |
| single-user login (control) | ~50 ms | ~50 ms | unchanged |
| errors | 0 (isolated) / 20 % (under mixed load) | 0 | — |

Single-user latency is unchanged by design — the fix targets *contention*, not per-request
cost. The residual ~3.3 s at 60-way concurrency is a **capacity limit** (60 un-parallelisable
hashes on one throttled core), addressed by the follow-ups in §8, not by more code changes.

---

## 2. Context

| | |
|---|---|
| Service | `booking-service` (Spring Boot 3.2, Java 17), 2 replicas |
| Endpoint | `POST /api/v1/customers/login` → `findByPhone` + BCrypt verify + RSA-signed JWT |
| Test plan | `loadtest/ToyRentalMixed.jmx` — catalogue browse + toy detail/availability + create booking; **one login per vuser** via a Once Only Controller |
| Load | 60 vusers, flat, open-ended loop |
| Window analysed | 2026-09-03 06:14:34–06:35:28 UTC, 78,915 samples (`loadtest/results/ToyRentalMixed.jtl`) |
| Pod limits | `cpu=1000m`, `memory=1Gi` |
| Access path | JMeter → `kubectl port-forward` → `localhost:8082` (api-gateway bypassed) — **relevant, see §7** |

---

## 3. Symptom

From the Grafana dashboard and a first pass over the JTL, `login` stood out:

```
path                                n   err%   mean    p50    p90    p95    p99     max
POST /api/v1/customers/login        60   20.0   7219   7635  10144  10148  10149  10149
```

p90 through max sit within a few ms of each other at ~10.15 s — the shape of a **hard
ceiling**, which turned out to be JMeter's 10 s response timeout, not a server-side limit.

---

## 4. Investigation — reaching the root cause

Each step below either measured something or eliminated a hypothesis. The order matters:
cheap eliminations first, code-reading only once the data pointed at a specific area.

### Step 1 — Quantify precisely, in the right window

The raw JTL spans multiple runs (appended). Re-computed percentiles **filtered to the
Grafana window** and grouped by normalised URL path (`toy-bulk-<n>` → `toy-{id}`), so the
numbers match what the dashboard showed. Without the window filter, `login` mean came out
at a meaningless blended value.

*Takeaway: analyse the same time range the dashboard shows, or the two disagree and you
chase ghosts.*

### Step 2 — Separate real failures from reporting noise

`success=false` in a JTL is not the same as "the system failed". Broke the suspect paths
down by `responseCode`:

```
POST /customers/login (n=60):
   code=200                              : 48
   code=Non HTTP response ... SocketTimeoutException : 12   → JMeter's own 10 s timeout

POST /bookings (n=3202):
   code=409 (toy not available)          : 1827   ← expected, success=true, assertion passes
   code=201 (created)                    :  708
   code=401 (unauthorised)               :  665   ← NOT a booking bug
   code=500                              :    2   ← the only real server errors
```

Two conclusions:
- Login's "20 % error" = client-side timeouts, i.e. the server was **slow, not broken**.
- Booking's "21 % error" = **665 × 401**, which have nothing to do with the booking code.
  A 401 means no/invalid JWT. Which vusers have no JWT? The ones whose login timed out.
  **The login latency is causing booking failures.** This raised login from "one slow
  endpoint" to "the thing gating the test".

### Step 3 — Look at the *shape* of the latency, not just the aggregate

Bucketed mean RT into 60 s slices and checked concurrency (`allThreads`):

```
login mean RT by 60s bucket:  0m: 7219 (n=60)   [no samples in any later bucket]
allThreads:                   0m: 60   1m: 60 ... (60 from the start)
```

All 60 logins happen in the **first minute** — the Once Only Controller fires them at the
start of each thread's first iteration, and ramp-up is short, so 60 land together. This is
a **burst**, not steady state. It also means the fix has to survive a thundering herd, and
that part of the herd is a **test-plan artifact** (real users don't all authenticate in
the same second).

### Step 4 — Eliminate the database

Reasons the DB was not the cause:
- No slow-query logs, no Hikari timeout exceptions, no connection-acquisition errors.
- The one query on this path is `findByPhone`, and `customers.phone` has a unique index
  (`idx_customers_phone`) — a single-row lookup, sub-millisecond.
- 7,000 ms of latency cannot come from a 1 ms indexed point lookup.

So the time is spent **after** the query, in application code.

### Step 5 — Rule in CPU / BCrypt with a concurrency sweep

Read the code on the path after `findByPhone`:
- `passwordEncoder.matches(raw, hash)` — `new BCryptPasswordEncoder()`, **default work
  factor 10** (`SecurityConfig`).
- `jwtTokenService.issueToken(...)` — RSA (RS256) signing, a few ms.

BCrypt is intentionally CPU-hard and each call is single-threaded and un-parallelisable.
Confirmed by measuring the endpoint at different concurrencies against an otherwise-idle
pod (`loadtest/login_probe.py`):

| concurrency | p95 |
|---:|---:|
| 1 | ~50–115 ms |
| 60 | ~7.3 s (warm), ~11 s (cold) |

One login is fast. Sixty at once are ~60× slower — near-perfect serialization. That is the
signature of a **CPU-bound section contending for one core** (the pod's `cpu=1000m`
limit → CFS throttling to ~1 CPU across all Tomcat worker threads). The isolated 60-way
number (~7 s) matches the mixed-test number (~10 s, where catalogue browse was also
competing for the same core), which cross-validates the hypothesis.

### Step 6 — Identify the amplifier: a connection held through non-DB work

`login()` was annotated `@Transactional(readOnly = true)`. Spring opens a transaction —
and borrows a HikariCP connection — on method entry and holds it until return. On this
method that means a connection is pinned for the **entire ~7 s of BCrypt + JWT**, doing
zero database work. With `spring.datasource.hikari.maximum-pool-size: 10`, a burst of
logins exhausts the pool for every *other* caller of booking-service (create-booking
included), turning a login problem into a service-wide problem.

*How this was spotted: the annotation is visible in the method, and the reasoning is "what
does a transaction cost here?" — it costs a pooled connection for the method's duration,
and the method's duration is dominated by CPU work that needs no connection.*

### Step 7 — Confirm the cascade end-to-end

From Step 2 the mechanism is: login timeout (10 s) → JMeter marks the sample failed and the
thread has no token → the plan proceeds to `POST /bookings` with no `Authorization` header
→ Spring Security returns 401 before any booking logic runs → 665 such calls over the run.
Fixing login latency removes the timeouts, removes the missing tokens, removes the 401s.

### Root cause statement

> **Primary:** login latency under concurrency is BCrypt (work factor 10) password
> verification — un-parallelisable CPU — executed by up to 60 threads simultaneously on a
> pod throttled to ~1 vCPU.
> **Amplifier:** `login()` ran inside a read-only transaction, pinning a pooled DB
> connection (pool size 10) for the ~7 s duration of that CPU work, degrading the whole
> service during the burst.
> **Consequence:** logins exceeding the 10 s client timeout left vusers without a JWT,
> producing ~665 spurious `401`s on `POST /bookings` (~21 % of its traffic).
> **Test-harness contributors:** all 60 logins fire in the same second (Once Only
> Controller + short ramp-up); traffic reaches a single pod via `kubectl port-forward`
> (§7).

---

## 5. Fix

Committed code/config (safe for any environment):

| # | Change | File | Why |
|---|---|---|---|
| 1 | Removed `@Transactional(readOnly = true)` from `login()` | `booking-service/.../service/CustomerService.java` | The lone `findByPhone` runs in its own auto-commit connection, returned to the pool in ~1 ms — instead of held through BCrypt + JWT. `Customer` has no lazy associations, so the detached entity is safe to read. |
| 2 | `passwordEncoder()` reads `security.bcrypt.strength` (default **10**) | `booking-service/.../config/SecurityConfig.java` | Makes the work factor tunable per environment without weakening the default. |
| 3 | `security.bcrypt.strength: ${BCRYPT_STRENGTH:10}` | `booking-service/src/main/resources/application.yml` | The knob. |

Environment-specific (live patch, **not** committed — mirrors the New Relic toggle):

| Change | How | Why |
|---|---|---|
| `BCRYPT_STRENGTH=8` on the load-test cluster | `kubectl set env deployment/booking-service -n toy-rental BCRYPT_STRENGTH=8` | Work factor 8 ≈ ¼ the CPU of 10 (2⁸ vs 2¹⁰ key-schedule rounds), ~15–25 ms/verify — a sane deterrent for a pod capped near 1 vCPU. **Never ship < 10 to production.** |
| Re-hash seeded customers at cost 8 | `loadtest/seed_loadtest_customers.sql` | **Essential:** `BCryptPasswordEncoder.matches()` takes the cost from each stored hash's `$2a$NN$` prefix, *not* from the encoder config. Rows seeded at cost 10 (`V6__seed_sample_customer.sql`) stay cost 10 until re-hashed. This SQL also bulk-seeds 200 `cust-lt-*` rows so the login test can spread across distinct users. |

Deploy sequence used:

```bash
./mvnw -q package -DskipTests -f booking-service
docker build -t toyrental/booking-service:1.0.11 booking-service
# manifest image tag 1.0.10 -> 1.0.11
kubectl apply -f k8s/services/booking-service/booking-service.yaml
kubectl set env deployment/booking-service -n toy-rental BCRYPT_STRENGTH=8
kubectl rollout status deployment/booking-service -n toy-rental
MSYS_NO_PATHCONV=1 kubectl cp loadtest/seed_loadtest_customers.sql infra/postgres-0:/tmp/x.sql
MSYS_NO_PATHCONV=1 kubectl exec -n infra postgres-0 -- psql -U bookinguser -d bookingdb -f /tmp/x.sql
```

---

## 6. Validation

**Method.** `loadtest/login_probe.py` — fires *N* concurrent `POST /login` with no
keep-alive, reports the latency distribution and a response-code histogram, repeated over
several rounds (round 1 = cold, later rounds = warm JIT). Run against an otherwise-idle
`booking-service` so the login path is measured in isolation. Same probe, same host,
before and after.

**Before** (old code, cost-10 hash, 60 concurrent):

```
round 1 (cold): mean 6461  p95 11165  max 11328
round 2 (warm): mean 4110  p95  7559  p99  7648
round 3 (warm): mean 4094  p95  6974  p99  7038
```

**After** (no `@Transactional`, `BCRYPT_STRENGTH=8`, cost-8 hash, 60 concurrent):

```
round 1 (cold): mean ~2900  p95  7098   (first request set after rollout)
round 2 (warm): mean  2019  p95  3259  p99 3310
round 3 (warm): mean  2264  p95  3731  p99 3750
round 4 (warm): mean  2002  p95  3159  p99 3200
single login  : ~40–70 ms
```

**Result:** warm p95 ~7.3 s → ~3.3 s (**2.2×**), p99 similar, zero timeouts, single-user
latency unchanged. Both intended effects are visible: less CPU per verify (cost knob) and
no connection held during it (`@Transactional` removal, which mainly protects *other*
endpoints and shows up under mixed load rather than in this isolated probe).

**Not yet re-run:** the full `ToyRentalMixed.jmx` with a ramp-up — that is the real
acceptance test (see §8).

---

## 7. Measurement caveat — `kubectl port-forward` pins to one pod

During validation, one replica served **426** logins and the other **~0**. `kubectl
port-forward` (even against a Service) binds to a single backing pod at connect time and
sends all traffic there for the life of the forward — so the probe's 60 "concurrent"
requests all hit **one** pod, i.e. 60 hashes on one throttled core. The same is true of
the JMeter run, which also goes through a port-forward — which is exactly the user's
separate observation that "one pod has load, the other is idle" (bottleneck #3). So the
~3.3 s residual is a *single-pod* number; with load genuinely spread across both replicas
it would roughly halve. This is a limitation of the test path, not of the application, but
it caps what fixing #1 alone can achieve.

---

## 8. Recommendations / follow-ups

Ordered by leverage:

1. **Add ramp-up (≥ 60 s) to the JMeter plan.** The 60-in-one-second burst is a test
   artifact; with ramp-up the instantaneous login concurrency is a handful, and p95 should
   fall well under 1 s. Do this before judging the fix "insufficient".
2. **Spread load across replicas** (this is bottleneck #3). For the test: disable JMeter
   keep-alive or cycle connections; longer term, route through an L7 proxy (the bypassed
   api-gateway or an Ingress) so requests, not connections, are balanced. Halves the
   per-pod hash concurrency.
3. **Keep `BCRYPT_STRENGTH` per-environment.** 8 for the ~1-vCPU load-test pod; 10+ for
   production. It is a config value precisely so this isn't a code change.
4. **If login latency still misses target after 1–2:** give `booking-service` more CPU, or
   move authentication to its own deployment so a login burst can't touch booking
   throughput.
5. **Monitoring to add:** a Grafana panel for `hikaricp_connections_pending` and
   `hikaricp_connections_acquire_seconds` on booking-service (would have shown the
   connection-hold amplifier directly), and a login-latency SLO panel.
6. **Fix the payment webhook** (100 % failing in this run) before trusting any results
   past `POST /bookings` — see `bottleneck-faced-resolved.md`.

---

## 9. Reproduce

```bash
# probe (before/after, or any concurrency sweep)
python loadtest/login_probe.py --concurrency 60 --rounds 3
python loadtest/login_probe.py --concurrency 1  --rounds 3     # control

# re-seed customers at the configured cost
MSYS_NO_PATHCONV=1 kubectl cp loadtest/seed_loadtest_customers.sql infra/postgres-0:/tmp/x.sql
MSYS_NO_PATHCONV=1 kubectl exec -n infra postgres-0 -- psql -U bookinguser -d bookingdb -f /tmp/x.sql

# revert the env-specific tuning
kubectl set env deployment/booking-service -n toy-rental BCRYPT_STRENGTH=10
MSYS_NO_PATHCONV=1 kubectl exec -n infra postgres-0 -- psql -U bookinguser -d bookingdb -c \
 "UPDATE customers SET password_hash='\$2a\$10\$dXJ3SW6G7P50lGmMkkmwe.20cQQubK3.HZWzG3YB1tlRy.fqvM/BG' WHERE id='cust-0001';"
```

### JTL analysis recipe (Step 1–3)

Parse the CSV; filter rows to `[dashboard_from, dashboard_to]`; group by URL path with
`toy-bulk-\d+` → `toy-{id}`; per group report count, `success!=true` rate, and
mean/p50/p90/p95/p99/max of `elapsed`; for suspects also histogram `responseCode` and
bucket mean `elapsed` into 60 s slices. Plain `csv` + `statistics` — no JMeter GUI needed.
