# ToyRental Platform — Startup Runbook

How to bring the whole stack back up from a stopped state (everything scaled to 0,
port-forwards and frontend dev server killed — see `SHUTDOWN.md`, which this mirrors).
Data is safe across a stop/start cycle: Postgres, Couchbase, MinIO, and Kafka all use real
PersistentVolumeClaims, not ephemeral storage, so nothing here recreates or seeds data.

Run everything from the repo root (`/Users/hardikpatil/Documents/toy-rent`) unless noted.

**Order matters.** This node has repeatedly hit CPU-throttling restart loops when too much
starts at once (documented in `CLAUDE.md`'s Known Bugs table). Bring infra up first and let
it settle before starting the app services — don't scale everything up in one shot.

**This whole doc assumes a *stopped* cluster** (namespaces and PVCs already exist, everything
just scaled to 0). If `kubectl get ns` doesn't show `toy-rental`/`infra`/`monitoring` at all,
you have a *fresh* cluster instead (first run, or Docker Desktop's Kubernetes was reset) —
skip to **"Fresh cluster? Start here instead"** below before anything else, or the steps below
will just fail against resources that don't exist yet.

---

## 0. Prerequisite

Docker Desktop must be running with its Kubernetes cluster enabled, and `kubectl` must be
pointed at the right context:
```bash
kubectl config current-context
# should print: docker-desktop  (the node itself is named desktop-control-plane / desktop-worker
# — that's the node name, not the context name; don't confuse the two)
```

Check which situation you're in before going further:
```bash
kubectl get ns
```
If `toy-rental`, `infra`, and `monitoring` are all listed → this is a stopped cluster, continue
with step 1 below. If none of them are listed → **jump to "Fresh cluster? Start here instead"**.

---

## Fresh cluster? Start here instead

Everything in this section was real friction the first time this cluster was built from
scratch (2026-09-01/02) — doing these up front turns a multi-hour debugging session into a
straight run-through. Once done, the regular stop/start cycle (steps 1-6 below, `SHUTDOWN.md`)
takes over as normal — this section is one-time-per-fresh-cluster, not part of the regular
cycle.

**1. Namespaces and network policy:**
```bash
kubectl apply -f k8s/namespace.yaml
kubectl apply -f k8s/network-policy.yaml
```

**2. Build the three app images.** They're never pushed to a registry — each Dockerfile
expects a pre-built jar plus `newrelic.jar` (gitignored, not in the repo) already sitting in
the build context. A fresh checkout has neither. `JAVA_HOME` also isn't set by default in this
shell — needed for `./mvnw`.
```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-17.0.20.8-hotspot"   # adjust if different

# One-time: get newrelic.jar into all three service dirs
curl -sSL -o /tmp/newrelic-java.zip https://download.newrelic.com/newrelic/java-agent/newrelic-agent/current/newrelic-java.zip
cd /tmp && unzip -o -q newrelic-java.zip
cd /c/Users/USER/Documents/toy-rent
cp /tmp/newrelic/newrelic.jar toy-service/newrelic.jar
cp /tmp/newrelic/newrelic.jar booking-service/newrelic.jar
cp /tmp/newrelic/newrelic.jar api-gateway/newrelic.jar

for svc in toy-service booking-service api-gateway; do
  (cd $svc && ../$svc/mvnw package -DskipTests -q)
done

docker build -t toyrental/toy-service:1.0.10 toy-service
docker build -t toyrental/booking-service:1.0.10 booking-service
docker build -t toyrental/api-gateway:1.0.7 api-gateway
```
Docker Desktop's Kubernetes shares the host's Docker image store, so these are immediately
visible to the cluster — no `kind load docker-image` or registry push needed (that would only
apply to a standalone `kind` cluster, not this one).

**3. Follow steps 1-3 below (infra, monitoring, app services) using `kubectl apply -f`, not
`kubectl scale`** — nothing exists yet to scale. Apply `k8s/infra/*/*.yaml` one file at a time
(each is fully self-contained: Secret/ConfigMap/StatefulSet-or-Deployment/Service/Job all in
one manifest), then `k8s/infra/prometheus/prometheus.yaml` +
`k8s/infra/grafana/grafana.yaml` + `k8s/infra/zipkin/zipkin.yaml`, then the three
`k8s/services/*/*.yaml` **one at a time**, waiting for each to reach `1/1 Ready` before the
next — see step 3's CPU-contention warning below, it applies even more here since nothing has
had a chance to settle yet.

**4. New Relic will fail to connect** (`k8s/services/newrelic-secret.yaml` ships a placeholder
key) **and that's expected** — but don't ignore it either. See "New Relic" under step 6 below:
an invalid key isn't just a quiet failure, the agent retries in a tight uncapped loop that's a
real, significant CPU cost across every JVM simultaneously, and was the dominant cause of a
liveness-probe crash-loop the first time this happened. Either get a real key before bringing
services up (`scripts/enable_new_relic.py`), or expect to need `kubectl set env
deployment/<name> -n toy-rental JDK_JAVA_OPTIONS=""` on each of the 3 app deployments if you
see multiple services crash-looping together under CPU pressure.

**5. Node.js.** If `node -v` / `npm -v` fail, Node.js isn't installed — `winget install
OpenJS.NodeJS.LTS` (needs a real UAC prompt approved interactively, not scriptable). If it's
freshly installed and still not found in an already-open terminal/session, that session's PATH
is stale — find it directly (`C:\Program Files\nodejs\node.exe` by default) rather than waiting
for PATH to catch up.

Once through all of the above, the cluster is in the same state the rest of this doc assumes —
continue with step 4 (port-forwards) onward as normal.

---

## 1. Scale infra back up

```bash
kubectl scale statefulset -n infra couchbase kafka minio postgres --replicas=1
kubectl scale deployment  -n infra keycloak redis wiremock postgres-exporter kafka-lag-exporter --replicas=1
```

`postgres-exporter` and `kafka-lag-exporter` are Prometheus scrape targets for
Postgres/Kafka server-side metrics (added alongside Couchbase's native `/metrics`, which
needs no separate pod — see `k8s/infra/prometheus/prometheus.yaml`'s `couchbase` job).
Scaling them up before Postgres/Kafka are ready is harmless — both retry until their
target is reachable (`kafka-lag-exporter` re-polls every 30s).

Wait for all of these to reach `1/1 Running` before moving on — Couchbase in particular can
take several minutes on this node. Poll with:
```bash
kubectl get pods -n infra -w
```
(Ctrl+C once everything shows `1/1 Running`.)

---

## 2. Scale monitoring back up

Independent of the app services — safe to do anytime, but convenient to do here:
```bash
kubectl scale deployment -n monitoring grafana prometheus zipkin --replicas=1
kubectl get pods -n monitoring -w
```

---

## 3. Scale the app services back up

Only after infra is fully `1/1 Running` — these depend on Postgres/Couchbase/Kafka being
reachable at startup (Flyway migrations, Couchbase bucket connections, Kafka consumer group
join).

**Use `kubectl apply`, not `kubectl scale`.** `SHUTDOWN.md` deletes these three services'
HorizontalPodAutoscalers as part of scaling down (a plain `--replicas=0` can't hold
against a live CPU-based HPA with `minReplicas: 2` — it just gets scaled back up).
`kubectl scale --replicas=1` here would bring the pods back but leave autoscaling gone
until you separately noticed and fixed it. Re-applying each manifest restores the
Deployment *and* recreates its HPA in one step:
```bash
kubectl apply -f k8s/services/toy-service/toy-service.yaml
kubectl apply -f k8s/services/booking-service/booking-service.yaml
kubectl apply -f k8s/services/api-gateway/api-gateway.yaml
kubectl get pods -n toy-rental -w
```
This also means each apply already includes 2 replicas (the manifests' own `replicas: 2`,
not 1) — no separate scale-up step needed afterward.

Apply one at a time and let each reach `1/1 Ready` before the next, watching
`kubectl top node` between them — three concurrent JVM cold starts have spiked this
node to 90%+ CPU before and triggered a real incident (Postgres's liveness probe timing
out under the load, getting killed, and every app losing its DB connection at once —
see `CLAUDE.md`'s Known Bugs table). Don't run all three applies back to back without
checking in between, and never combine this with a `kubectl rollout restart --all` on
top of an already-in-progress rollout.

Startup can take 1–3 minutes per pod under load on this node — the liveness/readiness probe
delays were already tuned (150s/120s) specifically for this, so don't intervene unless a pod
is still not `1/1 Ready` after ~5 minutes (see Troubleshooting below).

---

## 4. Re-establish port-forwards

Run each of these in the background (separate terminal tabs, or `&` + `disown`):
```bash
kubectl port-forward -n toy-rental svc/toy-service 8081:8081 &
kubectl port-forward -n toy-rental svc/booking-service 8082:8082 &
kubectl port-forward -n infra svc/minio 9000:9000 &
kubectl port-forward -n monitoring svc/prometheus 9090:9090 &
kubectl port-forward -n monitoring svc/grafana 3000:3000 &
kubectl port-forward -n monitoring svc/zipkin 9411:9411 &
```
(api-gateway is not port-forwarded — its Keycloak JWT validation was never wired up, so the
frontend and all testing bypass it entirely and talk to toy-service/booking-service directly.)

Two more you'll likely want, not in the original list but came up constantly during
performance-testing/DB work — not started by default, add if you need them:
```bash
kubectl port-forward -n infra svc/postgres 5432:5432 &
kubectl port-forward -n infra svc/couchbase 8091:8091 8093:8093 11210:11210 &
```

**Every one of these dies silently the moment its backing pod is recreated** — any
`kubectl apply`/`kubectl set env`/`kubectl rollout restart`/HPA scale-up on that Deployment or
StatefulSet kills the forward, even though it looks unrelated. This bit us repeatedly (Postgres,
Couchbase, toy-service, booking-service, Prometheus — all hit at least once in one session) and
each time looked like a fresh, unrelated failure (DBeaver hangs, curl connection refused, "no
data in Prometheus") until traced back to the same cause. If something that was just working
suddenly isn't, re-run its `kubectl port-forward` command before looking any further — check
with `netstat -ano | grep 127.0.0.1:<port>` (should show `LISTENING`) or just try reconnecting.

---

## 5. Start the frontend

```bash
cd frontend
npm run dev
```
Vite will print the actual local URL (usually `http://localhost:5173`, or the next free port
if that one's taken — check the terminal output).

---

## 6. Verify everything is actually up

```bash
curl -s -o /dev/null -w "toy-service: %{http_code}\n" http://localhost:8081/actuator/health
curl -s -o /dev/null -w "booking-service: %{http_code}\n" http://localhost:8082/actuator/health
```
Both should print `200`. Then open the frontend URL from step 5 in a browser and confirm the
catalogue loads with real toy data.

Admin panel: `http://localhost:5173/admin/login` (adjust port if Vite picked a different one) —
username `admin`, password `admin123`.

Grafana: `http://localhost:3000` — username `admin`, password `admin`.
Prometheus: `http://localhost:9090`.

Confirm all 6 scrape targets are up (api-gateway, toy-service, booking-service, couchbase,
postgres-exporter, kafka-lag-exporter):
```bash
curl -s "http://localhost:9090/api/v1/targets" | python3 -c "
import json,sys
d = json.load(sys.stdin)
for t in d['data']['activeTargets']:
    print(t['labels'].get('job'), '-', t['health'])
"
```
If `couchbase`/`postgres-exporter`/`kafka-lag-exporter` show `down` with a connection
timeout (not a 401/config error), it's almost always the `monitoring` → `infra`
NetworkPolicy exception in `k8s/network-policy.yaml` not being applied — reapply it with
`kubectl apply -f k8s/network-policy.yaml`.

Confirm Zipkin has all three services (distributed tracing, independent of New Relic —
see below):
```bash
curl -s http://localhost:9411/api/v2/services
# should print: ["api-gateway","booking-service","toy-service"]
```

Confirm each app's New Relic agent actually connected (it's baked into the image +
env vars already, no separate startup step needed — this just verifies it worked):
```bash
kubectl logs -n toy-rental -l app.kubernetes.io/name=toy-service --tail=200 | grep -i "connected to collector\|Invalid license key"
```
Look for `Agent ... connected to collector.newrelic.com:443`. If you see `Invalid
license key` instead, the agent still runs (failures here are non-fatal to the app) but
won't report data — and it retries fast enough to be a real CPU cost, not just a
harmless log line, so don't ignore it. See `NEW_RELIC_LICENSE_KEY` in
`k8s/services/newrelic-secret.yaml`'s `secretKeyRef` — the real key lives only in the
live cluster Secret, never in a file in this repo; re-patch it with
`kubectl patch secret newrelic-secret -n toy-rental --type=merge -p '{"stringData":{"NEW_RELIC_LICENSE_KEY":"..."}}'`
if it needs replacing.

**Currently disabled live on the cluster (2026-09-01).** With the placeholder key still
in place, the agent doesn't just fail quietly — it hammers `collector.newrelic.com` in a
tight reconnect loop on every `LicenseException`, no backoff. Across all 3 JVMs
simultaneously this was a major, sustained CPU cost (measured 350–550% node CPU) that
was actively causing liveness-probe crash-loops during a full cluster bring-up. Disabled
live via `kubectl set env deployment/<toy-service|booking-service|api-gateway> -n
toy-rental JDK_JAVA_OPTIONS=""` on each — this is a live patch only, not committed to the
manifests (which still have `JDK_JAVA_OPTIONS=-javaagent:/app/newrelic.jar`).

Don't re-enable with the placeholder key still in place — it recreates the same
crash-loop. Once a real `NEW_RELIC_LICENSE_KEY` is available, use
`scripts/enable_new_relic.py <key>` (see `scripts/README.md`), or manually:
1. Patch the secret (command above).
2. Restore each deployment's `JDK_JAVA_OPTIONS` to the manifest's real value by
   **re-`kubectl apply`-ing the manifest** — `kubectl apply -f
   k8s/services/<toy-service|booking-service|api-gateway>/<same>.yaml` for each. **Do not**
   use `kubectl set env deployment/<name> JDK_JAVA_OPTIONS-` (trailing dash) expecting it to
   "fall back" to the manifest's value — plain Kubernetes has no such override/base layer,
   that command just deletes the env var outright, and the agent silently never loads again
   (this bit us once already: 2026-09-01's Known Bugs entry in `CLAUDE.md`).

With a valid key the agent connects once and settles into lightweight periodic
reporting — the retry-storm behavior above is specific to invalid credentials, not
normal agent overhead. At last measured steady state (infra + monitoring + both app
service replicas, agent disabled) the node was at ~100% CPU of a 600% cap and
11.4GB/23.5GB memory, so there's comfortable headroom to re-add normal (non-storming)
agent overhead once the key is real.

---

## All URLs, once everything above is up

| What | URL | Login |
|---|---|---|
| Frontend | `http://localhost:5173` (Vite may pick a different port — check its terminal output) | — |
| Admin panel | `http://localhost:5173/admin/login` | `admin` / `admin123` |
| toy-service API | `http://localhost:8081` (e.g. `/api/v1/toys`) | — |
| toy-service health | `http://localhost:8081/actuator/health` | — |
| booking-service API | `http://localhost:8082` | — |
| booking-service health | `http://localhost:8082/actuator/health` | — |
| MinIO | `http://localhost:9000` | `minioadmin` / `minioadmin` |
| Prometheus | `http://localhost:9090` | — |
| Grafana | `http://localhost:3000` | `admin` / `admin` |
| Zipkin | `http://localhost:9411/zipkin/` | — |
| New Relic APM | `https://one.newrelic.com` → APM & Services → `toy-service` / `booking-service` / `api-gateway` | your New Relic account login |

`api-gateway` has no local URL — it's intentionally not port-forwarded (see step 4's
note: its Keycloak JWT validation was never wired up, so nothing actually routes through
it in this dev setup).

---

## Dynatrace Operator (one-time setup)

Not part of the regular stop/start cycle — the operator Deployment isn't scaled to 0 by
the shutdown flow this runbook mirrors, so this only needs doing once per cluster, not on
every startup.

```bash
# Pinned to v1.10.2 (https://github.com/Dynatrace/dynatrace-operator/releases/tag/v1.10.2).
# Uses the non-CSI manifest deliberately — applicationMonitoring works without the CSI
# driver, and skipping it avoids a permanent ~390Mi DaemonSet on a node with only 3
# pods to instrument (same reasoning below for skipping the host-level OneAgent
# DaemonSet). If you ever expand to cloudNativeFullStack or want cross-pod code-module
# caching, that means switching to kubernetes-csi.yaml instead — not just a DynaKube
# field flip, since useCSIDriver was removed as a toggle in current operator versions;
# treat it as reinstalling this piece from scratch.
#
# k8s/namespace.yaml must be applied FIRST — kubernetes.yaml's Deployment/ServiceAccount/
# Role/RoleBinding objects are hardcoded to `namespace: dynatrace` and fail if it
# doesn't exist yet.
kubectl apply -f k8s/namespace.yaml

kubectl apply -f https://github.com/Dynatrace/dynatrace-operator/releases/download/v1.10.2/kubernetes.yaml
kubectl -n dynatrace wait pod --for=condition=ready --selector=app.kubernetes.io/name=dynatrace-operator --timeout=300s

kubectl apply -f k8s/infra/dynatrace/

# Restart one at a time (not a single combined command) — this node has a documented
# history of freezing under concentrated load; spreading the 3x init-container-copy +
# JVM-cold-start work avoids stacking it. Watch `kubectl top node` between each.
kubectl rollout restart deployment -n toy-rental api-gateway
kubectl rollout status deployment -n toy-rental api-gateway --timeout=180s
kubectl rollout restart deployment -n toy-rental toy-service
kubectl rollout status deployment -n toy-rental toy-service --timeout=180s
kubectl rollout restart deployment -n toy-rental booking-service
kubectl rollout status deployment -n toy-rental booking-service --timeout=180s
```

`k8s/infra/dynatrace/secret.yaml` ships with placeholder tokens — replace `apiToken`/
`dataIngestToken` with real values from your Dynatrace tenant, and `dynakube.yaml`'s
`spec.apiUrl` with your real environment URL, before OneAgent data will actually reach a
tenant. Until then, `kubectl get dynakube -n dynatrace` reports a connectivity/auth error
in status — expected, and it doesn't block pod injection itself. See `CLAUDE.md`'s
Kubernetes section for what's monitored (toy-rental namespace, applicationMonitoring
mode only, apiVersion v1beta6).

---

## Troubleshooting

**A pod stays `0/1` for more than ~5 minutes, or is restarting repeatedly:**
Check what's actually happening before assuming it's broken:
```bash
kubectl describe pod -n <namespace> <pod-name>   # check Events at the bottom
kubectl logs -n <namespace> <pod-name> --previous
```
If it was killed by its own liveness probe (`Killing` event, "failed liveness probe") under
CPU pressure, check `kubectl top node` — if memory/CPU is pegged, scale down something less
essential (e.g. `monitoring` namespace) temporarily to give the starting pod room, then scale
it back up once things settle.

**Couchbase specifically taking a very long time:** normal on this node — its real baseline
footprint is close to its configured limits. Give it time before troubleshooting further.

**Node is generally overloaded:** check `kubectl top node`. If it's pinned, this is a resource
ceiling of the local Docker Desktop VM, not an application bug — see `CLAUDE.md`'s Known Bugs
table for the full history of this. Increasing Docker Desktop's allocated CPU/memory (Docker
Desktop → Settings → Resources) is the real fix if this keeps happening.
