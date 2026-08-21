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
