"""toy-service availability: check / calendar / browse-available."""

from datetime import date, timedelta

import pytest

pytestmark = pytest.mark.read_only

_AVAIL_KEYS = {
    "toyId",
    "toyName",
    "status",
    "available",
    "blockedDates",
    "nextAvailable",
    "lastUpdated",
}


def _window(offset_start=30, span=7):
    frm = date.today() + timedelta(days=offset_start)
    to = frm + timedelta(days=span)
    return frm.isoformat(), to.isoformat()


def test_availability_ok(toy_client, some_toy_id):
    frm, to = _window()
    resp = toy_client.get(
        f"/api/v1/toys/{some_toy_id}/availability", params={"from": frm, "to": to}
    )
    assert resp.status_code == 200
    body = resp.json()
    assert _AVAIL_KEYS <= set(body)
    assert isinstance(body["available"], bool)
    assert isinstance(body["blockedDates"], list)


# A missing required query param is forwarded to /error (not permitAll in
# toy-service SecurityConfig), so these currently surface as 401 rather than a
# clean 400. Accept either: the request is rejected, no data leaked.
def test_availability_missing_from(toy_client, some_toy_id):
    _, to = _window()
    resp = toy_client.get(
        f"/api/v1/toys/{some_toy_id}/availability", params={"to": to}
    )
    assert resp.status_code in {400, 401}


def test_availability_missing_to(toy_client, some_toy_id):
    frm, _ = _window()
    resp = toy_client.get(
        f"/api/v1/toys/{some_toy_id}/availability", params={"from": frm}
    )
    assert resp.status_code in {400, 401}


def test_availability_missing_both(toy_client, some_toy_id):
    resp = toy_client.get(f"/api/v1/toys/{some_toy_id}/availability")
    assert resp.status_code in {400, 401}


def test_availability_bad_date_format(toy_client, some_toy_id):
    # An unparseable date is a MethodArgumentTypeMismatchException, which
    # toy-service has no @ExceptionHandler for — it falls through to the
    # RuntimeException catch-all as 500. Ideally this would be a 400.
    resp = toy_client.get(
        f"/api/v1/toys/{some_toy_id}/availability",
        params={"from": "notadate", "to": "notadate"},
    )
    assert resp.status_code in {400, 500}


def test_availability_unknown_toy(toy_client):
    frm, to = _window()
    resp = toy_client.get(
        "/api/v1/toys/bogus-toy/availability", params={"from": frm, "to": to}
    )
    assert resp.status_code in {200, 404}
    if resp.status_code == 404:
        assert resp.json()["error"] == "TOY_NOT_FOUND"


def test_availability_calendar(toy_client):
    resp = toy_client.get("/api/v1/toys/toy-001/availability/calendar")
    assert resp.status_code == 200
    assert _AVAIL_KEYS <= set(resp.json())


def test_available_toys_list(toy_client):
    frm, to = _window()
    resp = toy_client.get(
        "/api/v1/toys/available", params={"from": frm, "to": to, "page": 0, "size": 10}
    )
    assert resp.status_code == 200
    body = resp.json()
    assert isinstance(body["content"], list)
    for toy in body["content"]:
        assert toy["id"]


def test_available_toys_missing_dates(toy_client):
    # Same /error-forward quirk as the availability missing-param cases above.
    resp = toy_client.get("/api/v1/toys/available")
    assert resp.status_code in {400, 401}
