#!/usr/bin/env python3
"""
startup.py - Bring the whole ToyRental stack back up from a stopped state,
following STARTUP.md's ordering rules.

This is the automated version of STARTUP.md steps 1-6 for a *stopped* cluster
(namespaces and PVCs already exist, everything just scaled to 0). It does NOT
handle a fresh cluster - image builds, first-time namespace/network-policy
apply, Node.js install, etc. If the toy-rental / infra / monitoring namespaces
don't all exist yet, this script tells you so and exits; follow STARTUP.md's
"Fresh cluster? Start here instead" section by hand for that.

What it does, in order (same as STARTUP.md):
  1. Scale infra back up (postgres, kafka, minio, couchbase + the small
     deployments) and wait for each to be Ready.
  2. Scale monitoring back up (grafana, prometheus, zipkin).
  3. `kubectl apply` the three app services ONE AT A TIME - toy-service,
     booking-service, api-gateway - waiting for each rollout to finish and
     checking node load in between, because this node has a documented history
     of CPU-throttling restart loops when too many JVMs cold-start at once.
     (apply, not scale, so each Deployment's HPA is recreated too.)
  4. (--port-forward) Start the STARTUP.md step-4 port-forwards as detached
     background processes, recording their PIDs so --stop-port-forward can
     kill them later.
  5. Print the frontend start command (a dev server isn't this script's to own).
  6. (--verify) Health-check both services, list Prometheus scrape-target
     health, list Zipkin services, and check each app's New Relic agent log.

Usage:
    python scripts/startup.py                     # steps 1-3, then print next steps
    python scripts/startup.py --port-forward --verify
    python scripts/startup.py --skip-infra        # infra already up
    python scripts/startup.py --infra-only        # stop after infra + monitoring
    python scripts/startup.py --stop-port-forward # kill forwards this script started

See scripts/README.md for the full guide.
"""

import argparse
import json
import os
import platform
import shutil
import signal
import subprocess
import sys
import time
import urllib.error
import urllib.request

NS_INFRA = "infra"
NS_MONITORING = "monitoring"
NS_APP = "toy-rental"

INFRA_STATEFULSETS = ["postgres", "kafka", "minio", "couchbase"]
INFRA_DEPLOYMENTS = ["keycloak", "redis", "wiremock", "postgres-exporter", "kafka-lag-exporter"]
MONITORING_DEPLOYMENTS = ["grafana", "prometheus", "zipkin"]
APP_SERVICES = ["toy-service", "booking-service", "api-gateway"]

# Re-applied one at a time in step 3. `kubectl apply` (not `kubectl scale`) so the
# per-service HorizontalPodAutoscaler defined in the same file is recreated too -
# SHUTDOWN.md deletes those HPAs on the way down. Same paths enable_new_relic.py uses.
MANIFEST_PATHS = {
    "toy-service": "k8s/services/toy-service/toy-service.yaml",
    "booking-service": "k8s/services/booking-service/booking-service.yaml",
    "api-gateway": "k8s/services/api-gateway/api-gateway.yaml",
}

NEWRELIC_SECRET = "newrelic-secret"
NEWRELIC_SECRET_KEY = "NEW_RELIC_LICENSE_KEY"
NEWRELIC_PLACEHOLDER = "REPLACE_WITH_REAL_NEW_RELIC_LICENSE_KEY"

# STARTUP.md step 4. (host_port:target_port, one or more per forward.)
PORT_FORWARDS = [
    (NS_APP, "svc/toy-service", ["8081:8081"]),
    (NS_APP, "svc/booking-service", ["8082:8082"]),
    (NS_INFRA, "svc/minio", ["9000:9000"]),
    (NS_MONITORING, "svc/prometheus", ["9090:9090"]),
    (NS_MONITORING, "svc/grafana", ["3000:3000"]),
    (NS_MONITORING, "svc/zipkin", ["9411:9411"]),
]
# Not in the default list - added by --with-db. Came up constantly during
# perf-testing / DB work (STARTUP.md step 4's second block).
DB_PORT_FORWARDS = [
    (NS_INFRA, "svc/postgres", ["5432:5432"]),
    (NS_INFRA, "svc/couchbase", ["8091:8091", "8093:8093", "11210:11210"]),
]

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
PID_FILE = os.path.join(SCRIPT_DIR, ".portforward.pids")
PF_LOG_DIR = os.path.join(SCRIPT_DIR, ".portforward-logs")

