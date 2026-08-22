# ToyRental Platform — Progress Log

Detailed, chronological record of every step taken on this project. Newest session
is appended at the bottom. Each session is dated; each step is numbered within it.

See `SPRINTS.md` for the sprint-level status (stories completed / remaining).

---

## Session 1 — 2026-08-21 — Sprint 1: Infrastructure

**Goal:** Scaffold all Sprint 1 files per CLAUDE.md — docker-compose, WireMock stubs,
and the three service skeletons (api-gateway, toy-service, booking-service).

### Steps

1. Read `CLAUDE.md` in full to establish the authoritative spec (tech stack, ports,
   schemas, Kafka topics, API surface, naming conventions, intentional bottlenecks).

2. Inspected the existing repo state before writing anything and found Spring
   Initializr scaffolds already present, generated against the wrong stack:
   - `api-gateway`, `toy-service`, `booking-service` all targeted Spring Boot
     **4.1.1** / Spring Cloud **2025.1.2** instead of the pinned **3.2.5 / 2023.0.1**.
   - Several dependency artifact IDs in the generated `pom.xml` files did not exist
     on Maven Central (`spring-boot-starter-kafka`, `spring-boot-starter-flyway`,
     `spring-boot-starter-webmvc`, `spring-boot-starter-security-oauth2-resource-server`,
     etc.) — fabricated by whatever generated them.
   - `api-gateway`'s Java package was `com.toyrental.api_gateway` instead of the
     `com.toyrental.gateway` mandated by CLAUDE.md's repo structure.
   - `booking-service/` and `toy-service/` were double-nested
     (`booking-service/booking-service/...`) instead of flat.
   - `application.yaml` (wrong extension/near-empty) existed instead of
     `application.yml`.

3. Flattened `toy-service/toy-service/*` → `toy-service/*` and
   `booking-service/booking-service/*` → `booking-service/*` via `Move-Item`,
   then removed the now-empty nested directories.

4. Recreated the api-gateway package as `com.toyrental.gateway` (main + test),
   removed the old `com.toyrental.api_gateway` package, and removed the stale
   `application.yaml` files in all three services.

5. Rewrote `pom.xml` for all three services against Spring Boot **3.2.5** /
   Spring Cloud **2023.0.1** / Java **17**, using real Maven Central coordinates:
   - **api-gateway**: `spring-cloud-starter-gateway`, `spring-boot-starter-security`,
     `spring-boot-starter-oauth2-resource-server`,
     `spring-boot-starter-data-redis-reactive`,
     `spring-cloud-starter-circuitbreaker-reactor-resilience4j`, actuator,
     micrometer-prometheus, lombok.
   - **toy-service**: `spring-boot-starter-web`, `-data-jpa`, `-validation`,
     `-security`, `-oauth2-resource-server`, `spring-kafka`,
     `com.couchbase.client:java-client:3.6.0`, `postgresql`, `flyway-core`,
     `springdoc-openapi-starter-webmvc-ui:2.5.0`, actuator, micrometer-prometheus,
     lombok.
   - **booking-service**: same base as toy-service plus
     `spring-cloud-starter-openfeign`, `resilience4j-spring-boot3:2.2.0`,
     `com.itextpdf:itext-core:8.0.3`, `io.minio:minio:8.5.9`.

6. Wrote `application.yml` + `application-dev.yml` for all three services,
   matching the exact structure documented in CLAUDE.md (datasource, Hikari,
   Flyway, Kafka, Couchbase bucket config, MinIO, WireMock base URL, Feign URL,
   Resilience4j razorpay instance, actuator/prometheus exposure, log pattern
   with `correlationId`).
   - **Deliberate deviation from the literal template, per the "Performance
     Engineering — Bottlenecks to Find" section (must NOT pre-fix):** left
     `toys` table without the composite `(category, age_group, is_active, status)`
     index, and set booking-service Hikari `maximum-pool-size: 10` instead of
     the template's pre-tuned `30`. Both are explicitly scheduled as Sprint 7
     fixes once JMeter proves the problem.

7. Wrote api-gateway Java classes:
   - `ApiGatewayApplication` (correct package).
   - `CorrelationIdFilter` — a Spring Cloud Gateway `GlobalFilter` that reads or
     generates `X-Correlation-ID`, injects it into the downstream request and
     the response headers.
   - `GatewayConfig` — Redis `KeyResolver` bean (keyed by client IP, since routes
     are reachable pre-authentication) + fallback `RouterFunction` routes for
     the circuit breaker, returning the standard error JSON shape.
   - `SecurityConfig` — reactive WebFlux JWT resource server against Keycloak;
     maps Keycloak's `realm_access.roles` claim to `ROLE_*` authorities (not
     done by Spring Security's defaults); public paths for actuator health,
     fallback routes, customer register/login, payment webhook, and `GET`
     toy-browse endpoints; `/api/v1/admin/**` requires `ROLE_ADMIN`; everything
     else requires authentication.
   - **Bug caught before it shipped:** first draft of `SecurityConfig` tried to
     register `CorrelationIdFilter` via `.addFilterAt(...)` on
     `ServerHttpSecurity`, which requires a `WebFilter`. `CorrelationIdFilter`
     implements Gateway's `GlobalFilter`, a different interface — would not have
     compiled. Fixed by removing the manual registration; Spring Cloud Gateway
     auto-applies all `GlobalFilter` beans to every route on its own.

8. Wrote toy-service Flyway migrations `V1`–`V4` (`toys`, `toy_images`,
   `toy_availability_log`, and 8 seeded sample toys — LEGO, Fisher-Price, Hot
   Wheels, Barbie, Nerf, Melissa & Doug, RC truck, Ravensburger puzzle — priced
   in INR).

9. Wrote booking-service Flyway migrations `V1`–`V6` (`customers`, `bookings`,
   `payments`, `notifications`, `monthly_reports`, and 1 seeded customer in
   Kharghar, Navi Mumbai, with a BCrypt hash for the dev password `password`).

10. Wrote infra files:
    - `docker-compose.yml` — postgres, couchbase (community 7.2.4), kafka
      (bitnami, single-node KRaft, no ZooKeeper), redis, minio + `minio-init`
      sidecar (creates the `toy-rental-reports` bucket via `mc`), keycloak
      (dev mode), wiremock (port 9090, mappings volume), prometheus, grafana —
      all on a `toyrental-net` bridge network. Scoped to infra only; the three
      Spring Boot services are meant to run on the host (`mvn spring-boot:run`)
      against these containers, matching Sprint 1's "Infrastructure" scope.
    - `docker/postgres-init/init-databases.sh` — creates `toydb`/`toyuser` and
      `bookingdb`/`bookinguser` inside the single postgres container.
    - `prometheus/prometheus.yml` — scrapes all three services'
      `/actuator/prometheus` via `host.docker.internal`.
    - `wiremock/mappings/razorpay-order-stub.json`,
      `razorpay-verify-stub.json`, `whatsapp-stub.json` — exact
      request/response shapes from CLAUDE.md's WireMock spec.
    - Left a comment in `docker-compose.yml` noting the Couchbase buckets
      (`toy-availability`, `logical-date`, `monthly-reports`) must be created
      manually via the web console (`localhost:8091`) after first startup —
      not automated in Sprint 1.

11. Validated `docker-compose.yml` with `docker compose config --quiet` — no
    errors.

12. Verified the final file tree for all three services matches CLAUDE.md's
    documented repo structure exactly (packages, file names, directory shape).

### Build verification (same session, on request)

13. Checked for Maven/Java on the machine. Found no `mvn` on `PATH` but each
    service has an `mvnw` wrapper. Found Java on `PATH` resolved to
    **JDK 26.0.2** — far newer than the project's pinned Java 17.

14. `JAVA_HOME` was set to `...\jdk-26.0.2\bin` (should point at the JDK root,
    not `bin`) — fixed inline for the build commands.

