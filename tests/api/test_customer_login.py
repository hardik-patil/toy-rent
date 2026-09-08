"""booking-service POST /api/v1/customers/login."""

import pytest

from helpers import assert_error_contract, is_jwt


@pytest.mark.read_only
def test_login_seed_ok(booking_client, config):
    resp = booking_client.post(
        "/api/v1/customers/login",
        json={
            "phone": config.seed_customer_phone,
            "password": config.seed_customer_password,
        },
    )
    assert resp.status_code == 200
    body = resp.json()
    assert is_jwt(body["accessToken"])
    assert body["tokenType"] == "Bearer"
    assert body["expiresInSeconds"] == 86400
    cust = body["customer"]
    assert cust["id"] == "cust-0001"
    assert cust["name"] == "Priya Deshmukh"
    assert cust["phone"] == config.seed_customer_phone
    assert cust["city"] == "Navi Mumbai"


@pytest.mark.read_only
def test_login_wrong_password(booking_client, config):
    resp = booking_client.post(
        "/api/v1/customers/login",
        json={"phone": config.seed_customer_phone, "password": "definitely-wrong"},
    )
    assert_error_contract(resp, status=401, error="INVALID_CREDENTIALS")


@pytest.mark.read_only
def test_login_unknown_phone(booking_client):
    resp = booking_client.post(
        "/api/v1/customers/login",
        json={"phone": "7000000000", "password": "whatever12"},
    )
    # 401, not 404 — no user enumeration.
    assert resp.status_code == 401


@pytest.mark.read_only
def test_login_missing_fields(booking_client):
    resp = booking_client.post("/api/v1/customers/login", json={})
    assert_error_contract(resp, status=400, error="VALIDATION_ERROR")


@pytest.mark.mutating
def test_fresh_customer_can_login(booking_client, fresh_customer):
    resp = booking_client.post(
        "/api/v1/customers/login",
        json={
            "phone": fresh_customer["phone"],
            "password": fresh_customer["password"],
        },
    )
    assert resp.status_code == 200
    body = resp.json()
    assert is_jwt(body["accessToken"])
    assert body["customer"]["id"] == fresh_customer["id"]