IS_WINDOWS = platform.system() == "Windows"


# --------------------------------------------------------------------------- #
# small shell helpers
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


def namespace_exists(ns):
    r = run(["kubectl", "get", "namespace", ns], check=False, capture=True, quiet=True)
    return r.returncode == 0


def preflight():
    """Refuse to run against a fresh (never-built) cluster - see module docstring."""
    kubectl_available()
    ctx = run(["kubectl", "config", "current-context"], check=False, capture=True, quiet=True)
    print(f"kubectl context: {ctx.stdout.strip() or '(unknown)'}")

    missing = [ns for ns in (NS_APP, NS_INFRA, NS_MONITORING) if not namespace_exists(ns)]
    if missing:
        print(
            "\nThis looks like a FRESH cluster - missing namespace(s): "
            + ", ".join(missing)
            + ".\nThis script only handles a *stopped* cluster (everything scaled to 0).\n"
            "Follow STARTUP.md's \"Fresh cluster? Start here instead\" section by hand:\n"
            "  - kubectl apply -f k8s/namespace.yaml\n"
            "  - kubectl apply -f k8s/network-policy.yaml\n"
            "  - build the 3 app images (needs JAVA_HOME + newrelic.jar - see STARTUP.md)\n"
            "  - kubectl apply -f k8s/infra/*/*.yaml, then monitoring, then services\n"
            "then re-run this script for the regular stop/start cycle.",
            file=sys.stderr,
        )
        sys.exit(1)


# --------------------------------------------------------------------------- #
# readiness
# --------------------------------------------------------------------------- #
def wait_rollout(kind, name, ns, timeout):
    """Block until `kubectl rollout status` reports the resource complete."""
    print(f"-- waiting for {kind}/{name} in {ns} (timeout {timeout}s) --")
    r = subprocess.run(
        ["kubectl", "rollout", "status", f"{kind}/{name}", "-n", ns, f"--timeout={timeout}s"],
        text=True,
    )
    if r.returncode != 0:
        print(
            f"WARNING: {kind}/{name} didn't report Ready within {timeout}s. It may still be "
            f"coming up (Couchbase especially is slow on this node). Check:\n"
            f"  kubectl get pods -n {ns}\n"
            f"  kubectl describe {kind}/{name} -n {ns}",
            file=sys.stderr,
        )
        return False
    return True


def node_worker_cpu():
    """Best-effort node CPU% from `docker stats` (metrics-server isn't installed here).
    Returns a float percent (relative to all host cores, so ~600 = fully pinned on the
    6-core WSL VM) or None if it can't be read."""
    if shutil.which("docker") is None:
        return None
    r = subprocess.run(
        ["docker", "stats", "--no-stream", "--format", "{{.Name}} {{.CPUPerc}}"],
        text=True, capture_output=True,
    )
    if r.returncode != 0:
        return None
    for line in r.stdout.splitlines():
        parts = line.split()
        if len(parts) == 2 and "worker" in parts[0]:
            try:
                return float(parts[1].rstrip("%"))
            except ValueError:
                return None
    return None


def cpu_breather(label):
    cpu = node_worker_cpu()
    if cpu is None:
        return
    print(f"   node worker CPU ~{cpu:.0f}% (of ~600% on this 6-core VM) after {label}")
    if cpu >= 400:
        print("   high - pausing 45s to let it settle before the next service "
              "(STARTUP.md step 3's CPU-contention warning)")
        time.sleep(45)


