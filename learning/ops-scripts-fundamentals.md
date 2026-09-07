# Ops Scripts, Explained — startup.py / shutdown.py / capture_diagnostics.py

How and why `scripts/startup.py`, `scripts/shutdown.py`, and
`scripts/capture_diagnostics.py` are built the way they are — for reading the code with
intent, not just running it. For the manual runbooks these automate, see
[STARTUP.md](../STARTUP.md), [SHUTDOWN.md](../SHUTDOWN.md), and
[heapdump-jfr.md](heapdump-jfr.md) (JFR/heap-dump *concepts* live there; this doc is about
the *scripts*).

---

## The shared shape

All three are plain-stdlib Python (no `pip install`) built from the same small skeleton:

```
argparse CLI  →  a run() helper wrapping subprocess  →  preflight guard(s)  →
ordered steps, printed as they happen  →  optional verify / summary output
```

`run()` and `kubectl_available()` are **copy-pasted across all three files**, not shared
via a common module. That's deliberate, not an oversight: these are three small,
independent ops scripts someone might open and read *one at a time*, months apart, without
first tracing an import graph. A shared `_common.py` would save ~15 lines per file at the
cost of "wait, where does `run()` actually come from" the first time you read any one of
them in isolation. Worth knowing as a real tradeoff (DRY vs. standalone-readability), not a
mistake to fix.

---

## `startup.py` — bringing a stopped cluster back up

Automates `STARTUP.md` steps 1–6, in order, for a cluster that's already been built once
(namespaces/PVCs exist, everything's just scaled to 0).

**`preflight()`** is the guard that keeps this script from doing something confusing on a
*fresh* cluster (no namespaces at all) — it checks all three namespaces exist and exits
with the manual fresh-cluster steps if not, rather than plowing ahead into `kubectl scale`
commands against resources that don't exist yet.

**`step_infra` / `step_monitoring` / `step_app_services`** — the three layers, brought up
in dependency order (infra, so Postgres/Kafka/Couchbase are reachable when app services
start; monitoring; then app services). The one asymmetry worth noticing: app services use
`kubectl apply` (re-applying the full manifest), not `kubectl scale` like the other two
layers. Why: `SHUTDOWN.md` **deletes** each app service's HorizontalPodAutoscaler on the way
down (see `shutdown.py` below), so a plain `--replicas=1` would bring the pod back with no
autoscaling at all. Re-applying the manifest recreates the Deployment *and* its HPA (defined
in the same YAML file) in one step.

**`wait_rollout()`** blocks on `kubectl rollout status` with a timeout, and — importantly —
doesn't treat a timeout as fatal for its own sake; it prints what to check
(`kubectl describe`) and returns `False`, letting the caller decide whether that's fatal
(infra) or just a warning (monitoring, which nothing depends on).

**`node_worker_cpu()` / `cpu_breather()`** exist because of a real, documented incident:
three JVMs cold-starting simultaneously has previously pinned this node's CPU hard enough
to crash-loop pods. Since `metrics-server` was never installed on this cluster,
`kubectl top` isn't available — `docker stats` (reading the underlying Docker Desktop VM
directly) is the workaround, and `cpu_breather()` inserts a 45s pause between app-service
rollouts if CPU is already high, rather than start three JVMs back-to-back no matter what.

**Port-forwards as detached background processes** (`start_port_forwards` /
`stop_port_forwards`) is the most "systems programming" part of either script:
- `subprocess.Popen(..., creationflags=DETACHED_PROCESS | CREATE_NEW_PROCESS_GROUP)` on
  Windows, `start_new_session=True` on POSIX — both mean "don't tie this child process's
  lifetime to this Python script's own process," so the port-forward survives after
  `startup.py` itself exits.
- Their PIDs get written to `scripts/.portforward.pids` — a plain text file, one
  `PID label [ports]` line each — specifically so a *later, separate* invocation
  (`--stop-port-forward`, or `shutdown.py`) can find and kill exactly the processes this
  script started, without guessing.
