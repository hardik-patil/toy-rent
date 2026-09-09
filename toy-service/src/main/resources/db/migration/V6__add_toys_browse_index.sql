-- Bottleneck #1 fix (CLAUDE.md "Performance Engineering — Bottlenecks to Find").
--
-- Catalogue browse / search / available all filter on
-- (category, age_group, is_active, status). V1 only created the four columns as
-- SEPARATE single-column indexes, so once the load-test bulk seed inflates
-- `toys` to ~50k rows Postgres BitmapAnds idx_toys_category + idx_toys_age_group
-- (~6k index rows each), rechecks hundreds of heap rows, then top-N sorts. Cheap
-- in isolation (~8ms) but it holds a JDBC connection that much longer per
-- request, and under concurrent browse load that starves the HikariCP pool —
-- surfacing as multi-second HikariDataSource.getConnection waits on *every*
-- endpoint sharing the pool, including the trivial GET /api/v1/toys/{toyId}.
-- See learning/hikari-pool-exhaustion-toys-browse.md.
--
-- IF NOT EXISTS so this is a no-op if the index was already created by hand
-- against a running DB (the recommended hot-fix while a new image ships).
-- Plain CREATE INDEX (not CONCURRENTLY): Flyway runs each migration in a
-- transaction and CONCURRENTLY cannot; on this table size the brief lock is
-- immaterial. For a large live DB, create it CONCURRENTLY out of band first,
-- then this migration no-ops.

CREATE INDEX IF NOT EXISTS idx_toys_browse
    ON toys (category, age_group, is_active, status);
