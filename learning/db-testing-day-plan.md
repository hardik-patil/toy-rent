# Database testing — day plan (ToyRental Postgres)

A hands-on, one-day plan covering database testing basics against this project's real
Postgres: schema/data sizing, reading query plans, locks, connections, a pgbench capacity
curve, observing the DB under JMeter load, and data-integrity checks.

Results go in a separate write-up — [db-testing-basics.md](db-testing-basics.md) — once done.
Companion to [hikari-pool-exhaustion-toys-browse.md](hikari-pool-exhaustion-toys-browse.md)
and [composite-index-load-testing.md](composite-index-load-testing.md).

---

## Starting point (checked 2026-11-10)

| | |
|---|---|
| Postgres | 15.19, `max_connections=200`, `shared_buffers=128MB` |
| `toydb` | `toys` 50,125 · `toy_availability_log` 2,104 · `toy_images` 0 |
| `bookingdb` | `customers` 598 · `bookings` 2,178 · `payments` 4,356 · `notifications` 2,104 · `monthly_reports` 5 |
| Tooling | No `psql`/`pgbench` on Windows — both exist **inside the `postgres-0` pod**. DBeaver available. |
| Gaps | `pg_stat_statements` **not loaded** (`shared_preload_libraries` empty); slow-query log **off** (`log_min_duration_statement = -1`) |

7 blocks, roughly an hour each. Each block has one thing to deliver, so stopping partway
still leaves you with something.

---

## Block 0 — Connect (15 min)

