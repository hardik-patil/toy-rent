# Performance Analysis Report — one replica loaded, the other idle

**Status:** root cause identified and explained; the one application-side contributor
fixed (Feign connection TTL, shipped in `booking-service:1.0.12`). The rest is
**test-methodology and cluster setup**, not application code — captured here as actions
for the next run.
**Bottleneck ID:** #3 in `learning/bottleneck-faced-resolved.md`.

---

## 1. Executive summary

During the 60-vuser mixed test, one `toy-service` pod carried nearly all the traffic while
its replica sat idle; same for `booking-service`. This is **not an application bug and not
autoscaling** — it is how Layer-4 load balancing interacts with long-lived HTTP
connections:

> **kube-proxy (iptables) picks a backend pod once per TCP connection, at connect time.**
> Anything that holds connections open — JMeter keep-alive, a pooled HTTP client,
> `kubectl port-forward` — keeps sending every request down the connection it already has,
> i.e. to the same pod, for the life of that connection.

Three separate connection-pinning layers were in play:

| layer | why it pinned | fix |
|---|---|---|
| `kubectl port-forward` (JMeter → service) | binds to **one** pod for the forward's whole lifetime | don't load-test through port-forward — NodePort / Ingress, or run JMeter in-cluster |
| JMeter HTTP keep-alive | 60 persistent connections, ~0 re-connects mid-test (JTL `Connect` p95 = 0) | disable keep-alive, or set a connection TTL / reset per iteration |
| booking-service → toy-service Feign | default `HttpURLConnection` keep-alive cache, and — once pooling was added for bottleneck #2 — a pooled keep-alive connection with no TTL | **fixed:** `spring.cloud.openfeign.httpclient.time-to-live: 30s` recycles connections so they re-balance |

Autoscaling can't paper over it: the HPAs show `cpu: <unknown>` because there is **no
`metrics-server`** in this cluster, so they're frozen at `minReplicas: 2` and wouldn't
redistribute load even if they scaled.

---

## 2. Evidence

### From the JTL
`Connect` time p95 = **0 ms** for `GET /api/v1/toys` and the other high-volume samplers
after the first few seconds — i.e. no new TCP connections are being made mid-test. Every
request rides a connection opened at ramp-up. With 60 such connections and iptables'
`statistic mode random` per-connection, a skew is expected and, with few connections,
can be extreme.

### From the cluster (measured during #1 validation)
A 60-concurrent login probe through `kubectl port-forward svc/booking-service`:

```
booking-service pod A : 426 logins
booking-service pod B :   0 logins
```

All 60 "concurrent" connections landed on **one** pod — because the port-forward itself
terminates on a single pod and never moves, regardless of the Service in front of it.

### The HPA
```
$ kubectl get hpa -n toy-rental
NAME              REFERENCE                    TARGETS              MINPODS  MAXPODS  REPLICAS
booking-service   Deployment/booking-service   cpu: <unknown>/60%   2        8        2
toy-service       Deployment/toy-service       cpu: <unknown>/60%   2        8        2
```
`<unknown>` = the metrics API isn't answering = `metrics-server` isn't installed
(confirmed: not in `kube-system`; it is not a default component of Docker Desktop's
Kubernetes).

---

## 3. Root cause

> A Kubernetes `Service` (ClusterIP) is L4: kube-proxy load-balances **connections**, not
> requests. Every connection-holding layer between JMeter and the pod — the port-forward,
> JMeter's keep-alive pool, the internal Feign client — therefore pins a stream of
> requests to whichever pod its connection first hit. With a small number of long-lived
> connections the distribution is lumpy to the point of one pod doing ~everything. The
> HPAs, which might at least have added pods, are inert because no `metrics-server` is
> feeding them CPU numbers.

Nothing here is a defect in `toy-service` / `booking-service` request handling.

---

## 4. Fix — application side (done)

Shipped in `booking-service:1.0.12` (alongside the bottleneck-#2 changes):

```yaml
spring:
  cloud:
    openfeign:
      httpclient:
        time-to-live: 30
        time-to-live-unit: seconds
```

When bottleneck #2 added a pooled HTTP client (`feign-hc5`), that by itself would have
made internal pinning *worse* — a pooled keep-alive connection is even stickier than
`HttpURLConnection`'s. The 30 s TTL forces each pooled connection to be torn down and
re-established periodically, and each re-connect is a fresh kube-proxy dice roll, so
booking-service's calls spread across both `toy-service` replicas over time instead of
nailing one.

There is no clean *per-request* internal balancing available without adding
infrastructure: the Feign client targets a fixed URL (`${feign.toy-service.url}`), there's
no service-discovery/`spring-cloud-loadbalancer` in the stack, and adding one is out of
scope for a tuning pass. The TTL is the pragmatic 80 %.

---

## 5. Fix — test methodology and cluster (for the next run, not code)

1. **Stop load-testing through `kubectl port-forward`.** It is a single-pod tunnel by
   design. Options, best first:
   - expose `toy-service` / `booking-service` as `NodePort` (or via the existing
     `k8s/ingress.yaml`) and point JMeter at that;
   - run the JMeter injector as a `Job` **inside** the cluster, hitting the Service DNS
     name directly — then kube-proxy actually balances across replicas.
2. **JMeter connection handling.** If keep-alive stays on, add
   `httpclient4.idletimeout` / "reset connections on each thread group iteration" so the
   pool churns and re-hashes across pods between iterations. Or turn keep-alive off for a
   worst-case-fanout run.
3. **Install `metrics-server`** so the HPAs work:
   ```bash
   kubectl apply -f https://github.com/kubernetes-sigs/metrics-server/releases/latest/download/components.yaml
   kubectl patch deployment metrics-server -n kube-system --type=json \
     -p='[{"op":"add","path":"/spec/template/spec/containers/0/args/-","value":"--kubelet-insecure-tls"}]'
   ```
   `--kubelet-insecure-tls` is required — the Docker Desktop node's kubelet serves a
   self-signed cert. After this, `kubectl top pod` works and the CPU-based HPAs can
   actually add replicas under load. (This was attempted in-session but blocked by a
   safety guard on `kubectl apply -f <remote URL>`; run it manually.)
4. **Proper per-request balancing** (bigger change): route the external path through an
   L7 proxy — the bypassed `api-gateway` (Spring Cloud Gateway) or an Ingress controller —
   which load-balances HTTP requests, not TCP connections.

---

## 6. Expected effect

With load genuinely spread across both replicas, per-pod concurrency roughly halves. For
bottleneck #1 that alone would take the isolated 60-way login p95 from ~3.3 s toward
~1.6 s (60 un-parallelisable BCrypt hashes → ~30 per pod). For #2 it halves the Feign and
lock contention per `toy-service` / `booking-service` JVM. So #3 is a **multiplier on the
#1 and #2 fixes**, not an independent latency source — which is why the app-side change
here is small and most of the work is in how the test is driven.

---

## 7. Reproduce / verify

```bash
# show the pinning: hit the service via port-forward, then check per-pod counts
python loadtest/login_probe.py --concurrency 60 --rounds 1
for p in $(kubectl get pods -n toy-rental -l app.kubernetes.io/name=booking-service -o name); do
  echo -n "$p  "; kubectl exec -n toy-rental $p -c booking-service -- \
    sh -c 'wget -qO- localhost:8082/actuator/prometheus' | grep 'customers/login",} '
done
# after the fix + an in-cluster injector, the two counts should be within ~10-20% of each other
```
