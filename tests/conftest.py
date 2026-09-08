"""
Fixtures for the ToyRental black-box API suite.

Runs against toy-service (:8081) and booking-service (:8082) reached over
`kubectl port-forward` (api-gateway :8080 is deliberately not covered — its
Keycloak JWT validation was never wired up). Everything is configurable via env
vars; the defaults are the documented dev values.

    TOY_BASE_URL              http://localhost:8081
    BOOKING_BASE_URL          http://localhost:8082
    ADMIN_USERNAME            admin
    ADMIN_PASSWORD            admin123
    SEED_CUSTOMER_PHONE       9821012345      (V6 seed: Priya Deshmukh, cust-0001)
    SEED_CUSTOMER_PASSWORD    password
    HTTP_TIMEOUT              10              per-request seconds
    POLL_TIMEOUT             30              async-assertion ceiling
    POLL_INTERVAL            1.0             poll gap
    BOOKING_START_OFFSET_DAYS 3              earliest bookable start = today + this
                                            (bump if the Couchbase logical date is
                                             advanced far ahead of wall clock)
"""

import os
import uuid
from dataclasses import dataclass

import pytest
import requests

from helpers import ApiClient, poll_until, random_phone, spread_date_range

# Toys guaranteed to exist by Flyway V4__seed_sample_toys.sql.
SEED_TOY_IDS = [f"toy-00{n}" for n in range(1, 9)]

_SEED_CUSTOMER_ADDRESS = {
    "area": "Kharghar",
    "flat": "B-204",
    "building": "Neelkanth Heights",
    "city": "Navi Mumbai",
    "pincode": "410210",
}


@dataclass(frozen=True)
class Config:
    toy_base_url: str
    booking_base_url: str
    admin_username: str
    admin_password: str
    seed_customer_phone: str
    seed_customer_password: str
    http_timeout: float
    poll_timeout: float
    poll_interval: float
    booking_start_offset_days: int


@pytest.fixture(scope="session")
def config():
    return Config(
        toy_base_url=os.getenv("TOY_BASE_URL", "http://localhost:8081"),
        booking_base_url=os.getenv("BOOKING_BASE_URL", "http://localhost:8082"),
        admin_username=os.getenv("ADMIN_USERNAME", "admin"),
        admin_password=os.getenv("ADMIN_PASSWORD", "admin123"),
        seed_customer_phone=os.getenv("SEED_CUSTOMER_PHONE", "9821012345"),
        seed_customer_password=os.getenv("SEED_CUSTOMER_PASSWORD", "password"),
        http_timeout=float(os.getenv("HTTP_TIMEOUT", "10")),
        poll_timeout=float(os.getenv("POLL_TIMEOUT", "30")),
        poll_interval=float(os.getenv("POLL_INTERVAL", "1.0")),
        booking_start_offset_days=int(os.getenv("BOOKING_START_OFFSET_DAYS", "3")),
    )


# --------------------------------------------------------------------------- #
# Service clients                                                             #
# --------------------------------------------------------------------------- #

@pytest.fixture(scope="session")
def toy_client(config):
    return ApiClient(config.toy_base_url, config.http_timeout)


@pytest.fixture(scope="session")
def booking_client(config):
    return ApiClient(config.booking_base_url, config.http_timeout)


# --------------------------------------------------------------------------- #
# Health gate — skips the whole session with an actionable message if either  #
# service is unreachable (most often a dead port-forward).                    #
# --------------------------------------------------------------------------- #

_HEALTH_HINT = """
{name} is not reachable / UP at {url}.

`kubectl port-forward` dies silently whenever its backing pod is recreated
(any kubectl apply / rollout restart / HPA scale). Bring the forwards back:

  python scripts/startup.py --stop-port-forward
  python scripts/startup.py --skip-infra --skip-monitoring --port-forward

or manually, each in its own terminal:

  kubectl port-forward -n toy-rental svc/toy-service 8081:8081
  kubectl port-forward -n toy-rental svc/booking-service 8082:8082

Also confirm the DBs are Flyway-seeded (toydb V4 toys, bookingdb V6 customer).
""".strip()


