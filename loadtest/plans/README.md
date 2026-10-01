# loadtest/plans/

| File | What |
|---|---|
| `mixed.jmx` | S3 mixed-traffic plan (80/12/8 browse/detail/booking). Smoke-verified. |
| `mixed-build-guide.md` | Element-by-element walkthrough + the 5 gotchas found building it. |
| `month-end-report.jmx` | J7 month-end report (PLAN.md/SLOs.md): concurrent-trigger idempotency check + poll-to-completion + PDF download. Run against real data, see RUN-LOG.md's J7 entry. |

catalogue-browse.jmx (S1) is still at loadtest/ root. New plans for S2 go here.

---

## `month-end-report.jmx` — how to run it

Not a throughput test (SLOs.md: J7 is "1 run/month", peak concurrency n/a) — a
correctness+timing test for `POST /api/v1/admin/reports/trigger`. One `setUp` Thread
Group (admin login), one regular Thread Group (`TRIGGER_THREADS` concurrent trigger
POSTs at the *same* month/year, proving the eventId+Couchbase idempotency check in
CLAUDE.md's Month-End Report Flow really collapses an admin double-click into one
report), and one `tearDown` Thread Group (poll `GET /api/v1/admin/reports` until the
new report reaches a terminal status or `POLL_TIMEOUT_S` elapses, then download its PDF).

**Pick a `MONTH`/`YEAR` with no existing `monthly_reports` row** (`UNIQUE(month, year)` —
a second real generation attempt for the same month would fail the DB constraint, not
silently re-run) and, for a non-empty report, one with real `CONFIRMED` bookings:
```bash
kubectl exec -n infra postgres-0 -- psql -U bookinguser -d bookingdb -c \
  "SELECT date_trunc('month', start_date)::date AS mon, status, count(*) FROM bookings GROUP BY 1,2 ORDER BY 1,2;"
```

```bash
STAMP=$(date +%Y%m%d-%H%M%S)
jmeter -n \
  -t loadtest/plans/month-end-report.jmx \
  -l loadtest/results/j7-$STAMP.jtl \
  -e -o loadtest/results/j7-$STAMP \
  -JMONTH=10 -JYEAR=2026 -JTRIGGER_THREADS=3 -JPOLL_INTERVAL_MS=2000 -JPOLL_TIMEOUT_S=90
```

Cross-check the authoritative duration server-side (per SLOs.md's SLI table — this
plan's own poll timing is only a coarse client-side check, off by up to one
`POLL_INTERVAL_MS`):
```bash
kubectl exec -n infra postgres-0 -- psql -U bookinguser -d bookingdb -c \
  "SELECT id, month, year, status, generated_at - created_at AS gen_duration FROM monthly_reports WHERE month=10 AND year=2026;"
curl -s "http://localhost:9090/api/v1/query?query=pdf_generation_duration_seconds_count"
```

**Race condition found and fixed while building this (2026-09-23):** the trigger POST
returns 202 as soon as the Kafka event is *published*, not once `report-cg` has
*consumed* it and inserted the `GENERATING` row — there's a real gap. The first poll
landing inside that gap gets `content[0]` = whichever *other* report happens to be
newest by `createdAt` (e.g. a prior month already `SUCCESS`), and a naive "take
`content[0]`'s status" loop declares victory instantly on the wrong report. Fixed by
also extracting `content[0]`'s own `month`/`year` and only trusting its `status` when
they match what was triggered — see the `JSR223PostProcessor` comment in the `.jmx`
itself. Confirmed by re-running against a fresh month: the fixed version correctly
polled 3 times across the real ~3s generation window instead of matching a stale row
on the first check.
