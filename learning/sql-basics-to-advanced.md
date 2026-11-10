# SQL — basics to advanced (PostgreSQL, using ToyRental's own data)

One learning path from `SELECT` to query plans, locks and MVCC. Every example runs against
this project's real databases — `toydb` (toy-service) and `bookingdb` (booking-service) —
so you can type each one and see real results instead of reading about made-up tables.

Companion to [db-testing-day-plan.md](db-testing-day-plan.md) (the hands-on testing day) and
[hikari-pool-exhaustion-toys-browse.md](hikari-pool-exhaustion-toys-browse.md) (a real
SQL-performance incident in this project).

**How to use it:** do the levels in order. Each ends with exercises; answers are at the
bottom. Don't read the answers until you've tried.

---

## Setup — where to type SQL

```bash
kubectl exec -it -n infra postgres-0 -- psql -U postgres -d toydb
```
Or DBeaver on `localhost:5433` after `kubectl port-forward -n infra svc/postgres 5433:5432`.

Useful psql commands (not SQL — they start with `\`):

| Command | Does |
|---|---|
| `\c bookingdb` | switch database |
| `\dt` | list tables |
| `\d toys` | describe a table: columns, types, indexes |
| `\di` | list indexes |
| `\timing on` | print how long every query took |
| `\x auto` | show wide rows vertically |
| `\q` | quit |

**Safety rule for this doc:** anything that changes data (`INSERT/UPDATE/DELETE`) is wrapped
in `BEGIN; … ROLLBACK;` so nothing is actually saved. Keep that habit on any shared database.

### The tables you'll use

```
toydb
  toys                  (id, name, brand, category, age_group, condition, status,
                         mrp, weekly_price, monthly_price, deposit_amount, is_active, created_at)
  toy_availability_log  (id, toy_id → toys.id, booking_id, blocked_from, blocked_to, action, reason)

bookingdb
  customers      (id, name, phone UNIQUE, email, area, city, pincode, is_active, created_at)
  bookings       (id, toy_id, customer_id → customers.id, start_date, end_date, rental_type,
                  rental_amount, deposit_amount, total_amount, status, payment_status, created_at)
  payments       (id, booking_id → bookings.id, customer_id, amount, type, method, status)
  notifications  (id, booking_id, customer_id, type, channel, message, status, sent_at)
  monthly_reports(id, month, year, total_bookings, total_revenue, status)
```

**Know the data before you query it** (as of 2026-11-10):
- `toys`: ~50k rows, ~47.5k active, mostly bulk-seeded `Load Test Toy N` rows. 8 categories:
  `BUILDING_BLOCKS, DOLLHOUSES, INFANT_TOYS, OUTDOOR_TOYS, PLAYSETS, PRETEND_PLAY,
  PUZZLES_GAMES, REMOTE_CONTROL`.
- `bookings`: ~2.2k rows, and **only** `CONFIRMED` and `PENDING` statuses, all `WEEKLY`.
  Examples below that filter on `CANCELLED` are correct SQL but return 0 rows here — swap in
  `'PENDING'` to see results. Most revenue sits in September 2026 (load-test bookings), so
  month-over-month numbers look extreme; that's the data, not your query.

Note `bookings.toy_id` has **no** foreign key to `toys` — they live in different databases
(each service owns its own DB). You cannot join across databases in Postgres, which is
exactly why booking-service calls toy-service over HTTP instead.

---

# Level 1 — Basics: reading data

### 1.1 SELECT and FROM

```sql
SELECT * FROM toys LIMIT 5;                         -- every column, 5 rows
SELECT name, brand, weekly_price FROM toys LIMIT 5; -- only the columns you need
```
`SELECT *` is fine for exploring; in application code always name columns — less data over
the wire, and the query doesn't silently change when someone adds a column.

### 1.2 WHERE — filtering rows

```sql
SELECT name, category, weekly_price
FROM toys
WHERE category = 'PRETEND_PLAY' AND weekly_price < 300;
```

Operators you'll use constantly:

| Operator | Example |
|---|---|
| `=`, `<>`, `<`, `>`, `<=`, `>=` | `weekly_price >= 500` |
| `AND`, `OR`, `NOT` | `is_active AND status = 'AVAILABLE'` |
| `IN (...)` | `status IN ('RENTED', 'CLEANING')` |
| `BETWEEN a AND b` (inclusive) | `weekly_price BETWEEN 200 AND 400` |
| `LIKE` / `ILIKE` | `name ILIKE '%lego%'` (`ILIKE` = case-insensitive, Postgres-only) |
| `IS NULL` / `IS NOT NULL` | `brand IS NULL` |

**Trap #1 — NULL:** `brand = NULL` is *never* true, not even for NULL rows. NULL means
"unknown", and unknown = unknown is unknown. Always use `IS NULL`.

**Trap #2 — AND binds tighter than OR:**
```sql
-- WRONG: returns every AVAILABLE toy of any category, plus cheap PRETEND_PLAY toys
WHERE category = 'PRETEND_PLAY' AND weekly_price < 300 OR status = 'AVAILABLE'
-- RIGHT: say what you mean with brackets
WHERE category = 'PRETEND_PLAY' AND (weekly_price < 300 OR status = 'AVAILABLE')
```

### 1.3 ORDER BY and LIMIT / OFFSET

```sql
SELECT name, weekly_price FROM toys
ORDER BY weekly_price DESC, name ASC   -- tie-break on name
LIMIT 10 OFFSET 20;                    -- page 3 of 10-per-page
```
Without `ORDER BY`, row order is **not guaranteed** — even if it looks stable today. Any
paginated query without a unique tie-breaker can show the same row on two pages.

### 1.4 DISTINCT, aliases, expressions

```sql
SELECT DISTINCT category FROM toys ORDER BY category;

SELECT name,
       weekly_price,
       monthly_price,
       weekly_price * 4 - monthly_price AS monthly_saving   -- computed column + alias
FROM toys
WHERE is_active
LIMIT 10;
```

### 1.5 Useful functions

```sql
SELECT upper(name), length(name), left(name, 10),
       coalesce(brand, 'Unbranded') AS brand,     -- first non-NULL value
       round(mrp / 83.0, 2)        AS mrp_usd,
       created_at::date            AS created_day, -- cast with ::
       now() - created_at          AS age          -- dates subtract to an interval
FROM toys LIMIT 5;
```

### Level 1 exercises
1. List the 10 most expensive active toys by `mrp`, showing name, brand and mrp.
2. Find toys whose name contains "lego" (any case) and that cost under ₹500/week.
3. List every distinct `age_group`, sorted.
4. Count how many toys have no brand. *(Peek ahead: `count(*)`)*

---

# Level 2 — Aggregation: summarising data

### 2.1 Aggregate functions

```sql
SELECT count(*)            AS toys,
       count(brand)        AS toys_with_brand,   -- count(col) skips NULLs
       min(weekly_price), max(weekly_price),
       round(avg(weekly_price), 2) AS avg_weekly,
       sum(mrp)            AS inventory_value
FROM toys
WHERE is_active;
```

### 2.2 GROUP BY

One output row per group:
```sql
SELECT category, count(*) AS toys, round(avg(weekly_price), 2) AS avg_weekly
FROM toys
GROUP BY category
ORDER BY toys DESC;
```
**Rule:** every column in `SELECT` must either be in `GROUP BY` or inside an aggregate.
`SELECT category, name, count(*) … GROUP BY category` is an error — which `name` would it
show?

Group by several columns:
```sql
SELECT category, age_group, count(*) FROM toys GROUP BY category, age_group ORDER BY 3 DESC;
```

### 2.3 HAVING — filter *groups*

`WHERE` filters rows *before* grouping; `HAVING` filters groups *after*.
```sql
SELECT category, count(*) AS toys
FROM toys
WHERE is_active               -- row filter
GROUP BY category
HAVING count(*) > 5000        -- group filter
ORDER BY toys DESC;
```

### 2.4 FILTER and CASE — conditional aggregation

```sql
-- \c bookingdb
SELECT rental_type,
       count(*)                                       AS bookings,
       count(*) FILTER (WHERE status = 'CANCELLED')   AS cancelled,
       sum(total_amount) FILTER (WHERE status <> 'CANCELLED') AS revenue,
       sum(CASE WHEN payment_status = 'SUCCESS' THEN 1 ELSE 0 END) AS paid  -- same idea, portable SQL
FROM bookings
GROUP BY rental_type;
```

`CASE` also works outside aggregates, to bucket values:
```sql
-- \c toydb
SELECT CASE WHEN weekly_price < 200 THEN 'budget'
            WHEN weekly_price < 500 THEN 'mid'
            ELSE 'premium' END AS tier,
       count(*)
FROM toys GROUP BY 1 ORDER BY 1;   -- "1" = first SELECT column
```

### 2.5 Logical order of execution

SQL is written in one order but *evaluated* in another. Knowing this explains most
"why can't I use that alias here?" errors:

```
FROM / JOIN  →  WHERE  →  GROUP BY  →  HAVING  →  SELECT  →  DISTINCT  →  ORDER BY  →  LIMIT
```
So a `SELECT` alias can be used in `ORDER BY` (later) but not in `WHERE` (earlier).

### Level 2 exercises
5. In `bookingdb`: bookings per `status`, most common first.
6. Total revenue (`total_amount`) per month of `start_date`. *(Hint: `date_trunc('month', start_date)`)*
7. Categories whose average `mrp` is above ₹3,000.
8. In one query: per `rental_type`, number of bookings and number that are `CONFIRMED`.

---

# Level 3 — Joins: combining tables

All examples here are in `bookingdb` (`\c bookingdb`).

### 3.1 INNER JOIN — only matching rows

```sql
SELECT b.id, c.name, c.phone, b.start_date, b.status
FROM bookings b
JOIN customers c ON c.id = b.customer_id
ORDER BY b.created_at DESC
LIMIT 10;
```
`b` and `c` are table aliases. Always qualify columns (`b.status`) once there's more than
one table — both tables could have a `status`.

### 3.2 LEFT JOIN — keep everything from the left side

Customers *with or without* bookings:
```sql
SELECT c.name, count(b.id) AS bookings     -- count(b.id), not count(*): NULLs aren't counted
FROM customers c
LEFT JOIN bookings b ON b.customer_id = c.id
GROUP BY c.id, c.name
ORDER BY bookings DESC;
```

**The anti-join** — rows on the left with *no* match on the right:
```sql
-- Customers who never booked
SELECT c.id, c.name
FROM customers c
LEFT JOIN bookings b ON b.customer_id = c.id
WHERE b.id IS NULL;
```

**Classic trap:** putting a right-table filter in `WHERE` silently turns a LEFT JOIN back
into an INNER JOIN:
```sql
-- WRONG: customers with no bookings vanish (b.status is NULL for them, NULL = 'CONFIRMED' is not true)
SELECT c.name, count(b.id) FROM customers c
LEFT JOIN bookings b ON b.customer_id = c.id
WHERE b.status = 'CONFIRMED' GROUP BY c.name;

-- RIGHT: put the right-table condition in ON
SELECT c.name, count(b.id) FROM customers c
LEFT JOIN bookings b ON b.customer_id = c.id AND b.status = 'CONFIRMED'
GROUP BY c.name;
```

### 3.3 Other joins

| Join | Returns |
|---|---|
| `RIGHT JOIN` | mirror of LEFT — rarely used, just swap the tables |
| `FULL JOIN` | everything from both sides, NULLs where no match |
| `CROSS JOIN` | every combination (rows × rows) — usually a bug unless intended |
| self-join | a table joined to itself (example below) |

### 3.4 Multi-table join

```sql
SELECT c.name, b.id AS booking, p.type, p.amount, p.status
FROM payments p
JOIN bookings  b ON b.id = p.booking_id
JOIN customers c ON c.id = b.customer_id
WHERE p.status = 'SUCCESS'
LIMIT 20;
```

### 3.5 Self-join — the double-booking check

Join `bookings` to itself to find two bookings of the same toy with overlapping dates —
the exact rule booking-service's `FOR UPDATE` lock protects:
```sql
SELECT a.toy_id, a.id AS booking_a, b.id AS booking_b
FROM bookings a
JOIN bookings b
  ON  a.toy_id = b.toy_id
  AND a.id < b.id                                  -- each pair once, never a row with itself
  AND a.start_date <= b.end_date
  AND a.end_date   >= b.start_date                 -- the standard "ranges overlap" test
WHERE a.status IN ('CONFIRMED', 'ACTIVE')
  AND b.status IN ('CONFIRMED', 'ACTIVE');
```
Should return zero rows. Learn the overlap test by heart: **A overlaps B ⇔ A.start ≤ B.end
AND A.end ≥ B.start.**

### 3.6 Row multiplication (fan-out) — the silent bug

Each booking has ~2 payments (rental + deposit). Join and then `sum` booking columns and
you double-count:
```sql
-- WRONG: total_amount counted once per payment row
SELECT sum(b.total_amount) FROM bookings b JOIN payments p ON p.booking_id = b.id;
-- RIGHT
SELECT sum(total_amount) FROM bookings;
```
Whenever a join's numbers look too big, check whether the join multiplied rows.

### Level 3 exercises
9. Every booking with the customer's name and area, newest 20.
10. Customers who have bookings but **zero** successful payments.
11. Per customer: number of bookings and total spent on non-cancelled bookings, top 10 spenders.
12. Bookings that have **no** notification row at all.

---

# Level 4 — Subqueries and CTEs

### 4.1 Scalar subquery — returns one value

```sql
-- \c toydb
SELECT name, weekly_price
FROM toys
WHERE weekly_price > (SELECT avg(weekly_price) FROM toys)
ORDER BY weekly_price LIMIT 10;
```

### 4.2 IN and EXISTS

```sql
-- \c bookingdb
-- Customers who've had a cancelled booking
SELECT name FROM customers
WHERE id IN (SELECT customer_id FROM bookings WHERE status = 'CANCELLED');

-- Same with EXISTS: stops at the first match per customer
SELECT name FROM customers c
WHERE EXISTS (SELECT 1 FROM bookings b
              WHERE b.customer_id = c.id AND b.status = 'CANCELLED');
```

**Trap — `NOT IN` with NULLs:** if the subquery returns even one NULL, `NOT IN` returns
*no rows at all*. Prefer `NOT EXISTS` for "not in" logic:
```sql
SELECT name FROM customers c
WHERE NOT EXISTS (SELECT 1 FROM bookings b WHERE b.customer_id = c.id);
```

### 4.3 Correlated subquery

A subquery that references the outer row runs (logically) once per outer row:
```sql
SELECT c.name,
       (SELECT max(b.start_date) FROM bookings b WHERE b.customer_id = c.id) AS last_booking
FROM customers c
LIMIT 10;
```
Readable, but on big tables a JOIN + GROUP BY is often faster. Check with `EXPLAIN`
(Level 7).

### 4.4 CTEs — `WITH`, for readable step-by-step queries

```sql
WITH spend AS (
    SELECT customer_id, sum(total_amount) AS total
    FROM bookings
    WHERE status <> 'CANCELLED'
    GROUP BY customer_id
),
ranked AS (
    SELECT customer_id, total,
           CASE WHEN total >= 10000 THEN 'GOLD'
                WHEN total >= 3000  THEN 'SILVER'
                ELSE 'BRONZE' END AS tier
    FROM spend
)
SELECT tier, count(*) AS customers, round(avg(total), 2) AS avg_spend
FROM ranked
GROUP BY tier
ORDER BY avg_spend DESC;
```
Read it top to bottom like a pipeline. In Postgres 12+ simple CTEs are inlined into the
main query, so they cost nothing over a subquery.

### Level 4 exercises
13. Toys (`toydb`) whose `mrp` is above the average `mrp` of **their own category**.
14. Using a CTE: monthly booking counts, then only months with more bookings than the overall monthly average.
15. Customers whose most recent booking was cancelled.

---

# Level 5 — Window functions

The single biggest jump from "can write SQL" to "can analyse with SQL". A window function
computes over a set of related rows **without collapsing them** like GROUP BY does.

```
function(...) OVER (PARTITION BY <groups> ORDER BY <order> [frame])
```

### 5.1 Ranking

```sql
-- \c toydb  — the 3 most expensive toys in each category
SELECT * FROM (
    SELECT category, name, mrp,
           row_number() OVER (PARTITION BY category ORDER BY mrp DESC) AS rn
    FROM toys WHERE is_active
) t
WHERE rn <= 3
ORDER BY category, rn;
```
`row_number` → 1,2,3,4 always. `rank` → 1,2,2,4 (ties share, then skip). `dense_rank` → 1,2,2,3.

(You can't use `rn` in the inner `WHERE` — window functions are computed at the `SELECT`
step, after `WHERE`. Hence the wrapping subquery.)

### 5.2 Aggregates as windows — totals without losing rows

```sql
SELECT category, name, mrp,
       round(avg(mrp) OVER (PARTITION BY category), 2)        AS category_avg,
       round(100.0 * mrp / sum(mrp) OVER (PARTITION BY category), 3) AS pct_of_category
FROM toys LIMIT 20;
```

### 5.3 Running totals and moving averages

```sql
-- \c bookingdb  — daily revenue with a running total and 7-day moving average
WITH daily AS (
    SELECT start_date AS day, sum(total_amount) AS revenue
    FROM bookings WHERE status <> 'CANCELLED'
    GROUP BY start_date
)
SELECT day, revenue,
       sum(revenue) OVER (ORDER BY day)                                   AS running_total,
       round(avg(revenue) OVER (ORDER BY day ROWS BETWEEN 6 PRECEDING AND CURRENT ROW), 2) AS ma_7d
FROM daily
ORDER BY day;
```

### 5.4 LAG / LEAD — compare with previous or next row

```sql
-- Days between a customer's consecutive bookings
SELECT customer_id, start_date,
       start_date - lag(start_date) OVER (PARTITION BY customer_id ORDER BY start_date) AS days_since_prev
FROM bookings
ORDER BY customer_id, start_date
LIMIT 30;
```

### 5.5 Percentiles — what you report in perf tests

```sql
SELECT percentile_cont(0.50) WITHIN GROUP (ORDER BY total_amount) AS p50,
       percentile_cont(0.95) WITHIN GROUP (ORDER BY total_amount) AS p95,
       percentile_cont(0.99) WITHIN GROUP (ORDER BY total_amount) AS p99
FROM bookings;
```
The same p50/p95/p99 you read off JMeter and Prometheus — here computed over any column.
Load a JMeter `.jtl` into a table and this is how you'd compute latency percentiles in SQL.

### Level 5 exercises
16. For each customer, their first booking only (use `row_number`).
17. Monthly revenue and the % change from the previous month.
18. In `toydb`: rank categories by number of toys with `dense_rank`.

---

# Level 6 — Changing data, constraints, transactions

### 6.1 INSERT, UPDATE, DELETE — always rehearse inside a transaction

```sql
-- \c toydb
BEGIN;

INSERT INTO toys (id, name, category, age_group, mrp, weekly_price, monthly_price, deposit_amount)
VALUES ('toy-sqltest1', 'SQL Practice Truck', 'REMOTE_CONTROL', '3-6', 999, 99, 299, 500)
RETURNING id, status, is_active, created_at;   -- RETURNING shows defaults the DB filled in

UPDATE toys SET weekly_price = weekly_price * 1.10
WHERE id = 'toy-sqltest1'
RETURNING name, weekly_price;

DELETE FROM toys WHERE id = 'toy-sqltest1';

ROLLBACK;   -- nothing above was saved
```
**Before any UPDATE/DELETE, run the same `WHERE` as a `SELECT count(*)` first.** An
UPDATE without a WHERE changes every row.

### 6.2 Upsert — INSERT … ON CONFLICT

Insert, or do something else if the row already exists. This project's Kafka idempotency
(`processed_events`) is this pattern:
```sql
BEGIN;
INSERT INTO monthly_reports (id, month, year, status)
VALUES ('rpt-sqltest', 1, 2099, 'GENERATING')
ON CONFLICT (month, year) DO UPDATE SET status = EXCLUDED.status   -- EXCLUDED = the row you tried to insert
RETURNING *;
ROLLBACK;
```
(`\c bookingdb` first.) `ON CONFLICT DO NOTHING` is the "already processed → skip" version.

### 6.3 Constraints — the database protecting itself

```sql
-- \c bookingdb
BEGIN;
INSERT INTO customers (id, name, phone, password_hash)
SELECT 'cust-dup', 'Duplicate', phone, 'x' FROM customers LIMIT 1;
-- ERROR: duplicate key value violates unique constraint "customers_phone_key"
ROLLBACK;
```

| Constraint | Guarantees |
|---|---|
| `PRIMARY KEY` | unique + not null; one per table |
| `UNIQUE` | no duplicates (NULLs allowed, and don't clash with each other) |
| `NOT NULL` | value required |
| `FOREIGN KEY` (`REFERENCES`) | value must exist in the parent table |
| `CHECK` | arbitrary rule, e.g. `CHECK (end_date >= start_date)` |
| `DEFAULT` | value used when none is given |

Constraints are the last line of defence — application validation can be bypassed, the
database's can't.

### 6.4 Transactions and ACID

```sql
BEGIN;
  -- several statements
COMMIT;     -- all of them happen
-- or
ROLLBACK;   -- none of them happen
```
- **A**tomicity — all or nothing.
- **C**onsistency — constraints hold before and after.
- **I**solation — concurrent transactions don't see each other's half-finished work.
- **D**urability — once COMMIT returns, it survives a crash (written to the WAL).

`SAVEPOINT` lets you roll back part of a transaction:
```sql
BEGIN;
UPDATE customers SET area = 'Vashi' WHERE id = (SELECT id FROM customers LIMIT 1);
SAVEPOINT s1;
UPDATE customers SET area = 'Oops';           -- no WHERE: every row!
ROLLBACK TO s1;                                -- undo just that
ROLLBACK;                                      -- and undo the rest, for this exercise
```

### Level 6 exercises
19. Inside BEGIN/ROLLBACK: insert a customer, insert a booking for them, then show both with a join.
20. Try to insert a booking with a `customer_id` that doesn't exist. What error, and which constraint?
21. Write an upsert on `customers` keyed on `phone` that updates `area` if the phone already exists.

---

# Level 7 — Indexes and query plans (the performance core)

### 7.1 What an index is

A B-tree index is a sorted copy of one or more columns, with pointers to the table rows —
like a book's index. Without it, Postgres reads every row (**Seq Scan**). With it, it jumps
straight to matching rows (**Index Scan**).

```sql
-- \c toydb
\di                         -- list indexes
\d toys                     -- indexes are listed at the bottom
```

Costs of indexes: disk space, and **every INSERT/UPDATE/DELETE must update every index**.
Don't index everything — index what your `WHERE`, `JOIN` and `ORDER BY` actually use.

### 7.2 EXPLAIN and EXPLAIN ANALYZE

```sql
EXPLAIN SELECT * FROM toys WHERE category = 'PRETEND_PLAY';           -- the plan, not run
EXPLAIN (ANALYZE, BUFFERS) SELECT * FROM toys WHERE category = 'PRETEND_PLAY';  -- runs it, real numbers
```
`EXPLAIN ANALYZE` **executes the query** — wrap an `UPDATE`/`DELETE` in `BEGIN … ROLLBACK`
before analysing it.

Reading one plan line:
```
Bitmap Heap Scan on toys  (cost=52.31..1840.10 rows=5012 width=180) (actual time=0.9..6.2 rows=5021 loops=1)
                           ^ estimate: startup..total cost        ^ reality
  Buffers: shared hit=1203 read=12
```
- **Plans are trees, read inside-out** — the most indented node runs first.
- `cost` is in abstract units, only useful for comparing plans.
- `rows=` estimated vs `actual … rows=` real — a large mismatch means the planner is
  guessing wrong (often stale stats → `ANALYZE toys;`).
- `loops=` — multiply actual time by loops for the node's true total.
- `shared hit` = found in memory, `read` = fetched from disk.

### 7.3 Scan and join types you'll see

| Node | Means |
|---|---|
| Seq Scan | reads the whole table — fine for small tables or when most rows match |
| Index Scan | walks the index, fetches each matching row |
| Index Only Scan | every needed column is in the index — no table visit at all |
| Bitmap Index/Heap Scan | collects matches from the index first, then reads table pages in order — common for medium-sized result sets |
| Nested Loop | for each outer row, look up inner rows — great when outer side is small and inner is indexed |
| Hash Join | builds a hash table from one side — good for large unsorted joins |
| Merge Join | both sides sorted, walked together |
| Sort / Limit | watch for `Sort Method: external merge Disk` — sort spilled out of `work_mem` |

### 7.4 Composite indexes — column order matters

This project's real fix (V6): `idx_toys_browse ON toys (category, age_group, is_active, status)`.

A composite index works left to right, like a phone book sorted by surname then first name:

| Query filters on | Can use `(category, age_group, …)`? |
|---|---|
| `category` | yes |
| `category AND age_group` | yes — best case |
| `age_group` only | not efficiently — the leading column is missing |

Compare with and without it (session-only settings, app unaffected):
```sql
EXPLAIN (ANALYZE, BUFFERS)
SELECT * FROM toys WHERE is_active AND category = 'PRETEND_PLAY' AND age_group = '3-6'
ORDER BY name LIMIT 20;

SET enable_indexscan = off; SET enable_bitmapscan = off;
-- run the same EXPLAIN again and compare Execution Time
RESET ALL;
```

### 7.5 Things that stop an index being used

```sql
WHERE lower(name) = 'lego'        -- function on the column: a plain index on name can't help
                                   -- fix: expression index  CREATE INDEX ON toys (lower(name));
WHERE name LIKE '%lego%'          -- leading wildcard: B-tree can't help (pg_trgm GIN index can)
WHERE name LIKE 'lego%'           -- trailing wildcard only: B-tree CAN help (with text_pattern_ops)
WHERE weekly_price::text = '99'   -- cast on the column
WHERE category = 'X' OR brand = 'Y'  -- OR across different columns: often a Seq Scan or BitmapOr
```
toy-service's search query uses `LOWER(name) LIKE '%q%'` — run Level 7's EXPLAIN on it and
you'll see the Seq Scan for yourself.

### 7.6 Partial and covering indexes

```sql
-- Partial: index only the rows queries care about (this project already has one)
--   CREATE INDEX idx_bookings_end_date ON bookings(end_date) WHERE status = 'ACTIVE';
-- Covering: INCLUDE extra columns so the query can be an Index Only Scan
--   CREATE INDEX ON toys (category) INCLUDE (name, weekly_price);
```
(Don't create indexes on this project's DBs by hand — schema changes go through a new
Flyway migration file, per CLAUDE.md.)

### 7.7 Pagination: OFFSET vs keyset

```sql
-- OFFSET: Postgres still reads and throws away 40,000 rows
SELECT id, name FROM toys ORDER BY name, id LIMIT 20 OFFSET 40000;

-- Keyset ("seek"): start after the last row of the previous page — stays fast at any depth
SELECT id, name FROM toys
WHERE (name, id) > ('Last Name On Prev Page', 'toy-lastid')
ORDER BY name, id LIMIT 20;
```
Time both with `\timing on`.

### Level 7 exercises
22. EXPLAIN ANALYZE the browse query for a category + age_group. Which scan type? How many buffers hit vs read?
23. EXPLAIN ANALYZE `SELECT * FROM toys WHERE lower(brand) LIKE '%lego%'`. Explain the plan.
24. In `bookingdb`, EXPLAIN ANALYZE "all bookings for one customer". Which index is used, and why does it exist?
25. Time `OFFSET 0`, `OFFSET 20000`, `OFFSET 45000` for the same ordered query. Describe the trend.

---

# Level 8 — Concurrency: isolation, locks, MVCC

### 8.1 MVCC — how Postgres lets readers and writers not block each other

Postgres never updates a row in place. An `UPDATE` writes a **new row version** and marks
the old one dead; each transaction sees the versions that were committed when its
snapshot was taken. Results:
- **Readers never block writers, writers never block readers.**
- Old versions pile up as **dead tuples** → `VACUUM` (autovacuum) cleans them.
- A table that's heavily updated and never vacuumed **bloats** and gets slower.

```sql
SELECT relname, n_live_tup, n_dead_tup, last_autovacuum
FROM pg_stat_user_tables ORDER BY n_dead_tup DESC;
```

### 8.2 Isolation levels

| Level | Prevents | Postgres default? |
|---|---|---|
| Read Committed | dirty reads — each *statement* sees data committed before it started | **yes** |
| Repeatable Read | also non-repeatable reads — whole *transaction* sees one snapshot | |
| Serializable | also anomalies like write skew — as if transactions ran one at a time; may abort with a serialization error you must retry | |

See Read Committed with two sessions:

| Session 1 | Session 2 |
|---|---|
| `BEGIN;` | |
| `SELECT area FROM customers WHERE id='<id>';` → `Vashi` | |
| | `UPDATE customers SET area='Nerul' WHERE id='<id>';` (auto-commits) |
| `SELECT area FROM customers WHERE id='<id>';` → **`Nerul`** — changed mid-transaction | |
| `ROLLBACK;` | |

Repeat with `BEGIN ISOLATION LEVEL REPEATABLE READ;` in Session 1 — the second SELECT
still says `Vashi`. (Set the area back afterwards.)

### 8.3 Row locks — SELECT … FOR UPDATE

Writers *do* block other writers on the same row. `FOR UPDATE` takes that write lock
during a read — "I'm reading this to change it, nobody else touch it". booking-service's
overlap check uses it so two people can't book the same toy for the same dates:

| Session 1 | Session 2 |
|---|---|
| `BEGIN;` | |
| `SELECT * FROM bookings WHERE toy_id='<id>' FOR UPDATE;` | |
| | `BEGIN;` `SELECT … same … FOR UPDATE;` → **waits** |
| `COMMIT;` | → continues |

Variants: `FOR UPDATE NOWAIT` (error immediately instead of waiting), `FOR UPDATE SKIP
LOCKED` (skip locked rows — the standard way to build a job queue in a table).

### 8.4 Seeing locks and who's blocking whom

```sql
SELECT pid, state, wait_event_type, wait_event,
       now() - query_start AS running_for, left(query, 60) AS query
FROM pg_stat_activity WHERE datname = current_database();

SELECT pid, pg_blocking_pids(pid) AS blocked_by, left(query, 60)
FROM pg_stat_activity WHERE cardinality(pg_blocking_pids(pid)) > 0;
```
Kill a stuck session: `SELECT pg_cancel_backend(<pid>);` (cancel its query) or
`pg_terminate_backend(<pid>)` (drop the connection). Not on production without a reason.

### 8.5 Deadlocks

Session 1 locks row A then wants B; Session 2 locks B then wants A — each waits for the
other forever. Postgres detects it after `deadlock_timeout` (1s) and aborts one. The
standard prevention: **always lock rows in the same order** (e.g. by id).

### Level 8 exercises
26. Reproduce the FOR UPDATE wait, and capture the blocked session in `pg_stat_activity`.
27. Reproduce a deadlock and copy the exact error message.
28. Show the difference between Read Committed and Repeatable Read with two sessions.

---

# Level 9 — Advanced toolbox

### 9.1 Recursive CTE — generate series and walk hierarchies

```sql
-- \c bookingdb — every day in December, with bookings that start on it (including zero days)
SELECT d::date AS day, count(b.id) AS bookings_starting
FROM generate_series(DATE '2026-12-01', DATE '2026-12-31', INTERVAL '1 day') AS d
LEFT JOIN bookings b ON b.start_date = d::date
GROUP BY d ORDER BY d;
```
`generate_series` fills gaps — a GROUP BY alone would just skip days with no bookings.

A true recursive CTE (counts down from 5) — the pattern used for org charts, category trees:
```sql
WITH RECURSIVE n(x) AS (
    SELECT 5            -- anchor
    UNION ALL
    SELECT x - 1 FROM n WHERE x > 1   -- recursive step
)
SELECT x FROM n;
```

### 9.2 LATERAL — "for each row, run this subquery"

```sql
-- Each customer's 2 most recent bookings
SELECT c.name, recent.start_date, recent.status
FROM customers c
CROSS JOIN LATERAL (
    SELECT start_date, status FROM bookings b
    WHERE b.customer_id = c.id
    ORDER BY start_date DESC LIMIT 2
) recent
LIMIT 20;
```

### 9.3 Set operations

```sql
SELECT customer_id FROM bookings WHERE status = 'CANCELLED'
INTERSECT                                    -- in both
SELECT customer_id FROM bookings WHERE status = 'CONFIRMED';
-- also UNION (dedupe), UNION ALL (keep duplicates — faster), EXCEPT (in first, not second)
```

### 9.4 JSON in Postgres

```sql
SELECT json_build_object('id', id, 'name', name, 'price', weekly_price) FROM toys LIMIT 3;   -- \c toydb
SELECT category, json_agg(name ORDER BY mrp DESC) FILTER (WHERE mrp > 9000) FROM toys GROUP BY category;
```
`jsonb` columns support indexing (GIN) and operators like `->`, `->>`, `@>`. This project
keeps JSON documents in Couchbase instead, but Postgres can do a lot of the same.

### 9.5 Views and materialized views

```sql
CREATE VIEW v_active_toys AS SELECT * FROM toys WHERE is_active;          -- saved query, always live
CREATE MATERIALIZED VIEW mv_category_stats AS
    SELECT category, count(*), avg(mrp) FROM toys GROUP BY category;       -- stored result, fast to read
REFRESH MATERIALIZED VIEW mv_category_stats;                               -- recompute when needed
```
(Read only here — creating objects on these DBs belongs in a Flyway migration.)

### 9.6 Partitioning (concept)

A huge table (e.g. years of `bookings`) can be split by range — one partition per month.
Queries filtered on the partition key only touch relevant partitions ("partition
pruning"), and old months can be dropped instantly instead of a slow DELETE. Worth it
from tens of millions of rows, not at this project's current size.

### 9.7 Database-level health queries

```sql
-- Biggest tables and their indexes
SELECT relname, pg_size_pretty(pg_total_relation_size(relid)) AS total
FROM pg_stat_user_tables ORDER BY pg_total_relation_size(relid) DESC;

-- Indexes never used since stats reset — candidates to drop
SELECT relname, indexrelname, idx_scan FROM pg_stat_user_indexes WHERE idx_scan = 0;

-- Tables read mostly by Seq Scan — candidates for an index
SELECT relname, seq_scan, idx_scan FROM pg_stat_user_tables ORDER BY seq_scan DESC;

-- Cache hit ratio — should be > 99% on a healthy OLTP DB
SELECT round(100.0 * sum(blks_hit) / nullif(sum(blks_hit) + sum(blks_read), 0), 2) AS cache_hit_pct
FROM pg_stat_database;

-- Connections by state
SELECT state, count(*) FROM pg_stat_activity GROUP BY state;
```
And once `pg_stat_statements` is enabled (see [db-testing-day-plan.md](db-testing-day-plan.md)):
```sql
SELECT calls, round(total_exec_time) AS total_ms, round(mean_exec_time, 2) AS mean_ms, left(query, 80)
FROM pg_stat_statements ORDER BY total_exec_time DESC LIMIT 10;
```
This is the single most useful query for DB performance work: which queries consume the
most time overall, not just which one is slowest once.

---

# SQL for a performance engineer — the checklist

When a request is slow and the database is suspected:

1. **Is it the DB at all?** Compare app latency with DB time (APM trace / Hikari
   `acquire` time). A long `getConnection` is a *pool* problem, not a slow query — that
   was exactly the case in [hikari-pool-exhaustion-toys-browse.md](hikari-pool-exhaustion-toys-browse.md).
2. **Which query?** `pg_stat_statements` top-by-total-time, or the slow-query log.
3. **Why is it slow?** `EXPLAIN (ANALYZE, BUFFERS)` — scan type, estimate vs actual, disk reads, sorts spilling.
4. **Is it waiting rather than working?** `pg_stat_activity.wait_event` — `Lock` waits are contention, not slowness.
5. **Is the server itself saturated?** CPU / IO / connections / cache hit ratio (postgres-exporter in Grafana).
6. **Fix one thing, re-measure under the same load.** Index, query rewrite, pool size, or schema — never all at once.

---

# Answers

<details><summary>Level 1</summary>

```sql
-- 1
SELECT name, brand, mrp FROM toys WHERE is_active ORDER BY mrp DESC LIMIT 10;
-- 2
SELECT name, weekly_price FROM toys WHERE name ILIKE '%lego%' AND weekly_price < 500;
-- 3
SELECT DISTINCT age_group FROM toys ORDER BY age_group;
-- 4
SELECT count(*) FROM toys WHERE brand IS NULL;
```
</details>

<details><summary>Level 2</summary>

```sql
-- 5
SELECT status, count(*) FROM bookings GROUP BY status ORDER BY count(*) DESC;
-- 6
SELECT date_trunc('month', start_date)::date AS month, sum(total_amount)
FROM bookings GROUP BY 1 ORDER BY 1;
-- 7
SELECT category, round(avg(mrp), 2) FROM toys GROUP BY category HAVING avg(mrp) > 3000;
-- 8
SELECT rental_type, count(*), count(*) FILTER (WHERE status = 'CONFIRMED') AS confirmed
FROM bookings GROUP BY rental_type;
```
</details>

<details><summary>Level 3</summary>

```sql
-- 9
SELECT b.id, c.name, c.area, b.start_date, b.status
FROM bookings b JOIN customers c ON c.id = b.customer_id
ORDER BY b.created_at DESC LIMIT 20;
-- 10
SELECT DISTINCT c.id, c.name
FROM customers c JOIN bookings b ON b.customer_id = c.id
WHERE NOT EXISTS (SELECT 1 FROM payments p
                  WHERE p.customer_id = c.id AND p.status = 'SUCCESS');
-- 11
SELECT c.name, count(b.id) AS bookings, sum(b.total_amount) AS spent
FROM customers c JOIN bookings b ON b.customer_id = c.id
WHERE b.status <> 'CANCELLED'
GROUP BY c.id, c.name ORDER BY spent DESC LIMIT 10;
-- 12
SELECT b.id FROM bookings b
LEFT JOIN notifications n ON n.booking_id = b.id
WHERE n.id IS NULL;
```
</details>

<details><summary>Level 4</summary>

```sql
-- 13 (correlated subquery)
SELECT name, category, mrp FROM toys t
WHERE mrp > (SELECT avg(mrp) FROM toys t2 WHERE t2.category = t.category);
-- 14
WITH monthly AS (
  SELECT date_trunc('month', start_date) AS m, count(*) AS n FROM bookings GROUP BY 1
)
SELECT m::date, n FROM monthly WHERE n > (SELECT avg(n) FROM monthly) ORDER BY m;
-- 15
WITH last AS (
  SELECT DISTINCT ON (customer_id) customer_id, status
  FROM bookings ORDER BY customer_id, created_at DESC     -- DISTINCT ON: Postgres "first row per group"
)
SELECT c.name FROM last JOIN customers c ON c.id = last.customer_id
WHERE last.status = 'CANCELLED';
```
</details>

<details><summary>Level 5</summary>

```sql
-- 16
SELECT * FROM (
  SELECT b.*, row_number() OVER (PARTITION BY customer_id ORDER BY start_date) AS rn FROM bookings b
) x WHERE rn = 1;
-- 17
WITH m AS (
  SELECT date_trunc('month', start_date) AS month, sum(total_amount) AS revenue
  FROM bookings WHERE status <> 'CANCELLED' GROUP BY 1
)
SELECT month::date, revenue,
       round(100.0 * (revenue - lag(revenue) OVER (ORDER BY month)) / lag(revenue) OVER (ORDER BY month), 1) AS pct_change
FROM m ORDER BY month;
-- 18
SELECT category, count(*), dense_rank() OVER (ORDER BY count(*) DESC) FROM toys GROUP BY category;
```
</details>

<details><summary>Level 6</summary>

```sql
-- 19
BEGIN;
INSERT INTO customers (id, name, phone, password_hash) VALUES ('cust-sqlt', 'Test User', '9000000001', 'x');
INSERT INTO bookings (id, toy_id, customer_id, start_date, end_date, rental_type,
                      rental_amount, deposit_amount, total_amount)
VALUES ('bkg-sqlt', 'toy-any', 'cust-sqlt', DATE '2026-12-01', DATE '2026-12-07', 'WEEKLY', 199, 500, 699);
SELECT c.name, b.id, b.status FROM bookings b JOIN customers c ON c.id = b.customer_id WHERE c.id = 'cust-sqlt';
ROLLBACK;
-- 20: ERROR: insert or update on table "bookings" violates foreign key constraint
--     "bookings_customer_id_fkey" — Key (customer_id)=(...) is not present in table "customers".
-- 21
BEGIN;
INSERT INTO customers (id, name, phone, password_hash, area)
VALUES ('cust-ups', 'Upsert Test', (SELECT phone FROM customers LIMIT 1), 'x', 'Kharghar')
ON CONFLICT (phone) DO UPDATE SET area = EXCLUDED.area
RETURNING id, phone, area;      -- returns the EXISTING customer's id, with area changed
ROLLBACK;
```
</details>

<details><summary>Level 7 — what you should see</summary>

- **22:** Index or Bitmap scan on `idx_toys_browse`; mostly `shared hit` once warm.
- **23:** Seq Scan with a `Filter:` line — `lower()` plus a leading `%` means no B-tree index can help; every row is checked.
- **24:** `idx_bookings_customer_id` — it exists because the "my bookings" endpoint filters on `customer_id`.
- **25:** Time grows roughly linearly with OFFSET — the skipped rows are still read and sorted. Keyset pagination fixes this.
</details>

<details><summary>Level 8 — what you should see</summary>

- **26:** Session 2 shows `wait_event_type = Lock`, `wait_event = transactionid`, and `pg_blocking_pids` lists Session 1's pid.
- **27:** `ERROR: deadlock detected` / `DETAIL: Process X waits for ShareLock on transaction Y; blocked by process Z …`
- **28:** Read Committed sees the other session's committed change on the next statement; Repeatable Read keeps the first snapshot until COMMIT/ROLLBACK.
</details>
