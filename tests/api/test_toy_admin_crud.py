"""toy-service admin CRUD: POST/PUT/DELETE /api/v1/toys + /api/v1/admin/toys/*.

Auth matrix: no token -> 401, customer token -> 403, admin token -> 2xx.
Created toys are registered in `created_toy_ids` and soft-deleted in a finalizer.
"""

import uuid

import pytest

pytestmark = [pytest.mark.mutating, pytest.mark.admin]


@pytest.fixture
def customer_toy_client(toy_client, seed_customer_token):
    """A toy-service client carrying a CUSTOMER token — for the 403 checks."""
    return toy_client.with_token(seed_customer_token)


def _toy_body(**overrides):
    body = {
        "name": f"IT Toy {uuid.uuid4().hex[:8]}",
        "description": "created by the API test suite",
        "brand": "PyTest",
        "category": "PRETEND_PLAY",
        "ageGroup": "3-6",
        "condition": "NEW",
        "status": "AVAILABLE",
        "mrp": 1999.00,
        "weeklyPrice": 199.00,
        "monthlyPrice": 649.00,
        "depositAmount": 800.00,
    }
    body.update(overrides)
    return body


@pytest.fixture
def created_toy(admin_toy_client, created_toy_ids):
    """A freshly admin-created toy; id tracked for cleanup."""
    resp = admin_toy_client.post("/api/v1/toys", json=_toy_body())
    assert resp.status_code == 201, f"{resp.status_code} {resp.text}"
    toy = resp.json()
    created_toy_ids.append(toy["id"])
    return toy


# ---- create -------------------------------------------------------------- #

def test_create_toy_requires_auth(toy_client):
    resp = toy_client.post("/api/v1/toys", json=_toy_body())
    assert resp.status_code == 401


def test_create_toy_forbidden_for_customer(customer_toy_client):
    resp = customer_toy_client.post("/api/v1/toys", json=_toy_body())
    assert resp.status_code == 403


def test_create_toy_as_admin(created_toy):
    assert created_toy["id"]
    assert created_toy["category"] == "PRETEND_PLAY"
    assert created_toy["status"] == "AVAILABLE"
    assert float(created_toy["weeklyPrice"]) == 199.00
    assert float(created_toy["depositAmount"]) == 800.00


def test_created_toy_is_publicly_gettable(toy_client, created_toy):
    resp = toy_client.get(f"/api/v1/toys/{created_toy['id']}")
    assert resp.status_code == 200
    assert resp.json()["name"] == created_toy["name"]


# ---- update ------------------------------------------------------------- #

def test_update_toy_requires_auth(toy_client, created_toy):
    resp = toy_client.put(f"/api/v1/toys/{created_toy['id']}", json=_toy_body())
    assert resp.status_code == 401


def test_update_toy_forbidden_for_customer(customer_toy_client, created_toy):
    resp = customer_toy_client.put(
        f"/api/v1/toys/{created_toy['id']}", json=_toy_body()
    )
    assert resp.status_code == 403


def test_update_toy_as_admin(admin_toy_client, toy_client, created_toy):
    resp = admin_toy_client.put(
        f"/api/v1/toys/{created_toy['id']}", json=_toy_body(weeklyPrice=249.00)
    )
    assert resp.status_code == 200
    got = toy_client.get(f"/api/v1/toys/{created_toy['id']}").json()
    assert float(got["weeklyPrice"]) == 249.00


def test_update_condition_as_admin(admin_toy_client, toy_client, created_toy):
    resp = admin_toy_client.put(
        f"/api/v1/admin/toys/{created_toy['id']}/condition", json={"condition": "FAIR"}
    )
    assert resp.status_code == 200
    got = toy_client.get(f"/api/v1/toys/{created_toy['id']}").json()
    assert got["condition"] == "FAIR"


# ---- admin read endpoints -------------------------------------------------- #

def test_admin_inventory_auth_matrix(admin_toy_client, customer_toy_client, toy_client):
    assert admin_toy_client.get(
        "/api/v1/admin/toys/inventory", params={"page": 0, "size": 10}
    ).status_code == 200
    assert customer_toy_client.get("/api/v1/admin/toys/inventory").status_code == 403
    assert toy_client.get("/api/v1/admin/toys/inventory").status_code == 401


def test_admin_low_stock(admin_toy_client):
    resp = admin_toy_client.get(
        "/api/v1/admin/toys/low-stock", params={"page": 0, "size": 10}
    )
    assert resp.status_code == 200
    assert "content" in resp.json()


# ---- delete ------------------------------------------------------------- #

def test_delete_toy_requires_auth(toy_client, created_toy):
    resp = toy_client.delete(f"/api/v1/toys/{created_toy['id']}")
    assert resp.status_code == 401


def test_delete_toy_forbidden_for_customer(customer_toy_client, created_toy):
    resp = customer_toy_client.delete(f"/api/v1/toys/{created_toy['id']}")
    assert resp.status_code == 403


def test_delete_toy_as_admin(admin_toy_client, toy_client):
    # dedicated throwaway toy (not the shared `created_toy`)
    toy_id = admin_toy_client.post("/api/v1/toys", json=_toy_body()).json()["id"]

    resp = admin_toy_client.delete(f"/api/v1/toys/{toy_id}")
    assert resp.status_code == 204

    detail = toy_client.get(f"/api/v1/toys/{toy_id}")
    if detail.status_code == 200:
        assert detail.json()["active"] is False  # soft delete
    else:
        assert detail.status_code == 404

    listing = toy_client.get("/api/v1/toys", params={"size": 200}).json()["content"]
    assert all(t["id"] != toy_id for t in listing)
