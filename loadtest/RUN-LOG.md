# ToyRental — Load Test Run Log

Tracked run history + analysis. Raw `.jtl` / HTML dashboards live in `results/` (git-ignored),
so the conclusions live here. Newest run first. Format per [SLOs.md](SLOs.md) §7.

---

## Run 001 — S3 mixed, first real run

| | |
|---|---|
| Run at | 2026-09-02 18:43 UTC |
| Analyzed | 2026-09-03 |
| Plan | `loadtest/plans/ToyRentalMixed.jmx` (user's edited copy of `mixed.jmx`) |
| Git SHA | `0da9587` (branch `docs/session-lessons-learned`) |
| Profile | `THREADS=60  RAMPUP=60  DURATION=300` (5 min, **not** the intended 15) |
| Data | `loadtest/results/ToyRentalMixed.jtl` (33,797 samples) |
| Verdict | **FAIL** on J1/J2/J3/J4 — but the dominant cause is a **test-harness artifact**, not a product bottleneck. Re-run needed before any of this counts. |

### Client-side (JMeter)

| Journey | p50 | p95 | p99 | max | SLO p95 (Expected) | Verdict |
|---|---|---|---|---|---|---|
| BROWSE | 531 | 1590 | 2433 | 6033 | ≤ 500 ms | FAIL (~3×) |
| GET toys/{id} | 428 | 1431 | 2224 | 4478 | ≤ 400 ms | FAIL |
| GET toys/{id}/availability | 469 | 1446 | 2321 | 4290 | ≤ 300 ms | FAIL |
| POST /bookings (201s) | 266 | 1918 | 5181 | 9289 | ≤ 1200 / p99 2500 | FAIL |
| LOGIN (label "HTTP Request") | — | ~10000 | — | 10155 | ≤ 600 ms | FAIL (~16×) |

Per-minute BROWSE p95: 1381 → 2012 → 2161 → 1060 → 946 (min 0–4). First ~2.5 min is JVM
warm-up; minutes 3–4 (~1000 ms, ~145 rps aggregate) are the truest steady state — still 2× SLO.

### Server-side (Prometheus, window 1788374601–1788374901)

| Signal | Value | Read |
|---|---|---|
| toy-service `/…/availability` p95 | **90 ms** (max 190) | server is **fast**; the 1446 ms client number is queueing, not compute |
| toy-service `/api/v1/toys` (browse) p95 | **not captured** | `percentiles-histogram` not enabled for this URI — only `/availability` has buckets |
| booking-service `/api/v1/bookings` p95 | 2370 ms avg, max 6850 | genuinely slow (see Hikari) |
| booking-service `/api/v1/customers/login` p95 | **9560 ms** avg, max 11050 | BCrypt is ~50 ms normally — this is starvation |
| **CPU: toy-service pod `pq5mp`** | **1.00 cores — pegged at its 1000m limit** | throttled |
| CPU: toy-service pod `gjbrg` | 0.27 cores | **idle sibling — load not balanced** |
| CPU: booking-service `b77s9` | 0.45 cores | |
| **HikariCP pending (booking-service)** | avg 1.67, **max 35** | **pool exhaustion — bottleneck #3 confirmed** |
| HikariCP active (booking-service) | max 10 | |
| JVM heap (both apps) | 106–210 MB | fine |
| GC pause rate | ≈ 0 | **bottleneck #6 not triggered** (5 min too short anyway) |
| Availability cache hit / miss | ~0 / 2.1 per s | 100% miss — bucket empty because **no booking ever confirmed** (webhook bug below) |
| Booking conflict rate | avg 0.23/s (78 total) | expected, low, fine |

### Root causes, in order of impact

1. **toy-service load imbalance → CPU throttle (TEST ARTIFACT).**
   `kubectl port-forward svc/toy-service` binds **one** backing pod at connect time; JMeter's
   keep-alive connections all stayed on it. One replica pegged at its 1-core limit and
   throttled; the other idle at 0.27. The 1.5 s read p95 is that throttling, **not query
   cost**. → **Bottleneck #1 (missing composite index) has NOT been tested yet** — can't be,
   until load is even.

2. **HikariCP exhaustion on booking-service (REAL — bottleneck #3).**
   `pending` max 35, `active` max 10. Booking + login both starve on DB connections during
   the ramp. This is the real bottleneck #3 evidence, independent of #1.

3. **Login thundering herd.**
   60 threads run login in the Once Only Controller during the 60 s ramp → 60 simultaneous
   `SELECT customers` + BCrypt on the exhausted pool + throttled CPU → 6–11 s each; **9 time
   out at 10 s** → those threads never get a token → they 401 for the rest of the run.

### Script bugs found in `ToyRentalMixed.jmx` (fix before Run 002)

| # | Symptom in the `.jtl` | Bug | Fix |
|---|---|---|---|
| 1 | `POST /api/v1/payments/webhook` = **1045 / 1045 → 401** | `Authorization: Bearer` header is in scope for the webhook. It's a `permitAll` route — Spring resource-server rejects *any* bearer with 401 before permitAll applies. **No booking ever reaches CONFIRMED**, so Couchbase `avail::` docs are never written. | Scope the `Bearer ${token}` Header Manager to **`POST /api/v1/bookings` only**. The global Header Manager carries `Content-Type` **only** — never `Authorization`, never on webhook or login. |
| 2 | 212 booking 401s; label `TOKEN_NOT_FOUND` ×1689 | Login timeout leaves `${token}` at its extractor default; that thread is broken for the whole run | RAMPUP=180 to spread logins; raise the login sampler's response timeout; or a `setUp Thread Group` that logs in a few times and shares tokens via `${__setProperty}` / props. Add a Response Assertion on login so failures are loud. |
| 3 | 11,178 samples labelled `JWT` / `TOKEN_NOT_FOUND`, 0 ms, code 200 | A **Debug/Dummy sampler named `${token}`** left in from 401 debugging | Delete or disable it |
| 4 | thousands of unique labels `GET /api/v1/toys/toy-bulk-NNNN/availability` | Sampler **name** contains `${toyId}` | Rename to a static label, e.g. `AVAILABILITY` |
| 5 | label `HTTP Request` ×60 | Login sampler never renamed | Rename `LOGIN` |
| 6 | 1× `GET /api/v1/toys/toyId/availability` → 404 | first-iteration race, `${toyId}` unresolved before the CSV loads | harmless; ignore |

> The clean scaffold `loadtest/plans/mixed.jmx` already has bugs 1, 3, 4, 5 done right and
> was smoke-verified at 0 % errors. Fastest path may be to re-apply the user's changes onto
> that, rather than debug the edited copy.

---

## TODO — Run 002 (tomorrow)

**Prep**

- [ ] Fix script bugs 1–5 (or restart from `mixed.jmx` and re-apply wanted edits).
- [ ] Decide load-distribution approach (pick one):
  - `kubectl scale deployment/toy-service -n toy-rental --replicas=1` → clean single-pod numbers, or
  - test through the NGINX ingress / `api-gateway` so both toy-service replicas get load.
- [ ] Enable `percentiles-histogram` for `http.server.requests` on **`/api/v1/toys`** in toy-service
      (`learning/prometheus-percentile-metrics.md`) — needs a rebuild+redeploy — so browse
      gets a server-side p95.
- [ ] Confirm port-forwards alive: `curl :8081/actuator/health`, `:8082/actuator/health`.
- [ ] (optional) `kubectl port-forward -n infra svc/couchbase 8091:8091 8093:8093 11210:11210`
      for the CB UI — not needed by the test itself.

**Run**

- [ ] `RAMPUP=180  DURATION=900  THREADS=60` (and a `THREADS=40` pass to grade against the
      SLOs.md Expected tier).
- [ ] Watch live in Grafana: `http_server_requests` p95 by `uri`, `hikaricp_connections_pending`,
      `rate(booking_conflict_total[1m])`, Kafka lag on `booking.confirmed`, per-pod CPU
      (both toy-service replicas).

**Then**

- [ ] With even load + CPU headroom, capture the **real browse p95 baseline** → that's the
      "before" for the missing-index test (bottleneck #1). Run `EXPLAIN ANALYZE` on the browse
      query at 50k rows and save the plan text.
- [ ] Confirm webhook now 200 and bookings reach CONFIRMED → `avail::` docs appear in Couchbase
      → availability cache starts showing hits.
- [ ] Re-check `hikaricp_connections_pending` under the booking load — if still > 0 sustained,
      that's clean bottleneck #3 evidence to act on next.
- [ ] Add a Run 002 entry above with the same structure; keep Run 001 for the before/after diff.
