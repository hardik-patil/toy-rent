# CLAUDE.md — ToyRental Platform

This file is the single source of truth for Claude Code working on this project.
Read this entire file before writing any code, creating any file, or making any decision.
Never deviate from the decisions documented here without explicit instruction.

---

## Project Overview

**Name:** ToyRental Platform
**Domain:** Premium kids toy rental marketplace — Navi Mumbai, India
**Purpose:** Learning vehicle for performance engineering, Kubernetes management, and bottleneck identification. Secondary goal: real business launch.
**Owner:** Hardik Patil — Performance Test Engineer

---

## Repository Structure

```
toy-rental/
├── CLAUDE.md                          ← you are here
├── api-gateway/                       ← Spring Cloud Gateway
│   ├── pom.xml
│   └── src/main/
│       ├── java/com/toyrental/gateway/
│       │   ├── ApiGatewayApplication.java
│       │   ├── config/
│       │   │   ├── GatewayConfig.java
│       │   │   ├── SecurityConfig.java
│       │   │   └── CorrelationIdFilter.java
│       │   └── filter/
│       │       └── JwtAuthFilter.java
│       └── resources/
│           ├── application.yml
│           └── application-dev.yml
│
├── toy-service/                       ← Toy catalogue + availability
│   ├── pom.xml
│   └── src/
│       ├── main/
│       │   ├── java/com/toyrental/toy/
│       │   │   ├── ToyServiceApplication.java
│       │   │   ├── controller/
│       │   │   │   ├── ToyController.java
│       │   │   │   ├── AvailabilityController.java
│       │   │   │   └── AdminToyController.java
│       │   │   ├── service/
│       │   │   │   ├── ToyService.java
│       │   │   │   ├── AvailabilityService.java
│       │   │   │   └── LogicalDateService.java
│       │   │   ├── repository/
│       │   │   │   ├── ToyRepository.java
│       │   │   │   ├── ToyImageRepository.java
│       │   │   │   └── ToyAvailabilityLogRepository.java
│       │   │   ├── entity/
│       │   │   │   ├── Toy.java
│       │   │   │   ├── ToyImage.java
│       │   │   │   └── ToyAvailabilityLog.java
│       │   │   ├── dto/
│       │   │   │   ├── ToyRequest.java
│       │   │   │   ├── ToyResponse.java
│       │   │   │   ├── AvailabilityResponse.java
│       │   │   │   └── PagedResponse.java
│       │   │   ├── kafka/
│       │   │   │   └── BookingEventConsumer.java
│       │   │   ├── couchbase/
│       │   │   │   ├── ToyAvailabilityDocument.java
│       │   │   │   ├── LogicalDateDocument.java
│       │   │   │   └── CouchbaseAvailabilityRepository.java
│       │   │   ├── exception/
│       │   │   │   ├── GlobalExceptionHandler.java
│       │   │   │   ├── ToyNotFoundException.java
│       │   │   │   └── ToyNotAvailableException.java
│       │   │   └── config/
│       │   │       ├── CouchbaseConfig.java
│       │   │       ├── KafkaConsumerConfig.java
│       │   │       └── SecurityConfig.java
│       │   └── resources/
│       │       ├── application.yml
│       │       ├── application-dev.yml
│       │       └── db/migration/
│       │           ├── V1__create_toys.sql
│       │           ├── V2__create_toy_images.sql
│       │           ├── V3__create_toy_availability_log.sql
│       │           └── V4__seed_sample_toys.sql
│       └── test/
│           └── java/com/toyrental/toy/
│               ├── controller/
│               │   └── ToyControllerTest.java
│               ├── service/
│               │   └── AvailabilityServiceTest.java
│               └── kafka/
│                   └── BookingEventConsumerTest.java
│
├── booking-service/                   ← Bookings + payments + reports
│   ├── pom.xml
│   └── src/
│       ├── main/
│       │   ├── java/com/toyrental/booking/
│       │   │   ├── BookingServiceApplication.java
│       │   │   ├── controller/
│       │   │   │   ├── CustomerController.java
│       │   │   │   ├── BookingController.java
│       │   │   │   ├── PaymentController.java
│       │   │   │   ├── AdminBookingController.java
│       │   │   │   └── AdminReportController.java
│       │   │   ├── service/
│       │   │   │   ├── CustomerService.java
│       │   │   │   ├── BookingService.java
│       │   │   │   ├── PaymentService.java
│       │   │   │   ├── NotificationService.java
│       │   │   │   ├── ReportService.java
│       │   │   │   └── PdfGeneratorService.java
│       │   │   ├── repository/
│       │   │   │   ├── CustomerRepository.java
│       │   │   │   ├── BookingRepository.java
│       │   │   │   ├── PaymentRepository.java
│       │   │   │   └── NotificationRepository.java
│       │   │   ├── entity/
│       │   │   │   ├── Customer.java
│       │   │   │   ├── Booking.java
│       │   │   │   ├── Payment.java
│       │   │   │   └── Notification.java
│       │   │   ├── dto/
│       │   │   │   ├── BookingRequest.java
│       │   │   │   ├── BookingResponse.java
│       │   │   │   ├── PaymentRequest.java
│       │   │   │   ├── PaymentResponse.java
│       │   │   │   ├── CustomerRequest.java
│       │   │   │   └── CustomerResponse.java
│       │   │   ├── kafka/
│       │   │   │   ├── BookingEventProducer.java
│       │   │   │   ├── PaymentEventConsumer.java
│       │   │   │   └── MonthEndTriggerConsumer.java
│       │   │   ├── client/
│       │   │   │   └── ToyServiceClient.java     ← Feign client
│       │   │   ├── couchbase/
│       │   │   │   ├── MonthlyReportDocument.java
│       │   │   │   └── CouchbaseReportRepository.java
│       │   │   ├── exception/
│       │   │   │   ├── GlobalExceptionHandler.java
│       │   │   │   ├── BookingNotFoundException.java
│       │   │   │   ├── ToyNotAvailableException.java
│       │   │   │   └── PaymentFailedException.java
│       │   │   └── config/
│       │   │       ├── CouchbaseConfig.java
│       │   │       ├── KafkaProducerConfig.java
│       │   │       ├── KafkaConsumerConfig.java
│       │   │       ├── FeignConfig.java
│       │   │       ├── MinioConfig.java
│       │   │       ├── Resilience4jConfig.java
│       │   │       └── SecurityConfig.java
│       │   └── resources/
│       │       ├── application.yml
│       │       ├── application-dev.yml
│       │       └── db/migration/
│       │           ├── V1__create_customers.sql
│       │           ├── V2__create_bookings.sql
│       │           ├── V3__create_payments.sql
│       │           ├── V4__create_notifications.sql
│       │           ├── V5__create_monthly_reports.sql
│       │           └── V6__seed_sample_customer.sql
│       └── test/
│           └── java/com/toyrental/booking/
│               ├── controller/
│               │   └── BookingControllerTest.java
│               ├── service/
│               │   └── BookingServiceTest.java
│               └── kafka/
│                   └── MonthEndTriggerConsumerTest.java
│
├── k8s/
│   ├── namespace.yaml
│   ├── ingress.yaml
│   ├── network-policy.yaml
│   ├── infra/
│   │   ├── postgres/
│   │   ├── couchbase/
│   │   ├── kafka/
│   │   ├── redis/
│   │   ├── minio/
│   │   ├── keycloak/
│   │   ├── wiremock/
│   │   ├── prometheus/
│   │   └── grafana/
│   └── services/
│       ├── api-gateway/
│       ├── toy-service/
│       └── booking-service/
│
├── helm/
│   ├── Chart.yaml
│   ├── values.yaml
│   ├── values-dev.yaml
│   ├── values-prod.yaml
│   └── templates/
│
├── wiremock/
│   └── mappings/
│       ├── razorpay-stub.json
│       └── whatsapp-stub.json
│
├── prometheus/
│   └── prometheus.yml
│
├── grafana/
│   └── dashboards/
│
└── docker-compose.yml
```

