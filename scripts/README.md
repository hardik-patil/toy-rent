# scripts/

Operational helper scripts for the ToyRental platform. Not part of the application build —
these talk to a live cluster via `kubectl`.

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
