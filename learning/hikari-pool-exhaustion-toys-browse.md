# HikariCP pool exhaustion — `getConnection` on `GET /api/v1/toys/{toyId}`

A New Relic distributed trace showed `GET /api/v1/toys/{toyId}` taking **3.43 s**, of
which **3.18 s was `com.zaxxer.hikari.HikariDataSource.getConnection`**. This is the
write-up of what that means, why it happened, and the fix.

Companion to [perf-analysis-login-bottleneck.md](perf-analysis-login-bottleneck.md) and
[perf-analysis-booking-latency.md](perf-analysis-booking-latency.md); this one is
toy-service / catalogue-read side.

---

## The trace

```
Servlet DispatcherServlet/service .......................... 3.40 s
  ToyController.getById ................................... 3.39 s
    HikariDataSource.getConnection ....................... 3.18 s   ← flagged slow
    (uninstrumented / actual SQL + mapping) ............... ~0.21 s
```

`ToyController.getById` → `ToyService.getById` is:

```java
@Transactional(readOnly = true)
public ToyResponse getById(String toyId) {
    Toy toy = toyRepository.findByIdAndActiveTrue(toyId)   // WHERE id = ? AND is_active = true
            .orElseThrow(() -> new ToyNotFoundException(toyId));
    return toResponse(toy);                                 // + WHERE toy_id = ? on toy_images
}
```

Two indexed lookups (`toys_pkey`, `idx_toy_images_toy_id`). `EXPLAIN` says sub-millisecond.
**The method is not slow. It spent 3.18 s waiting for a pooled JDBC connection to become
free.** Every endpoint on toy-service shares one HikariCP pool; when it's drained, even a
primary-key read queues.

---

## Evidence — `GET /actuator/prometheus` on toy-service

| metric | value (lifetime) | reading |
|---|---|---|
| `hikaricp_connections_max` | **20** | pool ceiling |
| `hikaricp_connections_min` | 5 | pre-warmed idle |
| `hikaricp_connections_usage_seconds` sum/count | **≈ 0.32 s** mean | how long each request *holds* a connection |
| `hikaricp_connections_acquire_seconds` sum/count | **≈ 1.6 s** mean | queue-wait for a connection |
| `hikaricp_connections_creation_seconds` sum/count | **≈ 0.28 s** mean | cost to open a new connection (pool growth) |
| `hikaricp_connections_timeout_total` | 0 | nothing hit the 30 s `connection-timeout` — it just crawled |
| `jdbc_query_seconds` sum/count | ≈ 0.077 s mean | per-statement, cumulative under past load |

**Little's law:** sustainable throughput without queueing ≈ `pool / mean_hold` =
`20 / 0.32` ≈ **60 req/s**. The browse-heavy JMeter mix pushes past that, so the pool
saturates and `getConnection` queues climb into seconds.

At rest (`kubectl top` idle) the same metrics read: usage ~8 ms, acquire ~0 ms, pool
size 5. The pathology only appears under concurrency.

---

## Why each connection is held ~0.32 s (not ~8 ms)

Two compounding causes:

### 1. Missing composite index — CLAUDE.md bottleneck #1

`toys` is **~50,125 rows** (50 k `toy-bulk-*` from `loadtest/seed_toys_bulk.sql`). Browse,
search and `/available` all filter `WHERE category = ? AND age_group = ? AND is_active AND
status = ?`. V1 created those columns only as **four separate single-column indexes**.
`EXPLAIN (ANALYZE, BUFFERS)` of a representative browse query:

```
Limit → Sort (top-N, key: name)
  → Bitmap Heap Scan on toys  (rows=491, Rows Removed by Filter: 274, Heap Blocks: 576)
      Recheck Cond: age_group='9-12' AND category='BUILDING_BLOCKS'
      Filter: is_active AND status='AVAILABLE'
      → BitmapAnd
          → Bitmap Index Scan idx_toys_age_group  (rows=6190)
          → Bitmap Index Scan idx_toys_category    (rows=6219)
   actual time ≈ 7.6 ms, shared hit=591     (warm cache, unloaded)
```

7.6 ms sounds fine — but it AND-s two ~6 k-row bitmaps, rechecks ~765 heap rows across 576
blocks, and throws 274 away. Under load the buffer cache churns (`shared hit` becomes
`shared read`), the sort spills, and this stretches to tens of ms — each of which is a
connection held that much longer. A single index on
`(category, age_group, is_active, status)` collapses this to one range scan of ~490 rows.

### 2. The JVM is CPU-throttled

The node worker was measured at **400 %+ CPU** (of ~600 % on the 6-core WSL VM) during the
trace — zipkin was crash-looping from the same contention. toy-service runs at a 1000 m
CPU limit; under node pressure it's throttled hard, so connection checkout, statement
parse, `ResultSet` → entity mapping and transaction commit all run 5–10× slower than the
isolated numbers. This multiplies the hold time, which multiplies the queue.

---

## Fixes applied

| # | change | file |
|---|---|---|
| 1 | `CREATE INDEX IF NOT EXISTS idx_toys_browse ON toys (category, age_group, is_active, status)` | `toy-service/src/main/resources/db/migration/V6__add_toys_browse_index.sql` (new) |
| 2 | Hikari `maximum-pool-size` 20 → **30**, `minimum-idle` 5 → **10** | `toy-service/src/main/resources/application.yml` |
| 3 | Postgres `max_connections` 100 → **200** (headroom for the bigger pools × replicas) | `k8s/infra/postgres/postgres.yaml`, `docker-compose.yml` |