15. First compile attempt (api-gateway, on JDK 26) failed:
    `cannot find symbol: variable log` in `CorrelationIdFilter.java`. Root
    cause: Lombok's `@Slf4j` annotation processing silently no-oped. JDK 26
    GA'd only weeks before this session and Lombok hooks into javac internals
    that change every JDK feature release — no released Lombok version
    (checked up to the latest, `1.18.46` on Maven Central) supports it yet.

16. Tried pinning Lombok to `1.18.46` (latest on Maven Central) as a lighter
    fix — still failed with the same error, confirming it's a genuine JDK 26
    incompatibility, not a stale-BOM-version issue.

17. Checked for an existing JDK 17 on the machine — none installed, only
    JDK 26. Since CLAUDE.md pins Java 17 for the project regardless of the
    Lombok issue, installed **Eclipse Temurin 17.0.20+8** via
    `winget install EclipseAdoptium.Temurin.17.JDK` (silent, accepted
    package/source agreements). This is the correct fix rather than chasing
    Lombok/JDK-26 compatibility, and it also brings the build JDK in line with
    the project's mandated version.

18. Reverted the temporary Lombok `1.18.46` pin in `api-gateway/pom.xml` back
    to the BOM-managed version, since JDK 17 removes the need for the
    workaround.

19. Recompiled all three services with `JAVA_HOME` pointed at Temurin 17:
    - `api-gateway` — `mvn compile` → **success**
    - `toy-service` — `mvn compile` → **success**
    - `booking-service` — `mvn compile` → **success**

20. Ran `mvn test-compile` on all three services (compiles test sources without
    running them, since the tests boot a Spring context that needs live
    Postgres/Kafka/Couchbase/Redis, not yet started) — **all three passed**.

**Not done in this session:**
- Test suites not actually run (need `docker compose up -d` first).
- Couchbase buckets not created (manual step, documented in compose file).
- No Keycloak realm import file yet — container starts in dev mode with
  `admin/admin` but no `toyrental` realm configured.
- No application code beyond scaffolding — no controllers, services,
  entities, DTOs, Kafka producers/consumers (Sprint 2/3 scope).

---

## Session 2 — 2026-08-21 — Sprint 2: Toy Service (completion, bugfixes, live validation)

**Goal:** Finish the toy-service surface left over from the prior session's
partial Sprint 2 work (entities/repositories/DTOs/`ToyService`/
`AvailabilityService`/`LogicalDateService`/Couchbase config already existed;
`ToyController` only covered the public browse/detail/search/categories
routes), fix any bugs found along the way, and prove the result actually
works end-to-end against live infra rather than just compiling.

### Steps

1. Read the existing toy-service code in full to establish what Sprint 1's
   session had actually built vs. what SPRINTS.md's S2 checklist still listed
   as outstanding: `AvailabilityController`, `AdminToyController`, a Kafka
   `BookingEventConsumer`, and all three unit test files were missing.

2. Added `ToyRepository.findByActiveTrueAndStatus(...)` (needed for the
   browse-available filter; the existing `findByActiveTrueAndStatusNot` only
   covered the inverse low-stock case).

3. **Bug found by inspection:** `SecurityConfig` permitted `GET` on
   `/api/v1/toys/**` but let every other verb (including the admin-only
   POST/PUT/DELETE CLAUDE.md documents for that same path) fall through to
   plain `authenticated()` — any logged-in customer, not just admins, could
   create/update/delete toys. Added explicit `HttpMethod.POST/PUT/DELETE`
   rules scoped to `hasRole("ADMIN")`, ordered before the generic
   `/api/v1/admin/**` rule.

