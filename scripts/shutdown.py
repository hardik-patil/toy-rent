#!/usr/bin/env python3
"""
shutdown.py - Bring the whole ToyRental stack down cleanly, following
SHUTDOWN.md's ordering rules. Companion to startup.py.

Order matters, and it's the reverse of startup.py: kill local processes first,
then scale down consumers before the infra they depend on (app services, then
monitoring, then infra last) - so nothing is left retrying against a target
that just disappeared mid-request.

Data is safe across this cycle - Postgres, Couchbase, MinIO, and Kafka all use
real PersistentVolumeClaims, not ephemeral storage. This only scales things to
0 and kills local processes; nothing here deletes or resets data.

What it does, in order (same as SHUTDOWN.md):
  0. Kill local processes - kubectl port-forwards and the frontend dev server.
     These aren't Kubernetes resources, so no kubectl command touches them.
  1. Scale down the app services (toy-rental namespace). Deletes each
     Deployment's HorizontalPodAutoscaler FIRST - a plain scale-to-0 doesn't
     hold against a live CPU-based HPA with minReplicas=2, it just gets
     scaled back up within ~15s (a real incident once - CLAUDE.md's Known
     Bugs table).
  2. Scale down monitoring (grafana, prometheus, zipkin) - no HPAs here.
  3. Scale down infra (postgres, kafka, minio, couchbase + the small
     deployments).
  4. (--verify) Confirm all three namespaces are empty of running pods, and
     print node-wide CPU/memory via `docker stats` (metrics-server isn't
     installed on this cluster). Also reports Jenkins' status (never
     touched - it's a plain docker container, not a Kubernetes resource).

Note: same as startup.py, this script never starts/stops/touches the Jenkins
container or the Dynatrace Operator - both are explicitly outside this
stop/start cycle. See STARTUP.md's "Jenkins CI/CD" section if you also want
to stop Jenkins (`docker stop jenkins`).

Usage:
    python scripts/shutdown.py                    # steps 0-3
    python scripts/shutdown.py --verify            # steps 0-3, then step 4
    python scripts/shutdown.py --apps-only         # step 0-1 only, leave infra/monitoring up
    python scripts/shutdown.py --keep-monitoring   # skip step 2
    python scripts/shutdown.py --keep-infra        # skip step 3
    python scripts/shutdown.py --skip-kill         # skip step 0 (local processes)

See scripts/README.md for the full guide.
"""

import argparse
import os
import platform
import shutil
import subprocess
import sys

NS_INFRA = "infra"
NS_MONITORING = "monitoring"
NS_APP = "toy-rental"

APP_SERVICES = ["api-gateway", "toy-service", "booking-service"]
MONITORING_DEPLOYMENTS = ["grafana", "prometheus", "zipkin"]
INFRA_STATEFULSETS = ["postgres", "kafka", "minio", "couchbase"]
INFRA_DEPLOYMENTS = ["keycloak", "redis", "wiremock", "postgres-exporter", "kafka-lag-exporter"]

IS_WINDOWS = platform.system() == "Windows"

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
# Same pidfile startup.py's --port-forward writes to. We don't use it to decide
# *what* to kill (SHUTDOWN.md's documented approach is a blanket kill, not a
# surgical one) - we just clean it up afterward so it doesn't reference PIDs
# that no longer exist.
PID_FILE = os.path.join(SCRIPT_DIR, ".portforward.pids")


# --------------------------------------------------------------------------- #
# small shell helpers (same shape as startup.py, kept independent on purpose -
# these two scripts are meant to be readable standalone)
# --------------------------------------------------------------------------- #
def run(cmd, check=True, capture=False, quiet=False):
    """Run a command. On failure: exit if check, else return the CompletedProcess."""
    if not quiet:
        print(f"$ {' '.join(cmd)}")
    result = subprocess.run(
        cmd,
        text=True,
        stdout=subprocess.PIPE if capture else None,
        stderr=subprocess.STDOUT if capture else None,
    )
    if result.returncode != 0 and check:
        if capture and result.stdout:
            print(result.stdout, file=sys.stderr)
        print(f"\nCommand failed ({result.returncode}): {' '.join(cmd)}", file=sys.stderr)
        sys.exit(result.returncode)
    return result


def kubectl_available():
    if shutil.which("kubectl") is None:
        sys.exit("Error: 'kubectl' not found on PATH. Install it and point it at your cluster.")


# --------------------------------------------------------------------------- #
# steps
# --------------------------------------------------------------------------- #
def step_kill_local_processes():
    print("\n=== 0. Kill local processes (port-forwards, frontend dev server) ===")
    if IS_WINDOWS:
        # taskkill.exe called directly via subprocess (no shell involved), so
        # plain single-slash flags are correct here - the double-slash form
        # in SHUTDOWN.md (//IM, //F) is only needed when typing at a Git Bash
        # prompt, where MSYS rewrites a leading single slash into a path.
        for image in ("kubectl.exe", "node.exe"):
            r = run(["taskkill", "/IM", image, "/F"], check=False, capture=True, quiet=True)
            if r.returncode == 0:
                print(f"  killed all {image} processes")
            else:
                print(f"  no running {image} processes (or already stopped)")
    else:
        for pattern in ("kubectl port-forward", "npm run dev"):
            run(["pkill", "-f", pattern], check=False, quiet=True)
            print(f"  sent pkill -f \"{pattern}\"")

    if os.path.isfile(PID_FILE):
        os.remove(PID_FILE)
        print(f"  removed stale {os.path.relpath(PID_FILE)} "
              "(its recorded PIDs no longer exist after the kill above)")


