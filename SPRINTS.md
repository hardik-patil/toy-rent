# ToyRental Platform — Sprint Tracker

Mirrors and expands on the "Sprint Progress Tracker" table in `CLAUDE.md`. That
table is the source of truth for status counts (keep both in sync when a sprint
closes); this file breaks each sprint into a story-level checklist so it's clear
exactly what's done and what's next.

Story lists for S2–S9 are inferred from the components, endpoints, and flows
CLAUDE.md documents for each area — they aren't literally enumerated in
CLAUDE.md, so treat them as a working checklist to refine as each sprint starts,
not a locked contract.

For the detailed step-by-step build record behind the checked items, see
`PROGRESS.md`.

---

## Status Overview

| Sprint | Status | Stories |
|---|---|---|
| S1 — Infrastructure | ✅ Complete | 6/6 |
| S2 — Toy Service | ✅ Complete | 9/9 |
| S3 — Booking Service | ✅ Complete | 10/10 |
| S4 — Kafka Pipeline | ✅ Complete | 10/10 |
| S5 — Month-End Report | ✅ Complete | 8/8 |
| S6 — Observability | ✅ Complete | 7/7 |
| S7 — Performance Eng | ⏭️ Skipped (user doing manually) | 0/8 |
| S8 — Kubernetes | ✅ Complete | 7/7 |
| S9 — React Frontend | 🔶 In progress | 3/6 |

---

## S1 — Infrastructure ✅ 6/6 (2026-08-21)

- [x] `docker-compose.yml` with all infra services (postgres, couchbase, kafka,
      redis, minio, keycloak, wiremock, prometheus, grafana)
- [x] WireMock stubs (razorpay order + verify, whatsapp send)
- [x] api-gateway skeleton (pom, `application.yml`, `GatewayConfig`,
      `CorrelationIdFilter`, `SecurityConfig`)
- [x] toy-service skeleton (pom, `application.yml`, Flyway V1–V4)
- [x] booking-service skeleton (pom, `application.yml`, Flyway V1–V6)
- [x] Local build verification (`mvn compile` + `test-compile`, all 3 services,
      JDK 17)

**Outstanding infra setup (not tracked as formal stories, needed before S2/S3
can run end-to-end):**
- [ ] Create Couchbase buckets (`toy-availability`, `logical-date`,
      `monthly-reports`) via the web console after first `docker compose up`
- [ ] Import a `toyrental` Keycloak realm (container currently runs empty
      dev-mode `admin/admin` with no realm)
- [ ] Run the actual test suites against live infra (only compiled so far)

---

## S2 — Toy Service ✅ 9/9 (2026-08-21)

- [x] `Toy` entity + `ToyRepository` + request/response DTOs
- [x] `ToyController` (catalogue, detail, search, categories) — browse-available
      lives on `AvailabilityController` instead (`GET /api/v1/toys/available`)
- [x] `AvailabilityController` + `AvailabilityService` (Couchbase-backed)
- [x] `AdminToyController` (add/update/soft-delete, inventory, low-stock,
      condition update, image upload)
- [x] `LogicalDateService` (Couchbase `logical-date::current`) — **deviation**:
      in-process 60s TTL cache instead of Redis, since toy-service declares no
      Redis dependency in the approved stack (only api-gateway does); falls
      back to `LocalDate.now()` if Couchbase is unreachable
- [x] `BookingEventConsumer` (Kafka: `booking.confirmed`/`booking.cancelled` →
      update Couchbase availability + `toy_availability_log`), plus
      `InternalToyController` for booking-service's pre-booking toy lookup and
      a manual availability-override endpoint
- [x] Couchbase config + `ToyAvailabilityDocument` / `LogicalDateDocument`
- [x] `GlobalExceptionHandler` + `ToyNotFoundException` /
      `ToyNotAvailableException`
- [x] Unit tests (`ToyControllerTest`, `AvailabilityServiceTest`,
      `BookingEventConsumerTest`) — 15 tests, all passing

