# Port Forwarding

**General concept:** port forwarding takes traffic hitting one address:port and
redirects it to a different address:port — a tunnel between two endpoints that
otherwise couldn't talk directly. Think of it like a phone forwarding service: you
call one number, but it's secretly ringing through to a totally different phone.

## In the `kubectl port-forward` case specifically

Your machine and the pods inside the Kubernetes cluster are on separate networks.
Pods get private IPs (something like `10.244.x.x`) that only exist inside the
cluster's internal network — your laptop can't route to them directly, no matter
what port you try.

```
Your machine                      Kubernetes cluster
┌─────────────┐                   ┌──────────────────────┐
│ DBeaver/app │                   │  postgres pod         │
│      │      │                   │  IP: 10.244.x.x:5432 │
│      ▼      │   API server      │      ▲                │
│ localhost   │◀─────tunnel──────▶│      │                │
│   :5433     │  (authenticated)  │      │                │
└─────────────┘                   └──────────────────────┘
```

`kubectl port-forward -n infra svc/postgres 5433:5432` does this:

1. Starts a `kubectl` process on your machine that opens and listens on **local**
   port `5433`.
2. It authenticates to the Kubernetes API server (same credentials your `kubectl`
   already uses) and asks it to open a tunnel to the target pod's port `5432`.
3. Any bytes that arrive on your local `5433` get streamed through that tunnel to
   the pod's `5432`, and the response streams back the same way — the client app
   has no idea it's not talking to a real local Postgres.

## Properties worth knowing

- **Temporary** — it only exists while that `kubectl` process is running. Close the
  terminal / hit Ctrl+C, and the tunnel is gone.
- **No cluster config needed** — unlike a `NodePort` or `LoadBalancer` Service, you
  don't have to expose anything permanently; it works against a plain
  `ClusterIP`-only Service (which is exactly what `postgres`/`kafka`/etc. are in
  this project — see `clusterIP: None` in `k8s/infra/postgres/postgres.yaml`).
- **One connection path at a time per invocation** — fine for local dev/debugging,
  not meant for production traffic.
- **The left number is yours to pick, the right one isn't** — the `LOCAL:REMOTE`
  syntax means `LOCAL` is just whatever you're listening on locally (pick anything
  free, e.g. `5433` to dodge a port conflict), but `REMOTE` has to match what's
  actually inside the pod.

## The Kafka gotcha

Port forwarding gets you *into* the cluster for the first connection, but Kafka's
protocol then tells the client a specific hostname to reconnect to for real work
(`KAFKA_ADVERTISED_LISTENERS` in `k8s/infra/kafka/kafka.yaml` —
`kafka.infra.svc.cluster.local`), and that hostname still needs to resolve back to
your tunnel. That's why connecting a real Kafka client needs an extra step beyond
plain Postgres/MinIO/etc. port-forwarding: mapping that hostname to `127.0.0.1` in
`/etc/hosts` so it resolves back to the forwarded port instead of failing to
resolve at all.
