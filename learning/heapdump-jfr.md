# Heap Dump & JFR From a Running Pod

## The gotcha specific to this repo

All three services' Dockerfiles (`api-gateway/Dockerfile`, `toy-service/Dockerfile`,
`booking-service/Dockerfile`) are built on **`eclipse-temurin:17-jre-jammy`** — a JRE, not
a JDK. Diagnostic tools like `jcmd`, `jmap`, `jstack` are **JDK-only binaries** and don't
exist in these containers:

```bash
kubectl exec -it <pod> -n toy-rental -- jcmd
# OCI runtime exec failed: exec: "jcmd": executable file not found in $PATH
```

Most generic "how to heap dump a Java pod" guides assume a JDK image and silently don't
apply here. Two real ways around it below — pick based on whether you can afford a pod
restart.

---

## JFR, 15 minutes — no pod restart needed (the on-demand way)

Uses `kubectl debug`'s **ephemeral container** feature: it attaches a temporary container
with a *full JDK* to the target pod, sharing just that one container's process namespace
— nothing in the running app container changes, and no restart happens.

1. Find the target pod and its container name:
   ```bash
   kubectl get pods -n toy-rental -l app.kubernetes.io/name=toy-service
   # e.g. toy-service-58cfb97f84-fnmk5, container name: toy-service
   ```

2. Attach a JDK debug container to it (`--profile=sysadmin` grants `SYS_PTRACE`, which the
   JVM Attach API needs to reach into another container's process):
   ```bash
   kubectl debug -it toy-service-58cfb97f84-fnmk5 -n toy-rental \
     --image=eclipse-temurin:17-jdk-jammy \
     --target=toy-service \
     --profile=sysadmin \
     -- bash
   ```

3. Inside the debug container, find the target JVM's PID (visible because of the shared
   process namespace — with `--target` it may well be PID 1) and the UID it runs as:
   ```bash
   ps -eo pid,user,args | grep '[a]pp.jar'      # PID + user
   awk '/^Uid:/{print $2}' /proc/<PID>/status   # numeric uid, e.g. 1000
   ```

   Cross-container attach here has **two** obstacles, and you hit this
   `AttachNotSupportedException` until both are solved:
   ```
   com.sun.tools.attach.AttachNotSupportedException: Unable to open socket file
   /tmp/.java_pid1: target process 1 doesn't respond within 10500ms or HotSpot VM not loaded
   ```

   **(a) UID.** The debug container is root, but all three services run their JVM as
   `uid 1000` (`USER toyrental` in every Dockerfile). The attach socket is mode `0600`
   and the JVM only honours a trigger file it owns — so `jcmd` must run as that uid.
   `eclipse-temurin:17-jdk-jammy` ships `setpriv`:
   ```bash
   SP="setpriv --reuid=1000 --regid=999 --clear-groups"
   ```

   **(b) Socket path across mount namespaces.** The JVM creates `/tmp/.java_pid1` in
   *its* mount namespace; `jcmd` in the debug container looks in *its own* `/tmp`. JDK 17
   normally falls back to `/proc/<pid>/root/tmp/` — but not when `--target` makes the
   shared pid literally `1` (it thinks same namespace). Prime the listener, then symlink
   the real socket into place:
   ```bash
   SOCK=/proc/1/root/tmp/.java_pid1
   [ -S "$SOCK" ] || { $SP sh -c 'touch /proc/1/root/tmp/.attach_pid1; kill -QUIT 1'; \
                       while [ ! -S "$SOCK" ]; do sleep 0.5; done; }
   ln -sf "$SOCK" /tmp/.java_pid1
   JC="$SP jcmd"          # now attaches
   ```

   `scripts/capture_diagnostics.py` does all of this automatically (reads Uid/Gid from
   `/proc/$PID/status`, primes + symlinks the socket, wraps every `jcmd` in `setpriv`).
   Verified end-to-end: JFR + a 194 MB heap dump captured from a live pod.

4. Start the recording (writes into the **target container's** filesystem, not the debug
   container's):
   ```bash
   $JC <PID> JFR.start duration=15m filename=/tmp/recording.jfr name=perf15
   ```
   Check progress any time with `$JC <PID> JFR.check`, or stop it early with
   `$JC <PID> JFR.stop name=perf15`.

5. Wait for it to finish (or `sleep 900` right there in the debug shell), then `exit` the
   debug container.

6. Copy the file out — note `-c toy-service`, since the file lives in the *original*
   container, not the ephemeral debug one:
   ```bash
   kubectl cp toy-rental/toy-service-58cfb97f84-fnmk5:/tmp/recording.jfr \
     ./recording.jfr -c toy-service
   ```

Open `recording.jfr` in **JDK Mission Control (JMC)** or VisualVM's JFR plugin.

---

## JFR, 15 minutes — the simpler alternative if a restart is acceptable

If you don't need it running *right now* on the current live process, skip `kubectl debug`
entirely — just tell the JVM to record from boot via an env var, no extra tooling needed:

```bash
kubectl set env deployment/toy-service -n toy-rental \
  JAVA_TOOL_OPTIONS="-XX:StartFlightRecording=duration=15m,filename=/tmp/recording.jfr,name=perf15"
```

This triggers a normal rolling restart. Wait 15 minutes, then `kubectl cp` the file out the
same way as step 6 above (no debug container needed — it's a plain file on the running
pod). **Remove the env var afterward**, or every future restart will start a recording:
```bash
kubectl set env deployment/toy-service -n toy-rental JAVA_TOOL_OPTIONS-
```

---

## Heap dump — always needs the `kubectl debug` route

There's no startup-flag equivalent for an on-demand heap dump (only
`-XX:+HeapDumpOnOutOfMemoryError`, which fires *only* on an actual OOM, not on request).
So this always needs `jcmd`/`jmap` via the same ephemeral debug container as above:

1. Same setup as the JFR on-demand method — steps 1–3 (attach debug container, find PID).
2. Trigger the dump:
   ```bash
   jcmd <PID> GC.heap_dump /tmp/heapdump.hprof
   ```
3. `exit` the debug container, then copy it out:
   ```bash
   kubectl cp toy-rental/toy-service-58cfb97f84-fnmk5:/tmp/heapdump.hprof \
     ./heapdump.hprof -c toy-service
   ```

Open `heapdump.hprof` in **Eclipse MAT** or VisualVM.

---

## Watch disk/heap size

- toy-service/booking-service are capped at `memory: 1Gi` (`CLAUDE.md`'s Kubernetes
  resource table) — a heap dump can be roughly that size, and it's written to the
  container's ephemeral storage (`/tmp`), which counts against the pod's ephemeral-storage
  limit if one's set. Copy it out and delete it from the pod promptly.
- A 15-minute JFR recording at default settings is usually much smaller (single-digit to
  tens of MB) unless allocation/GC activity is unusually heavy during the window.