---

## Technology Stack

### Versions — Never change these without explicit instruction

| Technology | Version |
|---|---|
| Java | 17 |
| Spring Boot | 3.2.5 |
| Spring Cloud | 2023.0.1 |
| Maven | 3.9.x |
| PostgreSQL | 15 |
| Couchbase SDK | 3.6.0 |
| Kafka | 3.7.x |
| Flyway | 10.x (via Spring Boot BOM) |
| Lombok | Latest via Spring Boot BOM |
| iText | 8.0.3 |
| MinIO SDK | 8.5.9 |
| Resilience4j | 2.2.0 |
| Micrometer | Latest via Spring Boot BOM |
| Docker | Latest |
| Kubernetes | 1.29 (Docker Desktop) |
| Helm | 3.x |

---

## Services — Ports and Responsibilities

| Service | Port | Package | Responsibility |
|---|---|---|---|
| api-gateway | 8080 | com.toyrental.gateway | Routing, JWT validation, rate limiting, correlation ID, circuit breaker |
| toy-service | 8081 | com.toyrental.toy | Toy catalogue, search, availability, Couchbase snapshots |
| booking-service | 8082 | com.toyrental.booking | Bookings, payments, notifications, monthly PDF report |

---

## Database Ownership — Strict Service Boundaries

**CRITICAL: No service ever touches another service's database.**
**No shared tables. No cross-service joins. Ever.**

### toy-service owns:
- PostgreSQL database: `toydb`
- Tables: `toys`, `toy_images`, `toy_availability_log`
- Couchbase bucket: `toy-availability`
- Couchbase bucket: `logical-date`

### booking-service owns:
- PostgreSQL database: `bookingdb`
- Tables: `customers`, `bookings`, `payments`, `notifications`, `monthly_reports`
- Couchbase bucket: `monthly-reports`

### api-gateway owns:
- Nothing. Stateless. Uses Redis only for rate limiting.

---

## PostgreSQL Schema — toy-service

```sql
-- V1__create_toys.sql
CREATE TABLE toys (
    id                VARCHAR(36)     PRIMARY KEY,
    name              VARCHAR(255)    NOT NULL,
    description       TEXT,
    brand             VARCHAR(100),
    category          VARCHAR(100)    NOT NULL,
    age_group         VARCHAR(50)     NOT NULL,
    condition         VARCHAR(50)     NOT NULL DEFAULT 'GOOD',
    status            VARCHAR(50)     NOT NULL DEFAULT 'AVAILABLE',
    mrp               NUMERIC(10,2)   NOT NULL,
    weekly_price      NUMERIC(10,2)   NOT NULL,
    monthly_price     NUMERIC(10,2)   NOT NULL,
    deposit_amount    NUMERIC(10,2)   NOT NULL,
    is_active         BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at        TIMESTAMP       NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMP       NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_toys_category    ON toys(category);
CREATE INDEX idx_toys_age_group   ON toys(age_group);
CREATE INDEX idx_toys_status      ON toys(status);
CREATE INDEX idx_toys_is_active   ON toys(is_active);
CREATE INDEX idx_toys_browse      ON toys(category, age_group, is_active, status);

-- V2__create_toy_images.sql
CREATE TABLE toy_images (
    id           VARCHAR(36)   PRIMARY KEY,
    toy_id       VARCHAR(36)   NOT NULL REFERENCES toys(id) ON DELETE CASCADE,
    url          TEXT          NOT NULL,
    is_primary   BOOLEAN       NOT NULL DEFAULT FALSE,
    sort_order   INT           NOT NULL DEFAULT 0,
    created_at   TIMESTAMP     NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_toy_images_toy_id ON toy_images(toy_id);

-- V3__create_toy_availability_log.sql
CREATE TABLE toy_availability_log (
    id            VARCHAR(36)   PRIMARY KEY,
    toy_id        VARCHAR(36)   NOT NULL REFERENCES toys(id),
    booking_id    VARCHAR(36),
    blocked_from  DATE          NOT NULL,
    blocked_to    DATE          NOT NULL,
    action        VARCHAR(50)   NOT NULL,
    reason        VARCHAR(100),
    created_at    TIMESTAMP     NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_avail_log_toy_id ON toy_availability_log(toy_id);
CREATE INDEX idx_avail_log_dates  ON toy_availability_log(toy_id, blocked_from, blocked_to);
```

## PostgreSQL Schema — booking-service

