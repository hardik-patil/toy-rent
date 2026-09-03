#!/usr/bin/env python3
"""
capture_diagnostics.py - Capture a JFR recording + heap dump (+ optional thread
dumps) from a running app pod, without restarting it.

The repo's service images are JRE-only (see learning/heapdump-jfr.md) - no
jcmd/jmap/jstack in the container. This script attaches a throwaway JDK
*ephemeral container* to the target pod via `kubectl debug --target ...
--profile=sysadmin`, runs the captures against the app JVM through the shared
process namespace, then copies the artifacts out with `kubectl cp -c <app
container>` (the files live in the original container, not the debug one).

Because it shells out to `kubectl` directly (no Git Bash in between) it also
sidesteps the MSYS path-mangling that bites `kubectl cp <ns>/<pod>:/tmp/...`
in an interactive Git Bash shell (CLAUDE.md's 2026-09-01 Known Bugs entry).

Usage:
    python scripts/capture_diagnostics.py <pod-name>
    python scripts/capture_diagnostics.py toy-service-cf6dd45cc-bxp6p --duration 5m
    python scripts/capture_diagnostics.py <pod> -d 10m --thread-dumps 5 --heap-all

Defaults: namespace toy-rental, duration 5m, JFR settings 'profile', output
under loadtest/results/diag-<timestamp>/.

Note: a Kubernetes ephemeral container cannot be removed from a pod's spec until
the pod is recreated - each run leaves one behind (named jfrcap-<timestamp>).
Harmless; they vanish on the next rollout.

See scripts/README.md for the full guide.
"""

import argparse
import datetime as dt
import os
import re
import shutil
import subprocess
import sys

DEFAULT_NAMESPACE = "toy-rental"
DEFAULT_IMAGE = "eclipse-temurin:17-jdk-jammy"
DEFAULT_DURATION = "5m"
DEFAULT_JFR_SETTINGS = "profile"  # 'profile' (~2% overhead) or 'default' (~1%)

# pod-name prefix -> app container name (all three services name the container
# after the service). Used to guess --app-container when it isn't passed.
KNOWN_SERVICES = ["toy-service", "booking-service", "api-gateway"]

REMOTE_JFR = "/tmp/capture.jfr"
REMOTE_HPROF = "/tmp/capture.hprof"
REMOTE_TDUMP = "/tmp/threaddump"  # -N.txt appended


