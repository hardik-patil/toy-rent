# Jenkinsfile Fundamentals — Mapped to ToyRental

A syntax-and-concepts reference for writing (not just reading) a Jenkinsfile, using the
real, working `loadtest/Jenkinsfile` in this repo as the running example. Companion to
[jmeter-jenkins-guide.md](jmeter-jenkins-guide.md) — that doc covers *why this pipeline is
shaped the way it is* (Docker vs. native, GitHub as source of truth, the gate design); this
one covers *the Groovy/declarative-pipeline syntax itself*, so you could rebuild
`loadtest/Jenkinsfile` from a blank file and explain every line of it to an interviewer.

---

## The mental model

A Jenkinsfile is Groovy, but **declarative pipeline** (what we use here, and what you should
default to) restricts you to a fixed skeleton — you fill in named blocks rather than write
arbitrary code. That restriction is a feature: it's what makes a declarative pipeline
readable, lintable, and renderable as the stage-by-stage UI you see in Jenkins.

```
pipeline {                    ← the whole file is exactly one of these
    agent any                 ← WHERE this runs (which machine/container)
    parameters { ... }        ← build-time INPUTS (a form the user fills in)
    environment { ... }       ← variables available to every stage
    stages {                  ← the WHAT, in order
        stage('Name') {
            steps { ... }     ← the actual commands for this stage
        }
    }
    post { ... }              ← runs AFTER all stages, branching on the result
}
```

Execution order: top to bottom through `stages`, one `stage` at a time, then `post` last —
always, no matter how the stages went. This mirrors `loadtest/Jenkinsfile` exactly:
`Checkout` → `Clean previous run artifacts` → `Prepare results dir` → `Run JMeter (non-GUI)`
→ `Performance gate` → `post { always { ... } }`.

---

## The skeleton, block by block

### `agent`

Says where the pipeline (or an individual stage) runs.
```groovy
agent any            // any available agent — here, the only one there is
agent none            // no default agent; every stage must declare its own
agent { label 'linux' }
agent { docker { image 'maven:3-jdk-17' } }
```
`loadtest/Jenkinsfile` uses `agent any` at the top level — there's exactly one Jenkins
instance in this setup (the `jenkins/jenkins:lts` container), acting as both controller and
its own agent, so "any available agent" always resolves to that one machine.

### `parameters`

Declares build-time inputs — this is what makes "Build with Parameters" show a form.
```groovy
parameters {
    choice(name: 'TEST_LEVEL', choices: ['smoke', 'expected'], description: '...')
    string(name: 'THREADS', defaultValue: '5', description: '...')
    booleanParam(name: 'SKIP_GATE', defaultValue: false, description: '...')
    text(name: 'NOTES', defaultValue: '', description: 'multi-line input')
}
```
Read a parameter anywhere in the pipeline as `params.NAME` (e.g. `params.THREADS`).
**Every `string` parameter's value is a String**, even if it looks numeric — `params.TPS`
is the text `"5"`, not the number `5`. Compare it as a string, or convert explicitly
(`params.TPS.toInteger()`) if you ever need real arithmetic on it.

### `environment`

Declares variables computed once, available to every stage as both `env.NAME` and bare
`NAME` inside Groovy string interpolation.
```groovy
environment {
    RESULTS_DIR = "loadtest/results/${env.BUILD_NUMBER}"
}
```
`BUILD_NUMBER` here is one of several variables **Jenkins injects automatically** into every
build (`env.BUILD_NUMBER`, `env.JOB_NAME`, `env.WORKSPACE`, `env.BUILD_URL`, …) — you don't
declare those yourself, you just reference them.

**`parameters` vs. `environment` — different jobs:** a parameter is something a *human (or
a trigger) sets when starting a build*; an environment variable is something *the pipeline
computes or fixes for the duration of one run*, often — as here — derived from a parameter
or a built-in like `BUILD_NUMBER`. Don't reach for a parameter when what you actually want
is a computed constant, and vice versa.

### `stages` / `stage` / `steps`

```groovy
stages {
    stage('Checkout') {
        steps {
            checkout scm
        }
    }
}
```
Every `stage` needs a name (shows up as its own box in the Jenkins UI's pipeline
visualization) and a `steps` block containing what actually runs. A stage can also carry
its own `agent`, `when` (conditional execution), and `post` — not used in
`loadtest/Jenkinsfile`, but common once a pipeline grows past one linear path.

### `post`

Runs once, after every stage has finished, branching on the final result:
```groovy
post {
    always   { /* runs no matter what */ }
    success  { /* only if every stage passed */ }
    unstable { /* the gate flagged it, but didn't hard-fail it */ }
    failure  { /* something actually broke */ }
    changed  { /* result differs from the previous build */ }
    aborted  { /* someone manually stopped the build */ }
}
```
`loadtest/Jenkinsfile` only uses `always` — archiving the `.jtl` and publishing the HTML
report should happen whether the run passed, failed, or went unstable, since you want to
*see* the report precisely when something went wrong, not only on success.

---