```sql
-- V1__create_customers.sql
CREATE TABLE customers (
    id             VARCHAR(36)    PRIMARY KEY,
    name           VARCHAR(255)   NOT NULL,
    phone          VARCHAR(15)    NOT NULL UNIQUE,
    email          VARCHAR(255)   UNIQUE,
    password_hash  VARCHAR(255)   NOT NULL,
    area           VARCHAR(100),
    flat           VARCHAR(100),
    building       VARCHAR(255),
    city           VARCHAR(100)   NOT NULL DEFAULT 'Navi Mumbai',
    pincode        VARCHAR(10),
    is_active      BOOLEAN        NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMP      NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMP      NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_customers_phone ON customers(phone);
CREATE INDEX idx_customers_email ON customers(email);

-- V2__create_bookings.sql
CREATE TABLE bookings (
    id                  VARCHAR(36)    PRIMARY KEY,
    toy_id              VARCHAR(36)    NOT NULL,
    customer_id         VARCHAR(36)    NOT NULL REFERENCES customers(id),
    start_date          DATE           NOT NULL,
    end_date            DATE           NOT NULL,
    rental_type         VARCHAR(20)    NOT NULL,
    rental_amount       NUMERIC(10,2)  NOT NULL,
    deposit_amount      NUMERIC(10,2)  NOT NULL,
    total_amount        NUMERIC(10,2)  NOT NULL,
    status              VARCHAR(50)    NOT NULL DEFAULT 'PENDING',
    payment_status      VARCHAR(50)    NOT NULL DEFAULT 'PENDING',
    delivery_flat       VARCHAR(100),
    delivery_building   VARCHAR(255),
    delivery_area       VARCHAR(100),
    delivery_city       VARCHAR(100),
    delivery_pincode    VARCHAR(10),
    cancelled_by        VARCHAR(50),
    cancel_reason       TEXT,
    cancelled_at        TIMESTAMP,
    returned_at         TIMESTAMP,
    return_condition    VARCHAR(50),
    damage_notes        TEXT,
    created_at          TIMESTAMP      NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP      NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_bookings_toy_id      ON bookings(toy_id);
CREATE INDEX idx_bookings_customer_id ON bookings(customer_id);
CREATE INDEX idx_bookings_status      ON bookings(status);
CREATE INDEX idx_bookings_dates       ON bookings(start_date, end_date);
CREATE INDEX idx_bookings_end_date    ON bookings(end_date) WHERE status = 'ACTIVE';
CREATE INDEX idx_bookings_admin       ON bookings(status, start_date, end_date);

-- V3__create_payments.sql
CREATE TABLE payments (
    id                   VARCHAR(36)    PRIMARY KEY,
    booking_id           VARCHAR(36)    NOT NULL REFERENCES bookings(id),
    customer_id          VARCHAR(36)    NOT NULL REFERENCES customers(id),
    amount               NUMERIC(10,2)  NOT NULL,
    type                 VARCHAR(50)    NOT NULL,
    method               VARCHAR(50)    NOT NULL,
    status               VARCHAR(50)    NOT NULL DEFAULT 'PENDING',
    razorpay_order_id    VARCHAR(255),
    razorpay_payment_id  VARCHAR(255),
    razorpay_signature   VARCHAR(500),
    failure_reason       VARCHAR(255),
    failure_code         VARCHAR(100),
    refund_id            VARCHAR(255),
    refunded_at          TIMESTAMP,
    created_at           TIMESTAMP      NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMP      NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_payments_booking_id ON payments(booking_id);
CREATE INDEX idx_payments_status     ON payments(status);

-- V4__create_notifications.sql
CREATE TABLE notifications (
    id             VARCHAR(36)    PRIMARY KEY,
    booking_id     VARCHAR(36)    REFERENCES bookings(id),
    customer_id    VARCHAR(36)    NOT NULL REFERENCES customers(id),
    type           VARCHAR(100)   NOT NULL,
    channel        VARCHAR(50)    NOT NULL,
    message        TEXT           NOT NULL,
    status         VARCHAR(50)    NOT NULL DEFAULT 'PENDING',
    sent_at        TIMESTAMP,
    failure_reason VARCHAR(255),
    created_at     TIMESTAMP      NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_notifications_booking_id  ON notifications(booking_id);
CREATE INDEX idx_notifications_customer_id ON notifications(customer_id);
CREATE INDEX idx_notifications_status      ON notifications(status);

-- V5__create_monthly_reports.sql
CREATE TABLE monthly_reports (
    id                VARCHAR(36)    PRIMARY KEY,
    month             INT            NOT NULL,
    year              INT            NOT NULL,
    total_bookings    INT            NOT NULL DEFAULT 0,
    total_revenue     NUMERIC(10,2)  NOT NULL DEFAULT 0,
    total_deposits    NUMERIC(10,2)  NOT NULL DEFAULT 0,
    pending_returns   INT            NOT NULL DEFAULT 0,
    top_toy_id        VARCHAR(36),
    top_toy_name      VARCHAR(255),
    pdf_storage_path  TEXT,
    status            VARCHAR(50)    NOT NULL DEFAULT 'GENERATING',
    generated_at      TIMESTAMP,
    created_at        TIMESTAMP      NOT NULL DEFAULT NOW(),
    UNIQUE (month, year)
);
CREATE INDEX idx_reports_month_year ON monthly_reports(year, month);
```

---

## Couchbase Documents

### Bucket: toy-availability (toy-service)
```json
Document ID: avail::toy-{toyId}
{
  "id": "avail::toy-042",
  "toyId": "toy-042",
  "toyName": "LEGO Technic 42155",
  "status": "AVAILABLE",
  "blockedDates": [
    {
      "bookingId": "bkg-00291",
      "from": "2025-08-01",
      "to": "2025-08-07",
      "reason": "BOOKING"
    }
  ],
  "nextAvailable": "2025-08-08",
  "lastUpdated": "2025-07-31T14:22:00Z"
}
```

### Bucket: logical-date (toy-service)
```json
Document ID: logical-date::current
{
  "id": "logical-date::current",
  "currentDate": "2025-08-31",
  "currentDateTime": "2025-08-31T23:59:00Z",
  "isMonthEnd": true,
  "isOverdueCheckDay": true,
  "businessMonth": 8,
  "businessYear": 2025,
  "timezone": "Asia/Kolkata",
  "mode": "REAL",
  "lastUpdated": "2025-08-31T00:00:01Z",
  "updatedBy": "system"
}
```

### Bucket: monthly-reports (booking-service)
```json
Document ID: report::yyyy-mm
{
  "id": "report::2025-08",
  "reportId": "rpt-2025-08",
  "month": 8,
  "year": 2025,
  "totalBookings": 47,
  "totalRevenue": 18650.00,
  "totalDeposits": 42000.00,
  "pendingReturns": 3,
  "topToy": {
    "toyId": "toy-042",
    "name": "LEGO Technic 42155",
    "rentals": 8
  },
  "revenueByWeek": [
    { "week": 1, "revenue": 4200.00 },
    { "week": 2, "revenue": 5100.00 },
    { "week": 3, "revenue": 4750.00 },
    { "week": 4, "revenue": 4600.00 }
  ],
  "pdfStoragePath": "reports/2025/08/monthly-report-2025-08.pdf",
  "generatedAt": "2025-08-31T00:02:15Z",
  "status": "SUCCESS"
}
```

---

## Kafka Topics — Complete Reference

| Topic | Partitions | Publisher | Consumer Groups | Key | DLT |
|---|---|---|---|---|---|
| booking.confirmed | 6 | booking-service | toy-service-cg, notification-cg | toyId | booking.confirmed.DLT |
| booking.cancelled | 6 | booking-service | toy-service-cg, notification-cg | toyId | booking.cancelled.DLT |
| booking.overdue | 3 | booking-service | notification-cg | customerId | booking.overdue.DLT |
| payment.success | 6 | booking-service | booking-internal-cg | bookingId | payment.success.DLT |
| payment.failed | 6 | booking-service | booking-internal-cg | bookingId | payment.failed.DLT |
| month.end.trigger | 1 | Admin API | report-cg | month-year | month.end.trigger.DLT |
| monthly.report.generated | 1 | booking-service | (future) | month-year | monthly.report.generated.DLT |

### Kafka Event Envelope — Every event MUST follow this structure
```json
{
  "eventId": "evt-uuid-v4",
  "eventType": "BOOKING_CONFIRMED",
  "eventVersion": "v1",
  "occurredAt": "2025-08-01T10:22:00Z",
  "correlationId": "corr-abc-123",
  "source": "booking-service",
  "payload": { }
}
```

### Idempotency Rule
Every Kafka consumer MUST check eventId before processing.
If eventId already processed → log and skip. Never reprocess.

### Correlation ID Rule
correlationId flows: HTTP header X-Correlation-ID → Kafka message header → all log statements.
Every log statement MUST include correlationId and eventId where applicable.

---

## API Endpoints — Complete Reference

### toy-service (/api/v1/toys/*)
```
GET    /api/v1/toys                                → paginated catalogue
GET    /api/v1/toys/{toyId}                        → toy detail
GET    /api/v1/toys/search?q=&category=&age=       → search
GET    /api/v1/toys/categories                     → all categories
GET    /api/v1/toys/{toyId}/availability?from=&to= → check availability
GET    /api/v1/toys/{toyId}/availability/calendar  → month calendar
GET    /api/v1/toys/available?from=&to=            → browse available
POST   /api/v1/toys                                → add toy (admin)
PUT    /api/v1/toys/{toyId}                        → update toy (admin)
DELETE /api/v1/toys/{toyId}                        → soft delete (admin)
POST   /api/v1/toys/{toyId}/images                 → upload image (admin)
GET    /api/v1/admin/toys/inventory                → inventory status
GET    /api/v1/admin/toys/low-stock                → low availability
PUT    /api/v1/admin/toys/{toyId}/condition        → update condition
GET    /internal/v1/toys/{toyId}                   → internal call from booking-service
PUT    /internal/v1/toys/{toyId}/availability      → update availability (Kafka consumer)
GET    /actuator/health
GET    /actuator/health/liveness
GET    /actuator/health/readiness
GET    /actuator/prometheus
```

