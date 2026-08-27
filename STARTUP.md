# ToyRental Platform — Startup Runbook

How to bring the whole stack back up from a stopped state (everything scaled to 0,
port-forwards and frontend dev server killed — see the shutdown this mirrors). Data is
safe across a stop/start cycle: Postgres, Couchbase, MinIO, and Kafka all use real
PersistentVolumeClaims, not ephemeral storage, so nothing here recreates or seeds data.

Run everything from the repo root (`/Users/hardikpatil/Documents/toy-rent`) unless noted.

**Order matters.** This node has repeatedly hit CPU-throttling restart loops when too much
starts at once (documented in `CLAUDE.md`'s Known Bugs table). Bring infra up first and let
it settle before starting the app services — don't scale everything up in one shot.

---

## 0. Prerequisite

Docker Desktop must be running with its Kubernetes cluster enabled, and `kubectl` must be
pointed at the `desktop-control-plane` context:
```bash
kubectl config current-context
# should print: desktop-control-plane
```

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
join):
```bash
kubectl scale deployment -n toy-rental api-gateway booking-service toy-service --replicas=1
kubectl get pods -n toy-rental -w
```

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
```
(api-gateway is not port-forwarded — its Keycloak JWT validation was never wired up, so the
frontend and all testing bypass it entirely and talk to toy-service/booking-service directly.)

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

---

## Dynatrace Operator (one-time setup)

Not part of the regular stop/start cycle — the operator Deployment isn't scaled to 0 by
the shutdown flow this runbook mirrors, so this only needs doing once per cluster, not on
every startup.

```bash
# Pin to a specific released version — check
# https://github.com/Dynatrace/dynatrace-operator/releases for the current one and
# update both this command and dynakube.yaml's apiVersion to match:
kubectl apply -f https://github.com/Dynatrace/dynatrace-operator/releases/download/vX.Y.Z/kubernetes.yaml
kubectl -n dynatrace wait pod --for=condition=ready --selector=app.kubernetes.io/name=dynatrace-operator --timeout=300s

kubectl apply -f k8s/namespace.yaml
kubectl apply -f k8s/infra/dynatrace/
```

`k8s/infra/dynatrace/secret.yaml` ships with placeholder tokens — replace `apiToken`/
`dataIngestToken` with real values from your Dynatrace tenant, and `dynakube.yaml`'s
`spec.apiUrl` with your real environment URL, before OneAgent data will actually reach a
tenant. Until then, `kubectl get dynakube -n dynatrace` reports a connectivity/auth error
in status — expected, and it doesn't block pod injection itself. See `CLAUDE.md`'s
Kubernetes section for what's monitored (toy-rental namespace, applicationMonitoring
mode only).

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
