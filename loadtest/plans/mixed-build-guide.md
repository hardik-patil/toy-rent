# Building `mixed.jmx` (S3) — walkthrough

A working scaffold is checked in as `mixed.jmx` — this doc explains every element in it so
you can rebuild, extend, or debug it. Scenario definition: [../PLAN.md](../PLAN.md) §S3.
JMeter concepts: [../../learning/jmeter-fundamentals.md](../../learning/jmeter-fundamentals.md).

The scaffold was smoke-tested (4 threads / 35s) against the live cluster: browse, detail,
calendar, login, booking-create (201 **and** 409), and webhook all pass. The five
gotchas found while getting there are called out below — they're the non-obvious part.

---

## Traffic model

One closed-model Thread Group, think-time on, split by three **Throughput Controllers**
in *Percent Executions* mode:

| Weight | Branch | Calls |
|---|---|---|
| 80% | browse | `GET /api/v1/toys?category=&ageGroup=` (`:8081`) |
| 12% | detail | `GET /api/v1/toys/{id}` + `GET /api/v1/toys/{id}/availability/calendar` (`:8081`) |
| 8%  | booking | `GET .../availability` → `POST /api/v1/bookings` → `POST /api/v1/payments/webhook` (`:8082`) |

Login (`POST /api/v1/customers/login`, `:8082`) runs **once per thread** in a
`Once Only Controller` and stashes the JWT in `${token}`.

---

## Element tree

```
Test Plan  "ToyRental Mixed (S3)"
├─ User Defined Variables   HOST, TOY_PORT=8081, BKG_PORT=8082, THREADS, RAMPUP,
│                           DURATION, CUST_PHONE, CUST_PASSWORD  — all __P() overridable
├─ HTTP Request Defaults    domain=${HOST}, protocol=http, timeouts 5s/15s  (NO port — set per sampler)
├─ HTTP Header Manager      Content-Type: application/json   ← Content-Type ONLY (see gotcha 2)
├─ CSV Data Set  browse     ../data/browse_params.csv → category,ageGroup   (recycle, shareMode=All)
├─ CSV Data Set  toys       ../data/toy_ids.csv       → toyId               (recycle, shareMode=All)
└─ Thread Group  "Mixed Users"   ${THREADS} / ramp ${RAMPUP} / scheduler+duration ${DURATION}
   ├─ Once Only Controller
   │  └─ POST /api/v1/customers/login   port=${BKG_PORT}, raw body {"phone":..,"password":..}
   │     ├─ JSON Extractor   token   ← $.accessToken   (default TOKEN_NOT_FOUND)
   │     └─ Response Assertion   code == 200
   ├─ Throughput Controller  "80 pct - browse"   percentThroughput=80.0
   │  ├─ GET /api/v1/toys   port=${TOY_PORT}, params category/ageGroup
   │  │  ├─ Response Assertion   code == 200
   │  │  └─ Response Assertion   body contains "content"
   │  └─ Flow Control Action   Pause ${__Random(1000,3000)}
   ├─ Throughput Controller  "12 pct - detail"   percentThroughput=12.0
   │  ├─ GET /api/v1/toys/${toyId}                        → assert 200
   │  ├─ GET /api/v1/toys/${toyId}/availability/calendar  → assert 200
   │  └─ Flow Control Action   Pause ${__Random(1000,3000)}
   └─ Throughput Controller  "8 pct - booking flow"   percentThroughput=8.0
      ├─ GET /api/v1/toys/${toyId}/availability   port=${TOY_PORT}
      │     params from=${__timeShift(yyyy-MM-dd,,P3D,,)}  to=${__timeShift(yyyy-MM-dd,,P10D,,)}
      │     └─ Response Assertion   code == 200
      ├─ POST /api/v1/bookings   port=${BKG_PORT}, raw JSON body
      │  ├─ HTTP Header Manager   Authorization: Bearer ${token}   ← scoped to THIS call only (gotcha 2)
      │  ├─ JSON Extractor   bookingId  ← $.id               (default NOTFOUND)
      │  ├─ JSON Extractor   orderId    ← $.razorpayOrderId   (default NOORDER)
      │  └─ Response Assertion   code matches ^(201|409)$, **Ignore Status ON** (gotcha 3)
      ├─ If Controller   ${__jexl3("${bookingId}" != "NOTFOUND",)}
      │  └─ POST /api/v1/payments/webhook   port=${BKG_PORT}
      │        body {"razorpay_order_id":"${orderId}", ...}   ← snake_case keys (gotcha 4)
      │        └─ Response Assertion   code == 200
      └─ Flow Control Action   Pause ${__Random(2000,5000)}
   (Summary Report listener — no filename; disabled View Results Tree at plan scope)
```

---

## The five gotchas (why the scaffold looks the way it does)

### 1. `ThreadGroup.duration` must be a `stringProp` to hold `${DURATION}`
The GUI writes it as `<longProp>`, which the non-GUI loader parses as a number *before*
variable substitution → `NumberFormatException: "${DURATION}"`. The scaffold uses
`<stringProp name="ThreadGroup.duration">${DURATION}</stringProp>`. If you edit the thread
group in the GUI and re-save, re-check this line.

