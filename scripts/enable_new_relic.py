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
import shutil
import subprocess
import sys

NAMESPACE = "toy-rental"
SECRET_NAME = "newrelic-secret"
SECRET_KEY = "NEW_RELIC_LICENSE_KEY"
DEPLOYMENTS = ["toy-service", "booking-service", "api-gateway"]
PLACEHOLDER = "REPLACE_WITH_REAL_NEW_RELIC_LICENSE_KEY"


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
    # Trailing "-" (no "=value") is kubectl set env's syntax for removing a
    # previously-set env var override, so the deployment falls back to the
    # JDK_JAVA_OPTIONS already defined in its manifest.
    run(["kubectl", "set", "env", f"deployment/{deployment}", "-n", namespace, "JDK_JAVA_OPTIONS-"])

    if wait_for_rollout:
        print(f"-- waiting for deployment/{deployment} rollout to finish --")
        run(["kubectl", "rollout", "status", f"deployment/{deployment}", "-n", namespace, "--timeout=300s"])


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
