#!/usr/bin/env python3
"""
application_status.py - One-glance "is the ToyRental stack up or down?" check.

Read-only. Queries the cluster (never changes it) and prints a per-workload
table plus a single overall verdict, then exits with a matching code so it's
usable in scripts and CI:

    exit 0  ALL UP      - every checked workload has all replicas Ready
    exit 1  DEGRADED    - some up, some not (a partial bring-up, or a crash)
    exit 2  STOPPED     - every checked workload is scaled to 0 (SHUTDOWN.md state)
    exit 2  DOWN        - nothing Ready and it's not a clean stop
    exit 3  can't check - kubectl missing / no cluster / namespace(s) absent

What it checks, by group:
  app        (ns toy-rental)   api-gateway, toy-service, booking-service
  infra      (ns infra)        postgres, kafka, minio, couchbase, keycloak,
                               redis, wiremock, postgres-exporter,
                               kafka-lag-exporter
  monitoring (ns monitoring)   grafana, prometheus, zipkin

Deployments and StatefulSets both; "Ready" means status.readyReplicas ==
spec.replicas and >= 1.

Usage:
    python scripts/application_status.py                # all three groups
    python scripts/application_status.py --app-only     # just the 3 app services
    python scripts/application_status.py --http         # also curl /actuator/health
    python scripts/application_status.py --quiet        # verdict line + exit code only

Note: does NOT check Jenkins (a plain docker container, not part of the K8s
stack) or the frontend dev server. See scripts/README.md for the full guide.
"""

import argparse
import json
import shutil
import subprocess
import sys
import urllib.error
import urllib.request

NS_APP = "toy-rental"
NS_INFRA = "infra"
NS_MONITORING = "monitoring"

APP_WORKLOADS = ["api-gateway", "toy-service", "booking-service"]
INFRA_WORKLOADS = [
    "postgres", "kafka", "minio", "couchbase",
    "keycloak", "redis", "wiremock", "postgres-exporter", "kafka-lag-exporter",
]
MONITORING_WORKLOADS = ["grafana", "prometheus", "zipkin"]

# (label, namespace, expected workload names)
GROUPS = [
    ("app", NS_APP, APP_WORKLOADS),
    ("infra", NS_INFRA, INFRA_WORKLOADS),
    ("monitoring", NS_MONITORING, MONITORING_WORKLOADS),
]

# --http probes these on localhost (needs `kubectl port-forward`, or
# scripts/startup.py --port-forward). api-gateway is deliberately not
# port-forwarded in this project, so it's not listed.
HTTP_HEALTH = [
    ("toy-service", "http://localhost:8081/actuator/health"),
    ("booking-service", "http://localhost:8082/actuator/health"),
]

# per-workload states, worst-last (used for the overall roll-up)
ORDER = ["UP", "STOPPED", "PARTIAL", "DOWN", "MISSING"]


# --------------------------------------------------------------------------- #
# helpers
# --------------------------------------------------------------------------- #
def kubectl_available():
    if shutil.which("kubectl") is None:
        sys.exit("Error: 'kubectl' not found on PATH. Install it and point it at your cluster.")


def kubectl_workloads(ns):
    """{name: {"kind", "desired", "ready"}} for Deployments + StatefulSets in ns,
    or None if the namespace / call fails (missing ns, no cluster, RBAC)."""
    r = subprocess.run(
        ["kubectl", "get", "deploy,statefulset", "-n", ns, "-o", "json"],
        text=True, capture_output=True,
    )
    if r.returncode != 0:
        return None
    out = {}
    for item in json.loads(r.stdout).get("items", []):
        status = item.get("status", {})
        out[item["metadata"]["name"]] = {
            "kind": item["kind"],
            "desired": item["spec"].get("replicas", 0) or 0,
            "ready": status.get("readyReplicas", 0) or 0,
        }
    return out


def classify(w):
    """w is a workload dict, or None if it isn't in the cluster at all."""
    if w is None:
        return "MISSING"
    desired, ready = w["desired"], w["ready"]
    if desired == 0:
        return "STOPPED"
    if ready == 0:
        return "DOWN"
    if ready < desired:
        return "PARTIAL"
    return "UP"


