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
| S6 — Observability | ⬜ Not started | 0/7 |
| S7 — Performance Eng | ⬜ Not started | 0/8 |
| S8 — Kubernetes | ⬜ Not started | 0/7 |
| S9 — React Frontend | ⬜ Not started | 0/6 |

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

## S6 — Observability — 0/7

- [ ] Wire the custom Prometheus metrics into code (cache hit/miss, booking
      counters, payment counters, PDF generation timer, report counter)
- [ ] Grafana dashboard JSON (provisioned into `grafana/dashboards`)
- [ ] Verify correlationId end-to-end across HTTP + Kafka + logs
- [ ] Structured logging review across all three services
- [ ] Tune liveness/readiness probe timings against real startup behavior
- [ ] Tracing setup (Zipkin, per the `monitoring` k8s namespace)
- [ ] Prometheus alerting rules

## S7 — Performance Engineering — 0/8

Matches the six intentional bottlenecks documented in CLAUDE.md, plus test
tooling and writeup:

- [ ] JMeter test plans (catalogue browse load, concurrent booking load, soak)
- [ ] Prove + fix missing composite index on `toys(category, age_group,
      is_active, status)`
- [ ] Prove + fix no cache warming on Couchbase startup (`ApplicationReadyEvent`)
- [ ] Prove + fix booking-service HikariCP pool exhaustion (10 → 30)
- [ ] Prove + fix Kafka single-partition consumer lag (→ 6 partitions)
- [ ] Prove + fix WireMock retry storm (wire the existing Resilience4j
      `razorpay` circuit breaker config into the actual call path)
- [ ] Prove + fix JVM heap/GC pressure under soak (`-Xmx512m -XX:+UseG1GC`)
- [ ] Performance test report/writeup (before/after for each bottleneck)

## S8 — Kubernetes — 0/7

- [ ] `k8s/namespace.yaml`, `ingress.yaml`, `network-policy.yaml`
- [ ] `k8s/infra/*` manifests (postgres, couchbase, kafka, redis, minio,
      keycloak, wiremock)
- [ ] `k8s/infra/{prometheus,grafana}` manifests
- [ ] `k8s/services/*` manifests (api-gateway, toy-service, booking-service)
      with probes + resource requests/limits from CLAUDE.md
- [ ] HPA per service (min/max replicas, CPU target from CLAUDE.md)
- [ ] Helm chart (`Chart.yaml`, `values.yaml`, `values-dev.yaml`,
      `values-prod.yaml`, templates)
- [ ] Deploy to Docker Desktop Kubernetes + smoke test

## S9 — React Frontend — 0/6

- [ ] Frontend scaffold (routing, API client, auth/token handling)
- [ ] Toy catalogue browse/search/detail pages
- [ ] Booking flow (availability calendar, date selection, checkout)
- [ ] Customer account (profile, my bookings, receipts)
- [ ] Admin dashboard (inventory, deliveries/pickups/overdue, reports)
- [ ] Build/deployment integration