`minimum-idle` was raised alongside the max because opening a connection costs ~280 ms
here — with only 5 warm, a spike from 5→30 serialises ~7 s of connection creation while
requests queue. 10 warm covers a normal burst; the pool still grows to 30 under sustained
load.

Not touched: booking-service's pool is already 30 (its `application.yml`), and its
`getById` path wasn't in the trace.

### Order of operations

1. **Postgres first.** `max_connections` is not reloadable — apply the manifest and
   restart the pod (below). Do this before the app pools grow, or the extra connections
   get `FATAL: sorry, too many clients already`.
2. **Rebuild + redeploy toy-service.** Both the V6 migration and the pool config are baked
   into the jar. Flyway runs V6 on boot; the new Hikari settings take effect on start.
3. **Immediate hot-fix, no rebuild:** create the index by hand right now —
   ```sql
   CREATE INDEX CONCURRENTLY idx_toys_browse ON toys (category, age_group, is_active, status);
   ```
   `CONCURRENTLY` doesn't lock out writers; V6's `IF NOT EXISTS` then no-ops on the next
   deploy.

### Confirming it worked

Re-run the browse-heavy plan (`loadtest/catalogue-browse.jmx` or `ToyRentalMixed*.jmx`)
and watch, on `GET /actuator/prometheus`:

- `hikaricp_connections_pending` stays ≈ 0 (was climbing into the tens)
- `hikaricp_connections_acquire_seconds` mean drops toward single-digit ms
- the `HikariDataSource.getConnection` span in New Relic shrinks to noise
- `EXPLAIN` of a browse query now shows `Index Scan using idx_toys_browse`, no `BitmapAnd`

---

## How to raise `max_connections` on the K8s cluster

`max_connections` is a **server-start parameter** (`context: postmaster`) — it cannot be
`SET` or `pg_reload_conf()`'d at runtime. Any method needs a Postgres **restart**.

### Method A — `-c` flag in the StatefulSet (what this repo does)

The `postgres:15` image's entrypoint appends `args:` to the `postgres` command, and
command-line `-c` flags override `postgresql.conf`.

```yaml
# k8s/infra/postgres/postgres.yaml → StatefulSet → containers[0]
args: ["-c", "timezone=Asia/Kolkata", "-c", "max_connections=200"]
```

Apply and restart:

```bash
kubectl apply -f k8s/infra/postgres/postgres.yaml
kubectl rollout restart statefulset/postgres -n infra
kubectl rollout status  statefulset/postgres -n infra      # waits for postgres-0 Ready
```

The `postgres-data` PVC persists, so no data loss. Anything with a live
`kubectl port-forward` to postgres will drop and needs re-running (the pod is recreated).

### Method B — `ALTER SYSTEM` (persists independently of the manifest)

```bash
kubectl exec -n infra postgres-0 -- psql -U postgres -c "ALTER SYSTEM SET max_connections = 200;"
kubectl rollout restart statefulset/postgres -n infra
```

Writes `postgresql.auto.conf` inside the data dir (on the PVC), so it survives image and
`args` changes. Downside: it's invisible in the repo — prefer Method A here, and keep the
two in sync if you ever use B.

### Method C — full `postgresql.conf` via ConfigMap

Mount a ConfigMap at `/etc/postgresql/postgresql.conf` and start with
`-c config_file=/etc/postgresql/postgresql.conf`. Only worth it once you're tuning many
parameters (`shared_buffers`, `work_mem`, `effective_cache_size`, …); overkill for one
setting.

### Verify

```bash
kubectl exec -n infra postgres-0 -- psql -U postgres -c "SHOW max_connections;"
# live headroom:
kubectl exec -n infra postgres-0 -- psql -U postgres -c \
  "SELECT count(*) used, current_setting('max_connections')::int AS limit FROM pg_stat_activity;"
```

### Capacity math — how big does it need to be?

```
required ≈ Σ over services ( hikari.maximum-pool-size × HPA maxReplicas )
        + superuser_reserved_connections (default 3)
        + slack for exporters / psql / DBeaver / Flyway (~10–15)
```

For this project at HPA max:

| service | pool | maxReplicas | worst-case |
|---|---|---|---|
| toy-service | 30 | 8 | 240 |
| booking-service | 30 | 8 | 240 |

That's 480 — far past 200. In practice the HPA rarely pins every replica, and 200 covers
the realistic 2–4 replicas each plus overhead. If you genuinely load to HPA max, the right
answer is **not** `max_connections=600` (each backend is a real process, ~2–10 MB RSS,
plus lock-table and snapshot overhead that scales with the number of backends) — it's a
connection pooler (**PgBouncer** in `transaction` mode) between the apps and Postgres, so
hundreds of app-side pool slots multiplex onto a few dozen real backends.

### Memory caveat

The `postgres` container here has **no memory limit**, so 200 is safe. If you add one,
budget `shared_buffers` (default 128 MB) + ~(2–10 MB × max_connections) + OS cache, and
raise the limit accordingly — or a scale-up will OOM-kill `postgres-0` under load, which
looks exactly like a crash-loop.

---

## One-line summary

`GET /toys/{toyId}` was slow not because of its own query but because the 20-slot HikariCP
pool was drained by slow, unindexed browse queries against a 50 k-row table on a
CPU-throttled JVM; fixed with the `idx_toys_browse` composite index (bottleneck #1), a
30/10 pool (bottleneck #3), and `max_connections=200` to back the bigger pool.
