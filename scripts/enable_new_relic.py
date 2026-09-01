#!/usr/bin/env python3
"""
enable_new_relic.py — Patch a real New Relic license key into the toy-rental
cluster and re-enable the Java agent on toy-service, booking-service, and
api-gateway.

Background: the agent was disabled live via `JDK_JAVA_OPTIONS=""` on all three
deployments because the placeholder license key sends it into a tight,
uncapped reconnect loop on every `LicenseException` — measured at 350-550%
sustained node CPU across the JVMs, and the dominant cause of a liveness-probe
crash-loop during a full cluster bring-up. See STARTUP.md's New Relic section
and CLAUDE.md's Known Bugs table (2026-09-01 entry) for the full story.

This script does NOT touch the checked-in manifests — it patches the live
cluster's Secret and clears the live env-var override, letting each
deployment fall back to the JDK_JAVA_OPTIONS already baked into
k8s/services/*/*.yaml (-javaagent:/app/newrelic.jar).

Usage:
    python scripts/enable_new_relic.py <NEW_RELIC_LICENSE_KEY>

See scripts/README.md for the full how-to-run guide.
"""

import argparse
import json
import os
import shutil
import subprocess
import sys

NAMESPACE = "toy-rental"
SECRET_NAME = "newrelic-secret"
SECRET_KEY = "NEW_RELIC_LICENSE_KEY"
DEPLOYMENTS = ["toy-service", "booking-service", "api-gateway"]
PLACEHOLDER = "REPLACE_WITH_REAL_NEW_RELIC_LICENSE_KEY"

# Manifest path per deployment, relative to the repo root (this script assumes it's run
# from there, matching scripts/README.md's documented usage). Re-applying the manifest —
# not `kubectl set env deployment/x JDK_JAVA_OPTIONS-` — is the correct way to restore
# JDK_JAVA_OPTIONS to its real -javaagent value: plain Kubernetes has no override/base
# layer for `kubectl set env` to "fall back" to, so the trailing-dash form just deletes the
# env var outright instead of restoring it. This bit us once already (2026-09-01's Known
# Bugs entry in CLAUDE.md) — the agent silently never loaded after using that approach.
MANIFEST_PATHS = {
    "toy-service": "k8s/services/toy-service/toy-service.yaml",
    "booking-service": "k8s/services/booking-service/booking-service.yaml",
    "api-gateway": "k8s/services/api-gateway/api-gateway.yaml",
}


def run(cmd, **kwargs):
    """Run a command, streaming output, and exit the script if it fails."""
    print(f"$ {' '.join(cmd)}")
    result = subprocess.run(cmd, **kwargs)
    if result.returncode != 0:
        print(f"\nCommand failed with exit code {result.returncode}: {' '.join(cmd)}", file=sys.stderr)
        sys.exit(result.returncode)
    return result


def check_kubectl_available():
    if shutil.which("kubectl") is None:
        sys.exit("Error: 'kubectl' not found on PATH. Make sure it's installed and configured.")


def validate_key(raw_key: str) -> str:
    key = raw_key.strip()
    if not key:
        sys.exit("Error: license key is empty.")
    if key == PLACEHOLDER:
        sys.exit(
            f"Error: that's still the placeholder value ({PLACEHOLDER!r}), not a real key.\n"
            "Get a real key from your New Relic account (one.newrelic.com -> API keys)."
        )
    if len(key) < 20:
        print(
            f"Warning: {key!r} looks short for a New Relic license key — double-check it.",
            file=sys.stderr,
        )
    return key


def patch_secret(namespace: str, key: str) -> None:
    print(f"\n== Patching secret '{SECRET_NAME}' in namespace '{namespace}' ==")
    patch = json.dumps({"stringData": {SECRET_KEY: key}})
    run(
        [
            "kubectl", "patch", "secret", SECRET_NAME,
            "-n", namespace,
            "--type=merge",
            "-p", patch,
        ]
    )


def reenable_agent(namespace: str, deployment: str, wait_for_rollout: bool) -> None:
    print(f"\n== Re-enabling agent on deployment '{deployment}' ==")
    manifest = MANIFEST_PATHS[deployment]
    if not os.path.isfile(manifest):
        sys.exit(
            f"Error: {manifest!r} not found. Run this script from the repo root "
            "(see scripts/README.md)."
        )
    # Re-apply the real manifest so JDK_JAVA_OPTIONS goes back to its actual
    # -javaagent:/app/newrelic.jar value — see the MANIFEST_PATHS comment above for why
    # this is NOT `kubectl set env deployment/x JDK_JAVA_OPTIONS-`.
    run(["kubectl", "apply", "-f", manifest])

    if wait_for_rollout:
        print(f"-- waiting for deployment/{deployment} rollout to finish --")
        # Some rollouts on this project's node (see CLAUDE.md's Known Bugs table) take
        # long enough — particularly api-gateway's pod termination — that 300s isn't
        # always enough even when the rollout is actually succeeding. Treat a timeout as
        # a warning, not a fatal error: check manually with `kubectl rollout status
        # deployment/<name> -n <namespace>` if this prints a warning below.
        result = subprocess.run(
            ["kubectl", "rollout", "status", f"deployment/{deployment}", "-n", namespace, "--timeout=400s"]
        )
        if result.returncode != 0:
            print(
                f"WARNING: rollout status wait for '{deployment}' timed out or errored — "
                "this doesn't necessarily mean it failed, the rollout may still complete "
                "shortly after. Check manually: "
                f"kubectl rollout status deployment/{deployment} -n {namespace}",
                file=sys.stderr,
            )


def verify_connection(namespace: str, deployment: str) -> None:
    print(f"\n-- {deployment} --")
    result = subprocess.run(
        ["kubectl", "logs", "-n", namespace, "-l", f"app.kubernetes.io/name={deployment}", "--tail=200"],
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        print(f"  (couldn't fetch logs: {result.stderr.strip()})")
        return

    matches = [
        line for line in result.stdout.splitlines()
        if "connected to collector" in line.lower() or "invalid license key" in line.lower()
    ]
    if not matches:
        print("  (no matching log lines yet — agent may still be connecting; re-run verification later)")
    else:
        for line in matches[-5:]:
            print(f"  {line}")


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Patch a real New Relic license key into the cluster and "
        "re-enable the agent on toy-service, booking-service, and api-gateway."
    )
    parser.add_argument("license_key", help="Real NEW_RELIC_LICENSE_KEY from your New Relic account")
    parser.add_argument("--namespace", default=NAMESPACE, help=f"Kubernetes namespace (default: {NAMESPACE})")
    parser.add_argument(
        "--skip-rollout-wait",
        action="store_true",
        help="Don't wait for each deployment's rollout to finish before moving to the next",
    )
    parser.add_argument(
        "--skip-verify",
        action="store_true",
        help="Skip the post-patch log check for agent connection",
    )
    args = parser.parse_args()

    check_kubectl_available()
    key = validate_key(args.license_key)

    patch_secret(args.namespace, key)

    for deployment in DEPLOYMENTS:
        reenable_agent(args.namespace, deployment, wait_for_rollout=not args.skip_rollout_wait)

    if not args.skip_verify:
        print("\n== Verifying agent connection ==")
        for deployment in DEPLOYMENTS:
            verify_connection(args.namespace, deployment)

    print(
        "\nDone. Look for 'Agent ... connected to collector.newrelic.com:443' above for each "
        "service. If you see 'Invalid license key' instead, double-check the key you passed "
        "(and its region — see scripts/README.md's Troubleshooting section)."
    )


if __name__ == "__main__":
    main()
