# Docker Desktop K8s Silently Running a Stale Image After a Rebuild

## The gotcha

Rebuilding a Docker image with the **same tag** (`docker build -t toyrental/toy-service:1.0.5 .`)
and then `kubectl rollout restart deployment/toy-service` does **not** guarantee the new pod
runs the new bits — even though Docker Desktop's Kubernetes node is supposed to share the
same image store as the `docker` CLI.

`imagePullPolicy: IfNotPresent` means: if *any* image already resolves to that exact
`name:tag` locally, skip pulling and just use it. In practice this node kept serving an
old cached image under `toyrental/toy-service:1.0.5` well after a fresh `docker build`
retagged that name to a different image ID locally — `kubectl rollout restart` just spins
up a new pod from whatever the tag currently resolves to *in the node's view*, which isn't
necessarily what `docker images` shows on the host.

## How to actually detect this happened

Don't trust "the rollout succeeded" or "the pod restarted" as proof the new image is
running. Compare digests:

```bash
# What's tagged locally right now
docker inspect toyrental/toy-service:1.0.5 --format '{{.Id}}'

# What the running pod actually pulled
kubectl get pod -n toy-rental <pod-name> \
  -o jsonpath='{.status.containerStatuses[0].imageID}'
```

If these don't match, the pod is running stale bits, full stop — no amount of
`rollout restart` will fix it while the tag stays the same.

## The fix

Bump the tag. Don't fight `IfNotPresent` caching — give the node a `name:tag` it has never
seen so it's forced to resolve the image you just built:

```bash
docker build -t toyrental/toy-service:1.0.6 .
kubectl set image deployment/toy-service toy-service=toyrental/toy-service:1.0.6 -n toy-rental
kubectl rollout status deployment/toy-service -n toy-rental
```

Remember to bump the tag everywhere it's hardcoded, not just the live cluster — this repo
has it in three places: `k8s/services/<service>/<service>.yaml`, `helm/values.yaml`
(`image.tag`), and whatever your build/CI script passes to `docker build -t`. Updating only
the live Deployment via `kubectl set image` fixes *this* cluster but drifts from the
manifests — the next `kubectl apply -f k8s/services/...` or `helm upgrade` would silently
revert to the old tag.

## Side note: port-forwards die on every pod replacement

Any `kubectl port-forward -n <ns> svc/<name> ...` you have running breaks the moment its
target pod terminates — it does **not** transparently follow the Service to the new pod,
even though you forwarded to the Service, not the Pod, directly. Expect to see
`error: lost connection to pod` in its log after any `rollout restart`, `set image`, or
scale-down/up cycle, and re-run the `port-forward` command afterward. If the old process is
still holding the local port when you try to restart it (`address already in use` even
though the pod is gone), it's a zombie from the dead connection — `pkill -f "port-forward
-n <ns> svc/<name>"` before retrying.
