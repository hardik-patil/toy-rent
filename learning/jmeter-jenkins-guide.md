# JMeter Scripting + Jenkins CI/CD — Tutoring Log

Written from a real tutoring pass on this repo: reviewing and fixing a hand-authored plan
(`loadtest/ToyRentalMixed-60-tps.jmx`), then (later) wiring it into a Jenkins pipeline.
This is **not** a restatement of JMeter theory — that's already covered in
[jmeter-fundamentals.md](jmeter-fundamentals.md) (anatomy, scoping, timers, correlation
basics, listeners, the standard `Common mistakes` list). This doc is the next layer down:
the specific, easy-to-miss bugs a real plan actually had, why each one breaks, and the
idioms that fix them for good.

**Status:** Part 1 (JMeter scripting) complete. Part 2 (Jenkins) not started yet.

---

# Part 1 — JMeter scripting: lessons from a real review

Case study: `loadtest/ToyRentalMixed-60-tps.jmx`, an open-model mixed plan (60 TPS target,
80/12/8 split across browse / detail / booking, JWT correlation, conditional webhook call).
Ambitious and structurally sound — but had five things that would break it outright and
several that would make it silently measure the wrong thing. None of these are exotic;
they're the JMeter equivalents of off-by-one errors, and every one of them is worth
recognizing on sight.

## 1. `${VAR}` typos don't fail loudly

```
ThreadGroup.ramp_time">${RAMP_UP)          <- closing ) instead of }
```

JMeter doesn't validate that `${...}` is balanced at load time in every field — an
unclosed reference like this is just consumed as a literal string. The ramp time becomes
the text `${RAMP_UP)`, JMeter tries to parse it as a number, and you get either a startup
error or a silent `0`. **Lesson:** after any manual XML edit (or hand-typing a `${}` in the
GUI's raw fields), re-open the plan in the GUI once before running non-GUI — the GUI will
often show an obviously-wrong value where the CLI just fails cryptically.

## 2. Nesting `${var}` inside a function call is fragile — use `vars.get()`

Two places in the original plan did this:

```
${__jexl3(System.currentTimeMillis() >= Long.parseLong(${tokenExpiry),)}   <- also has a typo
${__jexl3("${bookingid}" != "NOT_FOUND")}
```

Both patterns *can* work (JMeter resolves `${}` references before handing the string to
the function), but they're brittle: a typo inside the inner reference is invisible until
runtime, and quoting a variable's value directly into JEXL string literals breaks the
moment the value itself contains a quote or an unexpected character. The idiomatic fix
inside a `__jexl3`/`__groovy` expression is to reach into the variable map explicitly:

```
${__jexl3(vars.get("bookingid") != "NOT_FOUND")}
```

**Lesson:** once you're inside a scripting function (`__jexl3`, `__groovy`, JSR223), stop
using `${}` substitution for variables you need *inside* that script — use the script
language's own variable-access API (`vars.get(...)` for JEXL/Groovy). Reserve `${}` for
substituting into plain string fields (paths, headers, JSON bodies).

## 3. A stray comment inside a scripted field is not a comment

```xml
<stringProp name="WhileController.condition">${__jexl3(...)}

/*
First iteration: ...
*/</stringProp>
```

That `/* ... */` block looks like a helpful comment, but it's *inside the field's string
value* — JMeter (or the JEXL engine, or Groovy) sees it as part of the expression to
evaluate. Sometimes it's silently ignored, sometimes it's a parse error, and either way
it's not documentation, it's a landmine. **Lesson:** every JMeter element with a scripted
field has a separate, real comment mechanism — the `Comments` field in the GUI, stored as
`TestElement.comments` in the XML. Put your explanation there, never inside the
expression string.

## 4. `assume_success=true` on an assertion defeats the assertion

```xml
<ResponseAssertion ...>
  <boolProp name="Assertion.assume_success">true</boolProp>
  ...
</ResponseAssertion>
```

This flag marks the **sample** successful *regardless* of whether the assertion passes —
it's for the rare case where you want to run an assertion for its extracted side-effects
(none here) without letting it fail the sample. Combined with a real correctness check
(`response code is 201 or 409`), it silently converts every 500/timeout into "pass" in
your results. **Lesson:** don't reach for `assume_success` to make a "flexible" assertion —
express the flexibility *in the assertion itself* (a regex/set of acceptable values), and
leave `assume_success` false so a genuine failure still counts as one.

## 5. "Contains" in a JMeter Response Assertion is regex, not substring

I got this wrong in an earlier pass of this same review and want it on record: JMeter's
Response Assertion test types are **Matches** (full-string regex match), **Contains**
(regex *search* — `Pattern.matcher(x).find()`), **Equals** (literal string equality), and
**Substring** (literal substring, no regex). So `test_type=2` ("Contains") with pattern
`^(201|409)$` is already correct — the anchors make it behave like a full match even
though the engine is technically doing a "find". Don't assume "Contains" means "no regex"
— check the JMeter docs (or just test it) before changing an assertion type.

## 6. A CSV Data Set with a header row needs `ignoreFirstLine=true`

```
data/toy_ids.csv:
  toyId
  toy-bulk-46244
  ...
```

With `ignoreFirstLine=false`, the **first row read is the header string itself** — so the
first request that draws from this CSV gets `toyId=toyId` (a 404), and the first browse
request got `category=category&ageGroup=ageGroup` (matches nothing). This produced a real,
previously-mysterious finding earlier in this project's load-test analysis ("why do the
first requests look like they have literal placeholder values instead of real data?") —
the answer was exactly this flag. **Lesson:** any CSV Data Set backed by a file with a
header row needs `ignoreFirstLine=true`; it's independently `true`/`false`, unrelated to
whether `variableNames` is set.

## 7. Absolute paths break the moment the plan leaves your machine

```
C:\Users\USER\Documents\toy-rent\loadtest\data\toy_ids.csv
```

Works for you, breaks in Docker, in Jenkins, on a teammate's machine, or if the repo is
ever cloned to a different path. JMeter resolves a **relative** CSV/script path against the
`.jmx` file's own directory (both GUI and `-n` CLI), so `data/toy_ids.csv` (with the `.jmx`
at `loadtest/ToyRentalMixed-60-tps.jmx`) works everywhere without a `-J` override. This
matters *specifically* for the Jenkins track — a plan with baked-in absolute paths cannot
run on a CI agent at all.

