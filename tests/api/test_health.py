"""Health / readiness / JWKS smoke checks. Touch no data."""

import pytest

pytestmark = pytest.mark.smoke


def test_toy_health(toy_client):
    resp = toy_client.get("/actuator/health")
    assert resp.status_code == 200
    assert resp.json()["status"] == "UP"


def test_booking_health(booking_client):
    resp = booking_client.get("/actuator/health")
    assert resp.status_code == 200
    assert resp.json()["status"] == "UP"


@pytest.mark.parametrize("probe", ["liveness", "readiness"])
def test_toy_probes(toy_client, probe):
    resp = toy_client.get(f"/actuator/health/{probe}")
    assert resp.status_code == 200
    assert resp.json()["status"] == "UP"


@pytest.mark.parametrize("probe", ["liveness", "readiness"])
def test_booking_probes(booking_client, probe):
    resp = booking_client.get(f"/actuator/health/{probe}")
    assert resp.status_code == 200
    assert resp.json()["status"] == "UP"


def test_jwks_published(booking_client):
    resp = booking_client.get("/oauth2/jwks")
    assert resp.status_code == 200
    keys = resp.json().get("keys")
    assert keys, "JWKS response has no keys"
    for key in keys:
        assert key.get("kty") == "RSA"
        assert key.get("kid")