**Bugs found and fixed while completing/validating this sprint** (see
`PROGRESS.md` Session 2 for full detail): `LogicalDateService` compile error
(direct field access instead of Lombok getters — service never compiled
before this), `AvailabilityService` used `LocalDate.now()` directly, a
`SecurityConfig` matcher bug that silently `permitAll()`'d every HTTP verb on
`/api/v1/toys/**` instead of just GET (bypassing admin protection), Couchbase's
default JSON serializer lacking `JavaTimeModule` (broke every document
read/write with `LocalDate`/`Instant` fields), a missing Kafka JSON default-type
mapping, a non-executable `docker/postgres-init/init-databases.sh` (so
`toydb`/`bookingdb` were never created), and `bitnami/kafka:3.7` having been
pulled from Docker Hub (swapped for `apache/kafka:3.7.2`).

Validated end-to-end against live Postgres/Couchbase/Kafka: catalogue browse,
availability checks, admin auth enforcement (401 without JWT), a real
`booking.confirmed` event blocking a toy's dates, idempotent replay (no
duplicate rows), `booking.cancelled` releasing the block, and
browse-available correctly excluding a booked toy.

## S3 — Booking Service ✅ 10/10 (2026-08-22)

- [x] `Customer` entity/repository/DTOs + register/login — **deviation**:
      self-issued RSA JWT (`NimbusJwtEncoder`/`Decoder`, already transitively
      on the classpath via the approved oauth2-resource-server starter) rather
      than delegating to Keycloak, since no `toyrental` realm is provisioned
      yet and `customers.password_hash` is already the real credential store.
      User-approved decision; admin/staff auth can still move to Keycloak later.
- [x] `CustomerController` (me, update profile/address, my-bookings)
- [x] `Booking` entity/repository/DTOs
- [x] `BookingController` + booking flow (availability check, pessimistic lock,
      create/detail/receipt/cancel/extend)
- [x] `ToyServiceClient` (Feign) integration with toy-service
- [x] `PaymentController` + `PaymentService` (WireMock Razorpay order/verify,
      webhook handling)
- [x] `AdminBookingController` (today's deliveries/pickups, overdue, return,
      manual confirm)
- [x] `NotificationService` (WhatsApp send via WireMock) — built standalone in
      S3, wired to a real trigger (Kafka consumer) in S4