@pytest.fixture(scope="session", autouse=True)
def _require_services_up(toy_client, booking_client):
    for name, client in (
        ("toy-service", toy_client),
        ("booking-service", booking_client),
    ):
        try:
            resp = client.get("/actuator/health")
        except requests.RequestException as exc:
            pytest.skip(
                _HEALTH_HINT.format(name=name, url=client.base_url)
                + f"\n\n({type(exc).__name__}: {exc})"
            )
        if resp.status_code != 200 or resp.json().get("status") != "UP":
            pytest.skip(
                _HEALTH_HINT.format(name=name, url=client.base_url)
                + f"\n\n(HTTP {resp.status_code}: {resp.text[:200]})"
            )


# --------------------------------------------------------------------------- #
# Tokens                                                                      #
# --------------------------------------------------------------------------- #

@pytest.fixture(scope="session")
def admin_token(booking_client, config):
    resp = booking_client.post(
        "/api/v1/admin/login",
        json={"username": config.admin_username, "password": config.admin_password},
    )
    if resp.status_code != 200:
        pytest.skip(
            "admin login failed "
            f"(HTTP {resp.status_code}) — check ADMIN_USERNAME / ADMIN_PASSWORD "
            "on booking-service"
        )
    return resp.json()["accessToken"]


@pytest.fixture(scope="session")
def seed_customer_token(booking_client, config):
    resp = booking_client.post(
        "/api/v1/customers/login",
        json={
            "phone": config.seed_customer_phone,
            "password": config.seed_customer_password,
        },
    )
    if resp.status_code != 200:
        pytest.skip(
            "seed customer login failed "
            f"(HTTP {resp.status_code}) — bookingdb not Flyway-seeded "
            "(V6__seed_sample_customer) or SEED_CUSTOMER_* overridden wrongly"
        )
    return resp.json()["accessToken"]


@pytest.fixture(scope="session")
def admin_toy_client(toy_client, admin_token):
    client = toy_client.with_token(admin_token)
    # Probe cross-service JWT validation once, up front. If booking-service runs
    # >1 replica, each pod signs with its own in-memory RSA keypair; the token
    # came from the port-forwarded pod but toy-service fetches JWKS through the
    # (load-balanced) Service and may cache a different pod's key -> every
    # admin call here 401s. Fail once, loudly, instead of 30 opaque errors.
    probe = client.get("/api/v1/admin/toys/inventory", params={"size": 1})
    if probe.status_code == 401:
        pytest.fail(
            "toy-service rejected a valid booking-service admin JWT (401).\n"
            "Most likely cause: booking-service has >1 replica, each with its own\n"
            "in-memory JWT signing key (see tests/README.md 'Known flakiness').\n"
            "Fix for a test run:  kubectl scale deploy/booking-service "
            "-n toy-rental --replicas=1\n"
            "then re-run once the single pod is Ready."
        )
    return client


@pytest.fixture(scope="session")
def admin_booking_client(booking_client, admin_token):
    return booking_client.with_token(admin_token)


@pytest.fixture(scope="session")
def seed_booking_client(booking_client, seed_customer_token):
    return booking_client.with_token(seed_customer_token)


# --------------------------------------------------------------------------- #
# Customers                                                                   #
# --------------------------------------------------------------------------- #

def _register_and_login(booking_client, password="pytest-pw-123"):
    """Register a brand-new customer (random phone) and log in. Retries once on a
    phone collision. Returns the customer dict."""
    last = None
    for _ in range(3):
        phone = random_phone()
        body = {
            "name": f"IT {uuid.uuid4().hex[:8]}",
            "phone": phone,
            "email": f"it-{uuid.uuid4().hex[:8]}@example.com",
            "password": password,
            **_SEED_CUSTOMER_ADDRESS,
        }
        reg = booking_client.post("/api/v1/customers/register", json=body)
        last = reg
        if reg.status_code == 409:
            continue  # phone already taken — try another
        assert reg.status_code == 201, f"register failed: {reg.status_code} {reg.text}"
        login = booking_client.post(
            "/api/v1/customers/login", json={"phone": phone, "password": password}
        )
        assert login.status_code == 200, f"login failed: {login.status_code} {login.text}"
        token = login.json()["accessToken"]
        return {
            "phone": phone,
            "password": password,
            "id": reg.json()["id"],
            "token": token,
            "client": booking_client.with_token(token),
            "raw": reg.json(),
        }
    raise AssertionError(
        f"could not register a fresh customer after 3 tries; last: "
        f"{last.status_code if last else '?'} {last.text if last else ''}"
    )


