# JMeter Fundamentals — Mapped to ToyRental

A working reference for scripting and running JMeter against this stack. Not a tutorial
you read once — the "Common mistakes" section at the bottom is the part that actually
saves a wasted afternoon. For the test scenarios and pass/fail criteria, see
[../loadtest/PLAN.md](../loadtest/PLAN.md). For how to install and run, see
[../loadtest/README.md](../loadtest/README.md).

---

## The mental model

A `.jmx` is a tree. Execution order is top-to-bottom within a level, and **scope is by
nesting**:

```
Test Plan                         ← global vars, "run tearDown after shutdown"
└─ Thread Group                   ← the load profile: N threads, ramp, duration
   ├─ HTTP Request Defaults       ← config: applies to every sampler below it
   ├─ HTTP Header Manager         ← config: headers for every sampler below it
   ├─ CSV Data Set Config         ← config: feeds ${vars} from a file
   ├─ HTTP Request  "login"       ← sampler: actually makes a call
   │  └─ JSON Extractor           ← post-processor: pulls token out of the response
   ├─ HTTP Request  "browse"      ← sampler
   │  ├─ Response Assertion       ← assertion: pass/fail this sample
   │  └─ Constant Timer           ← timer: think-time BEFORE the next sampler
   └─ Summary Report              ← listener: collects results
```

- **Config elements, timers, assertions** attached at Thread Group level apply to *all*
  samplers in the group. Attached under one sampler, they apply to *that sampler only*.
- **Timers** are counter-intuitive: a timer anywhere in a scope pauses *before every
  sampler in that scope*, not "here". Put per-request think-time as a child of the
  request.
- **Pre/Post-processors** (extractors are post-processors) run around their scoped
  samplers.

---

## Thread Group — the load profile

| Field | Meaning | This project |
|---|---|---|
| Number of threads | Concurrent virtual users. Each thread is a sequential loop — it does not fire the next request until the previous one returns. | Start at **5** for debugging a script, not 100. |
| Ramp-up period | Seconds to start *all* threads. 100 threads / 20s ramp = one new thread every 0.2s. | Ramp ≈ threads, or slower. A 0s ramp on 200 threads is a spike test, not a load test. |
| Loop count / Scheduler + Duration | How long each thread keeps looping. `Scheduler` + `Duration` (seconds) is the clean way — run for time, not a fixed iteration count. | The starter `catalogue-browse.jmx` uses `scheduler=true`, `duration=300`, `loops=-1` (loop until the clock runs out). |

**Threads ≠ throughput.** Throughput (requests/sec) = `threads / (avg response time + think
time)`. 50 threads against a 100ms endpoint with no think-time ≈ 500 rps. Add 1s
think-time and the same 50 threads ≈ 45 rps. If you need a *target rps* regardless of
response time, you want a **Precise Throughput Timer** or the **Concurrency Thread Group**
(JMeter Plugins) which models arrival rate (open model) instead of fixed VUs (closed
model). Closed model is fine for everything in PLAN.md's first pass.

---

## Timers — why think-time matters

No timer at all = each thread hammers as fast as the server can reply. That is a **stress
test** (find the breaking point), not a **load test** (behaviour under realistic traffic).
A real user reads the catalogue for a few seconds between clicks.

- **Uniform Random Timer** + **Constant Timer**: combine for "1s fixed + 0–2s random" =
  1–3s think-time. This is the normal choice for the browse and mixed scenarios.
- **Precise Throughput Timer**: "hold 200 rps across the group" — use when the SLO is
  stated in rps.
- Keep think-time *out* of the pure stress runs (PLAN.md S1-stress) — there you *want* the
  firehose.

---

## Config elements

- **HTTP Request Defaults** — set `Server Name = localhost`, `Port = 8081` (toy-service)
  or `8082` (booking-service), `Protocol = http` once, so samplers only carry the path.
  The API gateway (8080) is bypassed in this setup — hit the services directly.
- **HTTP Header Manager** — `Content-Type: application/json` for POSTs; `Authorization:
  Bearer ${token}` for authenticated calls. A Header Manager under a sampler *merges
  with*, not replaces, one at group level.
- **CSV Data Set Config** — `Recycle on EOF = true` loops the file; `Stop thread on EOF =
  false`. `Sharing mode = All threads` means the file is one shared cursor (thread 1 gets
  row 1, thread 2 row 2…). `Ignore first line = true` **only works if `variableNames` is
  set** — otherwise the header row itself gets read as data.
  - ⚠️ The starter `catalogue-browse.jmx` has `filename` pointing at the `.jmx` itself,
    not `data/browse_params.csv` — fix that before the CSV does anything. Use a path
    relative to the `.jmx` location (`data/browse_params.csv`) or an absolute one.
- **User Defined Variables** vs **properties**: `${FOO}` is a variable (set in the plan).
  `${__P(FOO,default)}` reads a property you pass on the command line with `-JFOO=value`.
  The starter plan already parameterises `THREADS`, `RAMPUP`, `LOOPS` this way — that's
  how you drive the test matrix without editing the `.jmx`.

---

## Correlation — carrying the JWT

booking-service is the only token issuer. The flow for any authenticated scenario:

1. **HTTP Request** `POST /api/v1/customers/login` on port 8082, JSON body
   `{"phone":"9821012345","password":"password"}` (the `V6` seed customer).
