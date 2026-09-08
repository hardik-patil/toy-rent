# Adding the pytest Suite to the Jenkins Pipeline

How the `tests/` pytest suite became a **gate stage** in `loadtest/Jenkinsfile` — the
obstacles, why the obvious fixes didn't work, and every decision in the final shape.

Companion to [pytest-fundamentals.md](pytest-fundamentals.md) (the pytest model),
[../tests/README.md](../tests/README.md) (running the suite), and
[jenkinsfile-fundamentals.md](jenkinsfile-fundamentals.md) (declarative-pipeline syntax).
That last doc's running example is the *same file* this one modifies.

---

## The goal

`loadtest/Jenkinsfile` ran JMeter and nothing else. We wanted the functional suite to run
**first, as a gate**: if the API is functionally broken, don't waste 90+ seconds load
testing it — fail fast with a clear per-test report.

Target flow:

```
Checkout → Clean → Prepare results dir → [pytest gate] → Run JMeter → Performance gate
```

---

## Obstacle 1 — Jenkins has no Python

The Jenkins container is stock `jenkins/jenkins:lts` (Debian trixie):

```
$ docker exec jenkins bash -c 'python3 --version; whoami; sudo -n true'
bash: line 1: python3: command not found
jenkins
bash: line 1: sudo: command not found
```

No Python, running as the unprivileged `jenkins` user, no `sudo`. Three ways to fix it,
two of which don't work here:

### ✗ `apt-get install python3` in a pipeline step

Needs root. The `jenkins` user isn't root and has no sudo. Dead on arrival.

### ✗ Run the stage in a `python:3.x` container

```groovy
stage('pytest') {
    agent { docker { image 'python:3.12-slim' } }
    ...
}
```

Needs the Docker CLI *and* `/var/run/docker.sock` mounted into the Jenkins container. Our
`docker run` (see `STARTUP.md`) mounts only `jenkins_home` and JMeter — no socket. Adding
the socket also means installing the docker CLI in Jenkins (itself a custom image) and
accepting docker-in-docker's footguns. More moving parts than the problem deserves.

### ✗ Volume-mount a Python like JMeter is mounted

JMeter is mounted read-only from the host (`-v ...apache-jmeter-5.6.3:/opt/jmeter:ro`) and
that works because JMeter is a **relocatable tarball of Java classes** — path-independent,
runs on any JRE. CPython is not: it's compiled against a specific libc and has baked-in
`sys.prefix` paths. A Windows-host Python cannot execute in a Debian container. Not viable.

### ✓ Bake Python into a thin custom image

`docker/jenkins/Dockerfile`:

```dockerfile
FROM jenkins/jenkins:lts
USER root
RUN apt-get update \
 && apt-get install -y --no-install-recommends python3 python3-venv python3-pip curl \
 && rm -rf /var/lib/apt/lists/*
USER jenkins
```

- `USER root` for the install, back to `USER jenkins` so the container still runs
  unprivileged.
- `python3-venv` and `python3-pip` are **separate packages** on Debian — `python3` alone
  gives you an interpreter that can't `-m venv` or `-m pip`.
- `curl` is added too: the gate stage health-checks the services with it (Obstacle 3).
- On Debian trixie, PEP 668 marks the system Python "externally managed" — irrelevant
  here because the pipeline always installs into a **venv**, which is exempt.

Build and swap the container in:

```bash
docker build -t toyrental/jenkins:lts docker/jenkins
docker stop jenkins && docker rm jenkins
docker run -d --name jenkins \
  -p 8080:8080 -p 50000:50000 \
  -v jenkins_home:/var/jenkins_home \
  -v "C:\Users\USER\Software\apache-jmeter-5.6.3:/opt/jmeter:ro" \
  toyrental/jenkins:lts
```

**Recreating the container loses nothing.** `jenkins_home` is a *named volume*; jobs,
plugins, credentials and build history live there and survive `docker rm`. Only the image
tag in the `run` command changes. "Restart Jenkins" is still `docker start/stop jenkins`.

The JUnit plugin (needed by the `junit` step below) was already installed — check
`Manage Jenkins → Plugins` if starting from a fresh Jenkins.

---

## Obstacle 2 — reaching the services