### booking-service (/api/v1/*)
```
POST   /api/v1/customers/register                  → register customer
POST   /api/v1/customers/login                     → login → JWT
GET    /api/v1/customers/me                        → my profile
PUT    /api/v1/customers/me                        → update profile
PUT    /api/v1/customers/me/address                → update address
GET    /api/v1/customers/me/bookings               → my bookings

POST   /api/v1/bookings                            → create booking
GET    /api/v1/bookings/{bookingId}                → booking detail
GET    /api/v1/bookings/{bookingId}/receipt        → PDF receipt
PUT    /api/v1/bookings/{bookingId}/cancel         → cancel
PUT    /api/v1/bookings/{bookingId}/extend         → extend

POST   /api/v1/payments/initiate                   → initiate UPI (WireMock)
POST   /api/v1/payments/webhook                    → Razorpay callback (WireMock)
GET    /api/v1/payments/{paymentId}                → payment status
POST   /api/v1/payments/{bookingId}/refund         → refund deposit (admin)

GET    /api/v1/admin/bookings                      → all bookings
GET    /api/v1/admin/bookings/today/deliveries     → today's deliveries
GET    /api/v1/admin/bookings/today/pickups        → today's pickups
GET    /api/v1/admin/bookings/overdue              → overdue returns
PUT    /api/v1/admin/bookings/{bookingId}/return   → mark returned
PUT    /api/v1/admin/bookings/{bookingId}/confirm  → manual confirm

POST   /api/v1/admin/reports/trigger               → publish month.end.trigger
GET    /api/v1/admin/reports                       → list reports
GET    /api/v1/admin/reports/{reportId}            → report metadata
GET    /api/v1/admin/reports/{reportId}/pdf        → download PDF

GET    /actuator/health
GET    /actuator/health/liveness
GET    /actuator/health/readiness
GET    /actuator/prometheus
```

---

## Standard Error Response — All Services

Every error response MUST follow this exact shape. No exceptions.

```json
{
  "timestamp": "2025-07-31T14:22:00Z",
  "status": 409,
  "error": "TOY_NOT_AVAILABLE",
  "message": "Toy toy-042 is already booked for Aug 1-7",
  "correlationId": "corr-abc-123",
  "path": "/api/v1/bookings"
}
```

GlobalExceptionHandler in EVERY service handles:
- ResourceNotFoundException → 404
- ToyNotAvailableException → 409
- ValidationException → 400
- PaymentFailedException → 402
- RuntimeException (catch-all) → 500

---

## WireMock Stubs — Location and Behaviour

WireMock runs at: `http://wiremock:9090` (Docker Compose) / `http://wiremock.infra.svc.cluster.local:9090` (K8s)

### Stub 1: Razorpay UPI — POST /v1/orders
```json
Request:  POST /v1/orders
Response: 200
{
  "id": "order_mock123",
  "status": "created",
  "amount": 199900,
  "currency": "INR",
  "receipt": "bkg-00291"
}
```

### Stub 2: Razorpay Payment Verify — POST /v1/payments/verify
```json
Request:  POST /v1/payments/verify
Response: 200
{
  "razorpay_payment_id": "pay_mock456",
  "razorpay_order_id": "order_mock123",
  "razorpay_signature": "mock_signature_abc"
}
```

### Stub 3: WhatsApp Send — POST /whatsapp/send
```json
Request:  POST /whatsapp/send
Response: 200
{
  "messageId": "wamid.mock789",
  "status": "sent"
}
```

WireMock stub files location: `wiremock/mappings/`
- `razorpay-order-stub.json`
- `razorpay-verify-stub.json`
- `whatsapp-stub.json`

---

## Booking Flow — Critical Logic

This is the most important flow. Follow exactly.

```
POST /api/v1/bookings
  1. Validate JWT → extract customerId
  2. Call toy-service: GET /internal/v1/toys/{toyId} → verify toy exists
  3. Call toy-service: GET /api/v1/toys/{toyId}/availability?from=&to=
     → reads Couchbase avail::toy-{toyId}
     → if NOT available → throw ToyNotAvailableException → 409
  4. BEGIN TRANSACTION
     4a. INSERT INTO bookings (status=PENDING, payment_status=PENDING)
     4b. SELECT * FROM bookings WHERE toy_id=? AND date_range_overlaps
         FOR UPDATE                    ← CRITICAL: pessimistic lock
         If overlap found → ROLLBACK → throw ToyNotAvailableException
  5. Call WireMock: POST /v1/orders → get razorpay_order_id
  6. INSERT INTO payments (status=PENDING, razorpay_order_id)
  7. COMMIT TRANSACTION
  8. Return BookingResponse (status=PENDING, payment pending)

POST /api/v1/payments/webhook (Razorpay callback from WireMock)
  1. Verify razorpay_signature
  2. UPDATE payments SET status=SUCCESS, razorpay_payment_id
  3. UPDATE bookings SET status=CONFIRMED, payment_status=SUCCESS
  4. Publish → Kafka: booking.confirmed
  5. Return 200

Kafka Consumer (toy-service): booking.confirmed
  1. Check eventId idempotency
  2. Add blocked dates to Couchbase avail::toy-{toyId}
  3. Recalculate nextAvailable
  4. UPDATE toy_availability_log (action=BLOCKED)

Kafka Consumer (booking-service internal): booking.confirmed
  1. Check eventId idempotency
  2. POST WireMock /whatsapp/send → send confirmation message
  3. INSERT INTO notifications (status=SENT)
```

---

## Month-End Report Flow

```
POST /api/v1/admin/reports/trigger { month: 8, year: 2025 }
  → Publish Kafka: month.end.trigger

Kafka Consumer (booking-service): month.end.trigger
  1. Check idempotency: does report::2025-08 exist in Couchbase?
     YES → skip, return
     NO  → proceed
  2. INSERT INTO monthly_reports (status=GENERATING)
  3. Query bookings for month 8 year 2025
  4. Compute: totalBookings, totalRevenue, topToy, revenueByWeek
  5. Generate PDF using iText → byte[]
  6. Upload PDF to MinIO → path: reports/2025/08/monthly-report-2025-08.pdf
  7. UPDATE monthly_reports SET pdf_storage_path, status=SUCCESS, generated_at
  8. Save Couchbase doc: report::2025-08
  9. Publish Kafka: monthly.report.generated
```

---

## Logical Date — How Every Service Uses It

**NEVER use LocalDate.now() directly anywhere in business logic.**
**Always use LogicalDateService.getCurrentDate().**

```java
// LogicalDateService reads from Couchbase bucket: logical-date
// Document: logical-date::current
// Falls back to LocalDate.now() if Couchbase unavailable

@Service
public class LogicalDateService {
    public LocalDate getCurrentDate() {
        // Read from Couchbase logical-date::current
        // Return currentDate field
        // Cache in Redis for 60 seconds
    }

    public boolean isMonthEnd() {
        // Return isMonthEnd field from Couchbase doc
    }
}
```

