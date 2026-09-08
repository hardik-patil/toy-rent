"""booking-service /api/v1/admin/bookings + /api/v1/admin/reports."""

import pytest

pytestmark = pytest.mark.admin


@pytest.mark.read_only
def test_admin_bookings_list_ok(admin_booking_client):
    resp = admin_booking_client.get(
        "/api/v1/admin/bookings", params={"page": 0, "size": 10}
    )
    assert resp.status_code == 200
    body = resp.json()
    assert isinstance(body["content"], list)
    for row in body["content"]:
        assert row["id"] and row["status"]


@pytest.mark.read_only
def test_admin_bookings_status_filter(admin_booking_client):
    resp = admin_booking_client.get(
        "/api/v1/admin/bookings", params={"status": "PENDING", "page": 0, "size": 10}
    )
    assert resp.status_code == 200
    assert all(r["status"] == "PENDING" for r in resp.json()["content"])


@pytest.mark.read_only
def test_admin_bookings_forbidden_for_customer(seed_booking_client):
    assert seed_booking_client.get("/api/v1/admin/bookings").status_code == 403


@pytest.mark.read_only
def test_admin_bookings_requires_auth(booking_client):
    assert booking_client.get("/api/v1/admin/bookings").status_code == 401


@pytest.mark.read_only
@pytest.mark.parametrize("path", ["today/deliveries", "today/pickups", "overdue"])
def test_admin_run_sheets(admin_booking_client, path):
    resp = admin_booking_client.get(
        f"/api/v1/admin/bookings/{path}", params={"page": 0, "size": 10}
    )
    assert resp.status_code == 200
    assert "content" in resp.json()


@pytest.mark.mutating
@pytest.mark.slow
def test_admin_manual_confirm(pending_booking, admin_booking_client, poll):
    resp = admin_booking_client.put(
        f"/api/v1/admin/bookings/{pending_booking['id']}/confirm"
    )
    assert resp.status_code == 200

    owner = pending_booking["customer"]["client"]

    def _confirmed():
        r = owner.get(f"/api/v1/bookings/{pending_booking['id']}")
        return r.json() if r.status_code == 200 and r.json()["status"] == "CONFIRMED" else None

    poll(_confirmed)


@pytest.mark.read_only
def test_admin_reports_list(admin_booking_client):
    resp = admin_booking_client.get(
        "/api/v1/admin/reports", params={"page": 0, "size": 10}
    )
    assert resp.status_code == 200
    assert "content" in resp.json()


@pytest.mark.mutating
@pytest.mark.slow
def test_admin_reports_trigger(admin_booking_client):
    # Fire-and-forget Kafka job; only assert the 202 ack, not the async artifact.
    resp = admin_booking_client.post(
        "/api/v1/admin/reports/trigger", json={"month": 8, "year": 2026}
    )
    assert resp.status_code == 202