## 8. File I/O inside the load path corrupts your own measurements

The original plan had a JSR223 PostProcessor that wrote a CSV row to disk on every
"toy detail" request — a data-harvesting script that had leaked into the load path. Two
problems: post-processor execution time is counted **inside** its parent Transaction
Controller's sample time, so every detail-transaction's latency now includes a disk write;
and the disk I/O itself adds contention on the load-generator machine, especially when
JMeter and the system under test share one laptop (see `jmeter-fundamentals.md`'s mistake
#5). **Lesson:** if you need to harvest data *from* responses during a test, that's a
separate, one-off script/plan — never a component the load plan's timing depends on.

## 9. Don't stack two throughput-control mechanisms for one goal

The plan had **both**:
- A `ThroughputController` (percent-executions style) deciding which of three branches
  (browse/detail/booking) an iteration runs — 80/12/8.
- A separate `PreciseThroughputTimer` *inside each branch*, each pacing that branch to an
  absolute rate computed from the overall target TPS.

These don't compose cleanly — one gates *which* work happens, the other paces *when* work
happens, and running both means you can no longer reason about what rate you actually
achieved. **Fix applied:** one `PreciseThroughputTimer` at Thread-Group scope (placed after
the login block, so it doesn't throttle login itself — timer scope is "everything that
executes after this point, in this and nested scopes"), left the three
`ThroughputController`s to do only the mix-shaping. One mechanism per job.

**Also caught:** the percentages summed to 104% (browse 80 + detail 12 + booking 12,
though the last one was *named* "8%"). A percent-style `ThroughputController` set silently
tolerates not summing to 100 — it doesn't error, it just gives you a mix you didn't intend.
Always add the percentages by hand when reviewing one of these.

## 10. Size the thread pool for the throughput you want (Little's Law)

`THREADS=10` targeting `TPS=60` cannot work, regardless of how the timers are configured:

```
threads_needed ≈ target_TPS × (avg_response_time + think_time)
```

At roughly 1.2s per iteration, 60 TPS needs on the order of 70+ concurrent threads just to
have enough in flight; 10 threads physically cap you around 8 TPS. A `PreciseThroughputTimer`
can only *slow down* a thread pool that has more capacity than the target — it cannot
manufacture concurrency the thread group doesn't have. **Lesson:** when a throughput target
isn't being hit, check thread count against Little's Law before suspecting the timer
configuration.

## 11. Building a token-refresh loop correctly (`Once Only` + `While`)

The realistic pattern for an API whose tokens expire (this app's don't — 24h TTL, see
`JwtTokenService.EXPIRY_SECONDS` — but most real ones do, on the order of 5–60 minutes):

```
Once Only Controller                  (runs exactly once per thread)
  └─ JSR223 Sampler: vars.put("tokenExpiry", "0")

While Controller                      (condition checked every Thread-Group iteration)
  condition: currentTimeMillis() >= Long.parseLong(vars.get("tokenExpiry"))
  └─ login sampler
     └─ JSON Extractor: token = $.accessToken
     └─ Response Assertion: 200
     └─ JSR223 PostProcessor: vars.put("tokenExpiry", now + <ttl-in-ms>)
```

The subtle bug this fixes: the *init* (`tokenExpiry = "0"`) must live in its **own**
`Once Only Controller`, separate from the `While`. If the reset ran on every iteration
(e.g. as a bare sibling sampler with no Once-Only wrapper), the condition would be true on
*every* iteration and you'd log in every single time instead of once per TTL window. Once
seeded, the `While` loop is cheap on every iteration it doesn't fire (one boolean check),
and fires the body exactly once per TTL window: true → login → new expiry set → condition
re-checked → false → loop exits for this pass.

## 12. Naming and consistency review is part of the review

Small things that add up in a report you'll actually read at 2am during an incident:
- Name every sampler for what it does (`"HTTP Request"` tells you nothing in a 500-row
  report; `"login"` does).
- Keep `TransactionController.parent=true` consistent across parallel branches, or your
  "transactions" table has some entries nested and others not, for no principled reason.
- `Content-Type` at the plan level, `Authorization` scoped to only the samplers that need
  it (added *after* login, not at the top) — don't send a bearer token to endpoints that
  don't need one.

---

# Part 2 — Jenkins CI/CD pipeline

**Status:** ✅ implemented and verified working. Build #1 (`smoke` tier, 5 threads/90s)
ran the real `Regression_toyRental.jmx` against the live cluster through Jenkins:
2,152 requests, 0% errors, `perfReport` gate passed, `.jtl` archived, HTML dashboard
published. Everything below reflects what was actually built, not just the design.

**Revision history, for real (a live example of `CLAUDE.md`'s Known Bugs table):**
1. First pass ran Jenkins in Docker (`jenkins/jenkins:lts`, JMeter bind-mounted in).
2. Revised to a **native Windows install** to avoid a second, permanently-running
   containerization layer on top of Docker Desktop's already-resource-hungry Kubernetes
   cluster (documented CPU/memory contention history in `CLAUDE.md`).
3. **Native install hit a real blocker:** this machine has four JDKs installed
   (17, two 21s, and a 26), and `JAVA_HOME` was misconfigured — pointing at
   `...\jdk-26.0.2\bin` (a JDK too new for Jenkins to recognize, *and* including the
   `\bin` folder, which `JAVA_HOME` should never include). Whether the native Jenkins
   launcher picked up `JAVA_HOME` or `PATH` first was inconsistent enough to cause a
   confusing version-mismatch failure rather than a clean error.
4. **Reverted to Docker after all.** Docker Desktop's daemon was already running for the
   K8s cluster regardless — the marginal cost of *one more container* is small, and
   `jenkins/jenkins:lts` bundles its own JDK 21, fully isolated from the host's confusing
   multi-JDK/`JAVA_HOME` situation. This turned out to be the right call independent of
   the original resource-conservation motive: it sidestepped an entire class of
   environment problems the native path exposed. Architecture below (§3–4) reflects this
   final, working state.

**Target plan:** `loadtest/Regression_toyRental.jmx` (not `ToyRentalMixed-60-tps.jmx` —
see the second reference block at the bottom of this file for its structure). **It has one
known blocker that must be fixed before Jenkins can run it — §7 below, not yet applied.**

**Source of truth:** GitHub, not a local copy. Jenkins' `checkout scm` step clones
`https://github.com/hardik-patil/toy-rent.git` fresh on every build. This is *the*
structural decision behind everything below, so it's worth stating up front: whatever's
committed and pushed is what runs. A `.jmx` edit that only exists on your machine is
invisible to the pipeline.

**For the Jenkinsfile syntax itself** (the Groovy/declarative-pipeline mechanics — how to
actually read and write `pipeline{}`, `stages{}`, `post{}`, string interpolation, and so
on) — see [jenkinsfile-fundamentals.md](jenkinsfile-fundamentals.md), a dedicated
syntax-and-interview-prep reference built around this repo's real
`loadtest/Jenkinsfile`. This doc stays focused on *why the pipeline is shaped the way it
is*; that one covers *how to write the code*.

## 1. Why this exists — the two ways a load-test build can fail

A CI-run load test fails for one of two structurally different reasons, and the whole gate
design below exists to tell them apart:

- **The run itself is broken.** Wrong host, a `.jmx` typo, the target service down, a CSV
  file not found (see §7). Nothing measured is trustworthy — nothing "passed," nothing
  "failed," the test just didn't happen. This should be a hard pipeline `FAILURE`.
- **The run succeeded, but breached a target.** JMeter executed the full plan against a
  live, responding system, and the numbers say the app is too slow or too error-prone. This
  is a real signal about the *application*, not the pipeline — it should mark the build
  `UNSTABLE` (visible, alarming, but distinct from "the pipeline is broken").

Conflating these two is the single most common mistake in a first CI perf-gate: a script bug
and a real regression end up looking identical (both "red") unless the pipeline
deliberately keeps `FAILURE` and `UNSTABLE` as separate outcomes. §6's gate stage uses both
on purpose.

## 2. Jenkins basics — just enough vocabulary

- **Controller** — the Jenkins server itself (web UI, job configs, scheduling). **Agent** —
  where the actual work (`bat`/shell steps) executes. In this setup there's only one
  machine doing both jobs: the Jenkins process running directly on Windows is its own agent
  (`agent any` in the Jenkinsfile just means "run on whatever agent is available," which
  here is always the controller itself — there's nothing else registered, no separate
  agent machine or container).
- **Job** — a configured pipeline (created once in the UI). **Build** — one execution of a
  job, numbered (`#1`, `#2`, …) — `env.BUILD_NUMBER` in a Jenkinsfile refers to this.
- **Jenkinsfile** — a text file, checked into the repo, that *is* the pipeline definition.
  "Pipeline as code": the job configured in the Jenkins UI just points at this file (via
  SCM) rather than containing the pipeline logic itself.
- **Declarative vs. scripted pipeline** — two syntaxes for a Jenkinsfile. Declarative
  (`pipeline { ... }`, a fixed structure of `agent`/`stages`/`post`) is the modern default —
  more restrictive, but easier to read and lint. Scripted (arbitrary Groovy) is the older,
  more powerful, more error-prone style. Everything here is declarative.
- **Anatomy of a declarative Jenkinsfile** — the blocks used in §6:
  - `pipeline { agent { ... } ... }` — the whole file is one `pipeline` block; `agent`
    says where it runs.
  - `parameters { choice(...); string(...) }` — declares build-time inputs, shows up as a
    form when you click "Build with Parameters" in the UI.
  - `environment { NAME = "value" }` — variables available to every stage as `env.NAME` /
    `${NAME}` inside `sh` strings.
  - `stages { stage('Name') { steps { ... } } }` — the sequence of named steps; each
    `stage` shows as its own box in the Jenkins UI's pipeline visualization.
  - `post { always { } success { } failure { } unstable { } }` — runs after all stages,
    branching on the final build result. `always` runs no matter what — this is where
    artifact archiving and report publishing belong, so you get a report even on failure.

## 3. Architecture for this pipeline

```
┌─────────────────────────── Windows host ───────────────────────────┐
│                                                                      │
│  kubectl port-forward → localhost:8081 (toy-service)                │
│                       → localhost:8082 (booking-service)            │
│                                                                      │
│  ┌───────────────── Docker container: jenkins ─────────────────┐    │
│  │  jenkins/jenkins:lts (bundles its own JDK 21 — fully         │    │
│  │  isolated from the host's confusing multi-JDK / JAVA_HOME)   │    │
│  │                                                               │    │
│  │  bind mount (ro): apache-jmeter-5.6.3 → /opt/jmeter          │    │
│  │  named volume:    jenkins_home → /var/jenkins_home           │    │
│  │                                                               │    │
│  │  pipeline build:                                             │    │
│  │    checkout scm  ──────────────────────────────┐             │    │
│  │    /opt/jmeter/bin/jmeter -n -t loadtest/...    │             │    │
│  │      -JHOST=host.docker.internal ───────────────┼──────────► localhost:8081/8082
│  │    perfReport + publishHTML                     │             │    │
│  └──────────────────────────────────────────────────┼───────────┘    │
│                                                       │                │
└───────────────────────────────────────────────────────┼────────────────┘
                                                          ▼
                                          github.com/hardik-patil/toy-rent
                                          (checkout scm clones from here)
```

Two things carry the whole design:

1. **Only JMeter is bind-mounted; the repo isn't.** The repo enters the container fresh,
   every build, via `checkout scm` — that's what "running from GitHub" means concretely.
   JMeter is bind-mounted because it's a portable, already-installed, self-contained
   directory (`C:\Users\USER\Software\apache-jmeter-5.6.3`) with nothing to gain from being
   re-downloaded every build, and no image to build. **Verified working:** the bind-mounted
   `jmeter` shell script (Unix line endings, built on Linux, unzipped on Windows) runs
   without modification inside the Linux container — no CRLF problem in practice.
2. **`host.docker.internal` bridges container → host.** JMeter runs *inside* the container;
   the actual services are reachable via `kubectl port-forward` on the *host's* `localhost`.
   Docker Desktop for Windows auto-injects `host.docker.internal` into every container's
   `/etc/hosts` pointing at the host — no `--add-host` flag needed. Confirmed directly:
   `docker exec jenkins curl http://host.docker.internal:8081/actuator/health` → `200`.

**Alternatives considered, and why not (including one tried and reverted — see the
revision history above):**
- **Native Windows Jenkins install** — tried first, for resource-conservation reasons.
  Blocked by a real `JAVA_HOME` misconfiguration on this machine (pointed at an
  unsupported JDK version, with a malformed path). Reverted to Docker: since Docker
  Desktop's daemon is already running for the K8s cluster regardless, one more container
  is a small marginal cost, and `jenkins/jenkins:lts`'s bundled JDK sidesteps the host's
  Java version confusion entirely rather than fighting it.
- **Jenkins as a Kubernetes Deployment in the existing cluster** — would let Jenkins reach
  `toy-service`/`booking-service` by their in-cluster Service DNS names directly (no
  port-forward, no `host.docker.internal`), which sounds cleaner, but drags in a Helm
  chart or raw manifests, a PVC for `JENKINS_HOME`, and probably the Kubernetes agent
  plugin — real infrastructure unrelated to the actual goal of "learn a Jenkinsfile."
- **A JMeter Docker image (e.g. `justb4/jmeter`) instead of bind-mounting the local
  install** — more "correct" for a hardened CI setup (versioned, no host-path dependency),
  but means either Docker-outside-of-Docker (mounting the Docker socket into the Jenkins
  container) or a multi-container agent (Kubernetes plugin territory again). Good
  hardening exercise for *after* the first working pipeline, not before.

## 4. One-time environment setup

Before this: scale the K8s cluster down if you want the headroom (not strictly required —
in practice this session ran with the cluster already up the whole time, and it was fine):
```bash
kubectl scale statefulset -n infra couchbase kafka minio postgres --replicas=0
kubectl scale deployment  -n infra keycloak redis wiremock postgres-exporter kafka-lag-exporter --replicas=0
kubectl scale deployment  -n monitoring grafana prometheus zipkin --replicas=0
kubectl scale deployment  -n toy-rental api-gateway toy-service booking-service --replicas=0
```
(Or follow `SHUTDOWN.md` if it does more, e.g. removing HPAs.)

Start Jenkins:
```bash
docker pull jenkins/jenkins:lts
docker run -d --name jenkins \
  -p 8080:8080 -p 50000:50000 \
  -v jenkins_home:/var/jenkins_home \
  -v "C:\Users\USER\Software\apache-jmeter-5.6.3:/opt/jmeter:ro" \
  jenkins/jenkins:lts
```
- `jenkins_home` is a **named volume**, not a Windows bind-mount — this is Jenkins' own
  state (job configs, plugins, build history), and keeping it off a Windows bind-mount
  sidesteps the whole class of Linux-UID-vs-NTFS permission questions entirely.
- JMeter is the *only* bind mount, and it's read-only — nothing in Jenkins should ever
  need to write into your local JMeter install.
- The repo itself is deliberately **not** mounted here — it arrives via `checkout scm` in
  §6, not a mount.

The initial admin password is printed directly in `docker logs jenkins` during first boot
(look for "Please use the following password to proceed to installation") — no need to
`docker exec` for it. Then, in your browser at `http://localhost:8080`, work through
Jenkins' own setup wizard yourself: unlock, install the suggested plugins, create the
first admin user.

Two plugins beyond that default set, installed manually via **Manage Jenkins → Plugins →
Available**:
- **Performance** — provides the `perfReport` pipeline step (§6).
- **HTML Publisher** — provides the `publishHTML` pipeline step (§6).

**Verify before moving on:**
```bash
docker exec jenkins java -version
docker exec jenkins /opt/jmeter/bin/jmeter --version
```
If you're on Git Bash / MSYS and `docker exec` mangles a Unix path in the command (a
known class of bug already documented in `CLAUDE.md` for `kubectl cp`/`kubectl exec`),
prefix with `MSYS_NO_PATHCONV=1`.

**One known first-timer trip-up, fixed once, in advance:** the JMeter HTML dashboard
(§6's `publishHTML`) will render unstyled/broken the first time — Jenkins' default
Content-Security-Policy blocks the report's own CSS/JS by default. Fix once via **Manage
Jenkins → Script Console**:
```groovy
System.setProperty("hudson.model.DirectoryBrowserSupport.CSP", "")
```

## 5. Connecting Jenkins to GitHub

Create a new **Pipeline** job. Under "Pipeline," choose **"Pipeline script from SCM"** →
Git → repository URL `https://github.com/hardik-patil/toy-rent.git`, branch
`*/docs/session-lessons-learned` (or whichever branch has `Regression_toyRental.jmx` —
check with `git branch --show-current` before configuring the job; adjust once merged to
`main`). Script path: `loadtest/Jenkinsfile`.

The repo is **public**, so no credentials are needed for checkout. (If it were private:
a GitHub Personal Access Token or deploy key, added once via **Manage Jenkins →
Credentials**, then selected in the job's SCM config — not needed today, worth knowing for
later.)

**What actually triggers a build on a push** — two options, a real tradeoff, not a "pick
either":
- **Poll SCM** (`* * * * *` or similar, in the job's Build Triggers) — Jenkins checks
  GitHub on a schedule and starts a build if the branch moved. Simple, no inbound
  networking required, slightly delayed (up to the poll interval).
- **GitHub webhook** — GitHub calls Jenkins the instant you push. Instant, but requires
  Jenkins to be reachable *from GitHub's servers*, which a `localhost:8080` Docker Desktop
  setup isn't, without a tunnel (ngrok or similar) or a real public deployment.

**Recommendation: start with Poll SCM.** It's a one-line config, needs nothing exposed to
the internet, and teaches the actual pipeline mechanics without an unrelated tunneling
detour. Revisit the webhook once Jenkins has a real, reachable address.

## 6. The Jenkinsfile

Commit this at `loadtest/Jenkinsfile`:

```groovy
pipeline {
    agent any

    parameters {
        choice(name: 'TEST_LEVEL', choices: ['smoke', 'expected'], description: 'Which SLOs.md tier to run/gate against')
        string(name: 'THREADS',       defaultValue: '5',  description: 'Concurrent virtual users')
        string(name: 'RAMP_UP',       defaultValue: '10', description: 'Ramp-up seconds')
        string(name: 'TEST_DURATION', defaultValue: '90', description: 'Scheduler duration, seconds')
        string(name: 'TPS',           defaultValue: '5',  description: 'Target throughput')
    }

    environment {
        RESULTS_DIR = "loadtest/results/${env.BUILD_NUMBER}"
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
            }
        }

        stage('Clean previous run artifacts') {
            steps {
                sh 'rm -f toys_mrp*.csv'
            }
        }

        stage('Prepare results dir') {
            steps {
                sh "mkdir -p ${RESULTS_DIR}"
            }
        }

        stage('Run JMeter (non-GUI)') {
            steps {
                sh """
                    /opt/jmeter/bin/jmeter -n \
                        -t loadtest/Regression_toyRental.jmx \
                        -JHOST=host.docker.internal -JTOY_PORT=8081 -JBOOKING_PORT=8082 \
                        -JTHREADS=${params.THREADS} -JRAMP_UP=${params.RAMP_UP} \
                        -JTEST_DURATION=${params.TEST_DURATION} -JTPS=${params.TPS} \
                        -l ${RESULTS_DIR}/result.jtl \
                        -e -o ${RESULTS_DIR}/html-report
                """
            }
        }

        stage('Performance gate') {
            steps {
                perfReport sourceDataFiles: "${RESULTS_DIR}/result.jtl",
                    errorFailedThreshold: 20,
                    errorUnstableThreshold: 5
            }
        }
    }

    post {
        always {
            archiveArtifacts artifacts: "${RESULTS_DIR}/result.jtl", allowEmptyArchive: true
            publishHTML(target: [
                reportName: 'JMeter Dashboard',
                reportDir:  "${RESULTS_DIR}/html-report",
                reportFiles: 'index.html',
                keepAll: true,
                alwaysLinkToLastBuild: true,
                allowMissing: true
            ])
        }
    }
}
```

Walking through the parts not already covered in §2:
- **`parameters`** — `TEST_LEVEL` is a closed `choice`, not a free string, so the set of
  valid values is discoverable from the "Build with Parameters" form itself. The four
  numeric knobs default to `loadtest/SLOs.md`'s **Smoke** tier (2–5 VU, short duration,
  "script/env sanity") — the right default for a CI-triggered run; the heavier **Expected**
  tier (40 VU, 20 min) is something you'd override at build time, not the default.
- **Declarative `string` parameters are always strings**, even for these numeric knobs —
  they're substituted straight into `-J` flags as text, which is fine; don't go looking for
  a numeric parameter type, there isn't an idiomatic one for this use case.
- **`RESULTS_DIR` keyed by `BUILD_NUMBER`** — every build's raw `.jtl` and HTML report land
  in their own subdirectory, so builds never clobber each other's results, and old ones stay
  inspectable via **archiveArtifacts**.
- **`perfReport` thresholds are percentages of failed requests** — `errorFailedThreshold:
  20` marks the build `FAILURE` above 20% errors; `errorUnstableThreshold: 5` marks it
  `UNSTABLE` between 5–20%. This is deliberately an error-rate-only gate at the Smoke tier
  (matching SLOs.md's own words for that tier: "all green, ignore latency") — no p95 check
  yet, see §8 for why.
- **`archiveArtifacts` gets only the `.jtl`**, never the whole `html-report` directory —
  that's what `publishHTML` is for. Archiving both would double-store the same data and
  create two different "where do I look" answers for the same report.
- **`TEST_LEVEL` isn't consumed by the JMeter invocation itself** — the `.jmx`'s own `-J`
  properties don't know about a "level" concept; it's a hook for the gate stage to branch
  on later (§8). Don't conflate "which SLO tier we're gating against" with "what numbers we
  pass to JMeter" — related, but distinct, and a good thing to keep straight from the start.
- **`Clean previous run artifacts`, added after the first real build.** `Regression_
  toyRental.jmx`'s "Toys Details" thread group has a JSR223 PostProcessor that writes
  `toys_mrp<threadNum>.csv` via a **relative-path** `FileOutputStream` in append mode —
  which resolves against the workspace root (since JMeter is invoked from there, not from
  inside `loadtest/`), and never gets cleaned up by the plan itself. `checkout scm` doesn't
  remove untracked files either, so without this stage those files grow forever across
  builds. A real, live instance of Part 1's lesson 8 ("File I/O inside the load path
  corrupts your own measurements") — found by actually running the pipeline, not by
  reading the `.jmx` in advance.

**Not needed for this pipeline, on purpose:** Jenkins Credentials Manager. `CUST_PHONE`/
`CUST_PASSWORD` are already-committed synthetic load-test fixtures
(`loadtest/seed_loadtest_customers.sql`), not real secrets — passing them as plain `-J`
values (hardcoded or as two more `string` parameters, your call) matches how the rest of
the repo already treats them. `credentials()` / `withCredentials {}` are real, valuable
concepts — worth learning on a pipeline that actually has a secret to protect, not this one.

## 7. Prerequisite fix — `Regression_toyRental.jmx`'s absolute CSV paths

**✅ Applied and pushed.** Both `CSVDataSet` elements in `Regression_toyRental.jmx` used to
hold absolute Windows paths:

```
C:/Users/USER/Documents/toy-rent/loadtest/data/toy_ids.csv       (used twice)
C:/Users/USER/Documents/toy-rent/loadtest/data/browse_params.csv
```

This is Part 1, lesson 7 ("Absolute paths break the moment the plan leaves your machine")
made concrete: once Jenkins clones the repo fresh into its own container workspace via
`checkout scm`, `C:\Users\USER\...` doesn't exist inside that Linux container at all — the
CSV Data Sets will fail to find their files and every sampler that depends on them (toy
IDs, browse params) will error out. Fix (in the JMeter GUI, or by hand-editing the `.jmx`'s
`CSVDataSet.filename` properties): change both to paths relative to the `.jmx` file itself —
```
data/toy_ids.csv
data/browse_params.csv
```
— matching how `ToyRentalMixed-60-tps.jmx`'s own CSV Data Sets are already configured (see
the first reference block below). JMeter resolves a relative CSV path against the `.jmx`
file's own directory in both GUI and `-n` CLI modes, so this works identically on your
machine and inside the Jenkins container once the repo is checked out to
`loadtest/Regression_toyRental.jmx` either way. Verified: build #1 ran cleanly with the
relative paths, all CSV-driven samplers (toy IDs, browse params) resolved correctly.

## 8. Why the gate stops at "smoke," on purpose

`perfReport`'s thresholds only see the **whole-file** error rate and response time — it has
no concept of `loadtest/SLOs.md`'s real per-journey targets (J1 Browse p95 ≤500ms, J2
detail ≤400/300ms, J3 booking ≤1200/2500ms, etc.). Checking those for real means parsing
the JMeter HTML dashboard's `statistics.json` per transaction-controller label and failing
the build (via an `error()` step) if any journey's p95 breaches its own target — a real,
useful next lesson (JSON parsing in a Jenkinsfile, `sh(returnStdout: true)`, `error()`), but
meaningfully more work than the aggregate gate above.

**Deliberately deferred as a named "Part 3,"** not half-built into this pass: the `smoke`
tier's aggregate error-rate gate is the complete, working deliverable of Part 2. A
per-journey `expected`-tier gate (using the `TEST_LEVEL` parameter's second value, branching
the `perfReport` thresholds or adding a follow-on stage) is future work, once the basic
pipeline is running end-to-end.

## 9. Implementation checklist — status

1. ✅ Fix `Regression_toyRental.jmx`'s CSV paths (§7) and push.
2. ⏭️ Scale the K8s cluster down (§4) — skipped in practice; the cluster stayed up the
   whole session with no problems.
3. ✅ `docker run` Jenkins (§4) — reverted here from an attempted native install (see the
   revision history above). Setup wizard, Performance + HTML Publisher, CSP fix all done.
4. ✅ Create the Pipeline job pointed at GitHub (§5).
5. ✅ Commit `loadtest/Jenkinsfile` (§6), plus the `Clean previous run artifacts` stage
   added after the first real build surfaced the `toys_mrp*.csv` issue.
6. ✅ K8s cluster confirmed up throughout; `host.docker.internal:8081`/`:8082` both `200`
   from inside the Jenkins container.
7. ✅ **Build #1: SUCCESS.** 2,152 requests, 0.0% errors, avg 57ms, over 90s at 5
   threads. `perfReport` gate passed cleanly; `.jtl` archived; HTML dashboard published
   and rendering correctly (CSP fix confirmed working).
8. ⬜ Not yet done — worth doing next: deliberately break the gate (drop
   `errorUnstableThreshold` unrealistically low, or stop a service before triggering a
   build) to see `UNSTABLE`/`FAILURE` actually fire, not just in theory.
9. ⬜ Not yet done — set up **Poll SCM** as a build trigger, so a future push doesn't
   need a manual "Build with Parameters" click.

---

# Reference — `ToyRentalMixed-60-tps.jmx`'s structure

```
Test Plan (UDVs: HOST, TOY_PORT, BOOKING_PORT, THREADS, CUST_PHONE, CUST_PASSWORD,
                 RAMP_UP, TEST_DURATION, TPS, THROUGHPUT_PER_HOUR)
├─ HTTP Request Defaults, HTTP Header Manager (Content-Type)
├─ CSV Data Set × 2 (browse_params.csv, toy_ids.csv — relative paths, ignoreFirstLine=true)
└─ Thread Group "Mixed Users" (${THREADS}, ${RAMP_UP}, ${TEST_DURATION}, scheduler=true)
   ├─ Once Only Controller → JSR223: init tokenExpiry=0
   ├─ While Controller (relogin every ~10 min) → login → extract token → set tokenExpiry
   ├─ Precise Throughput Timer (${THROUGHPUT_PER_HOUR}, single, total rate)
   ├─ Throughput Controller 80% → TC_Browse → BROWSE → assert 200 → think 100-300ms
   ├─ Throughput Controller 12% → TC_Detail → 2× GET → assert 200 each → think 50-150ms
   └─ Throughput Controller  8% → TC_Booking → availability → POST bookings → assert
      201|409 → extract bookingid/orderid → If(bookingid found) → webhook → think 50-150ms
```

Run:

```bash
jmeter -n -t loadtest/ToyRentalMixed-60-tps.jmx \
  -JTPS=60 -JTEST_DURATION=300 -JTHREADS=150 -JRAMP_UP=10 \
  -l loadtest/results/mixed60.jtl -e -o loadtest/results/mixed60-report
```

---

# Reference — `Regression_toyRental.jmx`'s structure

The Jenkins CI target (Part 2). Three **independent** Thread Groups running concurrently
(not one mixed group with throughput controllers, unlike the plan above) — each with its
own `${THREADS}`/`${RAMP_UP}`/`${TEST_DURATION}`, so all three run at the same VU count and
schedule window by default.

```
Test Plan (UDVs: HOST, TOY_PORT, BOOKING_PORT, path_toys, path_bookings, path_login,
                 THREADS, RAMP_UP, TEST_DURATION, p_transactions_per_min (TPS*60, __groovy),
                 CUST_PHONE, CUST_PASSWORD)
├─ HTTP Request Defaults, HTTP Header Manager
├─ CSV Data Set × 2 (toy_ids.csv, ⚠ currently ABSOLUTE Windows paths — fix per §7 above)
│
├─ Thread Group "Toys Details" (${THREADS}, ${RAMP_UP}, ${TEST_DURATION}, scheduler=true)
│  └─ GET /toys/{toyId} → assert → Constant Throughput Timer → JSON Extractor → JSR223 PostProcessor
│
├─ Thread Group "BROWSE" (${THREADS}, ${RAMP_UP}, ${TEST_DURATION}, scheduler=true)
│  ├─ CSV Data Set (browse_params.csv, ⚠ also absolute — same fix)
│  └─ GET toys → Constant Throughput Timer → assert
│
└─ Thread Group "Bookings" (${THREADS}, ${RAMP_UP}, ${TEST_DURATION}, scheduler=true)
   ├─ Once Only Controller → POST /login → JSON Extractor → JSR223 PostProcessor
   ├─ While Controller (condition: __groovy, currentTimeMillis - tokenTimeStamp >= 900000)
   │  └─ POST /login → JSON Extractor → JSR223 PostProcessor
   └─ Simple Controller
      └─ POST /api/v1/bookings → HTTP Header Manager (Bearer) → Constant Throughput Timer
         → [JSR223 Assertion — disabled] → Response Assertion
```

Its relogin `While Controller` already uses `__groovy` for the static-method condition —
the exact fix `ToyRentalMixed-60-tps.jmx` needed applied after the event (see this file's
Part 1 lesson on `__jexl3` vs `__groovy`, and `CLAUDE.md`'s Known Bugs table, 2026-09-05
entry). Nothing to change there.

Run (once §7's path fix is applied):

```bash
jmeter -n -t loadtest/Regression_toyRental.jmx \
  -JTHREADS=10 -JRAMP_UP=10 -JTEST_DURATION=90 -JTPS=5 \
  -l loadtest/results/regression.jtl -e -o loadtest/results/regression-report
```