Overdue check uses logical date.
Month-end trigger eligibility uses logical date.
PDF report period uses logical date.

---

## Enums — Use These Exactly

```java
// Booking status
enum BookingStatus {
    PENDING, CONFIRMED, ACTIVE, RETURNED, CANCELLED, OVERDUE
}

// Payment status
enum PaymentStatus {
    PENDING, SUCCESS, FAILED, REFUNDED
}

// Payment type
enum PaymentType {
    RENTAL, DEPOSIT, REFUND, LATE_FEE
}

// Payment method
enum PaymentMethod {
    UPI, COD
}

// Rental type
enum RentalType {
    WEEKLY, MONTHLY
}

// Toy status
enum ToyStatus {
    AVAILABLE, RENTED, DAMAGED, CLEANING, RETIRED
}

// Toy condition
enum ToyCondition {
    NEW, GOOD, FAIR, POOR
}

// Notification type
enum NotificationType {
    BOOKING_CONFIRMED, BOOKING_CANCELLED, PAYMENT_SUCCESS,
    PAYMENT_FAILED, OVERDUE_REMINDER, DEPOSIT_REFUNDED,
    REPORT_GENERATED
}

// Notification channel
enum NotificationChannel {
    WHATSAPP, SMS
}

// Availability action
enum AvailabilityAction {
    BLOCKED, RELEASED
}

// Availability reason
enum AvailabilityReason {
    BOOKING, MAINTENANCE, DAMAGED, CLEANING
}

// Report status
enum ReportStatus {
    GENERATING, SUCCESS, FAILED
}

// Logical date mode
enum LogicalDateMode {
    REAL, SIMULATED
}
```

---

## Naming Conventions — Strictly Enforced

```
Entity classes:        PascalCase, singular    → Toy, Booking, Customer
Repository interfaces: PascalCase + Repository → ToyRepository
Service classes:       PascalCase + Service    → ToyService, AvailabilityService
Controller classes:    PascalCase + Controller → ToyController
DTO request:           PascalCase + Request    → BookingRequest
DTO response:          PascalCase + Response   → BookingResponse
Kafka producers:       PascalCase + Producer   → BookingEventProducer
Kafka consumers:       PascalCase + Consumer   → BookingEventConsumer
Couchbase docs:        PascalCase + Document   → ToyAvailabilityDocument
Config classes:        PascalCase + Config     → KafkaConsumerConfig

Package structure per service:
  com.toyrental.{service}.controller
  com.toyrental.{service}.service
  com.toyrental.{service}.repository
  com.toyrental.{service}.entity
  com.toyrental.{service}.dto
  com.toyrental.{service}.kafka
  com.toyrental.{service}.couchbase
  com.toyrental.{service}.exception
  com.toyrental.{service}.config

ID format:   UUID stored as VARCHAR(36)
             Generated via UUID.randomUUID().toString()
             Prefix convention:
               toys:     toy-{uuid}     e.g. toy-042
               bookings: bkg-{uuid}     e.g. bkg-00291
               payments: pay-{uuid}     e.g. pay-00182
               customers:cust-{uuid}    e.g. cust-0091
               events:   evt-{uuid}     e.g. evt-7f3a-bc12
               reports:  rpt-{yyyy-mm}  e.g. rpt-2025-08

Date format: ISO 8601 always
             LocalDate:     yyyy-MM-dd
             LocalDateTime: yyyy-MM-dd'T'HH:mm:ss'Z'
             Timezone:      Asia/Kolkata (IST)
```

---

## application.yml Structure — Per Service

### toy-service application.yml
```yaml
server:
  port: 8081

spring:
  application:
    name: toy-service
  datasource:
    url: jdbc:postgresql://${POSTGRES_HOST:localhost}:5432/toydb
    username: ${POSTGRES_USER:toyuser}
    password: ${POSTGRES_PASSWORD:toypass}
    hikari:
      maximum-pool-size: 20
      minimum-idle: 5
      connection-timeout: 30000
      idle-timeout: 600000
  jpa:
    hibernate:
      ddl-auto: validate
    show-sql: false
  flyway:
    enabled: true
    locations: classpath:db/migration
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
    consumer:
      group-id: toy-service-cg
      auto-offset-reset: earliest
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.springframework.kafka.support.serializer.JsonDeserializer
      properties:
        spring.json.trusted.packages: "com.toyrental.*"

couchbase:
  connection-string: ${COUCHBASE_CONNECTION_STRING:couchbase://localhost}
  username: ${COUCHBASE_USERNAME:Administrator}
  password: ${COUCHBASE_PASSWORD:password}
  bucket:
    availability: toy-availability
    logical-date: logical-date

management:
  endpoints:
    web:
      exposure:
        include: health,prometheus,info
  endpoint:
    health:
      probes:
        enabled: true
      show-details: always
  metrics:
    export:
      prometheus:
        enabled: true

logging:
  pattern:
    console: "%d{yyyy-MM-dd HH:mm:ss} [%thread] %-5level %logger{36} correlationId=%X{correlationId} - %msg%n"
  level:
    com.toyrental: DEBUG
    org.springframework.kafka: INFO
```

### booking-service application.yml
```yaml
server:
  port: 8082

spring:
  application:
    name: booking-service
  datasource:
    url: jdbc:postgresql://${POSTGRES_HOST:localhost}:5432/bookingdb
    username: ${POSTGRES_USER:bookinguser}
    password: ${POSTGRES_PASSWORD:bookingpass}
    hikari:
      maximum-pool-size: 30
      minimum-idle: 5
      connection-timeout: 30000
      idle-timeout: 600000
  jpa:
    hibernate:
      ddl-auto: validate
    show-sql: false
  flyway:
    enabled: true
    locations: classpath:db/migration
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.springframework.kafka.support.serializer.JsonSerializer
    consumer:
      auto-offset-reset: earliest
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.springframework.kafka.support.serializer.JsonDeserializer
      properties:
        spring.json.trusted.packages: "com.toyrental.*"

couchbase:
  connection-string: ${COUCHBASE_CONNECTION_STRING:couchbase://localhost}
  username: ${COUCHBASE_USERNAME:Administrator}
  password: ${COUCHBASE_PASSWORD:password}
  bucket:
    reports: monthly-reports

minio:
  endpoint: ${MINIO_ENDPOINT:http://localhost:9000}
  access-key: ${MINIO_ACCESS_KEY:minioadmin}
  secret-key: ${MINIO_SECRET_KEY:minioadmin}
  bucket: toy-rental-reports

wiremock:
  base-url: ${WIREMOCK_BASE_URL:http://localhost:9090}

feign:
  toy-service:
    url: ${TOY_SERVICE_URL:http://localhost:8081}

resilience4j:
  circuitbreaker:
    instances:
      razorpay:
        slidingWindowSize: 10
        failureRateThreshold: 50
        waitDurationInOpenState: 10s
        permittedNumberOfCallsInHalfOpenState: 3
  retry:
    instances:
      razorpay:
        maxAttempts: 3
        waitDuration: 1s

management:
  endpoints:
    web:
      exposure:
        include: health,prometheus,info
  endpoint:
    health:
      probes:
        enabled: true
      show-details: always
  metrics:
    export:
      prometheus:
        enabled: true

logging:
  pattern:
    console: "%d{yyyy-MM-dd HH:mm:ss} [%thread] %-5level %logger{36} correlationId=%X{correlationId} - %msg%n"
  level:
    com.toyrental: DEBUG
    org.springframework.kafka: INFO
```

