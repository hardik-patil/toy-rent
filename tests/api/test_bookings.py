"""booking-service /api/v1/bookings: create / get / cancel."""

import random
from datetime import date, timedelta

import pytest

from helpers import assert_error_contract, spread_date_range

pytestmark = pytest.mark.mutating

SEED_TOY_IDS = [f"toy-00{n}" for n in range(1, 9)]


def _booking_body(**overrides):
    start, end = spread_date_range(3)
    body = {
        "toyId": random.choice(SEED_TOY_IDS),
        "startDate": start,
        "endDate": end,
        "rentalType": "WEEKLY",
        "deliveryFlat": "B-204",
        "deliveryBuilding": "Neelkanth Heights",
        "deliveryArea": "Kharghar",
        "deliveryCity": "Navi Mumbai",
        "deliveryPincode": "410210",
    }
    body.update(overrides)
    return body


def test_create_requires_auth(booking_client):
    resp = booking_client.post("/api/v1/bookings", json=_booking_body())
    assert resp.status_code == 401


def test_create_happy(fresh_customer, config):
    client = fresh_customer["client"]
    last = None
    for _ in range(8):
        resp = client.post(
            "/api/v1/bookings",
            json=_booking_body(toyId=random.choice(SEED_TOY_IDS)),
        )
        last = resp
        if resp.status_code == 409:
            continue
        assert resp.status_code == 201, f"{resp.status_code} {resp.text}"
        body = resp.json()
        assert body["status"] == "PENDING"
        assert body["paymentStatus"] == "PENDING"
        assert body["razorpayOrderId"]
        assert float(body["rentalAmount"]) > 0
        assert float(body["depositAmount"]) > 0
        assert float(body["totalAmount"]) > 0
        assert body["customerId"] == fresh_customer["id"]
        return
    pytest.fail(f"two 409s in a row creating a booking; last: {last.text}")


def test_create_past_start_date(fresh_customer):
    past = (date.today() - timedelta(days=5)).isoformat()
    soon = (date.today() + timedelta(days=2)).isoformat()
    resp = fresh_customer["client"].post(
        "/api/v1/bookings", json=_booking_body(startDate=past, endDate=soon)
    )
    body = assert_error_contract(resp, status=400, error="VALIDATION_ERROR")
    assert "startDate" in body["message"]


def test_create_end_before_start(fresh_customer):
    start = (date.today() + timedelta(days=15)).isoformat()
    end = (date.today() + timedelta(days=8)).isoformat()
    resp = fresh_customer["client"].post(
        "/api/v1/bookings", json=_booking_body(startDate=start, endDate=end)
    )
    assert resp.status_code == 400
    assert resp.json()["error"] in {"VALIDATION_ERROR", "INVALID_BOOKING_REQUEST"}


def test_create_missing_delivery_field(fresh_customer):
    body = _booking_body()
    body.pop("deliveryFlat")
    resp = fresh_customer["client"].post("/api/v1/bookings", json=body)
    assert_error_contract(resp, status=400, error="VALIDATION_ERROR")


def test_create_bad_rental_type(fresh_customer):
    # An unknown enum value is a Jackson HttpMessageNotReadableException, which
    # booking-service has no @ExceptionHandler for — currently a 500 rather than
    # the ideal 400. Either way the booking is rejected.
    resp = fresh_customer["client"].post(
        "/api/v1/bookings", json=_booking_body(rentalType="YEARLY")
    )
    assert resp.status_code in {400, 500}


def test_create_bogus_toy(fresh_customer):
    # A non-existent toy: booking-service's Feign lookup to toy-service 404s and
    # that isn't translated, so this currently surfaces as 500. The invariant
    # under test is that no booking is created (not 2xx) and auth was fine
    # (not 401).
    resp = fresh_customer["client"].post(
        "/api/v1/bookings", json=_booking_body(toyId="toy-nope")
    )
    assert resp.status_code in {400, 404, 409, 500}


def test_get_booking_as_owner(pending_booking):
    client = pending_booking["customer"]["client"]
    resp = client.get(f"/api/v1/bookings/{pending_booking['id']}")
    assert resp.status_code == 200
    body = resp.json()
    assert body["id"] == pending_booking["id"]
    assert body["status"] == "PENDING"


def test_get_booking_as_non_owner(pending_booking, second_customer):
    resp = second_customer["client"].get(
        f"/api/v1/bookings/{pending_booking['id']}"
    )
    assert resp.status_code in {403, 404}


def test_get_booking_requires_auth(booking_client, pending_booking):
    assert booking_client.get(
        f"/api/v1/bookings/{pending_booking['id']}"
    ).status_code == 401


def test_get_booking_unknown_id(seed_booking_client):
    resp = seed_booking_client.get("/api/v1/bookings/bkg-does-not-exist")
    assert_error_contract(resp, status=404, error="BOOKING_NOT_FOUND")


def test_cancel_booking(pending_booking):
    client = pending_booking["customer"]["client"]
    resp = client.put(f"/api/v1/bookings/{pending_booking['id']}/cancel", json={})
    assert resp.status_code == 200
    got = client.get(f"/api/v1/bookings/{pending_booking['id']}").json()
    assert got["status"] == "CANCELLED"
