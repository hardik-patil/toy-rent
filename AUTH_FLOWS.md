# ToyRental Platform — Auth Requirements by Endpoint

Derived from `toy-service/src/main/java/com/toyrental/toy/config/SecurityConfig.java` and
`booking-service/src/main/java/com/toyrental/booking/config/SecurityConfig.java`. booking-service
is the platform's sole JWT issuer (self-signed RSA, via `NimbusJwtEncoder`); toy-service validates
tokens by fetching booking-service's public key from `/oauth2/jwks`. Both services read roles from
a flat `roles` claim (e.g. `["ADMIN"]`), not Keycloak's `realm_access.roles` shape.

A customer token and an admin token are both just bearer JWTs with a `roles` claim — toy-service's
ADMIN-gated routes only check that claim, so an admin token also passes any customer-gated route.
The reverse isn't true: a customer token never satisfies `hasRole("ADMIN")`.

---

## No token required (public)

**toy-service** — every `GET /api/v1/toys/**`:
- `GET /api/v1/toys` (browse), `/search`, `/categories`, `/metadata`
- `GET /api/v1/toys/{toyId}` (detail)
- `GET /api/v1/toys/{toyId}/availability`, `/availability/calendar`
- `GET /api/v1/toys/available`
- `/internal/v1/toys/**` (service-to-service, no gateway in front of it)

**booking-service:**
- `POST /api/v1/customers/register`
- `POST /api/v1/customers/login`
- `POST /api/v1/admin/login`
- `POST /api/v1/payments/webhook` (called by Razorpay/WireMock, not a logged-in user)

**Both services:**
- `/actuator/health`, `/actuator/health/**`, `/actuator/prometheus`
- `/swagger-ui/**`, `/v3/api-docs/**`

---

## Customer JWT required

booking-service's `anyRequest().authenticated()` catch-all — everything not explicitly `permitAll()`
or `hasRole("ADMIN")` above:

- `GET /api/v1/customers/me`
- `PUT /api/v1/customers/me`
- `PUT /api/v1/customers/me/address`
- `GET /api/v1/customers/me/bookings`
- `POST /api/v1/bookings`
- `GET /api/v1/bookings/{bookingId}`
- `GET /api/v1/bookings/{bookingId}/receipt`
- `PUT /api/v1/bookings/{bookingId}/cancel`
- `PUT /api/v1/bookings/{bookingId}/extend`
- `POST /api/v1/payments/initiate`
- `GET /api/v1/payments/{paymentId}`

---

## Admin JWT required (`hasRole("ADMIN")`)

**toy-service:**
- `POST /api/v1/toys` (create)
- `PUT /api/v1/toys/{toyId}` (update)
- `DELETE /api/v1/toys/{toyId}` (soft delete)
- `POST /api/v1/toys/{toyId}/images` (URL-based) and `/images/upload` (multipart)
- All `/api/v1/admin/**` (inventory, low-stock, condition update)

**booking-service:**
- All `/api/v1/admin/**` (bookings, today's deliveries/pickups, overdue, reports)
- `POST /api/v1/payments/{bookingId}/refund`

---

*Generated from the live `SecurityConfig` source — if either file changes, this doc will drift.
Regenerate rather than hand-edit.*
