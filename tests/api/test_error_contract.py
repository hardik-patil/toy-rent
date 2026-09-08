"""The standard six-key error body + X-Correlation-ID handling.

Only endpoints whose errors flow through GlobalExceptionHandler are asserted
here — see helpers.assert_error_contract for which those are.
"""

import uuid

import pytest

from helpers import assert_error_contract

pytestmark = pytest.mark.read_only


def test_correlation_id_echoed_toy(toy_client):
    cid = uuid.uuid4().hex
    resp = toy_client.get("/api/v1/toys/bogus-id", correlation_id=cid)
    body = assert_error_contract(
        resp, status=404, error="TOY_NOT_FOUND", correlation_id=cid
    )
    assert body["path"] == "/api/v1/toys/bogus-id"


def test_correlation_id_unknown_when_omitted(toy_client):
    resp = toy_client.get("/api/v1/toys/bogus-id", correlation_id=False)
    body = assert_error_contract(resp, status=404, error="TOY_NOT_FOUND")
    assert body["correlationId"] == "unknown"


def test_error_contract_booking_service(seed_booking_client):
    cid = uuid.uuid4().hex
    resp = seed_booking_client.get("/api/v1/bookings/bkg-nope", correlation_id=cid)
    assert_error_contract(
        resp, status=404, error="BOOKING_NOT_FOUND", correlation_id=cid
    )


def test_validation_error_shape(booking_client):
    cid = uuid.uuid4().hex
    resp = booking_client.post(
        "/api/v1/customers/register",
        json={"name": "X", "phone": "9998887777", "password": "short"},
        correlation_id=cid,
    )
    body = assert_error_contract(
        resp, status=400, error="VALIDATION_ERROR", correlation_id=cid
    )
    assert body["message"]