- [x] Resilience4j — **deliberately not wired** onto the Razorpay call path.
      CLAUDE.md's Performance Engineering section lists this as an
      intentional Sprint 7 bottleneck ("no circuit breaker on WireMock
      Razorpay call initially... Fix: add Resilience4j CB after storm proven
      in JMeter"), not a Sprint 3 deliverable — the config already sits in
      `application.yml`, dormant until Sprint 7.
- [x] Unit tests (`BookingControllerTest`, `BookingServiceTest`,
      `CustomerServiceTest`) — 19 tests, all passing

**Real bugs found and fixed via live validation** (full detail in
`PROGRESS.md` Session 3): a `VARCHAR(36)` id-column overflow from combining a
prefix with a full UUID (customer registration crashed on a real Postgres
error), `@CreationTimestamp` reading back `null` for two independent
reasons (flush timing, then a `merge()`-vs-`persist()` return-value bug),
and a payment webhook that could leave one booking's payments `SUCCESS`
while its own status stayed `PENDING` because WireMock's Razorpay stub
returns the same static order id for every order.

Validated end-to-end: register → login → JWT round-trip, the full
booking → payment → webhook → confirm → PDF receipt → cancel cycle, a 409 on
double-booking, and 401 enforcement on customer/admin routes.

## S4 — Kafka Pipeline ✅ 10/10 (2026-08-22)

- [x] Provision topics with correct partitions + DLTs — **deviation**: pinned
      at 1 partition per topic, not the 3/6 shown in CLAUDE.md's topic
      reference table. That table is the Sprint 7 target state; the
      Performance Engineering section lists "1 partition per topic
      initially... Fix: increase partitions after lag proven in Grafana" as
      an intentional bottleneck, the same way S1 left the composite index
      and Hikari pool size unfixed. 14 topics total (7 + their `.DLT` pairs).
- [x] Shared event envelope + correlationId propagation — added the
      `CorrelationIdFilter` toy-service was still missing (booking-service
      has had one since S3); verified via the log pattern prefix, not just
      the response header.
- [x] `BookingEventProducer` (`booking.confirmed`, `booking.cancelled`,
      `booking.overdue`) — confirmed/cancelled were pulled forward into S2/S3
      since the critical booking flow needed them immediately.
- [x] `PaymentEventConsumer` (booking-service internal, `payment.success`/
      `.failed`) — supplementary audit trail alongside the synchronous
      webhook confirmation already built in S3, not a replacement for it.
- [x] `BookingEventConsumer` idempotency (eventId check) in toy-service —
      done in S2.
- [x] Notification consumer path — `BookingNotificationConsumer`,
      booking-service's own consumption of its `booking.confirmed`/
      `cancelled`/`overdue` topics under consumer group `notification-cg`,
      wiring the previously-standalone `NotificationService` to real sends.
- [x] Overdue detection job → `booking.overdue` — `OverdueDetectionService`,
      `@Scheduled`, flips `ACTIVE` bookings past `end_date` to `OVERDUE`.
- [x] `MonthEndTriggerConsumer` skeleton (idempotency + a `GENERATING`
      placeholder in Postgres and Couchbase) — full implementation is S5.
- [x] DLT / error-handling consumers — `KafkaConsumerConfig` mirrored into
      booking-service (was toy-service-only before).
- [x] Kafka integration tests — one real embedded-broker test
      (`PaymentEventConsumerIntegrationTest`), plus 13 Mockito-based
      consumer/service tests.

**Serious incident, found and fixed during live validation**: booking-service's
first-ever consumer group started at `auto-offset-reset: earliest` and hit a
leftover headerless test message from S2's manual Kafka testing. With no
`ErrorHandlingDeserializer` wrapping the deserializer, that failure happened
at Kafka's poll loop, completely bypassing retry/backoff — the consumer spun
at CPU speed and produced a 19GB log file, driving this machine's disk to
100% capacity in under a minute before being caught and killed. Fixed by
wrapping both services' Kafka value deserializers in
`ErrorHandlingDeserializer`; re-verified stable under a tight monitor.

## S5 — Month-End Report ✅ 8/8 (2026-08-22)

- [x] `AdminReportController` (trigger, list, detail, PDF download) — trigger
      was built in S4; list/detail/PDF-download added now that ReportService
      produces real data.
- [x] `ReportService` — aggregates bookings whose `start_date` falls in the
      target month and that actually materialized (`CONFIRMED`/`ACTIVE`/
      `RETURNED`/`OVERDUE`, excluding `PENDING`/`CANCELLED`): total bookings,
      total revenue, total deposits, pending returns, top toy (name resolved
      via a live Feign call to toy-service), revenue by week.
- [x] `PdfGeneratorService` (iText 8.0.3) — extended with
      `generateMonthlyReportPdf`, a second `pdf.generation.duration` Timer
      tagged `type=monthly_report`.
- [x] MinIO upload integration — `MinioService`, `reports/{yyyy}/{MM}/
      monthly-report-{yyyy}-{MM}.pdf`, bucket auto-created if missing.
- [x] `MonthlyReportDocument` + `CouchbaseReportRepository` — extended the S4
      skeleton to the full CLAUDE.md-documented shape (`topToy`,
      `revenueByWeek`, `pdfStoragePath`).
- [x] Full `MonthEndTriggerConsumer` implementation + idempotency — aggregate
      → generate PDF → upload to MinIO → `SUCCESS` (or `FAILED`, caught and
      recorded rather than left stuck `GENERATING`) in both Postgres and
      Couchbase.
- [x] Publish `monthly.report.generated` — `MonthlyReportGeneratedProducer`,
      keyed by `month-year`; no consumers yet (CLAUDE.md's topic table marks
      this one "(future)").
- [x] Unit/integration tests — `ReportServiceTest`,
      `MonthEndTriggerConsumerTest` (rewritten for the full flow, including a
      report-generation-failure case), `AdminReportControllerTest` (the
      first controller test in this codebase to actually import
      `SecurityConfig` and assert `ROLE_ADMIN` enforcement, not just "no
      token" — a real gap in the existing test pattern, closed here). 8 new
      tests, all passing.

Validated end-to-end against live Postgres/Couchbase/Kafka/MinIO: three real
bookings across two toys in one month, a Kafka-triggered report matching
hand-computed totals exactly (including a leftover `OVERDUE` booking from S4
correctly counted), the PDF genuinely present in MinIO, the Couchbase
document matching CLAUDE.md's example shape field-for-field, and both
idempotency paths (same eventId; different eventId, same month/year)
independently confirmed with no duplicate rows.

## S6 — Observability ✅ 7/7 (2026-08-22)

- [x] Wire the custom Prometheus metrics into code (cache hit/miss, booking
      counters, payment counters, PDF generation timer, report counter) —
      also fixed a real Sprint 3 gap found while doing this: `payment.success.
      total`/`payment.failed.total` in `PaymentService` were built as plain
      untagged `Counter`s despite CLAUDE.md requiring `.tag("method", ...)`/
      `.tag("reason", ...)`. Switched from constructor-built fixed counters to
      inline `Counter.builder(...).tag(...).register(meterRegistry)` at each
      call site (Micrometer's `register()` is idempotent by name+tags, so this
      is safe for dynamic tag values). Confirmed live post-fix:
      `payment_success_total{method="UPI"}` and
      `payment_failed_total{reason="NO_PENDING_PAYMENT_FOUND"}` both showing
      real labels via `/actuator/prometheus`, not just asserted in a test.
- [x] Grafana dashboard JSON (provisioned into `grafana/dashboards`) — also
      found the dashboards folder + provisioning config didn't exist on disk
      at all (dashboard JSON alone in a mounted volume never appears in
      Grafana's UI without a provisioning config for both the dashboard
      provider and a datasource). Added `grafana/provisioning/{datasources,
      dashboards}` and mounted it in `docker-compose.yml`. Verified live:
      `/api/datasources` shows Prometheus auto-provisioned, `/api/search`
      shows "ToyRental Platform — Overview" (uid `toyrental-overview`)
      auto-loaded, 11 panels covering services-up, HTTP rates/5xx, cache hit
      ratio, booking/payment/report counters, PDF duration, JVM heap, Kafka
      lag.
- [x] Verify correlationId end-to-end across HTTP + Kafka + logs — also found
      and fixed a real gap: `OverdueDetectionService`'s `@Scheduled` job had
      no MDC correlationId set (no incoming HTTP request to derive one from),
      so every log line and every `booking.overdue` event it published showed
      `correlationId= ` (confirmed empty in Sprint 4's actual log output).
      Fixed by generating `"corr-overdue-" + UUID.randomUUID()` and setting it
      via `MDC.put(...)` in try/finally around the scheduled method. Verified
      live end-to-end with a real booking+webhook flow using a custom
      `X-Correlation-ID` header: the same ID appeared in booking-service's
      `BookingEventProducer` publish log, the Kafka message header, and
      toy-service's `BookingEventConsumer` consume log for the same eventId.
- [x] Structured logging review across all three services — grepped for
      `log.info/warn/error/debug` usage without `@Slf4j` (none found) and
      checked every `@Scheduled`/`@KafkaListener` class for MDC usage — the
      `OverdueDetectionService` gap above was the only one; all 4
      `@KafkaListener` classes already set MDC correctly.
- [x] Tune liveness/readiness probe timings against real startup behavior —
      both services' actual local startup (`Started ToyServiceApplication in
      5.036 seconds`, `Started BookingServiceApplication in 6.732 seconds`)
      sits well inside CLAUDE.md's documented probe delays (readiness
      initialDelay=30s, liveness initialDelay=60s), and both
      `/actuator/health/{liveness,readiness}` respond `UP` immediately after
      startup completes. No manifests exist yet to apply K8s-specific
      resource-constrained timing to (that's S8); current numbers verified
      as generously safe for local/dev startup behavior.
- [x] Tracing setup (Zipkin, per the `monitoring` k8s namespace) — added
      `micrometer-tracing-bridge-brave` + `zipkin-reporter-brave` to
      toy-service and booking-service (user-approved via AskUserQuestion;
      deliberately excluded api-gateway, which has no functional routes wired
      up in any sprint yet), 100% sampling, and a `zipkin` service in
      `docker-compose.yml`. Verified live: `/api/v2/services` lists both
      `toy-service` and `booking-service`, and a real traffic flow produced
      genuine spans (including Spring Security filter-chain detail) queryable
      via `/api/v2/traces`.
- [x] Prometheus alerting rules — `prometheus/alerts.yml`, 6 rules across 5
      groups (`ServiceDown`, `HighHttp5xxRate`, `PaymentFailureSpike`,
      `LowAvailabilityCacheHitRatio`, `KafkaConsumerLagHigh`,
      `JvmHeapNearLimit`), wired via `rule_files` in `prometheus.yml` and
      mounted in `docker-compose.yml`. Verified live via
      `/api/v1/rules` — all 6 loaded with `health":"ok"`, no parse errors.

**Infra note (not a code bug):** bringing up the `zipkin`/`grafana` containers
hit a real Docker Hub pull stall — both `docker compose up` and direct
`docker pull` for `openzipkin/zipkin:latest` and `grafana/grafana:latest`
wedged repeatedly (zero progress for 4+ minutes) even though the daemon and
`docker ps`/`docker images` stayed responsive throughout, and even survived
one full Docker Desktop restart. Resolved by retrying the pulls individually
(Docker resumes from whatever layers already completed) until each finished.
Separately, the containerized Grafana's default host port 3000 collided with
a pre-existing Homebrew-installed native Grafana on this machine (unrelated
to this project, running since before this session) — remapped the
container to host port **3001** in `docker-compose.yml` rather than touching
that unrelated service. Also worth noting for future `mvn test` runs on this
machine: a native PostgreSQL 15 install (`/Library/PostgreSQL/15`) also
listens on the default port 5432, separate from this project's Docker
Postgres (mapped to host **5433**) — running `mvn test` without
`SPRING_DATASOURCE_URL`/`POSTGRES_USER`/`POSTGRES_PASSWORD` env vars pointed
at 5433 will hit the wrong server and fail with a misleading "password
authentication failed" on Postgres' `contextLoads` tests. Both are pre-
existing local-machine quirks, not project bugs.

Full suites green with the correct env vars: toy-service 16/16, booking-
service 48/48 (12 test classes, including the 6 new `PaymentServiceTest`
cases added this sprint — a real coverage gap since Sprint 3, since
`PaymentService` had no dedicated test file despite containing the
multi-booking-per-order-id logic).

## S7 — Performance Engineering — ⏭️ Skipped 0/8

Skipped at the user's explicit request (2026-08-22) — they're doing the
performance-engineering work manually themselves rather than having Claude
Code do it. None of CLAUDE.md's six intentional bottlenecks have been
touched, and S8's manifests below deliberately carry the same unfixed
config forward unchanged (small HikariCP pool, no Couchbase cache warming,
1-partition Kafka topics, no circuit breaker wiring, default JVM heap,
missing composite index) — nothing in this sprint pre-fixes any of them.

## S8 — Kubernetes ✅ 7/7 (2026-08-22)

- [x] `k8s/namespace.yaml`, `ingress.yaml`, `network-policy.yaml` — three
      namespaces (`toy-rental`/`infra`/`monitoring`) matching CLAUDE.md's
      table; ingress routes everything through `api-gateway` only (per
      CLAUDE.md, toy-service/booking-service are never exposed directly);
      network policy default-denies ingress in both `toy-rental` and
      `infra`, then explicitly allows app-services → infra, api-gateway →
      app-services, and monitoring → app-services (Prometheus scrape),
      enforcing CLAUDE.md's "no service touches another's database" rule
      at the network layer, not just via credentials.
- [x] `k8s/infra/*` manifests (postgres, couchbase, kafka, redis, minio,
      keycloak, wiremock) — StatefulSets for the four stateful services
      (postgres, couchbase, kafka, minio), Deployments for the rest, all
      with readiness/liveness probes. `couchbase-init`/`minio-init` Jobs
      replace docker-compose's manual "create the cluster/buckets via the
      web console" step so a fresh deploy is smoke-testable without a
      human clicking through a UI.
- [x] `k8s/infra/{prometheus,grafana}` manifests — same content as
      Sprint 6's docker-compose config, retargeted at in-cluster DNS names
      (`toy-service.toy-rental.svc.cluster.local` etc. instead of
      `host.docker.internal`); Grafana's provisioning ConfigMaps carry the
      same auto-provisioned datasource + dashboard as Sprint 6. Also added
      `k8s/infra/zipkin` (not in CLAUDE.md's original list, added after
      Sprint 6's tracing work — CLAUDE.md's Namespaces section already
      places it in `monitoring`).
- [x] `k8s/services/*` manifests (api-gateway, toy-service, booking-service)
      with probes + resource requests/limits from CLAUDE.md — exact numbers
      from CLAUDE.md's tables (requests/limits, liveness
      initialDelay=60s/period=15s, readiness initialDelay=30s/period=10s).
- [x] HPA per service (min/max replicas, CPU target from CLAUDE.md) — exact
      numbers from CLAUDE.md's table (toy-service/booking-service 2-8 @
      60%, api-gateway 2-10 @ 60%); confirmed via `helm template` that HPA
      objects render correctly and are conditionally skippable per
      environment.
- [x] Helm chart (`Chart.yaml`, `values.yaml`, `values-dev.yaml`,
      `values-prod.yaml`, templates) — templates the three app services
      only (infra stays as raw manifests, matching how a real org would
      typically split "platform team owns infra via plain YAML/Helm
      subcharts" from "app team owns their own service chart"). `values-dev`
      drops to 1 replica/service and disables HPA (a 1-node kind cluster
      has nothing to scale across); `values-prod` explicitly re-states
      CLAUDE.md's numbers so a real install doesn't silently depend on
      `values.yaml` never drifting.
- [x] Deploy to Docker Desktop Kubernetes + smoke test — see the full
      story below; ended in a genuine end-to-end pass (register → login →
      browse → book → pay → CONFIRMED) via direct service port-forwards,
      with Prometheus confirming all three app services `up`.

### The real story — this was an unusually rough infra sprint

**1. Two Docker Hub registry-pull stalls (recurrence of the Sprint 6
pattern).** Both `docker compose up` for zipkin/grafana/prometheus and
`kind`'s own node-image pull got stuck with zero progress for extended
periods, even though the daemon itself stayed responsive. Root-caused as
the same class of registry stall Sprint 6 hit, not a new issue — resolved
each time by killing and retrying, and once by a full Docker Desktop
restart.

**2. Docker's *build* VM has a much slower network path than the host
itself, for this environment.** Building the three services' images via
a standard multi-stage `mvn package` Dockerfile stalled repeatedly — but a
direct `docker pull` and a direct host-side `curl` to Maven Central both
showed real (~120KB/s) throughput. Confirmed decisively: the build VM's
network path was the bottleneck, not general connectivity. Fixed by
building each service's jar on the *host* (`./mvnw package -DskipTests` —
fast, since `~/.m2` was already warm from this session's many `mvn test`
runs) and rewriting all three Dockerfiles to a simple single-stage
`COPY target/*.jar app.jar`, eliminating the in-container Maven step
entirely. `.dockerignore` updated to exclude `target/*` except
`!target/*.jar`.

**3. `api-gateway/mvnw` was missing its executable bit.** Same class of
bug as `docker/postgres-init/init-databases.sh` in Sprint 1 — caught while
building the jar on the host for the fix above. `chmod +x`.

**4. Resource contention crash-looped the K8s control plane.** Running
the full docker-compose stack *and* the K8s cluster *and* 3 concurrent
Maven/JVM builds simultaneously exceeded the Docker Desktop VM's original
~8GB budget — `kube-scheduler`/`kube-controller-manager` went into
CrashLoopBackOff from failing their own health checks under starvation.
Fixed by stopping docker-compose during K8s work (`docker compose down` —
data preserved in named volumes) and building on the host rather than
inside Docker's build VM (which also fixes finding #2).

**5. Couchbase could not be kept running in this environment — resolved in
a follow-up session, turned out to be much simpler than it looked.** Four
layers, each real:
   - First, a too-aggressive liveness probe (`initialDelaySeconds: 60`)
     was killing the container mid-startup before Couchbase Server's own
     (genuinely slow) boot sequence could finish — a classic liveness
     death spiral. Fixed by raising it to 120s.
   - That didn't fully resolve it: `kubectl get pod -o jsonpath` showed
     `reason: OOMKilled`. Escalated the container's own memory limit
     (1Gi → 2Gi → 4Gi) — still OOMKilled, consistently in ~5-7 seconds
     regardless of the limit (looked like a real gradual memory-hungry
     startup would survive longer at a bigger limit — this didn't, which
     was misleading).
   - Raised the whole Docker Desktop VM from ~8GB to ~12GB (user did this
     via Settings → Resources) to rule out node-wide pressure — no change
     at 4Gi, so this sprint's session ended here: scaled `couchbase` to 0
     replicas and proceeded without it, documented as unresolved/deferred.
   - **Follow-up session, resolved:** the "dies in ~5-7s regardless of
     limit" pattern wasn't actually evidence of a hard ceiling — it just
     meant every tested limit (up to 4Gi) was below the real threshold, so
     each attempt died at a similar point in Couchbase's startup sequence
     regardless of exactly which limit was set. Verified via `kubectl top
     pod` that Couchbase Server's genuine baseline footprint here (Erlang
     VM + ns_server + indexer/query init) is **~4.85Gi** — confirmed
     stable (Ready, 0 restarts, steady ~4.85Gi usage) at both 8Gi and 6Gi
     limits. No cgroup bug, no arm64 issue, no image-version problem —
     just a limit that needed one more doubling. Set the committed
     manifest to `requests: 3Gi / limits: 6Gi` for real headroom without
     being wasteful.

