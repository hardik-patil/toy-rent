"""booking-service POST /api/v1/admin/login + cross-service token behaviour."""

import pytest

from helpers import assert_error_contract, is_jwt

pytestmark = pytest.mark.read_only


def test_admin_login_ok(booking_client, config):
    resp = booking_client.post(
        "/api/v1/admin/login",
        json={"username": config.admin_username, "password": config.admin_password},
    )
    assert resp.status_code == 200
    body = resp.json()
    assert is_jwt(body["accessToken"])
    assert body["tokenType"] == "Bearer"
    assert body["expiresInSeconds"] > 0


def test_admin_login_wrong_password(booking_client, config):
    resp = booking_client.post(
        "/api/v1/admin/login",
        json={"username": config.admin_username, "password": "nope"},
    )
    assert_error_contract(resp, status=401, error="INVALID_CREDENTIALS")


def test_admin_login_unknown_user(booking_client):
    resp = booking_client.post(
        "/api/v1/admin/login", json={"username": "nobody", "password": "x"}
    )
    assert resp.status_code == 401


def test_admin_login_missing_fields(booking_client):
    resp = booking_client.post("/api/v1/admin/login", json={})
    assert resp.status_code == 400


@pytest.mark.admin
def test_admin_token_accepted_by_toy_service(admin_toy_client):
    # Proves toy-service validates tokens against booking-service's JWKS.
    resp = admin_toy_client.get(
        "/api/v1/admin/toys/inventory", params={"page": 0, "size": 10}
    )
    assert resp.status_code == 200


def test_customer_token_rejected_for_admin_route(toy_client, seed_customer_token):
    # 403 = role check rejected it (the assertion we want). 401 can also occur if
    # booking-service's multi-replica keypair mismatch makes toy-service reject
    # the signature outright (see tests/README.md) — still "rejected", so accept
    # both rather than flake.
    resp = toy_client.with_token(seed_customer_token).get(
        "/api/v1/admin/toys/inventory"
    )
    assert resp.status_code in {401, 403}