---

## Kubernetes — Namespaces and Labels

```
Namespaces:
  toy-rental    → application services (api-gateway, toy-service, booking-service)
  infra         → infrastructure (postgres, couchbase, kafka, redis, minio, keycloak, wiremock)
  monitoring    → observability (prometheus, grafana, zipkin)
  dynatrace     → Dynatrace Operator (OneAgent injection webhook, CSI driver)

Standard labels on every K8s resource:
  app.kubernetes.io/name: {service-name}
  app.kubernetes.io/version: "1.0.0"
  app.kubernetes.io/part-of: toy-rental
  app.kubernetes.io/managed-by: helm

HPA config:
  toy-service:     minReplicas=2, maxReplicas=8,  cpu=60%
  booking-service: minReplicas=2, maxReplicas=8,  cpu=60%
  api-gateway:     minReplicas=2, maxReplicas=10, cpu=60%

Resource requests/limits per service pod:
  api-gateway:     requests: cpu=200m, memory=256Mi  limits: cpu=500m,  memory=512Mi
  toy-service:     requests: cpu=250m, memory=512Mi  limits: cpu=1000m, memory=1Gi
  booking-service: requests: cpu=250m, memory=512Mi  limits: cpu=1000m, memory=1Gi

Liveness probe:  /actuator/health/liveness  initialDelay=60s period=15s
Readiness probe: /actuator/health/readiness initialDelay=30s period=10s
```

---

## Dynatrace OneAgent

Deep-code APM (JVM method tracing, DB/Couchbase/Kafka call timing) alongside the existing
Prometheus/Grafana/Zipkin stack — installed via the **Dynatrace Operator**, not a manual
sidecar. The operator watches a `DynaKube` custom resource and injects an OneAgent init
container into every pod in a namespace matched by that CR's `namespaceSelector` — no
Deployment or Helm template changes needed for injection itself.

```
Manifests:    k8s/infra/dynatrace/dynakube.yaml, k8s/infra/dynatrace/secret.yaml
Namespace:    dynatrace (operator + webhook + CSI driver)
Mode:         applicationMonitoring only — no host-level OneAgent DaemonSet, kept
              lighter for this single-node Docker Desktop cluster
Scope:        toy-rental namespace only (api-gateway, toy-service, booking-service),
              via the toy-rental namespace's dynatrace-injection: "enabled" label —
              infra and monitoring namespaces are NOT instrumented
Install:      one-time, not part of the regular stop/start cycle — see STARTUP.md's
              "Dynatrace Operator (one-time setup)" section
```