def http_get(url, timeout=5):
    try:
        with urllib.request.urlopen(url, timeout=timeout) as resp:
            return resp.status
    except urllib.error.HTTPError as exc:
        return exc.code
    except Exception:  # URLError, timeout, connection refused
        return None


def cluster_reachable():
    r = subprocess.run(
        ["kubectl", "cluster-info"], text=True, capture_output=True,
    )
    return r.returncode == 0


# --------------------------------------------------------------------------- #
# checks
# --------------------------------------------------------------------------- #
def check_group(label, ns, names, quiet):
    """Print one group's table; return list of (name, state)."""
    found = kubectl_workloads(ns)
    if found is None:
        if not quiet:
            print(f"\n-- {label} (ns {ns}) --")
            print(f"  namespace not found / unreachable - all {len(names)} workloads treated as MISSING")
        return [(n, "MISSING") for n in names]

    results = []
    if not quiet:
        print(f"\n-- {label} (ns {ns}) --")
    for name in names:
        w = found.get(name)
        state = classify(w)
        results.append((name, state))
        if not quiet:
            if w is None:
                detail = "not deployed"
            else:
                detail = f"{w['ready']}/{w['desired']} ready  ({w['kind']})"
            print(f"  {name:<22} {state:<8} {detail}")
    return results


def check_http(quiet):
    if not quiet:
        print("\n-- HTTP health (needs port-forwards up) --")
    all_ok = True
    for name, url in HTTP_HEALTH:
        status = http_get(url)
        ok = status == 200
        all_ok = all_ok and ok
        if not quiet:
            if status == 200:
                print(f"  {name:<22} 200 OK")
            elif status is None:
                print(f"  {name:<22} unreachable ({url})")
            else:
                print(f"  {name:<22} HTTP {status}")
    if not quiet and not all_ok:
        print("  (start them with: python scripts/startup.py --port-forward - forwards die "
              "when their backing pod is recreated)")
    return all_ok


# --------------------------------------------------------------------------- #
# verdict
# --------------------------------------------------------------------------- #
def overall(results):
    """results: list of (name, state). Return (verdict_word, exit_code, summary_counts)."""
    states = [s for _, s in results]
    counts = {k: states.count(k) for k in ORDER if states.count(k)}

    if all(s == "UP" for s in states):
        return "ALL UP", 0, counts
    if all(s == "STOPPED" for s in states):
        return "STOPPED", 2, counts
    if any(s in ("UP", "PARTIAL") for s in states):
        return "DEGRADED", 1, counts
    return "DOWN", 2, counts


# --------------------------------------------------------------------------- #
# main
# --------------------------------------------------------------------------- #
def main():
    parser = argparse.ArgumentParser(
        description=__doc__,
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument("--app-only", action="store_true",
                        help="Only check the 3 app services (ns toy-rental), skip infra/monitoring.")
    parser.add_argument("--http", action="store_true",
                        help="Also GET /actuator/health on localhost:8081/8082 (needs port-forwards).")
    parser.add_argument("--quiet", action="store_true",
                        help="Print only the final verdict line; suppress the per-workload tables.")
    args = parser.parse_args()

    kubectl_available()
    if not cluster_reachable():
        print("VERDICT: can't check - kubectl can't reach a cluster "
              "(kubectl cluster-info failed)", file=sys.stderr)
        sys.exit(3)

    groups = GROUPS[:1] if args.app_only else GROUPS

    results = []
    for label, ns, names in groups:
        results.extend(check_group(label, ns, names, args.quiet))

    http_ok = True
    if args.http:
        http_ok = check_http(args.quiet)

    verdict, code, counts = overall(results)

    # An HTTP probe failure downgrades ALL UP -> DEGRADED (pods Ready but not
    # actually serving, or the port-forwards just aren't up).
    if args.http and verdict == "ALL UP" and not http_ok:
        verdict, code = "DEGRADED", 1

    summary = ", ".join(f"{counts[s]} {s}" for s in ORDER if s in counts)
    scope = "app" if args.app_only else "app+infra+monitoring"
    if not args.quiet:
        print()
    print(f"VERDICT: {verdict}  [{scope}: {summary}]")
    sys.exit(code)


if __name__ == "__main__":
    main()