# --------------------------------------------------------------------------- #
# steps
# --------------------------------------------------------------------------- #
def step_infra(timeout):
    print("\n=== 1. Scale infra back up ===")
    run(["kubectl", "scale", "statefulset", "-n", NS_INFRA, *INFRA_STATEFULSETS, "--replicas=1"])
    run(["kubectl", "scale", "deployment", "-n", NS_INFRA, *INFRA_DEPLOYMENTS, "--replicas=1"])
    ok = True
    # Deployments settle fast; do them first, then the statefulsets (couchbase last / slowest).
    for dep in INFRA_DEPLOYMENTS:
        ok &= wait_rollout("deployment", dep, NS_INFRA, timeout)
    for sts in INFRA_STATEFULSETS:
        ok &= wait_rollout("statefulset", sts, NS_INFRA, timeout)
    if not ok:
        print("\nInfra not fully Ready - NOT continuing to app services (they need "
              "Postgres/Couchbase/Kafka reachable at startup). Fix infra, then re-run "
              "with --skip-infra.", file=sys.stderr)
        sys.exit(1)
    print("infra: all Ready.")


def step_monitoring(timeout):
    print("\n=== 2. Scale monitoring back up ===")
    run(["kubectl", "scale", "deployment", "-n", NS_MONITORING, *MONITORING_DEPLOYMENTS, "--replicas=1"])
    for dep in MONITORING_DEPLOYMENTS:
        wait_rollout("deployment", dep, NS_MONITORING, timeout)
    print("monitoring: done.")


def step_app_services(timeout, disable_new_relic):
    print("\n=== 3. Apply the app services (one at a time) ===")
    for svc in APP_SERVICES:
        manifest = MANIFEST_PATHS[svc]
        if not os.path.isfile(manifest):
            sys.exit(f"Error: {manifest!r} not found. Run this script from the repo root.")
        print(f"\n--- {svc} ---")
        run(["kubectl", "apply", "-f", manifest])
        if disable_new_relic:
            # Live override only (not committed to the manifest) - matches how the agent
            # was disabled during the 2026-09-01 bring-up. Triggers one more rollout.
            run(["kubectl", "set", "env", f"deployment/{svc}", "-n", NS_APP, 'JDK_JAVA_OPTIONS='])
        wait_rollout("deployment", svc, NS_APP, timeout)
        cpu_breather(svc)
    print("\napp services: applied.")


def check_new_relic_secret():
    r = run(
        ["kubectl", "get", "secret", NEWRELIC_SECRET, "-n", NS_APP,
         "-o", f"jsonpath={{.data.{NEWRELIC_SECRET_KEY}}}"],
        check=False, capture=True, quiet=True,
    )
    if r.returncode != 0 or not r.stdout.strip():
        print("New Relic: secret not found / empty - skipping check.")
        return
    import base64
    try:
        val = base64.b64decode(r.stdout.strip()).decode(errors="replace")
    except Exception:
        val = ""
    if val == NEWRELIC_PLACEHOLDER or not val:
        print(
            "\nNew Relic: the live secret still holds the PLACEHOLDER key. With the agent "
            "enabled (the manifests' default) an invalid key sends it into an uncapped "
            "reconnect loop - 350-550% node CPU across the 3 JVMs, enough to crash-loop "
            "liveness probes (CLAUDE.md, 2026-09-01).\n"
            "  -> re-run with --disable-new-relic, or set a real key first with "
            "scripts/enable_new_relic.py <key>."
        )
    else:
        masked = val[:6] + "..." + val[-4:] if len(val) > 12 else "(set)"
        print(f"New Relic: live secret has a real key ({masked}) - agent left enabled.")


# --------------------------------------------------------------------------- #
# port-forwards
# --------------------------------------------------------------------------- #
def _load_pidfile():
    if not os.path.isfile(PID_FILE):
        return []
    entries = []
    with open(PID_FILE) as fh:
        for line in fh:
            line = line.strip()
            if not line:
                continue
            pid_str, _, label = line.partition(" ")
            try:
                entries.append((int(pid_str), label))
            except ValueError:
                pass
    return entries


def _pid_alive(pid):
    if IS_WINDOWS:
        out = subprocess.run(
            ["tasklist", "/FI", f"PID eq {pid}", "/NH"], text=True, capture_output=True
        )
        return str(pid) in out.stdout
    try:
        os.kill(pid, 0)
    except OSError:
        return False
    return True


