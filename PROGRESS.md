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