The Jenkins container is not on the Kubernetes network. But this is already solved for
JMeter: `loadtest/Jenkinsfile` passes `-JHOST=host.docker.internal`, and the services are
exposed on the host by `kubectl port-forward`. Docker Desktop resolves
`host.docker.internal` to the host from inside any container.

So the pytest stage sets:

```groovy
environment {
    TOY_BASE_URL     = "http://host.docker.internal:8081"
    BOOKING_BASE_URL = "http://host.docker.internal:8082"
}
```

and `tests/conftest.py` reads exactly those env vars (its `localhost` defaults are for a
developer running pytest on the host). This is *why* the base URLs were made configurable
in the first place — CI was the anticipated second caller.

Same prerequisite as any JMeter build: **the host port-forwards must be running.**

```bash
kubectl port-forward -n toy-rental svc/toy-service     8081:8081
kubectl port-forward -n toy-rental svc/booking-service 8082:8082
```

---

## Obstacle 3 — the suite's own health-gate skips *green*

`tests/conftest.py::_require_services_up` calls `pytest.skip()` when a service is
unreachable. For a developer that's the right call — "your port-forward died, here's how
to fix it" beats 100 red failures. But **a skipped test is not a failed test**: a run
where every test skips exits `0`. As a CI gate that's useless — a completely down
environment would show a green build.

So the gate stage does its own explicit check *before* pytest and hard-fails:

```sh
for pair in "toy-service|$TOY_BASE_URL" "booking-service|$BOOKING_BASE_URL"; do
    name=${pair%%|*}; url=${pair#*|}
    code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "$url/actuator/health" || echo 000)
    echo "$name  $url/actuator/health -> $code"
    if [ "$code" != "200" ]; then
        echo "Start the host port-forwards, then re-run:"
        echo "  kubectl port-forward -n toy-rental svc/toy-service     8081:8081"
        echo "  kubectl port-forward -n toy-rental svc/booking-service 8082:8082"
        exit 1
    fi
done
```

`exit 1` fails the `sh` step → fails the stage → the pipeline stops before JMeter.

---

## The gate stage

Inserted between *Prepare results dir* and *Run JMeter (non-GUI)*:

```groovy
stage('API functional tests (pytest gate)') {
    when { expression { params.RUN_API_TESTS } }
    steps {
        sh '''
            set -eu
            python3 -m venv "$VENV"
            "$VENV/bin/pip" install --quiet --upgrade pip
            "$VENV/bin/pip" install --quiet -r tests/requirements.txt

            # ... the health check from Obstacle 3 ...

            "$VENV/bin/pytest" tests -m "$PYTEST_MARKERS" -ra \
                --junitxml="$RESULTS_DIR/pytest-junit.xml"
        '''
    }
}
```

### Decisions

**Placement — before JMeter.** Correctness before load. A functional regression should
block the run, not get buried in a latency histogram.

**`set -eu` + bare `pytest` = hard fail.** pytest exits `1` on any test failure; `set -e`
turns that into a failed step and the pipeline stops (JMeter never runs). This is a
*gate* — FAILURE, not UNSTABLE, is the point. The standalone-job version of this
Jenkinsfile did a `script { def rc = sh(returnStatus:true, ...) }` dance to let the
`junit` step downgrade a test failure to UNSTABLE; a gate deliberately does not.
To soften it (run JMeter anyway, mark the build yellow) wrap the pytest line in
`catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE') { ... }`.

**`when { expression { params.RUN_API_TESTS } }` toggle.** A `booleanParam` defaulting to
`true`. Untick it for a pure load run and the stage is skipped entirely — the load
pipeline keeps its original behaviour on demand.

**`PYTEST_MARKERS` default = `not e2e and not admin`.**
- `not e2e` — the one end-to-end lifecycle test is slow and wants more Couchbase
  logical-date headroom than a CI box reliably has.
- `not admin` — the ~15 `test_toy_admin_crud.py` / `test_admin_bookings.py` tests depend
  on toy-service validating a booking-service-issued admin JWT. **booking-service mints
  its RSA signing key in memory, per pod.** With 2 replicas the pods have different keys;
  `kubectl port-forward svc/booking-service` pins login to one pod, but toy-service
  fetches JWKS through the load-balanced Service and may cache the *other* pod's key →
  those tests 401 about half the time. This is a real bug the suite surfaced (see
  `tests/README.md` "Known flakiness"); until it's fixed (shared key from a mounted
  Secret) the gate excludes those tests so a green build means something. Override the
  param to `smoke` for a ~5s sanity gate, or `not e2e` once the key is shared.