`spec.apiUrl` in `dynakube.yaml` and both tokens in `secret.yaml` are dev-only
placeholders (same convention as `helm/values.yaml`'s `secrets:` block). Until replaced
with a real Dynatrace tenant URL and tokens, `kubectl get dynakube -n dynatrace` reports a
connectivity/auth error in status — expected, and does not block pod injection itself.

See `DYNATRACE.md` for the fuller what/how writeup.

---

## AWS Elastic Beanstalk Deployment

A real, publicly reachable deployment for this project's secondary "real business launch"
goal — runs **alongside**, not instead of, the Kubernetes deployment above, which stays
the local performance-engineering/learning environment.

```
Region:    ap-south-1 (Mumbai) — matches the business's actual Navi Mumbai market
Backend:   3 EB single-container-Docker environments (api-gateway, toy-service,
           booking-service), each pulling its image from its own ECR repo
Frontend:  static Vite build (frontend/dist) on S3 + CloudFront, not a 4th EB
           environment
Infra:     Postgres/Couchbase/Kafka/Redis/MinIO/Keycloak/WireMock/Prometheus/
           Grafana/Zipkin self-hosted on one EC2 instance, reusing
           docker-compose.yml unchanged — no managed RDS/MSK/ElastiCache/Capella
           for this pass, to avoid their cost on a solo/toy-scale deployment
Manifests: <service>/Dockerrun.aws.json, <service>/.ebextensions/environment.config
           for all three backend services
```

toy-service's and booking-service's CORS allow-list is now the `ALLOWED_CORS_ORIGINS` env
var (defaults to `http://localhost:*`, unchanged for local dev) instead of a hardcoded
value, so the CloudFront origin can be added without a code change per deployment.

See `AWS_DEPLOY.md` for the fuller what/how writeup, exact commands, and known
limitations of this first pass (HTTP-only mixed-content workaround, no autoscaling,
single AZ).

---

## Custom Prometheus Metrics — Must Implement

```java
// toy-service
Counter.builder("toy.availability.cache.hit")
    .description("Couchbase availability cache hits")
    .register(meterRegistry);

Counter.builder("toy.availability.cache.miss")
    .description("Couchbase availability cache misses")
    .register(meterRegistry);

// booking-service
Counter.builder("booking.created.total")
    .tag("rental_type", rentalType)
    .description("Total bookings created")
    .register(meterRegistry);

Counter.builder("booking.conflict.total")
    .description("Booking conflicts — toy not available")
    .register(meterRegistry);

Counter.builder("payment.success.total")
    .tag("method", paymentMethod)
    .description("Successful payments")
    .register(meterRegistry);

Counter.builder("payment.failed.total")
    .tag("reason", failureReason)
    .description("Failed payments")
    .register(meterRegistry);

Timer.builder("pdf.generation.duration")
    .description("Time to generate monthly report PDF")
    .register(meterRegistry);

Counter.builder("monthly.report.generated.total")
    .tag("status", status)
    .description("Monthly reports generated")
    .register(meterRegistry);
```

---

## Performance Engineering — Bottlenecks to Find

The following bottlenecks are intentionally present in the initial implementation.
Claude Code must NOT pre-fix these. They are found via JMeter tests.

```
1. Missing composite index on toys(category, age_group, is_active, status)
   → Will cause full table scan under catalogue browse load
   → Fix applied in Sprint 7 after JMeter proves the problem

2. No cache warming on Couchbase startup
   → Cold start stampede when Couchbase restarts
   → All 500 users miss cache → hit PostgreSQL → collapse
   → Fix: warm cache on ApplicationReadyEvent

3. HikariCP pool too small (initial: maximumPoolSize=10)
   → Pool exhaustion under concurrent booking load
   → Fix: increase to 30 after JMeter proves exhaustion

4. Kafka: 1 partition per topic initially
   → Notification service lags under 1000 rapid bookings
   → Fix: increase to 6 partitions after lag proven in Grafana

5. No circuit breaker on WireMock Razorpay call initially
   → Retry storm when WireMock returns 503
   → Fix: add Resilience4j CB after storm proven in JMeter

6. JVM heap: default Xmx (256m) initially
   → GC pressure under soak test
   → Fix: tune to -Xmx512m -XX:+UseG1GC after JFR analysis
```

---

## Things Claude Code Must NEVER Do

```
❌ Never use LocalDate.now() in business logic — always LogicalDateService
❌ Never query another service's database directly
❌ Never hardcode passwords, API keys, or secrets in any file
❌ Never use @Autowired — always use constructor injection
❌ Never use field injection — always constructor injection
❌ Never return raw entities from controllers — always use DTOs
❌ Never expose stack traces in API error responses
❌ Never use DDL in JPA (ddl-auto must be 'validate' in all envs)
❌ Never commit with Flyway migrations already applied — V numbers are permanent
❌ Never add a new Flyway migration that modifies an existing table
   without checking existing migration files first
❌ Never use wildcard imports in Java files
❌ Never suppress exceptions silently — always log with correlationId
❌ Never add a dependency not in the approved tech stack
   without asking first
```

---

## Things Claude Code Must ALWAYS Do

```
✅ Constructor injection for all Spring beans
✅ @Slf4j on every class that logs
✅ Include correlationId in every log statement
✅ Return DTOs from all controllers, never entities
✅ @Transactional on all service methods that write to DB
✅ @Transactional(readOnly=true) on all service methods that only read
✅ Use Optional<> return types from repositories
✅ Validate all incoming request bodies with @Valid
✅ Use @NotNull, @NotBlank, @Size on all DTO fields
✅ Check eventId before processing any Kafka event
✅ Write a unit test for every service method
✅ Use UUID.randomUUID().toString() for all ID generation
✅ Use pageable for all list endpoints (never return unbounded lists)
✅ Add @Operation and @Tag annotations (SpringDoc/OpenAPI) on controllers
✅ Log entry and exit of every Kafka consumer with eventId + correlationId
```

---

## Sprint Progress Tracker

| Sprint | Status | Completed Stories |
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

Update this section at the end of every sprint.

---

## Known Bugs / Production Issues Caught

Document every bug found during development here.
Format: date, service, description, root cause, fix applied.

| Date | Service | Bug | Root Cause | Fix |
|---|---|---|---|---|
| 2026-08-21 | toy-service | `LogicalDateService` failed to compile | `getCurrentDate()`/`isMonthEnd()`/`isOverdueCheckDay()` accessed `LogicalDateDocument`'s private fields directly instead of via its Lombok getters | Call the getters |
| 2026-08-21 | toy-service | `AvailabilityService` violated the "never call `LocalDate.now()` directly" rule | Fallback path in `loadOrDefault()` called `LocalDate.now()` instead of `LogicalDateService.getCurrentDate()` | Route through `LogicalDateService` |
| 2026-08-21 | toy-service | Admin-only toy writes (POST/PUT/DELETE `/api/v1/toys/**`) were reachable without a JWT | `SecurityConfig` used `.requestMatchers("GET", "/api/v1/toys/**")` — no such `(String, String)` overload exists, so `"GET"` was matched as a second URL *pattern*, making the rule `permitAll()` every HTTP verb on that path | Use the `HttpMethod.GET` overload explicitly |
| 2026-08-21 | toy-service | Couchbase writes/reads of any document with `LocalDate`/`Instant` fields threw `EncodingFailureException`/`DecodingFailureException`; `LogicalDateService` silently fell back to the wall clock every time | Couchbase SDK's default Jackson `JsonSerializer` has no `JavaTimeModule` registered | Build a custom `ClusterEnvironment` with a `JacksonJsonSerializer` backed by an `ObjectMapper` that registers `JavaTimeModule` |
| 2026-08-21 | toy-service | Kafka consumers couldn't deserialize events from another service | No `spring.json.value.default.type` set, so `JsonDeserializer` needed a `__TypeId__` header matching a class on this service's own classpath | Set `spring.json.use.type.headers: false` + `spring.json.value.default.type` to the envelope class |
| 2026-08-21 | infra | `toydb`/`bookingdb` and their users were never created on a fresh Postgres container | `docker/postgres-init/init-databases.sh` was committed without the executable bit, so `docker-entrypoint-initdb.d` skipped it with "bad interpreter: Permission denied" | `chmod +x` the script |
| 2026-08-21 | infra | `docker compose up kafka` failed to pull the image | `bitnami/kafka:3.7` (and the entire `bitnami/kafka` repo) was removed from Docker Hub in Bitnami's 2025 catalog restructuring | Switched to the official `apache/kafka:3.7.2` image at the same pinned version line |
| 2026-08-22 | booking-service | Customer registration crashed with a Postgres error | `"cust-" + UUID.randomUUID()` is 41 characters; every id column is `VARCHAR(36)` | Shared `IdGenerator.shortId(prefix)` (prefix + first 8 hex chars of a UUID) in both services |
| 2026-08-22 | booking-service | `createdAt` came back `null` on the same response that created the row | Two layers: `@CreationTimestamp` only populates at flush, which plain `save()` defers to commit; and once switched to `saveAndFlush()`, these entities' manually-assigned ids route Spring Data through `merge()` (returns a different object) instead of `persist()` | `saveAndFlush()`, with the return value reassigned: `booking = bookingRepository.saveAndFlush(booking)` |
| 2026-08-22 | booking-service | One payment webhook call left a booking with `SUCCESS` payments but `PENDING` status | WireMock's Razorpay stub returns the same static order id for every order; the webhook handler only confirmed the first booking found among matched payments | Group matched payments by distinct `bookingId` and confirm every one found |
| 2026-08-22 | booking-service | A new Kafka consumer group spun at CPU speed, filling the disk to 100% capacity (19GB log file in under a minute) | No `ErrorHandlingDeserializer` wrapping the value deserializer, so a leftover headerless test message threw a raw `SerializationException` at Kafka's poll loop — entirely outside `DefaultErrorHandler`'s retry/backoff | Wrapped both services' Kafka value deserializers in `ErrorHandlingDeserializer` |
| 2026-08-22 | booking-service | `payment.success.total`/`payment.failed.total` never carried the `method`/`reason` labels CLAUDE.md's metrics spec requires | Both counters were built once in `PaymentService`'s constructor as fixed, untagged `Counter` instances | Build inline at each increment site with `Counter.builder(...).tag(...).register(meterRegistry)` (Micrometer's `register()` is idempotent by name+tags) |
| 2026-08-22 | booking-service | `OverdueDetectionService`'s scheduled job logged and published every `booking.overdue` event with an empty `correlationId` | No incoming HTTP request to derive one from, and MDC was never set for this `@Scheduled` method | Generate `"corr-overdue-" + UUID.randomUUID()` and set it via `MDC.put(...)` in try/finally around the method |
| 2026-08-22 | all services (K8s) | All three deployments (toy-service, booking-service, api-gateway) fell into a restart loop (exit 137 / SIGKILL) after every image rollout on the local dev node | Under CPU throttling at the pod's 1000m/500m limit, JVM startup took 2+ minutes instead of the normal ~20s; `livenessProbe.initialDelaySeconds: 60` killed the container mid-startup, before Tomcat ever bound the port | Raised `initialDelaySeconds` to 150 (liveness) / 120 (readiness) in `helm/values.yaml`'s shared `probes:` block and all three raw `k8s/services/*/*.yaml` manifests |
| 2026-08-22 | api-gateway | `docker build` / any invocation of `./mvnw` failed with "permission denied" | `mvnw` was committed without the executable bit — same class of bug already caught once for `docker/postgres-init/init-databases.sh` | `chmod +x api-gateway/mvnw` |
| 2026-08-22 | infra (K8s) | `kube-scheduler`/`kube-controller-manager` crash-looped under load, failing their own health checks | Running docker-compose's full stack simultaneously with the K8s cluster plus several concurrent Docker builds exceeded the Docker Desktop VM's resource budget | Stop docker-compose during K8s work; build service jars on the host (`./mvnw package`) rather than inside Docker's build VM, which separately turned out to have a much slower network path than the host itself in this environment |
| 2026-08-22 | infra (K8s) | `couchbase-0` never reached Ready — OOMKilled in ~5-7 seconds flat at every tested container memory limit (1Gi/2Gi/4Gi), even after the whole Docker Desktop VM's memory was raised from 8GB to 12GB | Resolved on further investigation: not a cgroup/Erlang detection bug as first suspected. Couchbase Server's real baseline footprint on this setup (Erlang VM + ns_server + indexer/query init) is genuinely ~4.85Gi — confirmed via `kubectl top pod` once it was given enough room to boot. 4Gi was simply just under that threshold, so every attempt died at a similar point in startup regardless of the exact limit, which looked like a hard ceiling but wasn't | Raised the container's memory `requests`/`limits` to 3Gi/6Gi (verified stable at both 6Gi and 8Gi; 6Gi kept for reasonable headroom without waste). Confirmed Ready, 0 restarts, steady ~4.85Gi usage |
| 2026-08-22 | infra (K8s) | `couchbase-init`/`minio-init` Jobs hung indefinitely trying to reach `couchbase`/`minio`, indistinguishable from Couchbase itself still starting up — which is what let this go unnoticed for so long during the couchbase startup investigation | `network-policy.yaml`'s `allow-app-services-to-infra` policy only allowed ingress to `infra` pods from the `toy-rental` namespace — it never allowed same-namespace traffic, so `infra`-namespace Jobs calling `infra`-namespace Services (the exact case these init Jobs are) were silently blocked by the `default-deny-ingress` policy with no distinguishing error, just a hung connection | Added `podSelector: {}` (same-namespace) as an additional allowed `from` source alongside the `toy-rental` namespaceSelector |
| 2026-08-22 | infra (K8s) | The `monthly-reports` bucket silently failed to create — `couchbase-init`'s fallback logged a misleading "already exists" for every bucket-create failure regardless of the real cause | `--cluster-ramsize 256` left too little room for all three buckets (3 × 100MB bucket-ramsize > 256MB total quota); the real `ramQuota ... too large` error from `bucket-create` was masked by the script's blanket `\|\| echo "already exists"` fallback. Separately, re-running `cluster-init` against an already-initialized cluster silently no-ops instead of applying new quota values | Raised `--cluster-ramsize` to 512MB (comfortably fits all three 100MB buckets); added a `couchbase-cli setting-cluster` fallback so a Job re-run actually applies quota changes to an existing cluster, not just a fresh one; made the bucket-create failure message accurate instead of presuming "already exists" |
| 2026-08-22 | toy-service, booking-service | Couchbase being unreachable crashed the entire application instead of degrading gracefully as CLAUDE.md's design intends | `CouchbaseConfig`'s `@Bean` factory methods called `bucket.waitUntilReady()` synchronously during Spring context startup, which throws on failure and fails the whole context — the fallback logic in `AvailabilityService`/`LogicalDateService` only helps once a `Bucket` bean successfully exists, so it never got a chance to run | Wrapped `waitUntilReady` in a try/catch that logs a warning and returns the bucket reference anyway, since `cluster.bucket(name)` itself never blocks |
| 2026-08-22 | toy-service, booking-service | With the above fixed, a live booking-creation smoke test still 500'd on the availability check | `CouchbaseAvailabilityRepository.findByToyId()` (and booking-service's analogous `CouchbaseReportRepository.findByMonthAndYear()`) only caught `DocumentNotFoundException`, not the broader connectivity failure a fully unreachable Couchbase actually throws, so it propagated uncaught through `AvailabilityService` | Broadened the catch to `CouchbaseException` (the SDK's common base class `DocumentNotFoundException` also extends), treating "can't tell" the same as "not found" |
| 2026-09-01 | api-gateway, toy-service, booking-service (K8s) | Full fresh-cluster bring-up kept crash-looping under liveness probes across all 3 app services, worse after a Docker Desktop/WSL2 restart (all pods cold-starting simultaneously instead of the staggered one-at-a-time apply order) | Two compounding causes: (1) node-wide resource ceiling — WSL2's default 50%-of-host memory cap (~15.5GB) left too little headroom for infra + monitoring + up to 5 concurrent JVM cold starts; (2) independently, the New Relic Java agent's placeholder `NEW_RELIC_LICENSE_KEY` doesn't fail quietly — on every `LicenseException` it retries the collector connection in a tight loop with no backoff, and across 3-5 JVMs simultaneously this alone was measured at 350-550% sustained node CPU, real enough to be the dominant cause of the liveness-probe kills, not just log noise | Added `C:\Users\USER\.wslconfig` (`memory=24GB`, `processors=6`, `swap=8GB`) for headroom; disabled the New Relic agent live on all 3 deployments (`kubectl set env deployment/<name> -n toy-rental JDK_JAVA_OPTIONS=""`, not committed to the manifests) — CPU dropped from ~550% to ~79% immediately after, and every pod reached stable `1/1 Ready` within one rollout. See `STARTUP.md`'s New Relic section for the real-key re-enable steps once available — don't re-enable with the placeholder key still in place, it recreates the same crash-loop |

---

## Environment Variables Reference

| Variable | Used By | Default (dev) | Description |
|---|---|---|---|
| POSTGRES_HOST | toy-service, booking-service | localhost | PostgreSQL host |
| POSTGRES_USER | toy-service | toyuser | PostgreSQL username |
| POSTGRES_PASSWORD | toy-service | toypass | PostgreSQL password |
| KAFKA_BOOTSTRAP_SERVERS | all services | localhost:9092 | Kafka brokers |
| COUCHBASE_CONNECTION_STRING | toy-service, booking-service | couchbase://localhost | Couchbase connection |
| COUCHBASE_USERNAME | toy-service, booking-service | Administrator | Couchbase username |
| COUCHBASE_PASSWORD | toy-service, booking-service | password | Couchbase password |
| MINIO_ENDPOINT | booking-service | http://localhost:9000 | MinIO endpoint |
| MINIO_ACCESS_KEY | booking-service | minioadmin | MinIO access key |
| MINIO_SECRET_KEY | booking-service | minioadmin | MinIO secret key |
| WIREMOCK_BASE_URL | booking-service | http://localhost:9090 | WireMock endpoint |
| TOY_SERVICE_URL | booking-service | http://localhost:8081 | Toy service URL for Feign |
| KEYCLOAK_ISSUER_URI | api-gateway only | http://localhost:8180/realms/toyrental | Keycloak realm URI — no realm has ever been imported, so api-gateway's JWT validation is non-functional; toy-service/booking-service no longer use this at all (see BOOKING_SERVICE_JWK_SET_URI) |
| BOOKING_SERVICE_JWK_SET_URI | toy-service | http://localhost:8082/oauth2/jwks | Where toy-service fetches booking-service's public key to validate the JWTs booking-service issues (customer and admin) |
| ADMIN_USERNAME | booking-service | admin | Admin login username, checked by AdminAuthService — not a customers-table row |
| ADMIN_PASSWORD | booking-service | admin123 | Admin login password |
| REDIS_HOST | api-gateway | localhost | Redis host |
| REDIS_PORT | api-gateway | 6379 | Redis port |

---

*Last updated: Sprint 0 — Architecture & Design complete*
*Next: Sprint 1 — Infrastructure Setup*