def start_port_forwards(specs):
    existing = [e for e in _load_pidfile() if _pid_alive(e[0])]
    if existing:
        print(
            f"\n{len(existing)} port-forward(s) from a previous run still alive "
            f"(see {os.path.relpath(PID_FILE)}). Run --stop-port-forward first if you "
            "want a clean set. Skipping port-forward start.",
        )
        return
    os.makedirs(PF_LOG_DIR, exist_ok=True)
    launch_kwargs = {}
    if IS_WINDOWS:
        # DETACHED_PROCESS | CREATE_NEW_PROCESS_GROUP
        launch_kwargs["creationflags"] = 0x00000008 | 0x00000200
    else:
        launch_kwargs["start_new_session"] = True

    lines = []
    print("\n=== 4. Start port-forwards (detached) ===")
    for ns, target, ports in specs:
        label = target.split("/")[-1]
        logpath = os.path.join(PF_LOG_DIR, f"{label}.log")
        cmd = ["kubectl", "port-forward", "-n", ns, target, *ports]
        print(f"$ {' '.join(cmd)}   (-> {os.path.relpath(logpath)})")
        logfh = open(logpath, "ab")
        proc = subprocess.Popen(
            cmd, stdout=logfh, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL, **launch_kwargs
        )
        lines.append(f"{proc.pid} {label} [{','.join(ports)}]")
    with open(PID_FILE, "w") as fh:
        fh.write("\n".join(lines) + "\n")
    print(f"recorded {len(lines)} PIDs in {os.path.relpath(PID_FILE)}")
    print("giving them a few seconds to establish...")
    time.sleep(6)


def stop_port_forwards():
    entries = _load_pidfile()
    if not entries:
        print(f"No pidfile at {os.path.relpath(PID_FILE)} - nothing to stop. "
              "(If you started forwards by hand, kill them yourself: SHUTDOWN.md step 0.)")
        return
    for pid, label in entries:
        if not _pid_alive(pid):
            print(f"  {label} (pid {pid}) already gone")
            continue
        print(f"  killing {label} (pid {pid})")
        if IS_WINDOWS:
            subprocess.run(["taskkill", "/PID", str(pid), "/F"], capture_output=True)
        else:
            try:
                os.kill(pid, signal.SIGTERM)
            except OSError as exc:
                print(f"    ({exc})")
    os.remove(PID_FILE)
    print(f"removed {os.path.relpath(PID_FILE)}")


# --------------------------------------------------------------------------- #
# verify
# --------------------------------------------------------------------------- #
def http_get(url, timeout=5):
    try:
        with urllib.request.urlopen(url, timeout=timeout) as resp:
            return resp.status, resp.read().decode(errors="replace")
    except urllib.error.HTTPError as exc:
        return exc.code, ""
    except Exception as exc:  # URLError, timeout, connection refused
        return None, str(exc)


def verify():
    print("\n=== 6. Verify ===")

    print("\n-- service health (needs port-forwards up) --")
    any_refused = False
    for name, port in (("toy-service", 8081), ("booking-service", 8082)):
        status, _ = http_get(f"http://localhost:{port}/actuator/health")
        if status == 200:
            print(f"  {name}: 200 OK")
        elif status is None:
            any_refused = True
            print(f"  {name}: unreachable on localhost:{port}")
        else:
            print(f"  {name}: HTTP {status}")
    if any_refused:
        print("  (start them with --port-forward, or check they're running: "
              "SHUTDOWN.md notes forwards die when their backing pod is recreated)")

    print("\n-- Prometheus scrape targets --")
    status, body = http_get("http://localhost:9090/api/v1/targets")
    if status == 200:
        try:
            data = json.loads(body)
            targets = data["data"]["activeTargets"]
            by_job = {}
            for t in targets:
                by_job.setdefault(t["labels"].get("job", "?"), []).append(t["health"])
            for job in sorted(by_job):
                healths = by_job[job]
                mark = "up" if all(h == "up" for h in healths) else "DOWN"
                print(f"  {job}: {mark} ({'/'.join(healths)})")
        except (ValueError, KeyError) as exc:
            print(f"  (couldn't parse targets: {exc})")
    else:
        print(f"  Prometheus unreachable on localhost:9090 ({status})")

    print("\n-- Zipkin services --")
    status, body = http_get("http://localhost:9411/api/v2/services")
    if status == 200:
        print(f"  {body.strip()}")
    else:
        print(f"  Zipkin unreachable on localhost:9411 ({status})")

    print("\n-- New Relic agent (per app deployment) --")
    for svc in APP_SERVICES:
        r = subprocess.run(
            ["kubectl", "logs", "-n", NS_APP, "-l", f"app.kubernetes.io/name={svc}", "--tail=300"],
            text=True, capture_output=True,
        )
        matches = [
            ln for ln in r.stdout.splitlines()
            if "connected to collector" in ln.lower() or "invalid license key" in ln.lower()
        ]
        if matches:
            print(f"  {svc}: {matches[-1].strip()[:140]}")
        else:
            print(f"  {svc}: no connect/invalid-key line (agent disabled, or still connecting)")