2. Child **JSON Extractor**: Names `token`, JSON Path `$.accessToken`, Match No. `1`.
3. A **HTTP Header Manager** (at group scope, added *after* login) with
   `Authorization: Bearer ${token}`.
4. Every later sampler in the group now sends the bearer token.

Notes:
- Do the login **once per thread**, not once per iteration — put it in a `Once Only
  Controller`, or in a `setUp Thread Group` that stashes the token in a property.
- If `$.accessToken` comes back empty, the login 4xx'd — add a Response Assertion on the
  login sampler so a bad credential fails loudly instead of every downstream call
  mysteriously 401ing.
- Admin token: `POST /api/v1/admin/login` with `{"username":"admin","password":"admin123"}`.

---

## Assertions — cheap insurance

Under load, a `500` returns *faster* than a healthy `200`. Without an assertion, an
outage shows up in the Summary Report as "response time improved". Always add at least:

- **Response Assertion** → Field to Test: `Response Code`, Pattern Matching: `Equals`,
  pattern `200` (or `201` for booking create — check the controller).
- For the booking flow, also assert the response body contains the expected field
  (`"status":"PENDING"`), so a 200-with-wrong-body still fails.

`Error %` in the report counts assertion failures + transport errors. Read that column
*first*, before looking at latency — a 30% error rate makes the latency numbers
meaningless.

---

## Listeners — and why not to use them

GUI listeners (View Results Tree, every graph) buffer every sample in memory and will OOM
JMeter or distort results under real load. Rules:

- **Author** the script in the GUI with `View Results Tree` enabled + 1 thread / 1 loop.
- **Measure** from the command line in non-GUI mode (`-n`), writing a `.jtl` file.
- Disable or delete all GUI listeners before a real run (the starter plan already has
  `View Results Tree` disabled).
- Generate the HTML dashboard *from* the `.jtl` after the run (`-e -o`).

### Summary / Aggregate Report columns

| Column | What it is |
|---|---|
| Average | Mean elapsed ms. Misleading — one 10s stall hides behind 10,000 fast calls. |
| Median / 90% / 95% / 99% | Percentiles. **95% is the number you report.** "95% of requests finished within X ms." |
| Min / Max | Max is often a cold-start or GC pause — note it, don't obsess. |
| Error % | Failed samples ÷ total. Look here first. |
| Throughput | Completed requests/sec (or /min). Plateaus when the system saturates. |
| Received KB/sec | Bandwidth — matters for the catalogue (large JSON pages). |

`elapsed` = full request→last byte. `latency` = request→first byte. `Connect Time` =
TCP+TLS setup. If `Connect Time` climbs under load, you're exhausting connections
(client-side or server-side), not CPU.

---

## Running non-GUI

```bash
jmeter -n \
  -t loadtest/catalogue-browse.jmx \
  -l loadtest/results/s1-load-$(date +%Y%m%d-%H%M).jtl \
  -e -o loadtest/results/s1-load-$(date +%Y%m%d-%H%M) \
  -JTHREADS=100 -JRAMPUP=60 -JLOOPS=-1
```

- `-n` non-GUI, `-t` test plan, `-l` results file, `-e -o <dir>` generate HTML dashboard
  into `<dir>` (must not already exist).
- `-J` sets a property read by `${__P(...)}`. `-G` does the same for remote/distributed
  engines.
- Open `loadtest/results/<dir>/index.html` afterwards. The **Response Times Over Time**
  and **Active Threads Over Time** charts side by side tell you where it broke.

---

## Common mistakes (the actual point of this doc)

1. **Load-testing in GUI mode.** The GUI is for authoring. It caps throughput and its
   listeners eat heap. Always `-n` for real numbers.
2. **No think-time in a "load" test.** Without timers you wrote a stress test. Know which
   one you meant.
3. **Tiny dataset + recycle.** 18 rows in `browse_params.csv` recycled forever means
   after the first pass everything is served from Postgres's buffer cache and Couchbase —
   you're measuring cache, not the query. The composite-index bottleneck needs the 50k
   bulk seed (`seed_toys_bulk.sql`) *and* enough distinct param combos. See
   [composite-index-load-testing.md](composite-index-load-testing.md).
4. **Ignoring assertion failures.** A fast run with 40% errors is not a fast run.
5. **Load generator on the same machine as the system under test.** That's exactly this
   setup — JMeter, Docker Desktop, and the cluster all share one laptop's CPU. At high
   thread counts JMeter itself starves the pods and you measure contention, not the
   service. Keep concurrency modest, watch `docker stats` and the node CPU, and treat
   absolute numbers as relative-only (before/after the same box).
6. **Not warming the JVM.** First ~30s of any run hits cold JIT and empty caches. Either
   discard the first minute or add a short warm-up Thread Group.
7. **One giant run instead of before/after.** A single number in isolation ("p95 was
   240ms") means little. The method for every seeded bottleneck: measure → apply the fix
   → measure the identical plan again → diff.
8. **Coordinated omission.** A closed-model Thread Group can't send request N+1 until
   request N returns, so when the server stalls, JMeter politely stops sending — and
   under-reports how bad a latency spike really was. For arrival-rate accuracy use the
   Concurrency Thread Group / Precise Throughput Timer.
9. **Port-forward died mid-run.** Any `kubectl apply` / pod restart silently kills the
   `kubectl port-forward` JMeter is hitting (see
   [port-forwarding.md](port-forwarding.md)). A run that suddenly goes 100% error
   usually means the forward dropped, not that the service fell over.