```bash
kubectl port-forward -n infra svc/postgres 5433:5432 &
```
- **DBeaver:** `localhost:5433`, user `postgres`. One connection to `toydb`, one to `bookingdb`.
- **psql shell** (the fallback you'll use most):
  ```bash
  kubectl exec -it -n infra postgres-0 -- psql -U postgres -d toydb
  ```
  Run `\timing on` first — every query then prints its time.

If a connection hangs, re-run the port-forward first: it dies whenever the pod is
recreated (see [port-forwarding.md](port-forwarding.md)).

---

## Block 1 — Know your data (45 min)

You can't judge whether a query is slow until you know how big the tables are and what
the data looks like.

```sql
-- Table + index sizes
SELECT relname,
       pg_size_pretty(pg_table_size(relid))   AS table_size,
       pg_size_pretty(pg_indexes_size(relid)) AS index_size,
       n_live_tup, n_dead_tup, last_autovacuum, last_autoanalyze
FROM pg_stat_user_tables ORDER BY pg_total_relation_size(relid) DESC;

-- Every index and whether it's ever been used
SELECT relname, indexrelname, idx_scan, pg_size_pretty(pg_relation_size(indexrelid))
FROM pg_stat_user_indexes ORDER BY idx_scan;

-- Data distribution: skew is what makes the planner pick different plans
SELECT category, age_group, count(*) FROM toys GROUP BY 1,2 ORDER BY 3 DESC;
SELECT status, count(*) FROM toys GROUP BY 1;
SELECT is_active, count(*) FROM toys GROUP BY 1;
```
Run the size and index queries on `bookingdb` too.

**Write down:** the biggest table, any index with `idx_scan = 0` (an unused index costs
every write), and the dead-tuple ratio.

---

## Block 2 — Read query plans (1 hr) — the core skill

These are the app's real queries, from `ToyRepository.java` / `BookingRepository.java`.

**A. Catalogue browse** (the hottest path):
```sql
EXPLAIN (ANALYZE, BUFFERS)
SELECT * FROM toys
WHERE is_active = true AND category = 'PRETEND_PLAY' AND age_group = '3-6'
ORDER BY name LIMIT 20 OFFSET 0;
```
Prove what `idx_toys_browse` (V6) is worth — these settings only change your own session,
the app is unaffected:
```sql
SET enable_indexscan = off; SET enable_bitmapscan = off;
-- re-run the same EXPLAIN, compare
RESET ALL;
```

**B. Deep pagination:** re-run A with `OFFSET 0`, `OFFSET 10000`, `OFFSET 40000`. Time
climbs, because Postgres reads and throws away every skipped row.

**C. Search with a leading wildcard:**
```sql
EXPLAIN (ANALYZE, BUFFERS)
SELECT * FROM toys WHERE is_active = true
AND (lower(name) LIKE '%lego%' OR lower(brand) LIKE '%lego%') LIMIT 20;
```
Expect a **Seq Scan** — a B-tree index can't serve `LIKE '%x%'`. Log it as a finding;
don't fix it today.

**D. Booking overlap check** (`bookingdb`; runs with `FOR UPDATE` on every booking):
```sql
EXPLAIN (ANALYZE, BUFFERS)
SELECT * FROM bookings
WHERE toy_id = (SELECT toy_id FROM bookings LIMIT 1)
  AND status IN ('PENDING','CONFIRMED','ACTIVE')
  AND start_date <= DATE '2026-12-07' AND end_date >= DATE '2026-12-01';
```

**How to read every plan:**
- **Scan type:** Seq Scan, Index Scan, Bitmap Heap Scan or Index Only Scan.
- **Estimates:** `rows=` estimated vs `actual rows=`. A big gap means stale statistics —
  run `ANALYZE toys;` and look again.
- **Buffers:** `shared hit` (memory) vs `read` (disk).
- **Timing:** `Execution Time` vs `Planning Time`.

**Deliverable:** a table of query → plan type → execution time → takeaway, for A–D.

---

## Block 3 — Locks and concurrency (1 hr)

Tests the most important rule in the booking flow: the pessimistic lock. Open **two**
psql sessions on `bookingdb`, side by side.

| Session 1 | Session 2 |
|---|---|
| `BEGIN;` | |
| `SELECT * FROM bookings WHERE toy_id='<id>' FOR UPDATE;` | |
| | `BEGIN;` |
| | same `SELECT ... FOR UPDATE;` → **hangs** |
| `COMMIT;` | → returns immediately |

While Session 2 hangs, look at the wait from a **third** session:
```sql
SELECT pid, state, wait_event_type, wait_event, now()-query_start AS waiting, left(query,60)
FROM pg_stat_activity WHERE datname='bookingdb';

SELECT pid, pg_blocking_pids(pid) AS blocked_by, left(query,60)
FROM pg_stat_activity WHERE cardinality(pg_blocking_pids(pid)) > 0;
```

**Bonus — a deadlock:** lock row A in Session 1 and row B in Session 2, then have each
lock the other's row. Postgres kills one after `deadlock_timeout` (1s) — read the error.

Then `SET lock_timeout = '2s';` in Session 2 and repeat: it errors instead of waiting forever.

**Deliverable:** be able to explain why two customers booking the same toy for the same
dates can't both succeed.

---

## Block 4 — Connections (30 min)

```sql
SELECT datname, usename, application_name, state, count(*)
FROM pg_stat_activity GROUP BY 1,2,3,4 ORDER BY 5 DESC;
```

**Do the math:** each service's Hikari pool is 30 (`minimum-idle` 10). 2 replicas × 2
services = up to 120 connections at peak, plus exporters and DBeaver. HPA can scale each
service to 8 replicas → 480 against `max_connections = 200`. Note it as a finding.

**In Prometheus** (`localhost:9090`): `hikaricp_connections_active`,
`hikaricp_connections_pending`, `hikaricp_connections_acquire_seconds`. `pending` is the
one that matters — the same signal that caught bottleneck #1.

---

## Block 5 — Benchmark the database alone with pgbench (1 hr)

Postgres **alone** — no JVM, Hikari or network in the way — gives the ceiling to compare
JMeter numbers against.

Create the script inside the pod:
```bash
kubectl exec -it -n infra postgres-0 -- bash
cat > /tmp/browse.sql <<'EOF'
\set off random(0, 500)
SELECT * FROM toys WHERE is_active = true AND category = 'PRETEND_PLAY'
AND age_group = '3-6' ORDER BY name LIMIT 20 OFFSET :off;
EOF
```
Step up the load, 60 s per run:
```bash
for c in 1 5 10 20 40 80; do
  echo "== clients=$c"
  pgbench -n -U postgres -d toydb -f /tmp/browse.sql -c $c -j 4 -T 60 -P 10 | grep -E "tps|latency"
done
```

**Plot TPS and average latency against clients.** Find the **knee** — where TPS stops
climbing but latency keeps rising. That's Postgres saturated.

While it runs, watch `docker stats` and Grafana's postgres-exporter panels.

**Deliverable:** a capacity curve, plus a sentence like "Postgres alone does X tps at
Y ms; beyond Z clients it only queues."

---

## Block 6 — Watch the database under real app load (45 min)

Run `loadtest/catalogue-browse.jmx` at moderate load. While it runs, every minute or so:
```sql
SELECT state, wait_event_type, wait_event, count(*)
FROM pg_stat_activity WHERE datname='toydb' GROUP BY 1,2,3;
```
- **Many `active`, `wait_event = NULL`:** Postgres busy on CPU.
- **Many `idle`:** Postgres isn't the bottleneck — look at the app / Hikari.
- **`Lock` waits:** contention.

Compare against Block 5. If JMeter tops out far below pgbench's ceiling, the bottleneck
is above the database.

---

## Block 7 — Data integrity checks (30 min) — functional DB testing

```sql
-- bookingdb: any CONFIRMED/ACTIVE double-bookings? (must be 0)
SELECT a.toy_id, a.id, b.id FROM bookings a JOIN bookings b
  ON a.toy_id=b.toy_id AND a.id<b.id
 AND a.status IN ('CONFIRMED','ACTIVE') AND b.status IN ('CONFIRMED','ACTIVE')
 AND a.start_date<=b.end_date AND a.end_date>=b.start_date;

-- confirmed bookings without a successful payment
SELECT b.id FROM bookings b WHERE b.status='CONFIRMED'
AND NOT EXISTS (SELECT 1 FROM payments p WHERE p.booking_id=b.id AND p.status='SUCCESS');

-- totals that don't add up
SELECT id FROM bookings WHERE total_amount <> rental_amount + deposit_amount;

-- invalid date ranges
SELECT id FROM bookings WHERE end_date < start_date;
```
Also try inserting a duplicate customer `phone` inside `BEGIN; … ROLLBACK;` and confirm
the `UNIQUE` constraint rejects it.

---

## Optional — turn on the two missing observability tools

Both change Postgres config, so decide first:
- **Slow-query log (no restart):**
  `ALTER SYSTEM SET log_min_duration_statement = '200ms'; SELECT pg_reload_conf();`
  then `kubectl logs -n infra postgres-0 -f`.
- **`pg_stat_statements`:** top queries by total time across all traffic — the most useful
  tool for this work. Needs `shared_preload_libraries` in the Postgres manifest and a pod
  restart. Do it before Block 6 if you want top queries under load.

---

## End of day

Write results up as [db-testing-basics.md](db-testing-basics.md):
- the Block 2 plan table
- the Block 5 capacity curve
- the lock walkthrough
- findings: leading-wildcard search, deep `OFFSET`, HPA × pool connection math