- `_pid_alive()` is cross-platform by necessity: POSIX can send signal `0` to a PID as a
  liveness probe (raises `OSError` if it's gone); Windows has no such syscall exposed
  simply, so it shells out to `tasklist` and greps for the PID instead.

**`verify()`** is read-only reporting: service health, Prometheus scrape-target status
(parsed from its own `/api/v1/targets` JSON), Zipkin's service list, a New Relic
agent-connected/invalid-key grep over recent pod logs, and — added later — a Jenkins
container status check. Note what it does *not* do: touch anything. A `--verify` run is
always safe to re-run.

---

## `shutdown.py` — bringing it all back down

Same skeleton, steps in **reverse dependency order** (kill local processes → app services →
monitoring → infra), because scaling infra down first while app services are still trying
to serve requests just produces a pile of connection errors for no benefit.

**The one real design decision, explained (also covered live when this script was
written):** `SHUTDOWN.md` documents killing *every* `kubectl.exe`/`node.exe` process on
Windows via `taskkill`. `startup.py` already has a different, *surgical* mechanism — it
only kills PIDs it personally recorded. `shutdown.py`'s `step_kill_local_processes()`
deliberately uses the **blanket** approach instead, matching the doc rather than reusing
the surgical one, because `SHUTDOWN.md` treats the blanket kill as the primary, documented
method for a single-project dev machine. It does clean up `startup.py`'s now-stale PID
file afterward, so the two scripts don't disagree about what's still alive. One subtlety:
the doc's Windows command uses `taskkill //IM kubectl.exe //F` (double slash) — that's a
Git-Bash-prompt-specific escaping trick (MSYS rewrites a leading `/` into a path unless
doubled); calling `taskkill.exe` directly via `subprocess.run([...])`, with no shell in
between, means MSYS never sees the argument, so the script correctly uses plain single
slashes (`/IM`, `/F`) instead.

**`step_app_services()`** deletes each Deployment's HPA **before** scaling to 0 — this
order is the whole point of the step. A CPU-based HPA with `minReplicas=2` reconciles every
~15s; scale to 0 while it still exists and it just scales back up underneath you (a real
incident this project hit once). `--ignore-not-found=true` on the delete makes re-running
the script safe even if the HPAs are already gone.

**`verify()`'s pod check** uses
`kubectl get pods --field-selector=status.phase=Running`, not a plain `get pods` — so
`Completed` one-shot Jobs (`couchbase-init`, `minio-init`) don't show up as false "still
running" positives. `_running_pods()` exists as its own small function specifically so this
filter is applied once, consistently, rather than re-parsed inline three times.

---

## `capture_diagnostics.py` — live JVM diagnostics without a restart

A different problem domain from the other two: not cluster lifecycle, but pulling a JFR
recording, a heap dump, and/or thread dumps out of an already-running pod's JVM — without
restarting it, and despite the service images being JRE-only (no `jcmd`/`jmap`/`jstack`
baked in — see [heapdump-jfr.md](heapdump-jfr.md) for why and the fully-manual version of
this technique).

**The core mechanic: `kubectl debug --target <container> --profile=sysadmin`** attaches a
throwaway container (a real JDK image, `eclipse-temurin:17-jdk-jammy`) to the *pod*, sharing
the target container's **process namespace** — so from inside that ephemeral container,
`jcmd -l` can actually see the app's JVM process, even though the app container itself has
no JDK tools. `--profile=sysadmin` is what grants the `SYS_PTRACE` capability the JVM
Attach API needs to reach into another container's process at all.

**`build_remote_script()`** doesn't run a sequence of separate `kubectl exec` calls — it
builds one POSIX `sh` script as a string (`jcmd -l` to find the PID → `JFR.start` →
optionally interleaved `Thread.print` dumps on a sleep loop → `sleep` out the rest of the
window → `JFR.stop` → `GC.heap_dump`) and hands the whole thing to
`kubectl debug ... -- bash -c "<script>"` as one attached session. Building it as one script
keeps the *timing* (sleeps between thread dumps, the overall recording window) entirely
inside a single remote shell process, rather than trying to orchestrate sleeps from the
Python side across several separate `kubectl exec` round-trips.

**The non-obvious part: where the output files actually land.** `jcmd` is invoked *from*
the ephemeral debug container, but `JFR.start`/`GC.heap_dump` are diagnostic commands sent
*to* the target JVM process via its Attach API — the target JVM itself writes the resulting
`.jfr`/`.hprof` file, using its own process's filesystem view, which is the **app
container's** filesystem, not the ephemeral one. That's exactly why `copy_out()`'s
`kubectl cp` specifies `-c <app_container>` at the end, not the ephemeral container's name —
copying from the wrong container here would silently find nothing.

**Smaller things worth noticing:**
- `parse_duration()` accepts `300`, `300s`, `5m`, `1h` — one regex, not four separate flags,
  because a human typing a duration shouldn't have to remember which unit maps to which
  flag name.
- `derive_app_container()` guesses the container name from the pod name's prefix
  (`toy-service-xxxxx` → `toy-service`) purely as a convenience default — always overridable
  with `--app-container`, never silently wrong (falls through to an explicit error if the
  guess doesn't match a real container in the pod).
- Calling `kubectl` directly via `subprocess.run([...])` (a list of args, no shell) — same
  reasoning as `shutdown.py`'s `taskkill` call — sidesteps Git Bash/MSYS rewriting a
  Unix-style remote path (`/tmp/capture.jfr`) into a Windows path, which is exactly the
  failure mode documented for `kubectl cp`/`kubectl exec` typed directly at a Git Bash
  prompt (`CLAUDE.md`'s 2026-09-01 Known Bugs entry).
- `--dry-run` prints the exact remote script and the `kubectl debug` command **without**
  running `preflight()` or touching the cluster at all — useful for reading exactly what
  will execute before committing to a multi-minute blocking capture window.
- Bare `python capture_diagnostics.py` (no args) prints full `--help` and exits `0`, instead
  of argparse's default terse "the following arguments are required: pod" on stderr with
  exit code `2` — a small UX choice: the first thing someone reaches for when they don't
  remember a script's flags is running it with none.
- **The one known leak, documented rather than hidden:** a Kubernetes ephemeral container
  can't be *removed* from a pod's spec short of recreating the pod — every run leaves one
  behind (`jfrcap-<timestamp>`). Harmless (it uses no resources once its command exits), and
  the docstring says so up front rather than surprising someone who later runs
  `kubectl describe pod` and finds extra containers listed.

---

## What to notice across all three

- **Fail fast, before anything slow or destructive.** Every script has an explicit
  preflight/guard step (fresh-cluster check, pod-not-found check, `kubectl` on `PATH` at
  all) that runs *before* any real work starts.
- **Idempotency where it's cheap.** `--ignore-not-found=true`, checking for an existing PID
  file before starting new port-forwards, guessing-with-override rather than guessing-only —
  small touches that make "run it again if unsure" a safe default.
- **Windows and POSIX branches are explicit and local**, not hidden behind an abstraction —
  each platform-specific block sits right next to a comment explaining *why* it differs, so
  the difference is visible to the next reader instead of buried in a helper file.
- **Print statements as the logging strategy.** No logging framework, no log levels — just
  `print()` at each real step, because these are short-lived, interactively-run scripts
  where a scrolling terminal *is* the UI, not services with a log aggregator behind them.
