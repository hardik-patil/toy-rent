# Proving the Missing Composite Index With JMeter

## Why 12 seed rows aren't enough

`toys` ships with only ~12 rows from `V4__seed_sample_toys.sql`. At that volume Postgres's
query planner doesn't care whether a composite index exists — a sequential scan or a
handful of index lookups are equally instant. The bottleneck CLAUDE.md documents (missing
`idx_toys_browse` composite index on `(category, age_group, is_active, status)`) only
becomes *measurable* once there's enough data that merging several single-column indexes
actually costs something.

## Bulk-seeding realistic volume

Ad hoc SQL via `generate_series`, run directly against the live pod — not a Flyway
migration, just load-test data:

```bash
kubectl cp seed_toys_bulk.sql infra/postgres-0:/tmp/seed_toys_bulk.sql
kubectl exec -n infra postgres-0 -- psql -U toyuser -d toydb -f /tmp/seed_toys_bulk.sql
```

50,000 rows, randomized across the real `category`/`age_group`/`status` values already in
use (check these first with `SELECT DISTINCT ... FROM toys` — don't guess, the seed data's
categories are project-specific, e.g. `REMOTE_CONTROL`, `BUILDING_BLOCKS`, etc., not generic
placeholders).

## Reading the EXPLAIN ANALYZE that proves the bottleneck

The real query (`ToyRepository.browse`) filters `active = true AND category = ? AND
age_group = ?` (no `status` in this particular query, despite CLAUDE.md's 4-column index
proposal — check the actual `@Query` annotation, not just the schema doc, before assuming
which columns matter). At 50k rows:

```
Bitmap Heap Scan on toys
  Recheck Cond: (age_group = '9-12' AND category = 'BUILDING_BLOCKS')
  Filter: is_active
  ->  BitmapAnd
        ->  Bitmap Index Scan on idx_toys_age_group  (6367 rows)
        ->  Bitmap Index Scan on idx_toys_category    (5604 rows)
```

The `BitmapAnd` is the smoking gun: two separate single-column indexes each return a large
candidate set, and Postgres has to merge (`AND`) them in memory before it can even apply the
`is_active` filter and fetch heap pages. A composite index on `(category, age_group,
is_active)` collapses this into one direct index scan — no merge step, fewer heap blocks
touched. The effect is small per-query (~13ms at 50k rows) but compounds non-linearly under
concurrency: more CPU per query spent merging bitmaps, more shared-buffer contention.

## The JMeter regression scenario this becomes

1. **Thread Group** — concurrency profile (start small for debugging: 5 threads/5s ramp,
   scale to 100+ threads with a Scheduler duration for the real run).
2. **CSV Data Set Config** — feed `category`/`ageGroup` from a real value pool
   (`loadtest/data/browse_params.csv`), not one hardcoded pair. Hammering a single
   combo just tests Postgres's buffer cache, not the actual scan cost.
3. **HTTP Request** — `GET /api/v1/toys?category=${category}&ageGroup=${ageGroup}`.
4. **Response Assertion** — code 200. Cheap insurance against silently eating auth
   failures (a stale JWT 401 looks identical to a slow response in a summary report
   unless you assert on it).
5. Run once **before** the composite index migration lands, save the `.jtl`/HTML report.
   Run the identical `.jmx` again **after** the fix ships. The diff between those two
   runs — not a single run in isolation — is the actual regression check. Same idea
   applies to every other intentionally-seeded bottleneck in CLAUDE.md's Performance
   Engineering section (cache warming, HikariCP pool size, Kafka partitions, circuit
   breaker) — seed/trigger the condition, measure before, fix, measure after.
