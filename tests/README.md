# API test suite (`tests/`)

Black-box HTTP tests that exercise **toy-service** (`:8081`) and **booking-service**
(`:8082`) while they run, hitting them directly over `kubectl port-forward`.

- Not the same as `*/src/test/java` — those are Maven-bound JUnit slice/unit tests with
  mocked collaborators, run at build time.
- Not the same as `loadtest/` — that's JMeter/urllib latency probing, no correctness
  assertions.
- **api-gateway (`:8080`) is not covered** — its Keycloak JWT validation was never wired
  up, so all real traffic (frontend, these tests) talks to the two services directly.

## Prerequisites

- Python 3.9+.
- The cluster is up and both services are port-forwarded:
  ```
  kubectl port-forward -n toy-rental svc/toy-service     8081:8081
  kubectl port-forward -n toy-rental svc/booking-service 8082:8082
  ```
  or `python scripts/startup.py --skip-infra --skip-monitoring --port-forward`.
- The databases are **Flyway-seeded**:
  - `toydb` → `V4__seed_sample_toys.sql` (`toy-001` … `toy-008`)
  - `bookingdb` → `V6__seed_sample_customer.sql` (Priya Deshmukh, phone `9821012345`,
    password `password`)
- Admin creds are the defaults `admin` / `admin123` (env `ADMIN_USERNAME` /
  `ADMIN_PASSWORD` on booking-service), or override `ADMIN_USERNAME` / `ADMIN_PASSWORD`
  for the test run.

## Install

From the repo root:

```bash
python -m venv .venv
source .venv/Scripts/activate        # Git Bash / Windows
# .venv\Scripts\Activate.ps1         # PowerShell
# source .venv/bin/activate          # Linux / macOS
pip install -r tests/requirements.txt
```

## Run

```bash
pytest tests                                       # everything
pytest tests -m "not e2e"                          # skip the slow lifecycle test
pytest tests -m read_only                          # safe subset for a shared env
pytest tests -m smoke                              # health / JWKS only
pytest tests -m "not slow"                         # skip pollers / async triggers
pytest tests tests/api/test_booking_flow_e2e.py    # just the e2e
pytest tests -k availability -q
```

(If you move `pytest.ini` to the repo root, drop the `tests` path argument.)

### Config (env vars, all optional)

| var | default | meaning |
|---|---|---|
| `TOY_BASE_URL` | `http://localhost:8081` | toy-service base |
| `BOOKING_BASE_URL` | `http://localhost:8082` | booking-service base |
| `ADMIN_USERNAME` / `ADMIN_PASSWORD` | `admin` / `admin123` | admin login |
| `SEED_CUSTOMER_PHONE` / `SEED_CUSTOMER_PASSWORD` | `9821012345` / `password` | V6 seed customer |
| `HTTP_TIMEOUT` | `10` | per-request seconds |
| `POLL_TIMEOUT` / `POLL_INTERVAL` | `30` / `1.0` | async-assertion polling |
| `BOOKING_START_OFFSET_DAYS` | `3` | earliest bookable start = today + this |

```bash
BOOKING_START_OFFSET_DAYS=10 pytest tests          # bash
$env:BOOKING_START_OFFSET_DAYS="10"; pytest tests  # PowerShell
```

## Markers

| marker | selects |
|---|---|
| `smoke` | health / liveness / readiness / JWKS — fast, touches no data |
| `read_only` | only GETs / no-op auth POSTs — safe against a shared environment |
| `mutating` | creates or changes server-side state (rows, Couchbase ranges, Kafka) |
| `admin` | needs the admin token |
| `e2e` | the single full register→pay→confirm lifecycle test (slow) |
| `slow` | polls async state or triggers a background job |

Recommended: `-m read_only` for anything shared, `-m "not e2e"` for a quick pass.

## What it writes to the dev DB, and cleanup

| store | created by | cleaned up? |
|---|---|---|
| `toys` rows | `test_toy_admin_crud` (POST) | **yes** — soft-deleted in a session finalizer (`is_active=false`, row remains, leaves the catalogue) |
| `customers` rows | every `fresh_customer` / register test | no — no delete endpoint; accumulate (harmless) |
| `bookings` + `payments` rows | booking / payment / admin-confirm / e2e tests | no — no delete endpoint; some end `CANCELLED` / `CONFIRMED` |
| Couchbase blocked ranges | every created booking | partially — `CANCELLED` bookings release via the `booking.cancelled` Kafka event; `CONFIRMED` stay blocked (mitigated by randomised date ranges) |
| `reports` + Kafka | `test_admin_reports_trigger` (opt-in) | no — async artifact left as-is |

Tests self-namespace to avoid collisions across runs: random `70xxxxxxxx` phones,
randomised future booking windows, `IT Toy <uuid>` toy names. To return to a pristine
state, redeploy the DBs or run Flyway `clean` + `migrate`.

## Known flakiness

- **booking-service multi-replica JWT keypair mismatch.** `JwtKeyConfig` generates the
  RSA signing key **in memory, per pod**. With >1 booking-service replica each pod signs
  with a different key. `kubectl port-forward svc/booking-service` pins login to one pod,
  but toy-service fetches JWKS through the (load-balanced) Service and may cache a
  different pod's key — so toy-service **admin-route** calls 401 roughly half the time.
  The `admin_toy_client` fixture probes this once and fails fast with instructions.
  Workaround for a clean run (the HPA's `minReplicas: 2` will undo a bare `scale`,
  so remove/patch it first):
  ```
  kubectl patch hpa booking-service -n toy-rental -p '{"spec":{"minReplicas":1}}'
  kubectl scale deploy/booking-service -n toy-rental --replicas=1
  ```
  Real fix: load a shared key (mounted Secret) instead of generating one per instance.
  The ~85 booking-service-only and toy-service-public tests are unaffected either
  way; only the ~15 toy-service admin-route tests depend on this.
- **Port-forward dies on pod recreation.** Any `kubectl apply` / `rollout restart` / HPA
  scale silently kills the forward; the suite then skips wholesale with the health-gate
  message. Re-run the two `kubectl port-forward` commands (or
  `python scripts/startup.py --stop-port-forward` then `... --port-forward`).
- **booking-service issues a fresh in-memory RSA keypair per restart.** Tokens minted
  before a restart 401 afterwards. Token fixtures are session-scoped, so the run fails
  cleanly — just re-run.
- **`@FutureOrPresent` on `startDate` is validated against the Couchbase logical date**,
  not wall clock. If the logical date is advanced far ahead, bump
  `BOOKING_START_OFFSET_DAYS`.
- **`POST /api/v1/bookings` returns 409 for both** "toy not available" and the
  pessimistic-lock overlap. Re-running the same toy + dates yields 409; the fixtures use a
  fresh customer + randomised ranges and retry once.
- **webhook → CONFIRMED is Kafka-driven**, not strictly synchronous. Async assertions use
  `poll_until` bounded by `POLL_TIMEOUT` (default 30s).

## Extending

One module per functional area under `tests/api/`. Mark every new test (`read_only` vs
`mutating`, plus `admin` / `slow` / `e2e` as needed). Prefer a `conftest.py` fixture over
inline setup. Keep new pip dependencies out — `requests` is referenced only in
`helpers.ApiClient`.