### 2. Never send `Authorization: Bearer …` on a call that doesn't need it
Both services run Spring Security OAuth2 **resource server**. Its bearer-token filter
tries to validate *any* `Authorization: Bearer x` header and returns **401 before
`permitAll` is even considered**. A global Header Manager with `Bearer ${token}` therefore
401s the public browse calls *and the login call itself* (token is still `TOKEN_NOT_FOUND`
on the first iteration). Fix: global Header Manager carries **Content-Type only**; the
bearer lives in a Header Manager scoped under the single `POST /api/v1/bookings` sampler.
(`/payments/webhook` is `permitAll` and gets no bearer.)

### 3. A passing assertion does NOT rescue a sample JMeter already failed on the 4xx
`409` is expected (two threads race for the same toy+dates — correct behaviour). But
JMeter marks any 4xx sample failed from the response code alone; a passing `^(201|409)$`
assertion won't flip it back. The booking-create assertion has **"Ignore Status" checked**
(`Assertion.assume_success=true`) so the regex becomes the only pass/fail judge: 201 → ok,
409 → ok, anything else → fail. For the *real* conflict count, read the server metric
`booking_conflict_total` in Prometheus, not the JMeter dashboard.

### 4. The webhook DTO uses snake_case JSON keys
`RazorpayWebhookRequest` is annotated `@JsonProperty("razorpay_order_id")` /
`razorpay_payment_id` / `razorpay_signature`. camelCase keys deserialize to null → three
`@NotBlank` violations → 400. Body must be
`{"razorpay_order_id":"${orderId}","razorpay_payment_id":"pay_${__UUID}","razorpay_signature":"sig_load"}`.
(The booking-create body, by contrast, *is* camelCase — `BookingRequest` has no
`@JsonProperty` overrides. Don't assume one convention across the API.)

### 5. `POST /api/v1/bookings` already returns `razorpayOrderId`
No separate `POST /api/v1/payments/initiate` call is needed for S3 — the order is created
inside booking creation and comes back in `BookingResponse.razorpayOrderId` (camelCase in
the *response*). The `If Controller` skips the webhook when `bookingId == NOTFOUND` (i.e.
the create was a 409) so it never fires with a stale id.

Also worth knowing: WireMock's Razorpay stub returns the **same** `order_mock123` for
every order, so one webhook call can settle several pending payments at once. The confirm
+ `booking.confirmed` Kafka publish path still gets exercised — that's what S3 loads.
Don't chase the shared-id behaviour, it's a documented stub limitation.

---

## Data files

- `../data/browse_params.csv` — exists (18 rows). Widen it: more category/age combos,
  and some that return large pages, so you're not just measuring buffer cache.
- `../data/toy_ids.csv` — 2000 random `AVAILABLE` ids. Regenerate anytime:
  ```bash
  kubectl exec -n infra postgres-0 -- psql -U toyuser -d toydb -t -A \
    -c "SELECT id FROM toys WHERE status='AVAILABLE' AND is_active ORDER BY random() LIMIT 2000;" \
    > loadtest/data/toy_ids.csv
  sed -i '1i toyId' loadtest/data/toy_ids.csv
  ```
- CSV paths in the plan are `../data/...` — **relative to the `.jmx` file's folder**
  (`loadtest/plans/`), which is how JMeter's FileServer resolves them, not relative to
  where you launch `jmeter`.

---

## Validate incrementally

1. Open in GUI. Add a **View Results Tree** at Thread Group scope (temporarily). Disable
   the "12 pct" and "8 pct" Throughput Controllers.
2. 1 thread / 1 loop. Run. Check: login 200, `token` populated (click the login sample →
   look for the extractor result), browse 200, URL shows the substituted `category`.
3. Enable "12 pct". Run. `${toyId}` substitutes, calendar 200.
4. Enable "8 pct". 1 thread / 3 loops. create → 201 with an `id`; If Controller fires;
   webhook 200. Force a 409 by running 5 threads so two collide — confirm the sample is
   green (Ignore Status working).
5. Bump to 5 threads / 60s scheduler. Watch for correlation collisions (none if sharing
   mode is `All`).
6. **Disable/delete every GUI listener.** Go headless.

---

## Headless run (PLAN.md matrix rows 8–10)

```bash
STAMP=$(date +%Y%m%d-%H%M)
"/c/Users/USER/Software/apache-jmeter-5.6.3/bin/jmeter" -n \
  -t loadtest/plans/mixed.jmx \
  -l loadtest/results/s3-load-$STAMP.jtl \
  -e -o loadtest/results/s3-load-$STAMP \
  -JTHREADS=60 -JRAMPUP=60 -JDURATION=1200
```

- Load: `-JTHREADS=60 -JDURATION=1200` (20 min).
- Soak: `-JTHREADS=40 -JDURATION=14400` (4 h).
- Spike: can't be done with one flat Thread Group — swap the Thread Group for a
  **Concurrency Thread Group** (Custom Thread Groups plugin) or run two staggered plans.

Open `loadtest/results/<dir>/index.html`. Read error % → p95 per label → throughput vs
active threads. Pair with Grafana: `http_server_requests` p95 by `uri`,
`hikaricp_connections_pending`, `rate(booking_conflict_total[1m])`, Kafka lag on
`booking.confirmed`, per-pod CPU.