4. Added the Kafka event contract toy-service and (eventually) booking-service
   will share: `BookingEventEnvelope` (CLAUDE.md's standard envelope shape)
   and `BookingEventPayload` (bookingId/toyId/customerId/startDate/endDate),
   under a new `kafka` package.

5. **Bug found by inspection:** Couchbase consumers deserialize with
   `spring.json.trusted.packages` set but no default-type mapping — a
   producer from a different service package (booking-service, not yet
   built) would never carry a matching `__TypeId__` header. Added
   `spring.json.use.type.headers: false` +
   `spring.json.value.default.type: com.toyrental.toy.kafka.BookingEventEnvelope`
   to `application.yml`.

6. Extended `AvailabilityService` with `blockDates`/`releaseDates` (idempotent
   per bookingId — replaces any prior range for the same booking rather than
   duplicating), a `computeNextAvailable` scan, and `browseAvailable` (used by
   the new `GET /api/v1/toys/available` endpoint). Added `ToyService.toResponses(List<Toy>)`,
   refactoring the existing per-page image-lookup into a shared helper.

7. **Bug found by inspection:** `AvailabilityService.loadOrDefault()` called
   `LocalDate.now()` directly for the no-Couchbase-doc default, violating
   CLAUDE.md's explicit "never use `LocalDate.now()` in business logic" rule.
   Fixed to go through the now-injected `LogicalDateService`.

8. Wrote `AvailabilityController` (check/calendar/browse-available),
   `AdminToyController` (create/update/soft-delete/image-upload/inventory/
   low-stock/condition), and `InternalToyController` (the
   `/internal/v1/toys/**` routes CLAUDE.md documents for booking-service's
   Feign client, plus a manual block/release override endpoint reusing the
   same `AvailabilityService` methods the Kafka consumer uses).

9. Wrote `BookingEventConsumer`: `@KafkaListener`s for `booking.confirmed`/
   `booking.cancelled`, eventId idempotency via the existing
   `ProcessedEventRepository`, correlationId propagated into MDC for the
   duration of each event so the log pattern picks it up automatically.
   Deliberately did **not** mark the shared `process()` helper
   `@Transactional` — it's invoked as `this.process(...)` from the
   `@KafkaListener` methods (Spring AOP self-invocation), so the annotation
   would silently be a no-op; documented why in a comment instead of leaving
   a misleading annotation.

10. Wrote 15 unit tests: `ToyControllerTest` (`@WebMvcTest`, security filters
    disabled since the routes under test are all `permitAll`),
    `AvailabilityServiceTest` (Mockito, covers check/block/release/replace-
    on-same-bookingId), `BookingEventConsumerTest` (Mockito, covers both
    topics' happy path and the idempotent-skip path).

11. Compiled with JDK 17 (Temurin already present at
    `/Library/Java/JavaVirtualMachines/jdk-17.0.4.1.jdk` on this machine —
    no install needed this time, unlike Session 1's Windows environment).
    First compile attempt failed:

    **Bug found by compiling** (pre-existing, not introduced this session):
    `LogicalDateService.getCurrentDate()`/`isMonthEnd()`/`isOverdueCheckDay()`
    accessed `LogicalDateDocument.currentDate`/`.isMonthEnd`/
    `.isOverdueCheckDay` as bare field access across classes instead of via
    the Lombok-generated getters — the fields are `private`, so this never
    compiled. `LogicalDateService` had apparently never actually been built
    before this session. Fixed to call the getters.

12. Ran the new tests: 14/15 passed on the first run; the 15th
    (`blockDatesReplacesAnyExistingRangeForTheSameBooking`) NPE'd because the
    test itself forgot to stub `logicalDateService.getCurrentDate()` — a test
    bug, not a service bug. Fixed the test; all 15 passed after.

### Live validation against real infra

13. Brought up `docker compose up -d postgres couchbase kafka`.

    **Bug found:** `bitnami/kafka:3.7` — the entire `bitnami/kafka` repository
    — no longer exists on Docker Hub (Bitnami pulled free tags in an August
    2025 catalog restructuring; confirmed via the Docker Hub API returning
    zero tags). Swapped to `apache/kafka:3.7.2`, the official image at the
    same pinned 3.7.x line, translating Bitnami's `KAFKA_CFG_*` env vars to
    the official image's plain `KAFKA_*` names.

14. Postgres wouldn't bind port 5432 — a native PostgreSQL 15 install already
    running on this Mac (unrelated to this project) held the port. Rather
    than touch that install or the committed `docker-compose.yml` (which is
    correct for other environments), added a gitignored
    `docker-compose.override.yml` remapping the container to host port 5433
    (`ports: !override` — Compose's list-merge is append-by-default, so the
    plain form would have tried to bind *both* 5432 and 5433).

15. Docker itself got wedged recreating the postgres container — `docker
    start`/`inspect`/`rm` all hung indefinitely on that one container while
    `docker ps`/`docker system df` kept working, meaning it wasn't a daemon-
    wide hang. Asked the user before taking any disruptive action; they
    picked "restart Docker Desktop for me," but before doing that, a plain
    `docker rm -f` (already backgrounded from the wedge) finished on its own,
    letting `docker compose up -d postgres` recreate it cleanly without
    actually needing the restart.

16. **Bug found:** the freshly-created Postgres container logged
    `/docker-entrypoint-initdb.d/init-databases.sh: /bin/bash: bad
    interpreter: Permission denied` and skipped it — `toydb`/`bookingdb` and
    their users were never created. Root cause: the script was committed
    without the executable bit (`-rw-r--r--`). Fixed with `chmod +x`, then
    recreated the Postgres volume so the init script would actually run
    against an empty data directory (Postgres only runs
    `docker-entrypoint-initdb.d` scripts once, on first init).

17. Initialized the Couchbase cluster via its REST API (memory quota,
    services, `Administrator`/`password` credentials matching CLAUDE.md's dev
    defaults), created the `toy-availability` and `logical-date` buckets, and
    seeded `logical-date::current` via the N1QL query service.

18. Ran `toy-service` with `mvnw spring-boot:run`, overriding
    `SPRING_DATASOURCE_URL` to point at the host-mapped port 5433 (the
    checked-in `application.yml` hardcodes port 5432, which is correct for
    every environment except this one machine's port conflict — overridden
    via env var for this run rather than editing the file). App started
    clean in ~5s: Postgres, Couchbase, and both Kafka consumer groups all
    connected.

19. Exercised `GET /api/v1/toys` (seeded catalogue loads) and
    `GET /api/v1/toys/{id}/availability` (cache miss, available=true) —
    both worked.

20. Tested the SecurityConfig admin-gating fix from step 3 with a real
    unauthenticated `POST /api/v1/toys`. Got **400** (validation error, not
    401) — meaning the request reached the controller unauthenticated.

    **Bug found:** `.requestMatchers("GET", "/api/v1/toys/**").permitAll()`
    — `requestMatchers` has no `(String method, String pattern)` overload,
    so `"GET"` was being matched as a second URL *pattern* (which never
    matches any real path), and `/api/v1/toys/**` matched *regardless of HTTP
    method*. This made the whole rule `permitAll()` every verb on that path,
    silently overriding the ADMIN rules added in step 3 further down the
    chain — they were never wrong, they just never got evaluated. Fixed to
    `.requestMatchers(HttpMethod.GET, "/api/v1/toys/**")`. Re-verified:
    unauthenticated POST/DELETE now both return 401; GET still works.

21. Published a `booking.confirmed` event to the real Kafka topic via
    `kafka-console-producer.sh` inside the container. The consumer picked it
    up but threw on every retry:

    **Bug found:** `com.couchbase.client.core.error.EncodingFailureException`
    → `InvalidDefinitionException: Java 8 date/time type LocalDate not
    supported by default: add Module jackson-datatype-jsr310`. Couchbase's
    SDK uses its own internal Jackson `ObjectMapper`, entirely separate from
    Spring's autoconfigured one, and it has no `JavaTimeModule` registered by
    default. Every document in this service (`ToyAvailabilityDocument`,
    `LogicalDateDocument`) has `LocalDate`/`Instant` fields, so **every**
    Couchbase read and write was affected. Cross-checking the earlier "cache
    miss, available=true" result from step 19 against the log confirmed
    `LogicalDateService` had *also* been silently hitting this on every read
    and falling back to `LocalDate.now()` the whole time — a second symptom
    of the exact same root cause, masked because the read path catches and
    logs a WARN instead of propagating.

    Fixed `CouchbaseConfig` to build a custom `ClusterEnvironment` with a
    `JacksonJsonSerializer` backed by an `ObjectMapper` that registers
    `JavaTimeModule` (`jackson-datatype-jsr310` was already transitively on
    the classpath via `spring-boot-starter-web`, just never wired into
    Couchbase's own mapper instance).

22. Restarted and re-ran the full flow end-to-end, clean this time:
    - Availability check before booking: `available=true`, and the log now
      shows *zero* "Failed to read logical-date::current" fallback warnings.
    - Published `booking.confirmed` → log shows "Blocked availability" and
      "Processed eventId=..."; availability check flips to `available=false`
      with the correct blocked-date range.
    - Replayed the identical event (same eventId) → log shows "Skipping
      already-processed"; `toy_availability_log` and `processed_events` each
      have exactly one row (verified via `psql`, not just log-reading).
    - `GET /api/v1/toys/available` for the same date range correctly
      excludes the now-booked toy.
    - Published `booking.cancelled` for the same booking → log shows
      "Released availability"; availability check flips back to
      `available=true`, `blockedDates` empty; both the `BLOCKED` and
      `RELEASED` rows present in `toy_availability_log`.

23. Ran the full `mvn test` suite: all 15 new tests pass.
    `ToyServiceApplicationTests.contextLoads` still fails — it uses the
    committed `application.yml`'s hardcoded port 5432, which conflicts with
    this machine's native Postgres. Not a code bug (the full context
    demonstrably boots clean under `spring-boot:run` with the port
    overridden); left as the same known, already-tracked limitation as
    Session 1 rather than hacking the committed config around one machine's
    local port conflict.

24. Committed as `50c0544` (23 files: the new controllers/kafka
    package/DTOs/tests, plus the bugfixes above). Left
    `toyrental-postgres`/`kafka`/`couchbase` and `toy-service` running for
    continued use; `docker-compose.override.yml` (gitignored) holds the
    5433 port remap for this machine.

**Not done in this session:**
- Keycloak realm still not imported — the admin-auth fix was validated via
  401-without-JWT, not a full authenticated ROLE_ADMIN request.
- `POST /api/v1/toys/{toyId}/images` (image upload) accepts a pre-hosted URL
  only, not raw file bytes — consistent with toy-service having no MinIO
  dependency in the approved stack (booking-service owns that).
- Sprint 3 (booking-service) not started — `InternalToyController` and the
  `BookingEventEnvelope`/`BookingEventPayload` shape are ready for it to
  build against.

---

## Session 3 — 2026-08-22 — Sprint 3: Booking Service, Sprint 4: Kafka Pipeline

**Goal:** Build booking-service from scratch (Sprint 1 had only scaffolded
its pom/config/Flyway migrations), then wire the Kafka pipeline properly —
topic provisioning, notification/overdue/month-end-skeleton consumers —
across both services.

### Sprint 3 — Booking Service

Before writing code, asked the user to resolve a real architectural fork:
CLAUDE.md's `application.yml` validates JWTs against a Keycloak issuer-uri
(same as toy-service), but `customers.password_hash` exists and no Keycloak
realm is provisioned. User picked self-issued RSA JWTs
(`NimbusJwtEncoder`/`Decoder`, transitively already on the classpath via the
approved oauth2-resource-server starter — no new dependency) over building
Keycloak realm/Admin-API integration first.

Built: `Customer`/`Booking`/`Payment`/`Notification` entities and their
enums (plus an undocumented-but-necessary `NotificationStatus`), repositories
including a pessimistic-lock overlap query for the booking-creation race,
`CustomerService`/`JwtTokenService` (register/login), `BookingService` (the
full CLAUDE.md "Booking Flow — Critical Logic" — availability check via
Feign, insert-then-lock overlap guard, Razorpay order creation embedded in
the same transaction), `PaymentService` (webhook handling, deposit-only
refund — rental and deposit are tracked as separate payment rows sharing one
Razorpay order, since the schema's `refund_id`/`refunded_at` columns only
make sense against a single row), `NotificationService` (built standalone,
not yet wired to any trigger), `PdfGeneratorService` (iText booking
receipts), `ToyServiceClient`/`RazorpayClient`/`WhatsAppClient` (Feign),
self-issued-JWT `SecurityConfig`, and 19 unit tests.

Bugs found via live validation (register → login → book → pay → confirm →
receipt → cancel, against real Postgres/Couchbase/Kafka/WireMock):
- Customer registration crashed with a genuine Postgres error:
  `"cust-" + UUID.randomUUID()` is 41 characters against a `VARCHAR(36)`
  column — the exact same pattern was already live and unexercised in
  toy-service's `ToyService.create()`. Fixed with a shared
  `IdGenerator.shortId(prefix)` (prefix + first 8 hex chars of a UUID) in
  both services.
- `createdAt` came back `null` on the very response that created the row —
  diagnosed in two layers, not one. First: Hibernate's `@CreationTimestamp`
  only populates at flush, which a plain `save()` inside `@Transactional`
  defers to commit. Fixed with `saveAndFlush()` — except it *still* came
  back null, because these entities assign their own id (no
  `@GeneratedValue`), so Spring Data's `isNew()` check sees a non-null id
  and routes through `entityManager.merge()` rather than `persist()` —
  `merge()` returns a different managed instance than the one passed in.
  The actual fix was reassigning the return value:
  `booking = bookingRepository.saveAndFlush(booking)`.
- One webhook call could leave a booking with successful payments but a
  still-`PENDING` status: WireMock's Razorpay stub returns the same static
  order id for every order, so two bookings created close together
  genuinely shared one; the handler only confirmed the *first* booking
  found among matched payments. Caught live via `psql` showing exactly that
  inconsistency. Fixed by grouping matched payments by distinct
  `bookingId` and confirming every one found — re-verified by deliberately
  creating two simultaneously-pending bookings sharing one order id and
  firing a single webhook call; both correctly flipped to `CONFIRMED`.

### Sprint 4 — Kafka Pipeline

Added explicit `NewTopic` provisioning in both services, pinned at 1
partition per topic — CLAUDE.md's topic reference table shows 3/6, but the
Performance Engineering section lists single-partition Kafka as an
intentional Sprint 7 bottleneck, not something to pre-fix now, the same
precedent as Sprint 1's unfixed index and Hikari pool size. Added the
`CorrelationIdFilter` toy-service had been missing since Sprint 2 (verified
via the log pattern's correlationId prefix, not just the response header —
`ToyController` doesn't actually log anything at INFO for a plain GET, so
the response-header check alone would have been weaker evidence).

Added to booking-service: a `processed_events` idempotency table (mirroring
toy-service's), `PaymentEventProducer`/`Consumer` (payment.success/failed —
explicitly scoped as a supplementary audit trail, not a replacement for the
synchronous webhook confirmation already built and validated in Sprint 3),
`BookingNotificationConsumer` (booking-service's own consumption of its
booking.confirmed/cancelled/overdue topics under consumer group
`notification-cg`, finally wiring the standalone `NotificationService` to a
real trigger), `OverdueDetectionService` (`@Scheduled`, ACTIVE bookings past
`end_date` → OVERDUE + `booking.overdue`), and a Sprint-4 skeleton
`MonthEndTriggerConsumer` (idempotency + a `GENERATING` placeholder in both
Postgres and Couchbase; full aggregation deferred to Sprint 5).

**Serious incident during live validation:** booking-service's first-ever
consumer group (`notification-cg`) started at `auto-offset-reset: earliest`
and hit a leftover headerless test message on `booking.confirmed` from
Sprint 2's manual `kafka-console-producer` testing. Neither service's
`KafkaConsumerConfig` wrapped the value deserializer in
`ErrorHandlingDeserializer`, so the deserialization failure threw a raw
`SerializationException` at Kafka's poll loop — entirely outside
`DefaultErrorHandler`'s retry/backoff, which can only handle exceptions
thrown *from* a listener invocation, not from failing to construct the
record in the first place. The consumer re-fetched and re-failed the same
record as fast as the CPU allowed: caught once at 2.5 million log lines in
under 10 seconds, but a first, uncaught occurrence had already produced a
**19.2 GB log file** and driven this Mac's disk to **100% capacity with
under 600 MB free** before being noticed via a routine disk-usage check.
Killed the runaway process immediately, deleted the log, confirmed the
runaway wasn't caused by accumulated stale test data (checked — only 5
bookings and 0 stray pending payments existed at the time), then
root-caused and fixed by wrapping both services' Kafka value deserializers
in `ErrorHandlingDeserializer`. Re-verified by restarting under a tight
line-count monitor (killing automatically past 100k lines): stable at
~950 lines, and the consumer group's offset had cleanly advanced past the
poison record (0 → 7, zero lag) rather than getting stuck.

Also found and fixed live: republishing the exact same
`month.end.trigger` test message without a `__TypeId__` Kafka header
(hand-crafted via `kafka-console-producer`, which doesn't add headers)
correctly got dead-lettered by the new `ErrorHandlingDeserializer` instead
of looping — confirming the fix generalizes beyond the one incident, not
just patching the specific message that caused it.

Validated end-to-end: 14 topics provisioned at the correct partition count,
a real booking → webhook → `PAYMENT_SUCCESS` audit event →
`BOOKING_CONFIRMED` WhatsApp notification (visible as a `SENT` row in
`notifications`), cancellation producing a matching `BOOKING_CANCELLED`
notification, a SQL-simulated overdue booking correctly detected by the
scheduled job and reminded, idempotent month-end-trigger handling (exact
duplicate `eventId`; a different `eventId` for the same month/year — both
independently confirmed via the Couchbase/Postgres idempotency checks), and
401 enforcement on the admin trigger endpoint. Full suites: 15/15
toy-service, 33/34 booking-service (only the pre-existing environmental
context-load failure). Committed as `fe91e48`.

**Not done in this session:**
- Keycloak realm still not imported — admin-role testing throughout remains
  limited to "no token → 401"; no test yet asserts an authenticated
  non-admin request is correctly rejected with 403.
- Sprint 5 (month-end report) not started — `MonthEndTriggerConsumer` is
  still the Sprint 4 skeleton (idempotency + placeholder only, no
  aggregation/PDF/MinIO).

---

## Session 4 — 2026-08-22 — Sprint 5: Month-End Report

**Goal:** Turn Sprint 4's `MonthEndTriggerConsumer` skeleton into the full
CLAUDE.md "Month-End Report Flow" — real aggregation, a PDF, a MinIO upload,
and `monthly.report.generated`.

### Steps

1. Added `BookingRepository.findByStartDateBetweenAndStatusIn(...)` and
   `ReportService`, which aggregates bookings whose `start_date` falls in
   the target month and that actually materialized
   (`CONFIRMED`/`ACTIVE`/`RETURNED`/`OVERDUE` — excluding `PENDING`, never
   paid, and `CANCELLED`, didn't happen): total bookings, total revenue
   (sum of `rentalAmount`), total deposits held, pending returns (still
   `ACTIVE`/`OVERDUE`), the top-rented toy (name resolved via a live Feign
   call back to toy-service, since booking-service doesn't own toy names),
   and revenue grouped by week-of-month.

2. Extended `PdfGeneratorService` with `generateMonthlyReportPdf` and a
   second `pdf.generation.duration` Timer instance tagged
   `type=monthly_report` (the existing one, tagged `booking_receipt`,
   stays as-is — Micrometer needs two distinct `Timer` objects, not one
   reused across tag values).

3. Added `MinioConfig`/`MinioService` — `MinioClient` bean from the
   already-scaffolded `minio.*` properties, uploading to
   `reports/{yyyy}/{MM}/monthly-report-{yyyy}-{MM}.pdf`, creating the
   bucket defensively if the `minio-init` Compose sidecar hasn't run yet.

4. Extended `MonthlyReportDocument` from Sprint 4's skeleton fields to the
   full shape CLAUDE.md documents (nested `TopToy`, `RevenueByWeek`,
   `pdfStoragePath`), and rewrote `MonthEndTriggerConsumer` to the complete
   flow: idempotency → `GENERATING` placeholder → aggregate → generate PDF
   → upload to MinIO → `SUCCESS` in both Postgres and Couchbase →
   `monthly.report.generated`. A generation failure (aggregation, PDF, or
   MinIO) is caught and recorded as a `FAILED` row with a
   `monthly.report.generated` event of its own, rather than left stuck at
   `GENERATING` forever or retried into a loop.

5. Added `MonthlyReportGeneratedEnvelope`/`Payload`/`Producer` (keyed by
   `month-year`, matching CLAUDE.md's topic table; no consumers yet — the
   table marks this topic "(future)").

6. Extended `AdminReportController` with list/detail/PDF-download, and a
   `ReportNotFoundException` → 404 mapping in `GlobalExceptionHandler`.

7. Wrote `ReportServiceTest` (aggregation correctness, including a
   toy-service-lookup-failure fallback), rewrote `MonthEndTriggerConsumerTest`
   for the full flow (added a report-generation-failure case), and added
   `AdminReportControllerTest`.

   **Bug found while writing that last test:** the first assertion that a
   non-admin authenticated request gets 403 instead came back 202 — because
   `@WebMvcTest(controllers = AdminReportController.class)` doesn't load the
   project's own `SecurityConfig`, so Spring Security's default
   "any authenticated request passes" applied instead of the real
   `hasRole("ADMIN")` rule. Every other controller test in this codebase has
   the same gap (untested), just never exercised it because none of them
   previously asserted role-based rejection, only "no token at all →
   401/403". Fixed *this* test with `@Import({SecurityConfig.class,
   JwtKeyConfig.class})`; the same fix should be applied to
   `AdminBookingControllerTest`/`BookingControllerTest` if role-specific
   assertions are ever added there.

8. Compiled clean, ran the new/updated tests (8 passing), then the full
   suite (41/42 — only the known environmental context-load failure).

### Live validation

9. Brought up `minio` + `minio-init` (the sidecar creates the
   `toy-rental-reports` bucket automatically via `mc mb --ignore-existing`
   — confirmed in its logs). Restarted booking-service under the same
   tight line-count monitor Sprint 4's incident established as standard
   practice before any first-time-code-path Kafka consumer run; stable.

10. Created three real bookings across two toys (two for `toy-001`, one for
    `toy-002`) in December 2026, confirmed each via the payment webhook.
    Postgres already had a fourth booking left over from Sprint 4's
    overdue-detection test (`toy-008`, status `OVERDUE`, `start_date`
    2026-12-01) — deliberately left in place rather than cleaned up, since
    it's a legitimate test of whether the aggregation query correctly
    includes `OVERDUE` bookings alongside `CONFIRMED` ones.

11. Published a hand-crafted `month.end.trigger` message (with an explicit
    `__TypeId__` header this time, learned from Sprint 4's incident) for
    month=12, year=2026. The consumer resolved `toy-001`'s name via a real
    Feign call to toy-service and completed successfully. Verified against
    hand-computed expected values — total bookings 4, total revenue
    ₹1,196.00, total deposits ₹5,100.00, pending returns 1, top toy
    `toy-001`/"LEGO Technic 42155" with 2 rentals, revenue by week
    [548, 449, 199] — and every one matched exactly, in both the Postgres
    row and the Couchbase document (which matches CLAUDE.md's example
    shape field-for-field).

12. Confirmed the PDF is genuinely in MinIO (`mc ls` inside a throwaway
    `minio/mc` container, since this Mac has no `mc` CLI installed
    locally) — 1.6 KB at the expected path.

13. Tested both idempotency paths independently: republishing the identical
    `eventId` → "Skipping already-processed"; publishing a *different*
    `eventId` for the same month/year → "Skipping ... report already
    exists in Couchbase". Both correctly left exactly one row in
    `monthly_reports`.

14. Ran the full suite one final time: 41/42 (same known environmental
    failure). Updated `SPRINTS.md`/`CLAUDE.md`'s sprint trackers and
    `CLAUDE.md`'s Known Bugs table for Sprints 3–5 (had been skipped after
    Session 3's commit — Sprint 2 got this treatment immediately, Sprint
    3/4 didn't, catching up now).

**Not done in this session:**
- Keycloak realm still not imported.
- `monthly.report.generated` has no consumer yet (matches CLAUDE.md's
  topic table, which marks it "(future)").
- Sprint 6 (Observability) not started — the custom Prometheus metrics
  CLAUDE.md documents are wired into code as they've come up
  (`toy.availability.cache.*`, `booking.created.total`,
  `booking.conflict.total`, `payment.success/failed.total`,
  `pdf.generation.duration`, `monthly.report.generated.total`), but no
  Grafana dashboards or alerting rules exist yet.

## Session 5 — 2026-08-22 — Sprint 6: Observability

**Goal:** Close the 7 S6 stories — correct the metric tags CLAUDE.md
requires, get a real Grafana dashboard auto-provisioned (not just JSON on
disk), verify correlationId end-to-end, a structured-logging audit, tracing
via Zipkin, and Prometheus alerting rules.

### Steps

1. Planning pass surfaced a real Sprint 3 gap before writing any new code:
   `PaymentService`'s `payment.success.total`/`payment.failed.total`
   counters were built once in the constructor as fixed, untagged `Counter`
   instances, despite CLAUDE.md's spec explicitly requiring
   `.tag("method", ...)` / `.tag("reason", ...)`. Fixed by switching to
   inline `Counter.builder(...).tag(...).register(meterRegistry)` at each
   increment call site — Micrometer's `register()` is idempotent by
   name+tags, so this is safe even though tag values (method, failure
   reason) are dynamic. Added `PaymentServiceTest` (6 tests) — a real
   coverage gap since Sprint 3, since `PaymentService` had no dedicated test
   file at all despite containing the multi-booking-per-order-id bug found
   and fixed in Session 3. Used a real `SimpleMeterRegistry` (not mocked)
   specifically to assert actual tagged metric values, not just that
   `.increment()` was called.

2. Fixed `OverdueDetectionService`: its `@Scheduled` job had no MDC
   correlationId set (no incoming HTTP request to derive one from), which
   Session 3's actual log output had already shown producing
   `correlationId= ` (blank) on both its own logs and the `booking.overdue`
   event's downstream consumer. Fixed by generating
   `"corr-overdue-" + UUID.randomUUID()` and wrapping the method body in
   try/finally around `MDC.put`/`MDC.remove`.

3. Ran a structured-logging audit across all three services: grepped for
   `log.info/warn/error/debug` usage without `@Slf4j` on the class (none
   found) and checked every `@Scheduled`/`@KafkaListener` class for MDC
   usage — the `OverdueDetectionService` gap above was the only one; all 4
   `@KafkaListener` classes (`BookingEventConsumer` in toy-service,
   `MonthEndTriggerConsumer`/`PaymentEventConsumer`/
   `BookingNotificationConsumer` in booking-service) already set MDC
   correctly.

4. Added Zipkin tracing: asked the user via AskUserQuestion whether to add
   `micrometer-tracing-bridge-brave` + `zipkin-reporter-brave` (not in
   CLAUDE.md's original dependency table) — approved. Added to toy-service
   and booking-service only (api-gateway has no functional routes wired up
   in any sprint yet, so tracing it now would be premature). 100% sampling,
   `ZIPKIN_ENDPOINT` env var, and `traceId`/`spanId` added to the console
   log pattern alongside `correlationId`.

5. Wrote Grafana provisioning from scratch — the `grafana/dashboards`
   folder didn't exist on disk at all, and `docker-compose.yml` only
   mounted it with no provisioning config, meaning any dashboard JSON
   dropped there would never have actually appeared in Grafana's UI (a
   dashboard file alone needs a provisioning YAML pointing at it, plus a
   datasource config so its queries resolve to something). Added
   `grafana/provisioning/{datasources,dashboards}` and an 11-panel
   dashboard (`toyrental-overview.json`) covering every custom metric
   CLAUDE.md documents, using real Prometheus metric names matching what
   Micrometer actually emits (dots → underscores, `_total` suffix on
   counters).

6. Wrote `prometheus/alerts.yml` — 6 rules (`ServiceDown`,
   `HighHttp5xxRate`, `PaymentFailureSpike`, `LowAvailabilityCacheHitRatio`,
   `KafkaConsumerLagHigh`, `JvmHeapNearLimit`), two of which are explicitly
   annotated as expected to trip under load right now since they're
   watching CLAUDE.md's intentional Sprint 7 bottlenecks (cold Couchbase
   cache, single-partition Kafka topics) rather than bugs. Wired via
   `rule_files` in `prometheus.yml`, mounted in `docker-compose.yml`.

### Live validation

7. Bringing up the new `zipkin`/`grafana`/`prometheus` containers hit a
   real, persistent Docker Hub pull stall — `docker compose up` and direct
   `docker pull` for `openzipkin/zipkin:latest` and `grafana/grafana:latest`
   both wedged repeatedly (zero byte progress for 4+ minutes at a time)
   while the daemon itself (`docker ps`/`docker images`) stayed responsive
   throughout. Killed and retried several times; even a full Docker Desktop
   restart (asked the user first, since it would stop every running
   container — they approved) didn't fully resolve it on the first retry
   after restart. What worked: pulling each image individually and letting
   Docker resume from whatever layers had already completed, rather than
   retrying the full multi-image `docker compose up` each time. Also hit a
   port collision once the containers were finally up: the containerized
   Grafana's default host port 3000 was already bound by a pre-existing
   Homebrew-installed native Grafana on this machine (unrelated to this
   project, running since before this session) — remapped the container to
   host port 3001 in `docker-compose.yml` rather than touching that
   unrelated service.

8. Restarted toy-service and booking-service under the same tight
   log-line-count monitor established as standard practice since Sprint
   4's disk-fill incident; both started clean (~5s and ~7s respectively),
   no runaway logging.

9. Verified correlationId end-to-end with a real flow, not just the
   `OverdueDetectionServiceTest` unit test: logged in as the seeded
   customer, created a booking with a custom `X-Correlation-ID` header,
   fired the payment webhook, and confirmed the *same* correlationId
   appeared in booking-service's `BookingEventProducer`/`PaymentEventProducer`
   publish logs, the Kafka-consumed `BookingNotificationConsumer` log, and
   toy-service's `BookingEventConsumer` consume log — the full HTTP →
   Kafka header → logs chain CLAUDE.md's Correlation ID Rule requires.

10. Verified Zipkin (`/api/v2/services` lists both services; a real trace
    from the flow above shows genuine spans including Spring Security
    filter-chain detail), Grafana provisioning (`/api/datasources` shows
    Prometheus auto-provisioned; `/api/search` shows the dashboard
    auto-loaded — the actual proof the provisioning fix worked, not just
    that the JSON is valid), Prometheus alert rules (`/api/v1/rules` shows
    all 6 loaded with `health":"ok"`), and the payment metric fix live
    (`payment_success_total{method="UPI"}` and
    `payment_failed_total{reason="NO_PENDING_PAYMENT_FOUND"}` both present
    with real label values via `/actuator/prometheus`).

11. Ran the full suite in both services with the correct DB env vars.
    First attempt without them surfaced a separate, genuinely confusing
    local-machine issue worth documenting: a native PostgreSQL 15 install
    (`/Library/PostgreSQL/15`, unrelated to this project) also listens on
    the default port 5432 on this machine, distinct from this project's
    Docker Postgres (mapped to host port 5433) — `mvn test` without env
    vars silently connects to the wrong server and fails Spring context
    tests with a misleading "password authentication failed" rather than
    "connection refused". With the correct env vars: toy-service 16/16,
    booking-service 48/48 (12 test classes) — a full green run, no known
    environmental failures remaining once pointed at the right database.

**Not done in this session:**
- Sprint 7 (Performance Engineering) not started — none of CLAUDE.md's six
  intentional bottlenecks touched.
- API gateway still has no functional routes; Zipkin tracing deliberately
  not added there this sprint since there's nothing to trace yet.

## Session 6 — 2026-08-22 — Sprint 8: Kubernetes (Sprint 7 skipped at user's request)

**Goal:** Deploy the platform to Docker Desktop's Kubernetes — raw manifests
for infra + app services, a Helm chart for the three app services, HPA,
probes, and a live smoke test. User explicitly asked to skip Sprint 7
(performance engineering) and do it manually themselves.

### Steps

1. Wrote Dockerfiles (multi-stage Maven build initially) for all three
   services, plus the full `k8s/` tree: `namespace.yaml` (toy-rental/infra/
   monitoring), `ingress.yaml` (everything through api-gateway only),
   `network-policy.yaml` (default-deny + explicit allows enforcing
   CLAUDE.md's DB-isolation rule at the network layer), `k8s/infra/*` for
   all 7 infra components plus prometheus/grafana/zipkin (StatefulSets for
   stateful services, `couchbase-init`/`minio-init` Jobs replacing
   docker-compose's manual setup step), `k8s/services/*` for the three app
   services with CLAUDE.md's exact probe timings/resource limits/HPA
   numbers, and a full Helm chart (`Chart.yaml`, `values.yaml`,
   `values-dev.yaml`, `values-prod.yaml`, `templates/`) templating just the
   three app services.

2. Docker Desktop's Kubernetes was disabled with no CLI toggle available
   (`docker desktop` CLI only exposes `kubernetes status/reset-cluster/
   images`) — asked the user to enable it via Settings, which they did.

3. Hit the same Docker Hub registry-pull stall pattern from Sprint 6,
   twice — once for `kind`'s own node-image pull, once for the toy-service
   build's base image. Both eventually cleared with patience/retries; one
   round needed a full Docker Desktop restart.

4. Discovered the build stalls weren't really about Docker Hub at all:
   `docker build`'s Maven dependency downloads inside the build VM were
   getting near-zero throughput, while a direct host-side `curl` to Maven
   Central showed ~120KB/s — real, working bandwidth. Root-caused this as
   the build VM's own network path being the bottleneck. Fixed by
   building each jar on the host (`./mvnw package -DskipTests`, fast since
   `~/.m2` was warm from this session's many `mvn test` runs) and
   rewriting all three Dockerfiles to a simple `COPY target/*.jar app.jar`
   single-stage build, eliminating the in-container Maven step entirely.
   Found and fixed a real bug along the way: `api-gateway/mvnw` was
   missing its executable bit (same class of bug as Session 1's
   `init-databases.sh`).

5. With docker-compose *and* the K8s cluster *and* 3 concurrent builds all
   running at once, the Docker Desktop VM's original ~8GB budget was
   exceeded — `kube-scheduler`/`kube-controller-manager` crash-looped from
   failing their own health checks under resource starvation. Fixed by
   stopping docker-compose (`docker compose down`, data preserved in named
   volumes) during K8s work.

6. Applied all `k8s/infra/*` and `k8s/services/*` manifests, plus
   `network-policy.yaml` and `metrics-server` (not shipped by default on
   Docker Desktop's Kubernetes, needed patching with
   `--kubelet-insecure-tls` for kind's self-signed kubelet certs).

7. `couchbase-0` would not stabilize — a three-layer investigation:
   - A too-aggressive `livenessProbe` (`initialDelaySeconds: 60`) was
     killing the container mid-startup before Couchbase Server's own slow
     boot could finish. Fixed by raising it to 120s.
   - Still crash-looping — `kubectl get pod -o jsonpath` revealed
     `reason: OOMKilled`. Escalated the container's own memory limit
     (1Gi → 2Gi → 4Gi); still OOMKilled every time, consistently within
     ~5-7 seconds regardless of the limit — inconsistent with a genuine
     gradual memory-hungry startup, which would survive longer at a
     bigger limit.
   - Asked the user to raise the whole Docker Desktop VM's memory from
     ~8GB to ~12GB to rule out node-wide pressure (they did, via Settings
     → Resources) — no change in outcome, proving the container's own
     cgroup limit was always the binding constraint, not node capacity.
     The actual root cause (why Couchbase's baseline footprint on this
     image/arm64/Docker-Desktop-kind combination exceeds 4Gi to boot) was
     not further diagnosed. Asked the user how to proceed; they chose to
     scale Couchbase to 0 replicas and continue without it, relying on
     the app services' documented graceful-degradation fallbacks — a
     legitimate, real limitation left open for future investigation, not
     silently worked around.

8. Deployed the app services via `helm install toy-rental helm/ -f
   helm/values-dev.yaml` — confirmed kind-mode Docker Desktop DOES share
   locally-built images with the cluster (no `kind load` step needed; the
   standalone `kind` CLI can't even see this cluster, since it runs inside
   Docker Desktop's own VM, not as a host-visible sibling container).

9. The live smoke test immediately proved Couchbase's fallback design
   didn't actually work as documented — two real bugs found and fixed:
   - `CouchbaseConfig` in both services called `bucket.waitUntilReady()`
     synchronously inside a `@Bean` factory method, which throws and
     fails the *entire* Spring context if Couchbase is unreachable — far
     worse than the intended graceful degradation, since the downstream
     fallback logic never gets the chance to run if the bean itself never
     exists. Fixed with a try/catch that logs a warning and returns the
     bucket reference anyway (`cluster.bucket(name)` itself never
     blocks — only `waitUntilReady` does).
   - Even after that, booking creation still 500'd:
     `CouchbaseAvailabilityRepository.findByToyId()` only caught
     `DocumentNotFoundException`, not the broader connectivity failure a
     fully-down Couchbase actually throws, so it propagated uncaught
     through `AvailabilityService` and crashed `/availability`.
     `LogicalDateService` already caught `RuntimeException` broadly and
     was fine — only the availability repository had the narrower gap.
     Broadened the catch to `CouchbaseException` (the SDK's common base
     class). Applied the identical fix to booking-service's
     `CouchbaseReportRepository` for consistency.

10. Redeploying the fix under the *same* image tag silently didn't work —
    the new pods kept running the old broken code. Root-caused: kind-mode
    Docker Desktop's node containerd caches images by tag and doesn't
    auto-refresh a same-tag rebuild from the host. Fixed by bumping the
    tag (ended at `1.0.2` after two fix iterations) and updating both the
    Helm values and the raw manifests to match — a real operational
    lesson for any future iteration on this specific setup.

### Live validation

11. Full flow via direct `kubectl port-forward` to toy-service (8081) and
    booking-service (8082), bypassing api-gateway — its own Spring
    Security JWT resource-server config points at Keycloak's issuer-uri,
    but Keycloak's realm was never imported (a known Sprint 3/4 gap, not
    something this sprint introduced or is scoped to fix, since
    booking-service actually issues its own self-signed JWTs). Registered
    a customer, logged in, called `/api/v1/toys/{id}/availability`
    directly and confirmed the fix live (`available: true` plus a real
    `Couchbase unavailable ... treating as absent` WARN in toy-service's
    logs, not a 500), created a booking, fired the WireMock payment
    webhook, and confirmed the booking reached `status: CONFIRMED,
    paymentStatus: SUCCESS`.

12. Port-forwarded Prometheus separately and confirmed all three app
    services (`api-gateway`, `toy-service`, `booking-service`) showed as
    `up` targets via their in-cluster DNS names.

13. Updated `SPRINTS.md`/`CLAUDE.md`'s sprint trackers (S7 marked skipped,
    S8 marked complete) and `CLAUDE.md`'s Known Bugs table with all five
    real bugs found this sprint (mvnw executable bit; the resource-
    contention crash-loop; couchbase left unresolved/deferred; the
    CouchbaseConfig eager-connection bug in both services; the
    CouchbaseAvailabilityRepository/CouchbaseReportRepository narrow-catch
    bug in both services).

**Not done in this session:**
- Sprint 7 (Performance Engineering) skipped at the user's explicit
  request — none of CLAUDE.md's six intentional bottlenecks touched, and
  the K8s manifests deliberately carry the same unfixed config forward.
- Couchbase is not actually running in the K8s deployment (scaled to 0,
  unresolved OOM issue) — genuinely open, not a false "fixed" claim.
- `minio-init`'s Job kept failing/retrying intermittently even once
  `minio-0` itself was stable — non-blocking for the smoke test (only
  affects month-end PDF report storage, not exercised this sprint), left
  running in the background rather than chased further.
- api-gateway's JWT validation is still blocked on the pre-existing
  Keycloak-realm gap from Sprint 3/4 — smoke-tested around it via direct
  service port-forwards instead of fixing it, since it's out of this
  sprint's scope.
- Sprint 9 (React Frontend) not started.

## Session 7 — 2026-08-22 — Follow-up: actually fixing the Couchbase OOMKill

**Goal:** User asked to revisit Session 6's deferred Couchbase issue rather
than leave it unresolved. Turned out to be a much simpler fix than the
"unresolved deeper compatibility issue" conclusion Session 6 landed on.

### Steps

1. Scaled `couchbase` back up and reproduced the OOMKill reliably (dies in
   5-12s, `exitCode 137`/`reason OOMKilled` every time).

2. Ruled out corrupted persisted state as a contributing factor: deleted
   the `couchbase-data` PVC entirely for a completely fresh volume — still
   OOMKilled in ~8s. Not a stale-state issue.

3. Working theory shifted to Couchbase 7.2.4's bundled Erlang/OTP having a
   cgroup v2 memory-detection bug (confirmed this node uses cgroup v2).
   Tried pulling a much newer image (`couchbase/server:community-8.0.2`)
   to test — hit a genuine Docker Hub registry stall on this pull
   specifically (zero progress for 6+ minutes, unlike this session's
   other "slow but real" pulls), killed and retried twice with no
   improvement.

4. Pivoted to a differently-tagged Couchbase image already sitting locally
   on this machine (`couchbase:7.2.0`, Enterprise Edition, no pull
   needed) as a faster alternate data point. It survived meaningfully
   longer on first boot (~28-31s vs. 7.2.4's consistent 5-12s) before
   also eventually OOMKilling on a second boot cycle — a real clue that
   the crash wasn't happening at the very first instant of startup.

5. Read Couchbase's own internal log files directly (`babysitter.log`,
   `info.log`) by mounting the same PVC in a throwaway debug pod after
   scaling the StatefulSet to 0 — something never tried in Session 6,
   since stdout only ever showed one banner line regardless of how the
   container died. Confirmed no Erlang crash dump was ever written
   (expected — a real kernel SIGKILL gives no chance to flush one), and
   that the process gets meaningfully far into Couchbase's own bootstrap
   (past babysitter start, past chronicle leader election) before dying,
   not failing at the very first instant.

6. Tested a much larger container memory limit (8Gi, given the node now
   has ~12GB after Session 6's VM increase) — **it worked.** `couchbase-0`
   reached `1/1 Ready` and stayed stable. `kubectl top pod` showed actual
   steady-state usage of **~4.85Gi** — the real number the whole time.
   Confirmed the same stability at 6Gi (tighter but still comfortable
   headroom above the observed usage).

7. **Real root cause:** never a cgroup/Erlang/arm64/image-version bug —
   Couchbase Server's genuine baseline memory footprint on this setup is
   ~4.85Gi, and every Session 6 test (1Gi/2Gi/4Gi) was simply below that
   threshold. Because Couchbase's startup sequence is fairly consistent,
   every attempt hit the same wall at a similar point regardless of the
   exact limit, which looked like "dies immediately no matter what" but
   was really "dies as soon as real usage crosses whatever ceiling is
   set, and real usage climbs to ~4.85Gi within the first several
   seconds." Set the committed manifest to `requests: 3Gi / limits: 6Gi`.

8. With Couchbase finally stable long enough to matter, two more real
   bugs surfaced that Session 6 never had the chance to hit:
   - `couchbase-init`/`minio-init` Jobs hung indefinitely — root cause:
     `network-policy.yaml`'s infra-namespace allow rule only permitted
     ingress from the `toy-rental` namespace, never same-namespace
     traffic, so `infra`-namespace Jobs calling `infra`-namespace
     Services were silently blocked by the default-deny policy. This had
     likely been broken since Session 6 first applied the network
     policies, but was masked by Couchbase itself never staying up long
     enough for anyone to notice the difference between "still starting"
     and "genuinely blocked." Fixed by adding a same-namespace
     `podSelector: {}` allow source.
   - Even with that fixed, the `monthly-reports` bucket still failed to
     create (misreported as "already exists" by the init script's
     blanket fallback) — real cause: `--cluster-ramsize 256` didn't leave
     room for three 100MB buckets. Raised to 512MB, and added a
     `couchbase-cli setting-cluster` fallback since re-running
     `cluster-init` against an already-initialized cluster silently
     no-ops instead of applying new quota values.

9. Re-verified live: `/api/v1/toys/{id}/availability` now returns a clean
   response with a genuine "not found, treating as fully available" log
   line (not the earlier "Couchbase unavailable" connectivity warning),
   and all three buckets confirmed present via `couchbase-cli
   bucket-list`. `minio-init` (which had also been silently failing)
   completed cleanly once the network policy fix was in.

10. Updated CLAUDE.md's Known Bugs table (flipped the couchbase entry from
    "unresolved/deferred" to the real fix; added entries for the
    NetworkPolicy and bucket-quota bugs) and SPRINTS.md's S8 narrative to
    reflect the actual resolution.

**Lesson worth keeping:** an OOMKilled container that dies at a
suspiciously *consistent* short duration regardless of the configured
limit isn't necessarily evidence the limit doesn't matter — it can just
mean every tested value was on the same side of the real threshold.
`kubectl top pod` (or a deliberately oversized limit as a diagnostic,
then dialing back once real usage is known) settles it directly instead
of theorizing about exotic causes.

## Session 8 — 2026-08-22 — Wiring up real admin authentication

**Goal:** User asked "Do admin have inventory management access?" — the honest
answer was "designed for it (AdminToyController exists, ROLE_ADMIN-gated,
exactly per CLAUDE.md), but there's no working path to actually get an admin
token in this codebase." User asked to close that gap.

### Design decision

Asked the user to choose between two approaches: (a) a separate admin login
with no schema changes (a single configured username/password, admin as a
platform-operator concern entirely distinct from customers), or (b) flagging
a customer row as admin via a new `is_admin` column. User chose (a) —
recommended, since there's no "staff" concept anywhere in the existing schema
and mixing admin into the customers table would blur two different personas.

### Steps

1. `booking-service`: added `POST /api/v1/admin/login` (new
   `AdminAuthController`/`AdminAuthService`), checked against
   `ADMIN_USERNAME`/`ADMIN_PASSWORD` env vars (dev defaults admin/admin123),
   issuing a JWT with `roles: ["ADMIN"]` via a new `JwtTokenService
   .issueAdminToken()` method alongside the existing customer-token issuance.
   Reused the existing `InvalidCredentialsException`/`GlobalExceptionHandler`
   path for wrong credentials rather than adding a new exception type.

2. `booking-service`: added `GET /oauth2/jwks` (new `JwksController`)
   exposing the RSA public half of its self-signed signing key as a JWK Set
   — necessary because the keypair is generated fresh in memory on every
   restart (Sprint 3's decision), so no other service can validate its
   tokens against a hardcoded/shared key.

3. `toy-service`: replaced its `issuer-uri` (pointed at a Keycloak realm
   that was never imported — the actual reason admin routes were completely
   unreachable before this) with `jwk-set-uri` pointing at booking-service's
   new endpoint. Updated `SecurityConfig`'s authority-extraction to read the
   flat `roles` claim booking-service actually issues, instead of Keycloak's
   nested `realm_access.roles` shape — mirrors booking-service's own
   converter exactly, since both services must agree on how to read the
   same tokens.

4. Cleanup: removed booking-service's own leftover `issuer-uri` config line
   — it was already dead (shadowed by its own custom `JwtDecoder` bean),
   just misleading to leave in place.

5. Rebuilt both services (jar built on host, Docker image under tag `1.0.3`
   per this project's established host-build-then-copy pattern), bumped the
   shared Helm `image.tag` and all three raw `k8s/services/*/*.yaml`
   references, added `ADMIN_USERNAME`/`ADMIN_PASSWORD` to booking-service's
   Secret and `BOOKING_SERVICE_JWK_SET_URI` to toy-service's env in both the
   raw manifests and the Helm templates, `helm upgrade`'d.

### Live validation — a real rolling-update deadlock along the way

Redeploying briefly hit a genuine resource deadlock, not a code bug:
Kubernetes kept the old (1.0.2) pods running alongside the new (1.0.3) ones
per normal `RollingUpdate` behavior, waiting for the new pods to pass
readiness before scaling the old ones down — but with 6 JVMs (3 old + 3 new)
competing for CPU simultaneously, the new pods took 100+ seconds to start
(confirmed via log timestamps showing 25+ second gaps between consecutive
startup lines that normally log within milliseconds) and kept missing their
liveness probe deadline, restarting repeatedly. **Deleting the old pods
individually didn't help — their ReplicaSet just respawned replacements,
making contention worse.** The actual fix: scale the *old ReplicaSets*
themselves to 0 (`kubectl scale replicaset ... --replicas=0`), which stops
them from respawning. Once only the 3 new pods remained, they finished
starting normally within their probe budget.

Full auth test matrix, all live against the running services:

| Check | Result |
|---|---|
| `GET /oauth2/jwks` | 200, real JWK Set with a `kid` |
| Admin login (correct creds) | 200, token with `roles:["ADMIN"]` |
| Admin login (wrong password) | 401 `INVALID_CREDENTIALS` |
| Admin token → `GET /api/v1/admin/bookings` (booking-service) | 200, real data |
| **Admin token → `PUT /api/v1/admin/toys/{id}/condition` (toy-service)** | **200** — the critical test: toy-service validated a token it never issued, purely via fetching booking-service's JWKS |
| Customer token → admin endpoint on booking-service | 403 |
| Customer token → admin endpoint on toy-service | 403 |
| Regular customer flow (login, profile, browse) | Unaffected, still 200 |

Updated the Postman collection: added `Auth > Admin Login` (mirrors customer
Login's auto-token-capture pattern into `{{adminToken}}`), removed the
"not runnable" warnings from all four Admin folders, added a JWKS diagnostic
request to Health & Observability, added `adminUsername`/`adminPassword`
variables. Re-ran newman against the live services — all core admin
endpoints (Inventory, Low Stock, All Bookings, Today's Deliveries/Pickups,
Overdue Returns) returned real 200s. A couple of admin write-endpoint
requests in that specific run hit expected 404s from earlier requests in the
same sequential run mutating the same demo toy (delete-then-image-add on
`toy-002`) — self-inflicted by testing folders out of their intended
standalone context, not an auth bug. Reactivated `toy-002` afterward via a
direct Postgres update (no "undelete" endpoint exists) since it's the
collection's default example toy.

**Not done in this session:**
- api-gateway's own Keycloak gap is untouched — it still can't route
  authenticated requests. Only toy-service and booking-service were fixed,
  since that's what the admin-inventory-access question actually needed.
  Fixing api-gateway would need the same jwk-set-uri + roles-converter
  treatment, a natural follow-up if the gateway route ever needs to work.
