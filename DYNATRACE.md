# ToyRental Platform — Dynatrace OneAgent (What & How)

Deep-code APM (JVM method tracing, DB/Couchbase/Kafka call timing) for the three app
services, alongside the existing Prometheus/Grafana/Zipkin stack. This is additive
observability, not a replacement — `/actuator/prometheus` scraping and the Zipkin traces
documented in `CLAUDE.md` are unaffected.

See `CLAUDE.md`'s "Dynatrace OneAgent" subsection for the terse reference version of this;
this file is the fuller "what was built and why" writeup.

---

## What

| Piece | File | Purpose |
|---|---|---|
| Namespace | `k8s/namespace.yaml` | New `dynatrace` namespace for the operator; `toy-rental` namespace gets a `dynatrace-injection: "enabled"` label |
| Custom resource | `k8s/infra/dynatrace/dynakube.yaml` | `DynaKube` CR — tells the operator what to monitor and how |
| Credentials | `k8s/infra/dynatrace/secret.yaml` | Dev-only placeholder `apiToken`/`dataIngestToken` |
| Operator itself | *not vendored in this repo* | Installed from Dynatrace's own pinned-release manifest — see [How](#how) |
| Runbook step | `STARTUP.md` → "Dynatrace Operator (one-time setup)" | Install commands |

Scope, deliberately narrow for this project:

- **Namespace:** `toy-rental` only (api-gateway, toy-service, booking-service). `infra`
  and `monitoring` are **not** instrumented — Postgres/Couchbase/Kafka/Grafana etc. stay
  as they are.
- **Mode:** `applicationMonitoring` only, not `cloudNativeFullStack`. No host-level
  OneAgent DaemonSet — just the per-pod code-module injection. This node is already
  tight on CPU/memory (see `CLAUDE.md`'s Known Bugs table), and a host DaemonSet is
  mainly useful across many real nodes, not one local Docker Desktop VM.
- **Credentials:** placeholder values (`REPLACE_WITH_REAL_DYNATRACE_API_TOKEN` etc.),
  same convention as `helm/values.yaml`'s dev-only secrets (`toypass`, `minioadmin`,
  `admin123`). Nothing reports to a real Dynatrace tenant until these are swapped for
  real ones.

---

## How

### The mechanism: DynaKube CR + mutating webhook, not a manual sidecar

"Add a Dynatrace container to the pod" sounds like editing every Deployment's container
list by hand, but that's not how the Dynatrace Operator actually works, and it's not what
got built here. Instead:

1. The **Dynatrace Operator** runs in its own `dynatrace` namespace and registers a
   `MutatingWebhookConfiguration` with the Kubernetes API server.
2. It watches for `DynaKube` custom resources. This repo defines exactly one:
   `toy-rental-oneagent` in `k8s/infra/dynatrace/dynakube.yaml`.
3. That CR's `spec.namespaceSelector.matchLabels` says "any namespace labeled
   `dynatrace-injection: enabled`." Only `toy-rental` carries that label.
4. Whenever the API server admits a **new pod** in a matching namespace, it calls the
   webhook first. The webhook injects an `install-oneagent` init container plus
   `oneagent-share`/`oneagent-share-log` volumes into the pod spec, then lets admission
   continue.

Net effect: **zero changes to `toy-service.yaml`, `booking-service.yaml`,
`api-gateway.yaml`, or the Helm templates.** Injection happens transparently at pod
creation time, for every pod in `toy-rental`, present and future, as long as the CR and
namespace label exist.

### Why the operator itself isn't in this repo

Every other piece of infra in `k8s/infra/` (Postgres, Couchbase, Kafka, Redis, MinIO,
Keycloak, WireMock, Prometheus, Grafana) is a hand-authored Deployment/StatefulSet
against a plain container image — nothing here has ever installed a third-party
Kubernetes Operator before. The Dynatrace Operator ships as a large, official,
version-pinned bundle (CRDs + RBAC + the webhook service/cert + the operator Deployment
+ the CSI driver DaemonSet). Hand-transcribing that into this repo would mean keeping a
second, drifting copy of an upstream release in sync by hand.

Instead, following the same "pin an exact version" convention already used for
`apache/kafka:3.7.2`, the operator is installed straight from a pinned GitHub release:

```bash
kubectl apply -f https://github.com/Dynatrace/dynatrace-operator/releases/download/vX.Y.Z/kubernetes.yaml
```

Only the three genuinely project-specific pieces are repo-owned, hand-authored YAML:
the `DynaKube` CR (what to monitor), the token `Secret` (dev placeholders), and the
namespace label (which namespace it applies to).

**Note on `apiVersion`:** `dynakube.yaml` is pinned to `dynatrace.com/v1beta3`, matching
the operator API version current as of when this was written. Dynatrace has bumped this
CRD version across releases before — if you install a different operator release, check
`kubectl explain dynakube` (or the release's own docs) and update the `apiVersion` in
`dynakube.yaml` to match before applying it.

### Install order

1. Install the operator (pinned release above) into the `dynatrace` namespace; wait for
   its pods to be `Running`.
2. `kubectl apply -f k8s/namespace.yaml` — creates/labels namespaces, including the
   `toy-rental` → `dynatrace-injection: enabled` label the CR selects on.
3. `kubectl apply -f k8s/infra/dynatrace/` — creates the token Secret and the DynaKube
   CR.
4. Restart the app pods (`kubectl rollout restart deployment -n toy-rental api-gateway
   toy-service booking-service`, or just wait for the next natural rollout) so the
   webhook gets a chance to inject them — **already-running pods are not retroactively
   modified.**

Full commands live in `STARTUP.md`'s "Dynatrace Operator (one-time setup)" section.

### What "working" looks like before real credentials exist

With placeholder `apiUrl`/tokens, two things are true simultaneously and both are
expected:

- `kubectl get dynakube -n dynatrace` shows a connectivity/auth **error** in status —
  there's no real tenant at `https://ENVIRONMENT_ID.live.dynatrace.com/api` to talk to.
- New pods in `toy-rental` still get the `install-oneagent` init container injected —
  the webhook's job is just admission-time mutation, and doesn't depend on the tenant
  connection succeeding.

Confirm injection specifically (independent of tenant connectivity) with:

```bash
kubectl describe pod -n toy-rental -l app.kubernetes.io/name=toy-service
```

Look for the `install-oneagent` init container and `oneagent-share`/`oneagent-share-log`
volumes in the output.

### Going live with a real tenant

1. In Dynatrace, generate an API token (scopes: read/ingest metrics + topology at
   minimum) and a data-ingest token.
2. Replace the two placeholder values in `k8s/infra/dynatrace/secret.yaml`.
3. Replace `spec.apiUrl` in `k8s/infra/dynatrace/dynakube.yaml` with the real
   environment URL.
4. Re-apply both files; `kubectl get dynakube -n dynatrace` should move to a healthy
   status once the operator successfully connects.

Neither file should be committed with real tokens in it — treat them the same as the
other dev-only secrets already in this repo (see `helm/values.yaml`'s comment on
`secrets:`), and override locally or via a separate untracked values file instead.