def run(cmd, check=True, capture=False, timeout=None):
    """Run a command, streaming its output unless capture=True."""
    print(f"$ {' '.join(cmd)}")
    result = subprocess.run(
        cmd,
        text=True,
        timeout=timeout,
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
        sys.exit("Error: 'kubectl' not found on PATH.")


def parse_duration(text):
    """'300' / '300s' / '5m' / '1h' -> seconds (int)."""
    m = re.fullmatch(r"\s*(\d+)\s*([smh]?)\s*", text.lower())
    if not m:
        sys.exit(f"Error: can't parse duration {text!r} - use e.g. 300, 300s, 5m, 1h.")
    value, unit = int(m.group(1)), m.group(2)
    seconds = value * {"": 1, "s": 1, "m": 60, "h": 3600}[unit]
    if seconds <= 0:
        sys.exit("Error: duration must be > 0.")
    return seconds


def derive_app_container(pod):
    for svc in KNOWN_SERVICES:
        if pod.startswith(svc + "-"):
            return svc
    return None


def preflight(namespace, pod, app_container):
    kubectl_available()
    r = run(
        ["kubectl", "get", "pod", pod, "-n", namespace,
         "-o", "jsonpath={.status.phase}|{.spec.containers[*].name}"],
        check=False, capture=True,
    )
    if r.returncode != 0:
        sys.exit(f"Error: pod {pod!r} not found in namespace {namespace!r}.")
    phase, _, containers = r.stdout.partition("|")
    if phase.strip() != "Running":
        sys.exit(f"Error: pod {pod!r} is {phase.strip()!r}, not Running.")
    names = containers.split()
    if app_container not in names:
        sys.exit(
            f"Error: container {app_container!r} not in pod {pod!r} (has: {', '.join(names)}).\n"
            "Pass the right one with --app-container."
        )
    print(f"pod {pod} is Running; app container = {app_container}")


def build_remote_script(duration_s, jfr_settings, do_jfr, do_heap, heap_all,
                        thread_dumps, thread_interval):
    """POSIX-sh snippet run inside the ephemeral JDK container."""
    L = [
        "set -eu",
        'echo "[cap] locating target JVM via jcmd -l ..."',
        # jcmd -l lists every JVM it can see (the app JVM is visible through the
        # shared process namespace). Its own line has no '.jar', the grep -v is
        # belt-and-braces.
        "PID=$(jcmd -l 2>/dev/null | grep '\\.jar' | grep -v sun.tools.jcmd | awk 'NR==1{print $1}')",
        'if [ -z "${PID:-}" ]; then echo "[cap] ERROR: no .jar JVM found"; jcmd -l || true; exit 1; fi',
        'echo "[cap] target PID=$PID"',
    ]

    if do_jfr:
        L += [
            f'echo "[cap] JFR.start duration={duration_s}s settings={jfr_settings}"',
            f'jcmd "$PID" JFR.start name=capture duration={duration_s}s '
            f'filename={REMOTE_JFR} settings={jfr_settings}',
        ]

    slept = 0
    if thread_dumps > 0:
        L += [
            "i=1",
            f'while [ "$i" -le {thread_dumps} ]; do',
            f'  jcmd "$PID" Thread.print -l > {REMOTE_TDUMP}-$i.txt && echo "[cap] threaddump-$i"',
            "  i=$((i+1))",
            f'  [ "$i" -le {thread_dumps} ] && sleep {thread_interval} || true',
            "done",
        ]
        slept = (thread_dumps - 1) * thread_interval

    remain = max(0, duration_s - slept)
    if do_jfr and remain > 0:
        L.append(f'echo "[cap] recording... {remain}s remaining"; sleep {remain}')

    if do_jfr:
        L.append('echo "[cap] JFR.stop"; jcmd "$PID" JFR.stop name=capture || true')

    if do_heap:
        ha = "-all " if heap_all else ""
        L.append(
            f'echo "[cap] GC.heap_dump {ha}{REMOTE_HPROF}"; '
            f'jcmd "$PID" GC.heap_dump {ha}{REMOTE_HPROF}'
        )

    L += [
        "sync",
        f'ls -lh {REMOTE_JFR} {REMOTE_HPROF} {REMOTE_TDUMP}-*.txt 2>/dev/null || true',
        'echo "[cap] remote capture complete"',
    ]
    return "\n".join(L)


def copy_out(namespace, pod, app_container, remote, local):
    r = run(
        ["kubectl", "cp", f"{namespace}/{pod}:{remote}", local, "-c", app_container],
        check=False,
    )
    if r.returncode != 0 or not os.path.isfile(local):
        print(f"WARNING: failed to copy {remote} out of the pod.", file=sys.stderr)
        return False
    print(f"  -> {local} ({os.path.getsize(local) / 1024 / 1024:.1f} MiB)")
    return True


EXAMPLES = """\
examples:
  # 5-minute JFR + heap dump from a toy-service pod (namespace toy-rental)
  python scripts/capture_diagnostics.py toy-service-cf6dd45cc-bxp6p

  # 10-minute window, and 5 thread dumps 10s apart during it
  python scripts/capture_diagnostics.py toy-service-cf6dd45cc-bxp6p -d 10m --thread-dumps 5

  # heap dump only, from a booking-service pod, no JFR
  python scripts/capture_diagnostics.py booking-service-86487d76d8-jzp2q --skip-jfr

  # see exactly what it would run without touching the cluster
  python scripts/capture_diagnostics.py <pod> -d 5m --dry-run

artifacts land in loadtest/results/diag-<timestamp>/ (override with -o).
see scripts/README.md for the full guide and the known cross-container attach caveat.
"""


def main():
    p = argparse.ArgumentParser(
        prog="capture_diagnostics.py",
        description="Capture JFR + heap dump (+ optional thread dumps) from a running app pod, "
                    "no restart, via a throwaway JDK ephemeral container.",
        epilog=EXAMPLES,
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )

    p.add_argument("pod", help="Target pod name, e.g. toy-service-cf6dd45cc-bxp6p")
    p.add_argument("-n", "--namespace", default=DEFAULT_NAMESPACE,
                   help=f"Namespace (default: {DEFAULT_NAMESPACE})")
    p.add_argument("-d", "--duration", default=DEFAULT_DURATION,
                   help=f"JFR recording length: 300, 300s, 5m, 1h (default: {DEFAULT_DURATION})")
    p.add_argument("--app-container", default=None,
                   help="App container name in the pod (default: guessed from the pod name)")
    p.add_argument("--image", default=DEFAULT_IMAGE,
                   help=f"JDK image for the debug container (default: {DEFAULT_IMAGE})")
    p.add_argument("--jfr-settings", default=DEFAULT_JFR_SETTINGS,
                   help=f"JFR settings profile: profile | default (default: {DEFAULT_JFR_SETTINGS})")
    p.add_argument("--heap-all", action="store_true",
                   help="GC.heap_dump -all (skip the pre-dump full GC; dumps ALL objects, not just live)")
    p.add_argument("--thread-dumps", type=int, default=0, metavar="N",
                   help="Also take N thread dumps spaced --thread-interval apart, during the window")
    p.add_argument("--thread-interval", type=int, default=10, metavar="SEC",
                   help="Seconds between thread dumps (default: 10)")
    p.add_argument("--skip-jfr", action="store_true", help="Don't record JFR")
    p.add_argument("--skip-heap", action="store_true", help="Don't take a heap dump")
    p.add_argument("-o", "--output-dir", default=None,
                   help="Where to write artifacts (default: loadtest/results/diag-<timestamp>)")
    p.add_argument("--keep-remote", action="store_true",
                   help="Leave the capture files in the pod's /tmp (default: delete them after copy)")
    p.add_argument("--dry-run", action="store_true", help="Print what would run and exit")

    # Bare `python capture_diagnostics.py` -> show full help and exit 0, rather than
    # argparse's terse "the following arguments are required: pod" on stderr / exit 2.
    if len(sys.argv) == 1:
        p.print_help()
        sys.exit(0)

    args = p.parse_args()

    duration_s = parse_duration(args.duration)
    do_jfr = not args.skip_jfr
    do_heap = not args.skip_heap
    if not do_jfr and not do_heap and args.thread_dumps <= 0:
        sys.exit("Nothing to do: --skip-jfr and --skip-heap with no --thread-dumps.")

    app_container = args.app_container or derive_app_container(args.pod)
    if not app_container:
        sys.exit(
            f"Can't guess the app container from pod name {args.pod!r} "
            f"(known: {', '.join(KNOWN_SERVICES)}). Pass --app-container."
        )

    ts = dt.datetime.now().strftime("%Y%m%d-%H%M%S")
    out_dir = args.output_dir or os.path.join("loadtest", "results", f"diag-{ts}")
    eph_name = f"jfrcap-{ts}"

    remote_script = build_remote_script(
        duration_s, args.jfr_settings, do_jfr, do_heap, args.heap_all,
        max(0, args.thread_dumps), max(1, args.thread_interval),
    )

    debug_cmd = [
        "kubectl", "debug", args.pod, "-n", args.namespace,
        "--image", args.image,
        "--target", app_container,
        "--profile", "sysadmin",
        "-c", eph_name,
        "-i", "--",
        "bash", "-c", remote_script,
    ]

    print("=== plan ===")
    print(f"  pod            : {args.namespace}/{args.pod}")
    print(f"  app container  : {app_container}")
    print(f"  JFR            : {'yes' if do_jfr else 'no'}"
          + (f" ({duration_s}s, settings={args.jfr_settings})" if do_jfr else ""))
    print(f"  heap dump      : {'yes' if do_heap else 'no'}"
          + (" (-all)" if (do_heap and args.heap_all) else ""))
    print(f"  thread dumps   : {args.thread_dumps}"
          + (f" every {args.thread_interval}s" if args.thread_dumps > 0 else ""))
    print(f"  debug image    : {args.image}")
    print(f"  ephemeral name : {eph_name}")
    print(f"  output dir     : {out_dir}")
    est = duration_s + 90
    print(f"  est. wall time : ~{est}s\n")

    if args.dry_run:
        print("--- remote script ---")
        print(remote_script)
        print("\n--- kubectl debug ---")
        print("$ " + " ".join(debug_cmd[:-1]) + " '<remote script above>'")
        return

    preflight(args.namespace, args.pod, app_container)
    os.makedirs(out_dir, exist_ok=True)

    print(f"\n=== running capture (attaches ephemeral container {eph_name}) ===")
    print("Streaming [cap] progress from inside the pod; this blocks for the whole window.\n")
    # generous buffer over the JFR window for image pull + GC.heap_dump write
    run(debug_cmd, timeout=duration_s + 600)

    print("\n=== copying artifacts out ===")
    copied = []
    if do_jfr:
        dst = os.path.join(out_dir, f"{args.pod}.jfr")
        if copy_out(args.namespace, args.pod, app_container, REMOTE_JFR, dst):
            copied.append(dst)
    if do_heap:
        dst = os.path.join(out_dir, f"{args.pod}.hprof")
        if copy_out(args.namespace, args.pod, app_container, REMOTE_HPROF, dst):
            copied.append(dst)
    for i in range(1, max(0, args.thread_dumps) + 1):
        dst = os.path.join(out_dir, f"{args.pod}-threaddump-{i}.txt")
        if copy_out(args.namespace, args.pod, app_container, f"{REMOTE_TDUMP}-{i}.txt", dst):
            copied.append(dst)

    if not args.keep_remote:
        print("\n=== cleaning up pod /tmp ===")
        run(
            ["kubectl", "exec", args.pod, "-n", args.namespace, "-c", app_container,
             "--", "sh", "-c",
             f"rm -f {REMOTE_JFR} {REMOTE_HPROF} {REMOTE_TDUMP}-*.txt"],
            check=False,
        )

    print("\n=== done ===")
    if not copied:
        print("No artifacts were copied - check the [cap] output above for errors.", file=sys.stderr)
        sys.exit(1)
    for f in copied:
        print(f"  {f}")
    print(
        "\nOpen with:\n"
        "  *.jfr    -> JDK Mission Control (JMC) or VisualVM's JFR plugin\n"
        "  *.hprof  -> Eclipse MAT (Leak Suspects) or VisualVM\n"
        "  *-threaddump-*.txt -> diff consecutive ones / fastThread.io\n"
        f"\nEphemeral container {eph_name} stays in the pod spec until its next rollout - harmless."
    )


if __name__ == "__main__":
    main()