@pytest.fixture
def fresh_customer(booking_client):
    return _register_and_login(booking_client)


@pytest.fixture
def second_customer(booking_client):
    return _register_and_login(booking_client)


# --------------------------------------------------------------------------- #
# Toys / bookings                                                             #
# --------------------------------------------------------------------------- #

@pytest.fixture(scope="session")
def some_toy_id(toy_client):
    resp = toy_client.get("/api/v1/toys", params={"size": 1})
    if resp.status_code == 200:
        content = resp.json().get("content") or []
        if content:
            return content[0]["id"]
    return "toy-003"


@pytest.fixture(scope="session")
def created_toy_ids(admin_toy_client):
    """IDs of toys created by admin-CRUD tests; soft-deleted in a session finalizer."""
    ids = []
    yield ids
    for toy_id in ids:
        try:
            admin_toy_client.delete(f"/api/v1/toys/{toy_id}")
        except Exception:  # noqa: BLE001 — best-effort cleanup
            pass


@pytest.fixture
def pending_booking(fresh_customer, toy_client, config):
    """
    Create a PENDING booking for a fresh customer. Picks a random seed toy + a
    random future window, checks availability first, and only POSTs when the toy
    is free — retrying a fresh (toy, window) on an unavailable check or a 409.
    Prior runs accumulate CONFIRMED bookings that block ranges, so the retry
    budget is generous.
    """
    import random as _random

    client = fresh_customer["client"]
    last = None
    for _ in range(20):
        toy_id = _random.choice(SEED_TOY_IDS)
        start, end = spread_date_range(config.booking_start_offset_days)

        avail = toy_client.get(
            f"/api/v1/toys/{toy_id}/availability", params={"from": start, "to": end}
        )
        if avail.status_code == 200 and avail.json().get("available") is not True:
            continue

        body = {
            "toyId": toy_id,
            "startDate": start,
            "endDate": end,
            "rentalType": "WEEKLY",
            "deliveryFlat": "B-204",
            "deliveryBuilding": "Neelkanth Heights",
            "deliveryArea": "Kharghar",
            "deliveryCity": "Navi Mumbai",
            "deliveryPincode": "410210",
        }
        resp = client.post("/api/v1/bookings", json=body)
        last = resp
        if resp.status_code == 409:
            continue
        assert resp.status_code == 201, f"booking create failed: {resp.status_code} {resp.text}"
        data = resp.json()
        assert data["status"] == "PENDING"
        assert data["razorpayOrderId"]
        return {
            "id": data["id"],
            "razorpay_order_id": data["razorpayOrderId"],
            "customer": fresh_customer,
            "body": data,
            "start": start,
            "end": end,
        }
    raise AssertionError(
        "could not create a PENDING booking after 20 tries — every seed toy's "
        f"windows are blocked. Last response: "
        f"{last.status_code if last else 'n/a'} {last.text if last else ''}"
    )


@pytest.fixture
def confirmed_booking(pending_booking, booking_client, poll):
    """pending_booking + Razorpay webhook + poll until CONFIRMED."""
    wh = booking_client.post(
        "/api/v1/payments/webhook",
        json={
            "razorpay_order_id": pending_booking["razorpay_order_id"],
            "razorpay_payment_id": "pay_mock456",
            "razorpay_signature": "mock_signature_abc",
        },
    )
    assert wh.status_code == 200, f"webhook failed: {wh.status_code} {wh.text}"
    owner = pending_booking["customer"]["client"]

    def _confirmed():
        r = owner.get(f"/api/v1/bookings/{pending_booking['id']}")
        if r.status_code == 200 and r.json().get("status") == "CONFIRMED":
            return r.json()
        return None

    body = poll(_confirmed)
    return {**pending_booking, "body": body}


# --------------------------------------------------------------------------- #
# Misc                                                                        #
# --------------------------------------------------------------------------- #

@pytest.fixture(scope="session")
def poll(config):
    def _poll(predicate):
        return poll_until(
            predicate,
            timeout=config.poll_timeout,
            interval=config.poll_interval,
        )

    return _poll


@pytest.fixture
def correlation_id():
    return uuid.uuid4().hex
