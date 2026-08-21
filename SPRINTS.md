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
| S2 — Toy Service | ⬜ Not started | 0/9 |
| S3 — Booking Service | ⬜ Not started | 0/10 |
| S4 — Kafka Pipeline | ⬜ Not started | 0/10 |
| S5 — Month-End Report | ⬜ Not started | 0/8 |
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

## S2 — Toy Service — 0/9

- [ ] `Toy` entity + `ToyRepository` + request/response DTOs
- [ ] `ToyController` (catalogue, detail, search, categories, browse-available)
- [ ] `AvailabilityController` + `AvailabilityService` (Couchbase-backed)
- [ ] `AdminToyController` (add/update/soft-delete, inventory, low-stock,
      condition update, image upload)
- [ ] `LogicalDateService` (Couchbase `logical-date::current`, Redis-cached,
      falls back to `LocalDate.now()`)
- [ ] `BookingEventConsumer` (Kafka: `booking.confirmed`/`booking.cancelled` →
      update Couchbase availability + `toy_availability_log`)
- [ ] Couchbase config + `ToyAvailabilityDocument` / `LogicalDateDocument`
- [ ] `GlobalExceptionHandler` + `ToyNotFoundException` /
      `ToyNotAvailableException`
- [ ] Unit tests (`ToyControllerTest`, `AvailabilityServiceTest`,
      `BookingEventConsumerTest`)

## S3 — Booking Service — 0/10

- [ ] `Customer` entity/repository/DTOs + register/login (JWT issuance)
- [ ] `CustomerController` (me, update profile/address, my-bookings)
- [ ] `Booking` entity/repository/DTOs
- [ ] `BookingController` + booking flow (availability check, pessimistic lock,
      create/detail/receipt/cancel/extend)
- [ ] `ToyServiceClient` (Feign) integration with toy-service
- [ ] `PaymentController` + `PaymentService` (WireMock Razorpay order/verify,
      webhook handling)
- [ ] `AdminBookingController` (today's deliveries/pickups, overdue, return,
      manual confirm)
- [ ] `NotificationService` (WhatsApp send via WireMock)
- [ ] Resilience4j circuit breaker wired onto the Razorpay call path
- [ ] Unit tests (`BookingControllerTest`, `BookingServiceTest`)

## S4 — Kafka Pipeline — 0/10

- [ ] Provision topics with correct partitions + DLTs (per CLAUDE.md's topic
      table)
- [ ] Shared event envelope helper + correlationId propagation
      (HTTP header → Kafka header → logs)
- [ ] `BookingEventProducer` (`booking.confirmed`, `booking.cancelled`)
- [ ] `PaymentEventConsumer` (booking-service internal, `payment.success`/`.failed`)
- [ ] `BookingEventConsumer` idempotency (eventId check) in toy-service
- [ ] Notification consumer path (WhatsApp send on `booking.confirmed`)
- [ ] Overdue detection job → `booking.overdue` event
- [ ] `MonthEndTriggerConsumer` skeleton (idempotency check against Couchbase)
- [ ] DLT / error-handling consumers
- [ ] Kafka integration tests (embedded broker or Testcontainers)

## S5 — Month-End Report — 0/8

- [ ] `AdminReportController` (trigger, list, detail, PDF download)
- [ ] `ReportService` (bookings aggregation for the target month)
- [ ] `PdfGeneratorService` (iText 8.0.3)
- [ ] MinIO upload integration (`reports/yyyy/mm/...`)
- [ ] `MonthlyReportDocument` + `CouchbaseReportRepository`
- [ ] Full `MonthEndTriggerConsumer` implementation + idempotency
- [ ] Publish `monthly.report.generated`
- [ ] Unit/integration tests for the report generation flow

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