def step_app_services():
    print("\n=== 1. Scale down app services (toy-rental) ===")
    # --ignore-not-found so re-running this script (or running it after the
    # HPAs were already deleted by hand) doesn't error out.
    run(["kubectl", "delete", "hpa", "-n", NS_APP, *APP_SERVICES, "--ignore-not-found=true"])
    run(["kubectl", "scale", "deployment", "-n", NS_APP, *APP_SERVICES, "--replicas=0"])
    print("app services: HPAs deleted, replicas set to 0.")


def step_monitoring():
    print("\n=== 2. Scale down monitoring ===")
    run(["kubectl", "scale", "deployment", "-n", NS_MONITORING, *MONITORING_DEPLOYMENTS, "--replicas=0"])
    print("monitoring: done.")


def step_infra():
    print("\n=== 3. Scale down infra ===")
    run(["kubectl", "scale", "statefulset", "-n", NS_INFRA, *INFRA_STATEFULSETS, "--replicas=0"])
    run(["kubectl", "scale", "deployment", "-n", NS_INFRA, *INFRA_DEPLOYMENTS, "--replicas=0"])
    print("infra: done.")


# --------------------------------------------------------------------------- #
# verify
# --------------------------------------------------------------------------- #
def _running_pods(ns):
    r = run(
        ["kubectl", "get", "pods", "-n", ns, "--field-selector=status.phase=Running",
         "-o", "custom-columns=NAME:.metadata.name", "--no-headers"],
        check=False, capture=True, quiet=True,
    )
    if r.returncode != 0:
        return []
    return [line for line in r.stdout.splitlines() if line.strip()]


def verify():
    print("\n=== 4. Verify ===")

    print("\n-- running pods per namespace (should be empty; Completed Jobs are fine) --")
    all_clear = True
    for ns in (NS_APP, NS_MONITORING, NS_INFRA):
        pods = _running_pods(ns)
        if pods:
            all_clear = False
            print(f"  {ns}: still Running -> {', '.join(pods)}")
        else:
            print(f"  {ns}: clear")
    if not all_clear:
        print("  (a Deployment/StatefulSet not yet at 0 replicas can take a few seconds to "
              "actually terminate its pods - re-run --verify shortly if something's still up)")

    print("\n-- node resource usage (metrics-server isn't installed on this cluster) --")
    if shutil.which("docker") is None:
        print("  docker not on PATH - can't check")
    else:
        r = subprocess.run(
            ["docker", "stats", "--no-stream", "--format",
             "table {{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}"],
            text=True, capture_output=True,
        )
        print(r.stdout if r.returncode == 0 else "  docker stats failed")

    print("\n-- Jenkins (plain docker container, not part of this script's cycle) --")
    r = subprocess.run(
        ["docker", "inspect", "-f", "{{.State.Status}}", "jenkins"],
        text=True, capture_output=True,
    )
    if r.returncode != 0:
        print("  no container named 'jenkins' found")
    elif r.stdout.strip() == "running":
        print("  still running at http://localhost:8080 - untouched by this script; "
              "docker stop jenkins if you want it down too")
    else:
        print(f"  exists but not running (status: {r.stdout.strip()})")


# --------------------------------------------------------------------------- #
# main
# --------------------------------------------------------------------------- #
def main():
    parser = argparse.ArgumentParser(
        description="Bring the ToyRental stack down cleanly (SHUTDOWN.md steps 0-4).",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument("--skip-kill", action="store_true",
                        help="Skip step 0 (killing local kubectl port-forwards / npm run dev).")
    parser.add_argument("--apps-only", action="store_true",
                        help="Do step 1 (app services) and stop - leave monitoring/infra up.")
    parser.add_argument("--keep-monitoring", action="store_true",
                        help="Skip step 2 - leave grafana/prometheus/zipkin running.")
    parser.add_argument("--keep-infra", action="store_true",
                        help="Skip step 3 - leave postgres/kafka/minio/couchbase running.")
    parser.add_argument("--verify", action="store_true",
                        help="Run step-4 checks (running pods per namespace, docker stats, Jenkins status).")
    args = parser.parse_args()

    kubectl_available()

    if not args.skip_kill:
        step_kill_local_processes()
    else:
        print("\n=== 0. Kill local processes - skipped (--skip-kill) ===")

    step_app_services()

    if args.apps_only:
        print("\n--apps-only: stopping before monitoring/infra.")
        if args.verify:
            verify()
        return

    if not args.keep_monitoring:
        step_monitoring()
    else:
        print("\n=== 2. Monitoring - skipped (--keep-monitoring) ===")

    if not args.keep_infra:
        step_infra()
    else:
        print("\n=== 3. Infra - skipped (--keep-infra) ===")

    if args.verify:
        verify()

    print("\nDone. Bring it back with: python scripts/startup.py --port-forward --verify")


if __name__ == "__main__":
    main()
