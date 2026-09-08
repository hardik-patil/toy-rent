# pytest Fundamentals — Mapped to ToyRental

A syntax-and-concepts reference for **writing** pytest tests (not just running them), using
the real `tests/` suite in this repo as the running example. Companion to
[../tests/README.md](../tests/README.md), which is the *how to run it* / prerequisites doc;
this one is *the pytest model itself*, so you could rebuild `tests/` from a blank directory
and explain every construct in it.

Same spirit as [jmeter-fundamentals.md](jmeter-fundamentals.md) and
[jenkinsfile-fundamentals.md](jenkinsfile-fundamentals.md).

---

## The mental model

pytest is three ideas:

1. **Discovery by name.** pytest walks `testpaths`, imports every file matching `test_*.py`,
   and collects every function named `test_*` (and `Test*` classes, which this suite
   doesn't use). No base class, no registration, no `if __name__ == "__main__"`.
2. **A test passes unless an exception escapes it.** Almost always that exception is an
   `AssertionError` from a bare `assert`. No `self.assertEqual`; no return value is checked.
3. **Fixtures are dependency injection.** A test (or another fixture) receives a resource by
   naming it as a parameter. pytest builds the graph and tears it down in reverse.

Everything else — parametrization, markers, skips, reports — is detail on top of those.

---

## Anatomy of one test

From `tests/api/test_toy_catalogue.py`:

```python
def test_get_toy_detail_404(toy_client):
    resp = toy_client.get("/api/v1/toys/toy-nope")
    assert_error_contract(resp, status=404, error="TOY_NOT_FOUND")
    assert resp.json()["path"] == "/api/v1/toys/toy-nope"
```

- **`def test_get_toy_detail_404`** — name starts with `test_` → discovered.
- **`(toy_client)`** — a parameter whose name matches a fixture → pytest injects it. The
  test never constructs `toy_client` itself.
- **body** — the *Arrange / Act / Assert* shape: (arrange is trivial here — just the URL),
  **act** = the one call under test, **assert** = checks. Keep "act" to a single call so a
  failure is unambiguous about *what* broke.

### Plain `assert`, and why the failure output is good

pytest rewrites the bytecode of your test modules at import time so that `assert a == b`
prints *both sides* on failure — you don't write `assertEqual`. Example: change
`"toy-001"` to `"toy-999"` in `test_search_by_q_lego` and you get

```
>       assert "toy-999" in ids
E       AssertionError: assert 'toy-999' in ['toy-001']
```

That readout (the actual `ids` list, inline) is the entire reason plain `assert` is
preferred. It only works for test modules and files registered via
`conftest.py` — which is why shared assertion logic lives in `tests/helpers.py`
(`assert_error_contract`) *and* why that helper still gets rich output: `helpers.py` is
imported by `conftest.py`, so pytest rewrites it too. (You can force rewriting of an
arbitrary module with `pytest.register_assert_rewrite("mypkg.helpers")` before it's
imported — not needed here.)

### One assert or many?

Multiple asserts in a test is fine and normal. They run top to bottom; the **first**
failure stops the test (later asserts don't run). Order them cheapest-and-most-fundamental
first: `test_get_toy_detail_404` checks the status/contract before poking at `path`, so a
totally wrong response fails on the contract, not on a confusing `KeyError`.

---

## Fixtures

A fixture is a function decorated with `@pytest.fixture` that returns (or `yield`s) a value.
Anything — a test or another fixture — gets it by naming it as a parameter.

### `conftest.py` — fixtures without imports

`tests/conftest.py` is auto-discovered by pytest for every test under `tests/`. Fixtures
defined there are visible to the whole tree with no `import`. A `conftest.py` deeper in the
tree (e.g. `tests/api/conftest.py`, which this suite doesn't have) would add fixtures for
just that subdirectory.

### The fixture graph in this suite

Fixtures depend on other fixtures, so `tests/conftest.py` is really one dependency graph:

```
config  (reads env vars once)
  ├── toy_client ────────────┐
  ├── booking_client ────────┤
  │      ├── admin_token ─────┼── admin_toy_client      (toy_client + admin_token)
  │      │                    ├── admin_booking_client  (booking_client + admin_token)
  │      └── seed_customer_token ── seed_booking_client
  └── _require_services_up  (autouse — runs before everything)

fresh_customer (function scope) ── pending_booking ── confirmed_booking
```

Read one entry: `admin_toy_client` names `toy_client` and `admin_token` as parameters, so
pytest creates `config` → `booking_client` → `admin_token` (a real POST to
`/api/v1/admin/login`) → then `toy_client`, then hands `admin_toy_client` both. Every test
that asks for `admin_toy_client` reuses that same chain.

### Scope — how often a fixture is rebuilt

```python
@pytest.fixture(scope="session")   # once per `pytest` invocation
def config(): ...

@pytest.fixture                     # scope="function" is the default — once per test
def fresh_customer(booking_client): ...
```

- **session**: `config`, `toy_client`, `booking_client`, all the `*_token` and `*_client`
  fixtures, `some_toy_id`, `poll`. These are expensive (an HTTP login) and safe to share —
  a token doesn't change between tests.
- **function**: `fresh_customer`, `second_customer`, `pending_booking`, `confirmed_booking`,
  `correlation_id`. Each test needs its *own* customer / booking so tests don't interfere;
  rebuilding per test is the point.

A lower-scoped fixture may depend on a higher-scoped one (`fresh_customer` uses the
session `booking_client`); the reverse is an error.

### `yield` = setup / teardown

A fixture that `yield`s runs the part before `yield` as setup, hands the yielded value to
the test, and runs the part after `yield` as teardown — even if the test failed.
`created_toy_ids` in `tests/conftest.py`:

```python
@pytest.fixture(scope="session")
def created_toy_ids(admin_toy_client):
    ids = []
    yield ids                       # tests append the ids of toys they create
    for toy_id in ids:              # teardown: soft-delete every one
        try:
            admin_toy_client.delete(f"/api/v1/toys/{toy_id}")
        except Exception:
            pass                    # best-effort — never let cleanup fail the run
```

Session scope + `yield` = "run this teardown once, after the whole suite". That's the
suite's only cleanup; customers and bookings have no delete endpoint and are left behind
by design (see `tests/README.md`).

### `autouse` — a fixture nobody asks for

```python
@pytest.fixture(scope="session", autouse=True)
def _require_services_up(toy_client, booking_client):
    ... pytest.skip(<message>) ...   # if a /actuator/health call fails
```

`autouse=True` means every test in scope gets it whether or not it names it. Session-scoped
+ autouse = a one-time gate that runs before any token fixture, so a dead cluster produces
*one* clean skip reason instead of 100 confusing fixture errors. The leading underscore is
convention for "you never reference this by name".

### A fixture that returns a helper (`poll`)

```python
@pytest.fixture(scope="session")
def poll(config):
    def _poll(predicate):
        return poll_until(predicate, timeout=config.poll_timeout,
                          interval=config.poll_interval)
    return _poll
```

The fixture's *value* is a function. Tests call `poll(lambda: ...)` for async assertions
(webhook → booking `CONFIRMED`). This is how you parametrise a fixture with per-call
arguments.

---

## Parametrization — one test, many cases

`@pytest.mark.parametrize` runs the same body once per tuple, each as its own reported
test:

```python
@pytest.mark.parametrize("probe", ["liveness", "readiness"])
def test_toy_probes(toy_client, probe):
    resp = toy_client.get(f"/actuator/health/{probe}")
    assert resp.status_code == 200
```

Collected as `test_toy_probes[liveness]` and `test_toy_probes[readiness]` — run, fail, and
select (`-k liveness`) independently. `test_admin_run_sheets` does the same over three URL
paths, `test_admin_inventory_auth_matrix` folds the "no token / customer / admin" matrix
into one test with three explicit assertions (a judgement call — parametrize when the cases
are truly symmetric, inline when they're a short fixed list).

---

## Markers — tagging and selecting

A marker is a label. Register every marker in `tests/pytest.ini` (and `--strict-markers`
turns a typo'd marker into an error instead of a silent no-op):

```ini
[pytest]
markers =
    smoke: fast health/JWKS checks, no data touched
    read_only: only GETs / no-op auth POSTs; safe against a shared env
    mutating: creates or changes server-side state
    admin: requires the admin token
    e2e: full multi-step booking lifecycle (slow)
    slow: polls async state or triggers background work
addopts = -ra --strict-markers --timeout=30
```

Apply them three ways:

```python
pytestmark = pytest.mark.read_only                 # module-level: every test in the file
pytestmark = [pytest.mark.mutating, pytest.mark.admin]

@pytest.mark.slow                                   # one test
def test_webhook_confirms_booking(...): ...

@pytest.mark.parametrize(...)                       # parametrize is also a marker
```

Select with `-m` boolean expressions:

```
pytest tests -m smoke
pytest tests -m "not e2e"
pytest tests -m "read_only and not admin"
```

The Jenkins gate runs `-m "not e2e and not admin"` — see
[pytest-jenkins-integration.md](pytest-jenkins-integration.md) for why those two are
excluded.

---

## Skip and "documented imperfect behaviour"

`pytest.skip(reason)` inside a test or fixture stops that test as **skipped** (not failed).
Use it when the test *can't run here*, not when the code is wrong:

- `_require_services_up` skips the whole session when a service is unreachable.
- `admin_token` skips (not fails) if `/api/v1/admin/login` doesn't return 200 — a missing
  admin config is an environment problem, not a bug in the code under test.

When the app's behaviour is *arguably wrong but real*, don't skip and don't pretend — pin
the actual behaviour and leave a comment. `test_toy_availability.py`:

```python
def test_availability_missing_from(toy_client, some_toy_id):
    # A missing required query param is forwarded to /error (not permitAll in
    # toy-service SecurityConfig), so this currently surfaces as 401 rather than
    # a clean 400. Accept either: the request is rejected, no data leaked.
    ...
    assert resp.status_code in {400, 401}
```

If someone later adds a proper `@ExceptionHandler` and it becomes a clean 400, the test
still passes; if it regresses to a 500, it fails. `pytest.xfail`/`@pytest.mark.xfail` is
the other tool here (expected-failure that flips to XPASS when fixed) — this suite prefers
the `in {...}` form because both codes are genuinely acceptable outcomes.

---

## Factoring assertions — a plain function, not a fixture

`tests/helpers.py::assert_error_contract` is called from ~12 tests:

```python
def assert_error_contract(resp, *, status=None, error=None, correlation_id=None):
    body = resp.json()
    assert set(body) == {"timestamp", "status", "error", "message",
                         "correlationId", "path"}
    ...
```

It's a **function**, not a fixture, because it needs a per-call argument (`resp`) and
returns nothing stateful — fixtures are for *resources*, helpers are for *repeated logic*.
It lives in `helpers.py` (imported by `conftest.py`) so pytest still rewrites its asserts
for good failure output.

---

## Running and selecting

| command | effect |
|---|---|
| `pytest tests` | everything under `testpaths` |
| `pytest tests -v` | one line per test + PASS/FAIL/SKIP |
| `pytest tests -q` | dots |
| `pytest tests -ra` | end-of-run summary of every non-pass with its reason (in `addopts` here) |
| `pytest tests -k availability` | tests whose id contains "availability" |
| `pytest tests -m "not e2e"` | marker expression |
| `pytest tests/api/test_toy_catalogue.py::test_categories` | one test by node id |
| `pytest tests -x` | stop at first failure |
| `pytest tests --lf` / `--ff` | last-failed only / failed-first |
| `pytest tests --tb=short` | shorter tracebacks |
| `pytest tests --junitxml=reports/junit.xml` | machine-readable report (Jenkins reads this) |

**Exit codes** (the Jenkins gate branches on these): `0` all passed, `1` some failed,
`2` interrupted, `3` internal error, `4` usage error, `5` no tests collected.

---

## How the config and imports resolve

- `tests/pytest.ini` has `[pytest]` at the top → pytest treats `tests/` as the **rootdir**
  when you run `pytest tests` (or run from inside `tests/`). Running a bare `pytest` from
  the repo root does **not** find it — markers go unregistered and `--strict-markers`
  isn't applied. Always pass `tests` (or `cd tests`).
- `testpaths = tests` — where discovery starts when no path is given.
- `addopts` — flags folded into every run (`-ra --strict-markers --timeout=30`).
- **No `__init__.py`** anywhere in `tests/`. With pytest's default `prepend` import mode,
  the directory holding `conftest.py` (`tests/`) goes on `sys.path`, so `from helpers
  import ...` works from every test module. Add `__init__.py` files and you'd switch to
  package-import semantics and need `tests.helpers` — avoid.
- `--timeout=30` is from the `pytest-timeout` plugin (in `tests/requirements.txt`): no
  single test may hang more than 30s, which matters when a port-forward dies mid-run.

---

## First-timer mistakes to skip

- **Naming.** `check_toys()` is not collected; it must be `test_toys()`. Same for files:
  `toy_tests.py` is invisible, `test_toys.py` is found.
- **Calling a fixture.** `toy_client()` — no. You *name* it as a parameter; pytest calls it.
- **`print` debugging vanishing.** pytest captures stdout; it only shows on failure, or
  with `-s`.
- **Asserting truthiness of a response.** `assert resp` is always true for a
  `requests.Response`. Assert `resp.status_code == 200`.
- **One test doing five things.** If `test_booking_flow` fails at step 7, you want the name
  and the step obvious. Keep unit-ish tests to one act; reserve the long chain for the
  single `@e2e` test.
- **Shared mutable state between tests.** Tests must not depend on order. This suite's
  `fresh_customer` / random phones / spread date ranges exist precisely so each test is
  self-contained and re-runnable.
- **A bare `pytest` from the repo root.** See the config section above — pass `tests`.

---

## Where to go next

- [../tests/README.md](../tests/README.md) — prerequisites, the run matrix, data hygiene,
  known flakiness.
- [pytest-jenkins-integration.md](pytest-jenkins-integration.md) — running this suite as a
  gate stage in `loadtest/Jenkinsfile`.
- `tests/conftest.py` and `tests/helpers.py` — every construct above, in ~350 lines.
