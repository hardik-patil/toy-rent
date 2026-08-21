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
