"""booking-service /api/v1/customers/me , /me/address , /me/bookings."""

import pytest


@pytest.mark.read_only
def test_me_requires_auth(booking_client):
    assert booking_client.get("/api/v1/customers/me").status_code == 401


@pytest.mark.read_only
def test_me_with_seed_token(seed_booking_client, config):
    resp = seed_booking_client.get("/api/v1/customers/me")
    assert resp.status_code == 200
    body = resp.json()
    assert body["id"] == "cust-0001"
    assert body["phone"] == config.seed_customer_phone
    assert body["area"] == "Kharghar"
    assert body["flat"] == "B-204"
    assert body["building"] == "Neelkanth Heights"
    assert body["city"] == "Navi Mumbai"
    assert body["pincode"] == "410210"


@pytest.mark.read_only
@pytest.mark.admin
def test_me_with_admin_token(admin_booking_client):
    # The admin subject ("admin") is not a customers row; getById(subject) most
    # likely 404s. Accept either — record the actual.
    resp = admin_booking_client.get("/api/v1/customers/me")
    assert resp.status_code in {200, 404}


@pytest.mark.mutating
def test_update_me(fresh_customer):
    client = fresh_customer["client"]
    resp = client.put("/api/v1/customers/me", json={"name": "IT Renamed"})
    assert resp.status_code == 200
    me = client.get("/api/v1/customers/me").json()
    assert me["name"] == "IT Renamed"
    assert me["phone"] == fresh_customer["phone"]


@pytest.mark.mutating
def test_update_me_address(fresh_customer):
    client = fresh_customer["client"]
    new_address = {
        "flat": "C-901",
        "building": "Palm Beach Residency",
        "area": "Nerul",
        "city": "Navi Mumbai",
        "pincode": "400706",
    }
    resp = client.put("/api/v1/customers/me/address", json=new_address)
    assert resp.status_code == 200
    me = client.get("/api/v1/customers/me").json()
    for key, value in new_address.items():
        assert me[key] == value


@pytest.mark.read_only
def test_my_bookings_requires_auth(booking_client):
    assert booking_client.get("/api/v1/customers/me/bookings").status_code == 401


@pytest.mark.read_only
def test_my_bookings_with_seed_token(seed_booking_client):
    resp = seed_booking_client.get(
        "/api/v1/customers/me/bookings", params={"page": 0, "size": 10}
    )
    assert resp.status_code == 200
    assert isinstance(resp.json()["content"], list)


@pytest.mark.mutating
def test_my_bookings_lists_new_booking(confirmed_booking):
    client = confirmed_booking["customer"]["client"]
    resp = client.get("/api/v1/customers/me/bookings", params={"page": 0, "size": 50})
    assert resp.status_code == 200
    rows = {b["id"]: b for b in resp.json()["content"]}
    assert confirmed_booking["id"] in rows
    assert rows[confirmed_booking["id"]]["status"] in {"CONFIRMED", "PENDING"}
