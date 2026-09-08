"""booking-service POST /api/v1/customers/register."""

import uuid

import pytest

from helpers import assert_error_contract, random_phone

_ADDRESS = {
    "area": "Kharghar",
    "flat": "B-204",
    "building": "Neelkanth Heights",
    "city": "Navi Mumbai",
    "pincode": "410210",
}


def _body(**overrides):
    body = {
        "name": f"IT {uuid.uuid4().hex[:8]}",
        "phone": random_phone(),
        "email": f"it-{uuid.uuid4().hex[:8]}@example.com",
        "password": "pytest-pw-123",
        **_ADDRESS,
    }
    body.update(overrides)
    return body


@pytest.mark.mutating
def test_register_ok(booking_client):
    resp = booking_client.post("/api/v1/customers/register", json=_body())
    assert resp.status_code == 201
    body = resp.json()
    assert body["id"]
    assert body["createdAt"]
    assert "password" not in body and "passwordHash" not in body
    assert body["city"] == "Navi Mumbai"


@pytest.mark.mutating
def test_register_ok_without_email(booking_client):
    payload = _body()
    payload.pop("email")
    resp = booking_client.post("/api/v1/customers/register", json=payload)
    assert resp.status_code == 201
    assert not resp.json().get("email")


@pytest.mark.mutating
def test_register_short_password(booking_client):
    resp = booking_client.post(
        "/api/v1/customers/register", json=_body(password="short")
    )
    body = assert_error_contract(resp, status=400, error="VALIDATION_ERROR")
    assert "password" in body["message"]


@pytest.mark.mutating
def test_register_nine_digit_phone(booking_client):
    resp = booking_client.post(
        "/api/v1/customers/register", json=_body(phone="123456789")
    )
    body = assert_error_contract(resp, status=400, error="VALIDATION_ERROR")
    assert "phone" in body["message"]


@pytest.mark.mutating
def test_register_blank_name(booking_client):
    resp = booking_client.post("/api/v1/customers/register", json=_body(name=""))
    assert_error_contract(resp, status=400, error="VALIDATION_ERROR")


@pytest.mark.mutating
def test_register_invalid_email(booking_client):
    resp = booking_client.post(
        "/api/v1/customers/register", json=_body(email="notanemail")
    )
    assert_error_contract(resp, status=400, error="VALIDATION_ERROR")


@pytest.mark.read_only
def test_register_duplicate_phone(booking_client, config):
    # The V6 seed customer's phone always exists — no new data created.
    resp = booking_client.post(
        "/api/v1/customers/register", json=_body(phone=config.seed_customer_phone)
    )
    assert_error_contract(resp, status=409, error="CUSTOMER_ALREADY_EXISTS")