# --------------------------------------------------------------------------- #
# main
# --------------------------------------------------------------------------- #
def main():
    parser = argparse.ArgumentParser(
        description="Bring the ToyRental stack up from a stopped cluster (STARTUP.md steps 1-6).",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument("--skip-infra", action="store_true",
                        help="Infra is already Running - skip step 1.")
    parser.add_argument("--skip-monitoring", action="store_true",
                        help="Skip step 2 (grafana/prometheus/zipkin).")
    parser.add_argument("--infra-only", action="store_true",
                        help="Do infra (+ monitoring unless --skip-monitoring) and stop before app services.")
    parser.add_argument("--disable-new-relic", action="store_true",
                        help="Set JDK_JAVA_OPTIONS='' on the 3 app deployments after apply "
                             "(use if the newrelic-secret still has the placeholder key).")
    parser.add_argument("--port-forward", action="store_true",
                        help="After services are up, start STARTUP.md step-4 port-forwards detached.")
    parser.add_argument("--with-db", action="store_true",
                        help="Include postgres:5432 and couchbase:8091/8093/11210 forwards (implies --port-forward).")
    parser.add_argument("--stop-port-forward", action="store_true",
                        help="Kill port-forwards previously started by this script, then exit.")
    parser.add_argument("--verify", action="store_true",
                        help="Run step-6 checks (health, Prometheus targets, Zipkin, New Relic).")
    parser.add_argument("--timeout", type=int, default=600,
                        help="Per-resource readiness timeout in seconds (default: 600).")
    args = parser.parse_args()

    if args.stop_port_forward:
        stop_port_forwards()
        return

    preflight()

    if not args.skip_infra:
        step_infra(args.timeout)
    else:
        print("\n=== 1. Infra - skipped (--skip-infra) ===")

    if not args.skip_monitoring:
        step_monitoring(args.timeout)
    else:
        print("\n=== 2. Monitoring - skipped (--skip-monitoring) ===")

    if args.infra_only:
        print("\n--infra-only: stopping before app services.")
        return

    step_app_services(args.timeout, args.disable_new_relic)

    if not args.disable_new_relic:
        check_new_relic_secret()

    if args.port_forward or args.with_db:
        specs = list(PORT_FORWARDS)
        if args.with_db:
            specs += DB_PORT_FORWARDS
        start_port_forwards(specs)
    else:
        print("\n=== 4. Port-forwards - not started (pass --port-forward) ===")
        print("Run these yourself (each in its own terminal / backgrounded):")
        for ns, target, ports in PORT_FORWARDS:
            print(f"  kubectl port-forward -n {ns} {target} {' '.join(ports)} &")

    print("\n=== 5. Frontend ===")
    print("  cd frontend && npm run dev      # Vite prints the actual URL (usually :5173)")

    if args.verify:
        verify()

    print("\nDone. Full URL table + logins are in STARTUP.md's "
          "\"All URLs, once everything above is up\" section.")


if __name__ == "__main__":
    main()