## Groovy string interpolation — the one gotcha that bites everyone

Jenkinsfiles are Groovy, and Groovy has **two kinds of strings** that look almost identical
but behave completely differently:

```groovy
sh 'echo $HOME'              // single-quoted: a literal string, Groovy touches nothing.
                              // $HOME is passed through as-is for the SHELL to expand.

sh "echo ${env.HOME}"        // double-quoted: a GString. Groovy substitutes ${...}
                              // BEFORE the shell ever sees the string.
```

This is exactly why `loadtest/Jenkinsfile`'s JMeter invocation uses a **triple-double-quoted**
block (`"""..."""` — a multi-line GString):
```groovy
sh """
    /opt/jmeter/bin/jmeter -n \
        -t loadtest/Regression_toyRental.jmx \
        -JTHREADS=${params.THREADS} -JRAMP_UP=${params.RAMP_UP} \
        ...
"""
```
`${params.THREADS}` has to be resolved by **Groovy**, at pipeline-authoring time, into the
literal text `5` — the shell running inside the container never knows a Jenkins parameter
existed; it just sees `-JTHREADS=5` as plain text. If this block had used single quotes
(`sh '''...'''`) instead, `${params.THREADS}` would be sent to the shell **literally**,
unexpanded, and JMeter would receive a garbage property value.

**Rule of thumb:** need a Groovy/Jenkins value (`params.X`, `env.X`) inside a shell command
→ double quotes. Need a literal `$` that the *shell itself* should expand (rare in this
pipeline, common in more complex scripts) → single quotes, or escape it as `\$`.

---

## The steps `loadtest/Jenkinsfile` actually uses