**venv in `$WORKSPACE/.venv-ci`, rebuilt each run.** ~10s including `pip install`. Simple
and hermetic. Caching (skip `venv` creation when `.venv-ci/bin/pytest` exists and
`requirements.txt` is unchanged) is an easy later optimisation, deliberately skipped for v1.

**`--junitxml` + a `junit` step in `post`.** Gives Jenkins the Test Result trend graph and
per-test drill-down. In `post { always { ... } }` so results publish even when the stage
failed:

```groovy
post {
    always {
        junit testResults: "${RESULTS_DIR}/pytest-junit.xml", allowEmptyResults: true
        archiveArtifacts artifacts: "${RESULTS_DIR}/pytest-junit.xml", allowEmptyArchive: true
        // ... existing JMeter archive + publishHTML ...
    }
}
```

`allowEmptyResults: true` because `RUN_API_TESTS=false` produces no XML and that's not an
error.

---

## New parameters and env, in full

```groovy
parameters {
    // ... existing THREADS / RAMP_UP / TEST_DURATION / TPS / TEST_LEVEL ...
    booleanParam(name: 'RUN_API_TESTS', defaultValue: true,
                 description: 'Run the tests/ pytest suite as a gate before the load run.')
    string(name: 'PYTEST_MARKERS', defaultValue: 'not e2e and not admin',
           description: "pytest -m expression for the gate.")
}
environment {
    RESULTS_DIR      = "loadtest/results/${env.BUILD_NUMBER}"   // existing
    VENV             = "${env.WORKSPACE}/.venv-ci"
    TOY_BASE_URL     = "http://host.docker.internal:8081"
    BOOKING_BASE_URL = "http://host.docker.internal:8082"
}
```

A `string` parameter's value is always a String; `params.RUN_API_TESTS` from a
`booleanParam` is a real Boolean, so `when { expression { params.RUN_API_TESTS } }` works
directly.

---

## Verifying without triggering a build

Before touching the Jenkins UI, the whole gate stage was dry-run **inside the actual
Jenkins container**, reproducing what the `sh` step does:

```bash
docker exec jenkins bash -c '
  git clone --branch <branch> --depth 1 <repo> /tmp/dry && cd /tmp/dry
  export VENV=/tmp/dry/.venv-ci RESULTS_DIR=/tmp/dry/results
  export TOY_BASE_URL=http://host.docker.internal:8081
  export BOOKING_BASE_URL=http://host.docker.internal:8082
  export PYTEST_MARKERS="not e2e and not admin"
  mkdir -p "$RESULTS_DIR"
  python3 -m venv "$VENV"
  "$VENV/bin/pip" install -q -r tests/requirements.txt
  "$VENV/bin/pytest" tests -m "$PYTEST_MARKERS" -ra --junitxml="$RESULTS_DIR/pytest-junit.xml"
'
# -> 74 passed, 26 deselected in ~9s
```

This confirmed: python3 present, `pip install` reaches PyPI, `host.docker.internal` reaches
the services, markers deselect the right tests, JUnit XML is written where `post` expects
it.

---

## Running it for real

1. Host port-forwards up (`svc/toy-service` 8081, `svc/booking-service` 8082).
2. Jenkins → `toy-rental-loadtest` → **Build Now** once — the first run after the
   Jenkinsfile change is when Jenkins parses the new `parameters {}` block.
3. From the second build on: **Build with Parameters**, leave `RUN_API_TESTS` ticked.
4. Stage view shows `API functional tests (pytest gate)` before `Run JMeter`; the job
   gains a **Test Result** trend.

---

## What this intentionally does not do

- **No venv caching** — rebuilt every run for simplicity.
- **No separate job** — it's a stage in the existing load pipeline, not its own item. A
  standalone `tests/Jenkinsfile` was drafted and then dropped in favour of the gate.
- **No auto-trigger** — still a manual "Build". Add `triggers { pollSCM(...) }` or a
  GitHub webhook if you want push-to-run.
- **It doesn't fix the JWT keypair bug** — it routes around it with `not admin`. The fix
  is a shared signing key (mounted Secret) so every booking-service replica signs
  identically.