**6. Couchbase's fallback design didn't actually work — two real code
bugs found live, both fixed.**
   - `CouchbaseConfig` in both services called `bucket.waitUntilReady(...)`
     synchronously inside a `@Bean` factory method — if Couchbase is
     unreachable, this throws and fails the *entire* Spring context, which
     is worse than CLAUDE.md's documented design (graceful degradation).
     The downstream fallback logic (`AvailabilityService.loadOrDefault()`,
     `LogicalDateService`'s wall-clock fallback) only helps once a `Bucket`
     bean actually exists — it never got the chance to run. Fixed by
     wrapping `waitUntilReady` in try/catch and returning the (unverified)
     bucket reference anyway, since `cluster.bucket(name)` itself never
     blocks.
   - Even after that fix, the live smoke test's booking-creation step
     still 500'd: `CouchbaseAvailabilityRepository.findByToyId()` only
     caught `DocumentNotFoundException`, not the broader connectivity
     failure Couchbase being fully down actually throws — so it propagated
     uncaught through `AvailabilityService` and crashed the
     `/availability` endpoint. `LogicalDateService` already caught
     `RuntimeException` broadly and was fine; only the availability
     repository had the narrower gap. Fixed by broadening the catch to
     `CouchbaseException` (the SDK's common base class). Applied the
     identical fix to booking-service's `CouchbaseReportRepository` for
     consistency, even though it's off the smoke test's critical path.

**6a. Two more real bugs surfaced only once Couchbase was actually stable
long enough to reveal them (follow-up session).**
   - `couchbase-init`/`minio-init` hung indefinitely trying to reach their
     targets — indistinguishable from Couchbase itself still starting up,
     which is exactly why this went unnoticed for so long. Root cause:
     `network-policy.yaml`'s `allow-app-services-to-infra` rule only
     allowed ingress to `infra` pods from the `toy-rental` namespace — it
     never allowed same-namespace traffic, so `infra`-namespace Jobs
     calling `infra`-namespace Services (exactly what these init Jobs do)
     were silently blocked by `default-deny-ingress`, with a hang instead
     of a clear rejection. Fixed by adding `podSelector: {}` (same
     namespace) as an additional allowed source.
   - Once that was fixed, `couchbase-init` ran but the `monthly-reports`
     bucket still failed to create, misreported as "already exists" by
     the script's blanket fallback. Real cause: `--cluster-ramsize 256`
     left too little room for three 100MB buckets (300MB > 256MB). Fixed
     by raising it to 512MB, and — since `cluster-init` silently no-ops
     quota changes on an already-initialized cluster — added a
     `couchbase-cli setting-cluster` fallback so a Job re-run actually
     applies new quota values, plus made the bucket-create failure
     message honest instead of presuming a specific cause.

**7. kind-mode Docker Desktop caches images by tag — same-tag rebuilds
don't auto-refresh.** After fixing #6, redeploying under the *same* image
tag (`1.0.0`) still ran the old, broken code — the cluster's node
containerd had its own cached copy keyed by tag, and a host-side rebuild
under an identical tag doesn't invalidate it. Lesson for any future
iteration on this setup: always bump the tag (or delete pods forcibly) to
force a genuinely fresh pull. Ended up rebuilding under `1.0.1` then
`1.0.2` as the fixes landed.

**8. Pre-existing, not-a-Sprint-8 finding: api-gateway can't validate
JWTs.** Its Spring Security JWT resource-server config points at
`http://keycloak.infra.svc.cluster.local:8080/realms/toyrental`, but per
Sprint 3's approved architectural decision, booking-service issues its own
self-signed RSA JWTs and no Keycloak realm was ever imported — Keycloak's
OIDC discovery document simply doesn't exist. This is a known gap from
Sprint 3/4, not something this sprint introduced or is scoped to fix. The
smoke test bypassed api-gateway and hit toy-service/booking-service
directly via `kubectl port-forward`, which is a fully legitimate way to
validate the K8s deployment mechanics.

### Live validation

Full flow via direct service port-forwards (bypassing api-gateway per
finding #8): registered a customer, logged in, checked
`/api/v1/toys/{id}/availability` directly (confirmed the finding-#6 fix —
`available: true` with a live `Couchbase unavailable ... treating as
absent` WARN in toy-service's logs, not a 500), created a booking, fired
the WireMock payment webhook, and confirmed the booking reached
`status: CONFIRMED, paymentStatus: SUCCESS`. Prometheus (port-forwarded
separately) showed all three app services as `up` targets via their
in-cluster DNS names.

**Follow-up session:** with Couchbase now actually running (finding #5)
and both init Jobs completing (finding #6a), re-checked the same
availability endpoint and got a clean response with no fallback
warning — `AvailabilityService` logged a genuine "No Couchbase
availability document ... treating as fully available" (a real "not
found" from a healthy cluster) instead of the earlier "Couchbase
unavailable" connectivity warning. Verified all three buckets
(`toy-availability`, `logical-date`, `monthly-reports`) present via
`couchbase-cli bucket-list`. The fallback code from finding #6 is
still in place and still correct to keep — it's what makes a future
Couchbase outage degrade instead of crash — but the platform is no
longer relying on it to function day-to-day.

## S9 — React Frontend — 3/6 (2026-08-22, in progress)

- [x] Frontend scaffold — Vite + React + TypeScript + Tailwind v4, React
      Router, Zustand auth store, Axios API client with auth interceptor.
      `frontend/` talks directly to toy-service/booking-service (api-gateway
      stays out of scope per its existing Keycloak gap); both services'
      `SecurityConfig` got a dev-only CORS bean for this.
- [x] Toy catalogue browse/search/detail pages — colorful/playful catalogue
      grid with search + category/age-group filters (dropdowns populated from
      `GET /api/v1/toys/metadata`), toy detail page with image gallery.
- [x] Booking flow — live availability check, weekly/monthly rental-type radio
      toggle, date range selection, checkout, and a dev-only "simulate
      payment" button against the WireMock-stubbed webhook. No visual
      calendar widget (uses the availability endpoint's from/to check, not
      `/availability/calendar`).
- [ ] Customer account — "my bookings" list + cancel is done; profile editing
      and PDF receipt download are not built yet.
- [ ] Admin dashboard — inventory list + add/edit toy form (category/age-group
      dropdowns and condition/status radio groups, all fetched from
      `/metadata`) + real photo upload to MinIO are done. Deliveries/pickups/
      overdue views and the monthly reports UI are not built yet.
- [ ] Build/deployment integration — runs via `npm run dev` against
      port-forwarded services only; no Dockerfile/K8s manifest for the
      frontend itself yet.