| Step | What it does | Needs a plugin? |
|---|---|---|
| `checkout scm` | Clones the repo the job is configured against, into the current workspace. **Needed even in a "Pipeline script from SCM" job** — SCM config only tells Jenkins where to *find the Jenkinsfile itself*; it does not automatically check out the rest of the repo into the workspace for later stages. Forgetting this `checkout scm` and expecting `loadtest/Regression_toyRental.jmx` to just be there is the single most common first-timer mistake. | Git plugin (default) |
| `sh '''...'''` | Runs a shell command on a Linux/macOS agent. `bat` is the Windows equivalent — pick based on what OS the *agent* (not your own machine) actually runs. | Built in |
| `archiveArtifacts artifacts: '...'` | Copies matching files into Jenkins' own storage for this build, downloadable from the build page forever (or until log rotation). | Built in |
| `publishHTML(target: [...])` | Serves a directory of HTML (here, JMeter's `-e -o` dashboard) as a tab on the build page, through Jenkins' own web server. | HTML Publisher |
| `perfReport sourceDataFiles: '...', errorFailedThreshold: N, errorUnstableThreshold: N` | Parses a JMeter `.jtl`, computes the error rate, and sets the build result to `FAILURE`/`UNSTABLE`/`SUCCESS` based on the two thresholds. | Performance |

---

## `loadtest/Jenkinsfile`, annotated

```groovy
pipeline {
    agent any                                          // ① one machine, no agent pool

    parameters {                                        // ② the "Build with Parameters" form
        choice(name: 'TEST_LEVEL', choices: ['smoke', 'expected'], description: '...')
        string(name: 'THREADS',       defaultValue: '5',  description: '...')
        string(name: 'RAMP_UP',       defaultValue: '10', description: '...')
        string(name: 'TEST_DURATION', defaultValue: '90', description: '...')
        string(name: 'TPS',           defaultValue: '5',  description: '...')
    }

    environment {
        RESULTS_DIR = "loadtest/results/${env.BUILD_NUMBER}"   // ③ per-build output folder
    }

    stages {
        stage('Checkout') {
            steps { checkout scm }                      // ④ repo arrives here, not before
        }

        stage('Clean previous run artifacts') {
            steps { sh 'rm -f toys_mrp*.csv' }           // ⑤ single-quoted: no Groovy vars
        }                                                //    needed, so no interpolation risk

        stage('Prepare results dir') {
            steps { sh "mkdir -p ${RESULTS_DIR}" }       // ⑥ double-quoted: RESULTS_DIR must
        }                                                //    be resolved by Groovy first

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
                """                                       // ⑦ every ${...} here is Groovy,
            }                                              //    resolved before the shell runs
        }

        stage('Performance gate') {
            steps {
                perfReport sourceDataFiles: "${RESULTS_DIR}/result.jtl",
                    errorFailedThreshold: 20,             // ⑧ >20% errors -> FAILURE
                    errorUnstableThreshold: 5              //   5-20% errors -> UNSTABLE
            }
        }
    }

    post {
        always {                                          // ⑨ runs regardless of outcome
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

If you can explain ①–⑨ out loud without looking at the annotations, you can explain this
file to an interviewer.

---

## Common mistakes (the actual point of this doc)

1. **Forgetting `checkout scm` in a "Pipeline script from SCM" job.** The job config
   already points at a repo — it's tempting to assume the whole repo just appears in the
   workspace. It doesn't; only the `Jenkinsfile` itself gets fetched to load the pipeline
   definition. Every other file your stages need comes from an explicit `checkout scm` step.
2. **Single-quoting a string that needs `${}` interpolation** (or the reverse — double-
   quoting one that doesn't, and getting bitten later when it *accidentally* contains a
   `${...}`-shaped substring). Pick the quote style based on "does Groovy need to fill in a
   value here," not habit.
3. **Comparing a `string` parameter as if it were a number.** `params.THREADS == 5` is
   always `false` — `params.THREADS` is `"5"`, a String. Compare as a string
   (`params.THREADS == '5'`) or convert (`params.THREADS.toInteger() == 5`).
4. **Writing arbitrary Groovy logic directly inside `steps{}`.** Declarative pipelines
   restrict what's allowed in `steps{}` to actual pipeline steps (`sh`, `checkout`,
   `archiveArtifacts`, …) — real Groovy control flow (`if`, loops, variable assignment
   beyond a simple step call) needs a `script { ... }` block wrapping it. None of
   `loadtest/Jenkinsfile`'s stages need this yet, but it's the first wall you hit the moment
   a pipeline needs a real decision (e.g. branching gate thresholds on `params.TEST_LEVEL`).
5. **`sh` on a Windows agent / `bat` on a Linux agent.** Match the step to the *agent's* OS,
   not your own machine's. This pipeline's agent is the Linux-based `jenkins/jenkins:lts`
   container, so `sh` throughout — even though the container happens to be running on a
   Windows host.
6. **A stray missing `}`.** Indentation is cosmetic in Groovy (unlike YAML) — brace matching
   is what actually defines structure. A missing closing brace produces a confusing parse
   error far from the real mistake; if a pipeline suddenly fails to even *start*, count
   braces before suspecting logic.
7. **Thinking a passing `perfReport` means the app is fast.** It means the error rate is
   below threshold — nothing here checks latency yet (see the design doc's §8 on why the
   gate deliberately stops at "smoke" tier). Don't over-claim what a green build proves.
8. **Not cleaning up a plan's own side effects.** `Regression_toyRental.jmx` writes
   `toys_mrp*.csv` outside the results directory, in append mode, with nothing to reset it —
   exactly why this Jenkinsfile has an explicit `Clean previous run artifacts` stage.
   A pipeline is also responsible for tidying up whatever the thing it runs leaves behind.

---

## Interview-ready talking points

Short answers you should be able to give unprompted, in your own words, about *this* file:

- **"What's a Jenkinsfile?"** — Pipeline-as-code: the build/test/deploy process, checked
  into the repo alongside the code it builds, instead of configured by hand in a UI.
- **"Declarative or scripted, and why?"** — Declarative: a fixed `pipeline{}` structure
  (`agent`/`stages`/`post`), more restrictive than raw Groovy but easier to read, lint, and
  visualize — the right default unless a pipeline needs real programmatic control flow.
- **"Walk me through your pipeline."** — Checks out the repo from GitHub, cleans up a
  side-effect file the test plan itself generates, runs a JMeter load test non-interactively
  against the running services, gates the build on the JMeter run's error rate, and
  archives/publishes the results regardless of outcome.
- **"How do you gate a build on quality, not just 'did it run'?"** — `perfReport`'s two
  thresholds map error rate to two different outcomes: `FAILURE` (>20% errors — treat as
  broken) vs. `UNSTABLE` (5–20% — a real signal worth looking at, but not a hard stop).
  That distinction is deliberate, not a rounding choice — see this repo's design doc for
  the reasoning.
- **"`archiveArtifacts` vs. `publishHTML` — why both?"** — `archiveArtifacts` keeps the raw
  `.jtl` (the ground-truth data, small, worth keeping forever); `publishHTML` serves the
  human-readable dashboard as a clickable tab on the build page. One file, two audiences.
- **"What would you add for a production version of this?"** — Named, on purpose, as
  *not yet built*: a GitHub webhook instead of manual/polled triggers; a per-journey SLO
  gate (parsing `statistics.json` against `loadtest/SLOs.md`'s real targets, not just
  aggregate error rate); Jenkins Credentials Manager, once there's an actual secret in the
  pipeline. Being able to name what's deliberately deferred — and why — is a stronger signal
  than pretending the current scope is the final answer.

---

## Practice: rebuild it from memory

Before checking `loadtest/Jenkinsfile` again, try writing the skeleton yourself from just
this doc's "skeleton, block by block" section:

1. `pipeline { agent any }` — the outer shell.
2. Add a `parameters` block with one `choice` and one `string` parameter.
3. Add an `environment` block computing one variable from `env.BUILD_NUMBER`.
4. Add three `stages`: one that just does `checkout scm`, one that runs an `sh` step using
   a parameter via `${params.X}`, one that runs an `sh` step using **no** interpolation
   (single-quoted).
5. Add a `post { always { ... } }` block with one `echo` step.

If it compiles conceptually (braces balanced, every `stage` has a name and a `steps`), you
understand the syntax well enough to have written the real thing.
