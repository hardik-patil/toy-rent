# loadtest/

JMeter test plans and supporting data for performance-testing ToyRental. This folder is
the "how to run it" reference; [PLAN.md](PLAN.md) is the "what to run and why".
[../learning/jmeter-fundamentals.md](../learning/jmeter-fundamentals.md) is the JMeter
crash-course.

```
loadtest/
├── README.md              ← you are here
├── PLAN.md                ← the test plan: scenarios, matrix, session flow
├── SLOs.md                ← KPIs/SLO targets per journey + SLO-breach → bottleneck decoder
├── RUN-LOG.md             ← run history + analysis (results/ is git-ignored, conclusions live here)
├── catalogue-browse.jmx   ← S1 starter plan (composite-index bottleneck)
├── plans/                 ← new .jmx files go here (S2 booking, S3 mixed)
├── data/
│   └── browse_params.csv  ← category/ageGroup pool for S1
├── results/               ← .jtl + HTML dashboards land here (git-ignored)
└── seed_toys_bulk.sql     ← ad-hoc 50k-row toys seed (NOT a Flyway migration)
```

---

## Prerequisites

### 1. Java — already present

`java -version` should work (JDK 17 at `C:\Program Files\Eclipse Adoptium\...`). JMeter
needs Java 8+; 17 is fine.

### 2. JMeter — not yet installed

```powershell
winget install Apache.JMeter
```

or download the binary from <https://jmeter.apache.org/download_jmeter.cgi>, unzip, and
run `apache-jmeter-5.6.3/bin/jmeter.bat` (GUI) / `jmeter` (CLI). Add `bin/` to `PATH` so
`jmeter -n ...` works from the repo root.

Recommended plugins (via the JMeter Plugins Manager JAR in `lib/ext/`): **Custom Thread
Groups** (Concurrency / Stepping / Ultimate — needed for the stress and scalability runs
in PLAN.md) and **3 Basic Graphs**.

### 3. The stack must be up

Follow [../STARTUP.md](../STARTUP.md). You need, at minimum:

- `toy-service` reachable at `http://localhost:8081` (port-forward)
- `booking-service` reachable at `http://localhost:8082` (port-forward)
- Prometheus at `:9090` and Grafana at `:3000` for server-side observation
- The API gateway is **not** used — plans hit the services directly.

Sanity check:
```bash
curl -s -o /dev/null -w "toy %{http_code}\n"     http://localhost:8081/actuator/health
curl -s -o /dev/null -w "booking %{http_code}\n" http://localhost:8082/actuator/health
```

> ⚠️ Every port-forward dies silently when its pod is recreated (`kubectl apply`, HPA
> scale, rollout). If a run suddenly goes all-errors, re-run the `kubectl port-forward`
> before suspecting the service. See [../learning/port-forwarding.md](../learning/port-forwarding.md).

### 4. Seed realistic data (S1 / S3 only)

The 8-row `V4` seed is too small for the query planner to care about the missing index.
Bulk-seed 50k rows:
```bash
MSYS_NO_PATHCONV=1 kubectl cp loadtest/seed_toys_bulk.sql infra/postgres-0:/tmp/seed_toys_bulk.sql
kubectl exec -n infra postgres-0 -- psql -U toyuser -d toydb -f /tmp/seed_toys_bulk.sql
```
Undo: `kubectl exec -n infra postgres-0 -- psql -U toyuser -d toydb -c "DELETE FROM toys WHERE id LIKE 'toy-bulk-%';"`

(`MSYS_NO_PATHCONV=1` stops Git Bash mangling the pod-side `/tmp/...` path — see
`CLAUDE.md`'s Known Bugs table.)

---

## Running a plan

**Author / debug** in the GUI (1 thread, 1 loop, View Results Tree on):
```bash
jmeter -t loadtest/catalogue-browse.jmx
```

**Measure** in non-GUI mode, always:
```bash
STAMP=$(date +%Y%m%d-%H%M)
jmeter -n \
  -t loadtest/catalogue-browse.jmx \
  -l loadtest/results/s1-load-$STAMP.jtl \
  -e -o loadtest/results/s1-load-$STAMP \
  -JTHREADS=100 -JRAMPUP=60 -JLOOPS=-1
```

- `-n` non-GUI · `-t` plan · `-l` raw results · `-e -o <dir>` HTML dashboard (dir must
  not exist yet).
- `-J<name>=<value>` overrides a `${__P(name,default)}` property — this is how you walk
  the PLAN.md test matrix without editing the `.jmx`. `catalogue-browse.jmx` exposes
  `THREADS`, `RAMPUP`, `LOOPS`.
- The thread group in the starter plan is scheduler-based with a hardcoded 300s
  `duration` — edit that in the GUI for longer soak runs, or reparameterise it as
  `${__P(DURATION,300)}`.

Open `loadtest/results/<dir>/index.html` for the dashboard.

---

## Known issues in the current starter plan

- **CSV path bug**: `catalogue-browse.jmx`'s CSV Data Set Config `filename` points at the
  `.jmx` file itself, not `data/browse_params.csv`. Fix before relying on parameterised
  browse traffic (set it to `data/browse_params.csv`, path relative to the plan).
- `browse_params.csv` has 18 rows — fine for a smoke test, thin for a real run. Widen the
  pool (more category/age combos, and combos that return large result sets) once the 50k
  seed is in.
- Only S1 has a plan. S2 (booking flow) and S3 (mixed) are specced in PLAN.md but not yet
  built — that's the scripting practice.
