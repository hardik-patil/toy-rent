# scripts/

Operational helper scripts for the ToyRental platform. Not part of the application build —
these talk to a live cluster via `kubectl`.

---

## startup.py

Brings the whole stack back up from a **stopped** cluster — the automated version of
`STARTUP.md` steps 1–6. Stopped means the `toy-rental` / `infra` / `monitoring` namespaces
and their PVCs still exist and everything is just scaled to 0 (the state `SHUTDOWN.md`
leaves things in).

### What it does, in order

1. **Infra** — `kubectl scale` the `infra` StatefulSets (`postgres`, `kafka`, `minio`,
   `couchbase`) and small Deployments (`keycloak`, `redis`, `wiremock`, `postgres-exporter`,
   `kafka-lag-exporter`) back to 1, then waits for each via `kubectl rollout status`.
   If infra doesn't come fully Ready it stops here rather than starting app services against
   a half-up backend.
2. **Monitoring** — `kubectl scale` `grafana` / `prometheus` / `zipkin` back to 1.
3. **App services** — `kubectl apply` `toy-service`, then `booking-service`, then
   `api-gateway`, **one at a time**, waiting for each rollout to finish and printing node
   worker CPU (`docker stats`, since `metrics-server` isn't installed) in between. If CPU is
   above ~400% it pauses 45s before the next one. `apply` rather than `scale` so each
   Deployment's HPA — which `SHUTDOWN.md` deletes — is recreated from the same manifest.
4. *(`--port-forward`)* starts the `STARTUP.md` step-4 port-forwards as **detached**
   background processes, recording PIDs in `scripts/.portforward.pids` and per-forward logs
   under `scripts/.portforward-logs/` (both git-ignored). Without the flag it just prints the
   commands for you to run yourself.
5. Prints the frontend start command (`cd frontend && npm run dev`) — a dev server isn't
   this script's to own.
6. *(`--verify`)* health-checks both services, lists Prometheus scrape-target health, lists
   Zipkin services, and greps each app's New Relic agent log for `connected to collector` /
   `Invalid license key`. The HTTP checks need the port-forwards up (via `--port-forward` or
   your own).

### Prerequisites

- `kubectl` on PATH, pointed at the cluster (`kubectl config current-context` → `docker-desktop`).
- A **stopped**, not fresh, cluster. If any of the three namespaces is missing the script
  prints the fresh-cluster steps from `STARTUP.md` and exits — do those by hand first.
- Python 3.7+, standard library only.

### How to run

From the repo root:

```bash
python scripts/startup.py                          # steps 1–3, then print next steps
python scripts/startup.py --port-forward --verify  # + start forwards + full verification
python scripts/startup.py --skip-infra             # infra already Running
python scripts/startup.py --infra-only             # infra (+ monitoring), stop before apps
python scripts/startup.py --stop-port-forward      # kill forwards this script started
```

### Options

| Flag | Effect |
|---|---|
| `--skip-infra` | Skip step 1 (infra already up). |
| `--skip-monitoring` | Skip step 2. |
| `--infra-only` | Do steps 1–2 and stop before app services. |
| `--disable-new-relic` | Set `JDK_JAVA_OPTIONS=''` on the 3 app deployments after apply. Use **only** if `newrelic-secret` still holds the placeholder key (otherwise the agent's uncapped reconnect loop is a real CPU cost — see `enable_new_relic.py` below). With a real key in the Secret, leave this off; the script prints which case it detected. |
| `--port-forward` | Start the step-4 port-forwards detached after services are up. |
| `--with-db` | Also forward `postgres:5432` and `couchbase:8091/8093/11210` (implies `--port-forward`). |
| `--stop-port-forward` | Kill port-forwards recorded in `scripts/.portforward.pids`, then exit. Does nothing to forwards you started by hand (see `SHUTDOWN.md` step 0 for those). |
| `--verify` | Run the step-6 checks. |
| `--timeout N` | Per-resource readiness timeout in seconds (default 600 — Couchbase is slow on this node). |

### Notes / limitations

- **Not for a fresh cluster** — no image builds, namespace/network-policy apply, or Node.js
  install. That's a one-time manual run-through of `STARTUP.md`'s fresh-cluster section.
- Port-forwards die silently whenever their backing pod is recreated (any later
  `kubectl apply` / `rollout restart` / HPA scale-up). If a previously-working local
  connection drops, `--stop-port-forward` then `--skip-infra --skip-monitoring --port-forward`
  re-establishes the set. This is `kubectl port-forward` behaviour, not a script bug.
- On Windows the detached forwards are real background `kubectl.exe` processes; the PID file
  is how `--stop-port-forward` (via `taskkill`) finds them. Deleting the PID file by hand
  orphans them — `taskkill //IM kubectl.exe //F` then clears everything (`SHUTDOWN.md` step 0).

---

## enable_new_relic.py

Patches a real New Relic license key into the cluster and re-enables the New Relic Java
agent on `toy-service`, `booking-service`, and `api-gateway`.

### Why this exists

During the 2026-09-01 cluster bring-up, the agent was disabled live (via
`JDK_JAVA_OPTIONS=""` on all three deployments) because the placeholder license key
shipped in `k8s/services/newrelic-secret.yaml` doesn't fail quietly — on every
`LicenseException` the agent retries the collector connection in a tight loop with no
backoff. Across 3+ JVMs simultaneously this was measured at 350–550% sustained node CPU,
and was the dominant cause of a liveness-probe crash-loop, not just log noise.

See `STARTUP.md`'s New Relic section and `CLAUDE.md`'s Known Bugs table (2026-09-01 entry)
for the full story.

This script does **not** touch the checked-in manifests — it patches the live cluster's
`newrelic-secret` Secret and clears the live env-var override, letting each deployment
fall back to the `JDK_JAVA_OPTIONS` already baked into `k8s/services/*/*.yaml`
(`-javaagent:/app/newrelic.jar`).

### Prerequisites

- `kubectl` installed and pointed at the right context (`kubectl config current-context`
  should show your cluster — `docker-desktop` for local dev).
- The cluster already up (see `STARTUP.md`) with the `toy-rental` namespace and its three
  deployments existing.
- Python 3.7+ (uses only the standard library — no `pip install` needed).
- A **real** New Relic license key:
  1. Log into your New Relic account at [one.newrelic.com](https://one.newrelic.com).
  2. Go to your user menu → **API keys**.
  3. Copy the **License key** (not an API/ingest key — those are different key types;
     the Java agent specifically wants the license key).

### How to run

From the repo root:

```bash
python scripts/enable_new_relic.py <YOUR_REAL_LICENSE_KEY>
```

On Windows, if `python` isn't on PATH but `py` is:

```bash
py scripts/enable_new_relic.py <YOUR_REAL_LICENSE_KEY>
```

### What it does, in order

1. Validates the key isn't empty and isn't still the placeholder value.
2. Patches the `newrelic-secret` Secret in the `toy-rental` namespace with your real key.
3. For each of `toy-service`, `booking-service`, `api-gateway`:
   - Re-`kubectl apply`s that deployment's real manifest (`k8s/services/<name>/<name>.yaml`)
     to restore `JDK_JAVA_OPTIONS` to its real `-javaagent:/app/newrelic.jar` value (this
     triggers a rolling restart). **Not** `kubectl set env deployment/x JDK_JAVA_OPTIONS-` —
     that trailing-dash form doesn't "fall back" to the manifest's value, it just deletes the
     env var outright, since plain Kubernetes has no override/base layer for `set env` to
     revert to. Learned this the hard way once already (see `CLAUDE.md`'s Known Bugs table,
     2026-09-01 entry) — the agent silently never loaded after using that approach.
   - Waits for the rollout to finish before moving to the next deployment, so the three
     don't cold-start simultaneously (this cluster has a documented history of CPU
     contention when too many JVMs start at once — see `STARTUP.md`). A timeout here is a
     warning, not fatal — this node has occasionally taken longer than 400s on a pod
     termination even when the rollout succeeds; the script prints what to check manually.
4. Tails each deployment's logs looking for `connected to collector` or
   `Invalid license key` and prints what it finds.

Because it re-applies the actual manifest, run it from the repo root — it looks for
`k8s/services/<name>/<name>.yaml` relative to the current directory.

### Options

| Flag | Effect |
|---|---|
| `--namespace <ns>` | Target a different namespace (default: `toy-rental`) |
| `--skip-rollout-wait` | Don't wait for each rollout before moving to the next (faster, but risks the same concurrent-cold-start CPU contention this project has hit before) |
| `--skip-verify` | Skip the post-patch log check |

### Expected output

For each service you should eventually see a log line like:

```
Agent <version> ... connected to collector.newrelic.com:443
```

If you instead see `Invalid license key`, the key is wrong — double check you copied the
**license key**, not an API key, and that you copied it from the correct New Relic account
region.

### Troubleshooting

- **Still seeing `Invalid license key` after patching**: New Relic accounts are
  region-specific (US vs EU). If your account is EU-based, the agent also needs
  `NEW_RELIC_HOST=collector.eu01.nr-data.net` set — this isn't currently wired into the
  manifests since the project assumes a US account. Ask if you need this added.
- **`kubectl: command not found`**: install `kubectl` and make sure it's on PATH.
- **Rollout hangs / times out**: check `kubectl get pods -n toy-rental` and
  `kubectl describe pod ...` — this usually means the same node-resource pressure
  documented in `STARTUP.md`'s Troubleshooting section, not something this script did
  wrong. Consider `--skip-rollout-wait` and applying the deployments one at a time
  yourself with pauses in between.
- **Want to disable the agent again later**: re-run whatever originally disabled it —
  `kubectl set env deployment/<name> -n toy-rental JDK_JAVA_OPTIONS=""` for each of the
  three deployments.
