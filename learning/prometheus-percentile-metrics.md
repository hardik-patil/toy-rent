# Getting Real p90/p95/p99 Into Grafana (Not Just Averages)

## The gotcha

Spring Boot's default Micrometer → Prometheus export for `http_server_requests_seconds`
only gives you `_count`, `_sum`, and `_max` per endpoint:

```
http_server_requests_seconds_count{uri="/api/v1/toys",...} 14.0
http_server_requests_seconds_sum{uri="/api/v1/toys",...} 2.825944876
```

That's enough to compute an **average** (`sum / count`) but there is no `_bucket` series at
all — so `histogram_quantile()` in PromQL has nothing to work with, and any Grafana panel
built assuming real percentiles will just come back empty. Percentile histograms are opt-in
per meter name.

## The fix

`application.yml`, under `management.metrics`:

```yaml
management:
  metrics:
    distribution:
      percentiles-histogram:
        http.server.requests: true
```

The dotted key (`http.server.requests`) is a single literal YAML key, not three nested
levels — this is the standard idiom Spring Boot expects here, and it flattens correctly to
the property `management.metrics.distribution.percentiles-histogram.http.server.requests`.

This is baked into the jar (`src/main/resources/application.yml`), so it needs a full
rebuild + redeploy to take effect — see
[docker-desktop-stale-image-tag.md](docker-desktop-stale-image-tag.md) for the gotcha that
bit us doing exactly this (same tag rebuild silently not picked up by the running pod).
Once it's actually live, `_bucket` series appear:

```
http_server_requests_seconds_bucket{uri="/api/v1/toys",le="0.089478485",} 1.0
http_server_requests_seconds_bucket{uri="/api/v1/toys",le="+Inf",} 3.0
```

## PromQL for Grafana

Single endpoint:
```promql
histogram_quantile(0.95, sum(rate(http_server_requests_seconds_bucket{uri="/api/v1/toys"}[5m])) by (le))
```

All endpoints on one panel — group by `uri` instead of filtering to one, so each endpoint
gets its own line:
```promql
histogram_quantile(0.95, sum(rate(http_server_requests_seconds_bucket[5m])) by (le, uri))
```
Legend field: `{{uri}} - p95` (Grafana's label templating), otherwise every line in the
legend just shows the raw PromQL and is unreadable once there's more than one series.

## Gotcha: empty result right after enabling this

`histogram_quantile(..., rate(..._bucket[5m]))` needs Prometheus to have actually scraped
**at least two data points with real elapsed time and real traffic** inside that 5m window.
Right after a redeploy — zero traffic yet, or the target's only been scraped once — the
query legitimately returns `{"result": []}` even though the config is correct. Don't assume
a broken query; generate a few requests against the endpoint, wait one or two scrape
intervals (`scrape_interval` in Prometheus's config, commonly 15s), and rerun.

## Panel busy-ness

Grouping by `uri` across ~15 endpoints × 4 percentiles (p50/p90/p95/p99) puts up to ~60
lines on one panel. That's a real design choice, not a mistake to fix by default — a busy
"everything at once" panel some people genuinely want for a first pass, using Grafana's
legend click-to-isolate to explore, rather than pre-filtering to a tidier subset.
