# ToyRental Platform — Shutdown Runbook

How to bring the whole stack down cleanly (the state `STARTUP.md` brings it back up
from). Data is safe across a stop/start cycle — Postgres, Couchbase, MinIO, and Kafka
all use real PersistentVolumeClaims, not ephemeral storage, so nothing here deletes or
resets data. This just scales everything to 0 and kills the local processes
(port-forwards, frontend dev server).

Run everything from the repo root (`/Users/hardikpatil/Documents/toy-rent`) unless noted.

**Order matters, and it's the reverse of `STARTUP.md`.** Scale down consumers before the
infra they depend on — app services first, then monitoring, then infra last — so nothing
is left retrying against a target that just disappeared mid-request.

---

## 0. Kill local processes first

These aren't Kubernetes resources — `kubectl scale` doesn't touch them.

**On Windows/Git Bash, `pkill -f` doesn't reliably reach these** — `kubectl.exe` and
`node.exe` are native Windows processes, not part of Git Bash's own MSYS process tree, so
`pkill -f "kubectl port-forward"` / `pkill -f "npm run dev"` can silently match nothing.
Use `taskkill` instead (confirmed working 2026-09-02):
```bash
taskkill //IM kubectl.exe //F
taskkill //IM node.exe //F
```
This kills *every* `kubectl.exe`/`node.exe` process, not just this project's — fine given
this is a single-project dev machine, but check `tasklist //FI "IMAGENAME eq kubectl.exe"`
/ `tasklist //FI "IMAGENAME eq node.exe"` first if you have unrelated kubectl/node work
running elsewhere you don't want killed.

On macOS/Linux, the original `pkill -f` commands work as documented:
```bash
pkill -f "kubectl port-forward"
pkill -f "npm run dev"    # or Ctrl+C in the frontend's terminal tab
```

---

## 1. Scale down the app services (toy-rental namespace)

**Important — these three have an active HorizontalPodAutoscaler apiece
(`minReplicas: 2`, CPU-based).** A plain `kubectl scale deployment --replicas=0` will
not hold — the HPA reconciles every ~15s and scales it right back up to 2, since a
CPU-resource-metric HPA can't have `minReplicas: 0`. This exact fight caused a real
resource incident once already (see `CLAUDE.md`'s Known Bugs table) — delete the HPA
first, every time:

```bash
kubectl delete hpa -n toy-rental api-gateway toy-service booking-service
kubectl scale deployment -n toy-rental api-gateway toy-service booking-service --replicas=0
```

To bring them back later with autoscaling restored, re-apply each service's manifest
(this recreates the HPA too, since it's defined in the same file):
```bash
kubectl apply -f k8s/services/toy-service/toy-service.yaml
kubectl apply -f k8s/services/booking-service/booking-service.yaml
kubectl apply -f k8s/services/api-gateway/api-gateway.yaml
```

---

## 2. Scale down monitoring

No HPAs here — a plain scale-to-0 holds fine.

```bash
kubectl scale deployment -n monitoring grafana prometheus zipkin --replicas=0
```

---

## 3. Scale down infra

```bash
kubectl scale statefulset -n infra couchbase kafka minio postgres --replicas=0
kubectl scale deployment  -n infra keycloak redis wiremock postgres-exporter kafka-lag-exporter --replicas=0
```

---

## 4. Verify everything is actually down

```bash
kubectl get pods -n toy-rental
kubectl get pods -n monitoring
kubectl get pods -n infra
```
All three should show no running pods (Jobs like `couchbase-init`/`minio-init` will
still show as `Completed` — that's fine, they're one-shot Jobs, not scaled resources).

```bash
kubectl top node
```
CPU/memory should drop close to idle within a minute or two of the last scale-down.
**This errors with `Metrics API not available`** on this cluster — `metrics-server` has
never been installed here (see `CLAUDE.md`'s Known Bugs table). Use this instead, which
works off the underlying Docker container directly and needs no cluster-side component:
```bash
docker stats --no-stream --format "table {{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}"
```
(Node-wide only, not per-pod — fine for confirming things actually went idle.)

---

## Troubleshooting

**A deployment's pod count won't go to 0 / keeps coming back:** check for an HPA first
(`kubectl get hpa -n <namespace>`) — this is almost always a forgotten HPA fighting the
scale-down, exactly like Step 1's warning above. Delete the HPA, then scale.

**Not part of this cycle:** the Dynatrace Operator (`dynatrace` namespace) isn't scaled
down here, same as `STARTUP.md` never scales it up — it's a one-time install, left
running (or not, if you never completed that setup) independent of this stop/start flow.
