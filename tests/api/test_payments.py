"""booking-service /api/v1/payments: webhook (no auth) / initiate / confirm."""

import pytest

from helpers import assert_error_contract

pytestmark = pytest.mark.mutating

_WEBHOOK_BODY = {
    "razorpay_order_id": "order_mock123",
    "razorpay_payment_id": "pay_mock456",
    "razorpay_signature": "mock_signature_abc",
}


def test_webhook_no_auth_required(booking_client):
    # The webhook is permitAll. With no live pending payment for the static dev
    # order id it returns 402 PAYMENT_FAILED (business logic), NOT 401/403 — which
    # is exactly the point: the request was processed without authentication.
    resp = booking_client.post("/api/v1/payments/webhook", json=dict(_WEBHOOK_BODY))
    assert resp.status_code not in (401, 403), f"{resp.status_code} {resp.text}"
    assert resp.status_code in {200, 402}


def test_webhook_missing_fields(booking_client):
    resp = booking_client.post("/api/v1/payments/webhook", json={})
    assert_error_contract(resp, status=400, error="VALIDATION_ERROR")


@pytest.mark.slow
def test_webhook_confirms_booking(pending_booking, booking_client, poll):
    wh = booking_client.post(
        "/api/v1/payments/webhook",
        json={
            "razorpay_order_id": pending_booking["razorpay_order_id"],
            "razorpay_payment_id": "pay_mock456",
            "razorpay_signature": "mock_signature_abc",
        },
    )
    assert wh.status_code == 200

    owner = pending_booking["customer"]["client"]

    def _confirmed():
        r = owner.get(f"/api/v1/bookings/{pending_booking['id']}")
        return r.json() if r.status_code == 200 and r.json()["status"] == "CONFIRMED" else None

    body = poll(_confirmed)
    assert body["paymentStatus"] == "SUCCESS"


def test_payment_initiate_requires_auth(booking_client):
    resp = booking_client.post(
        "/api/v1/payments/initiate", json={"bookingId": "bkg-x", "method": "UPI"}
    )
    assert resp.status_code == 401


def test_payment_initiate_ok(pending_booking):
    client = pending_booking["customer"]["client"]
    resp = client.post(
        "/api/v1/payments/initiate",
        json={"bookingId": pending_booking["id"], "method": "UPI"},
    )
    assert resp.status_code in {200, 201}, f"{resp.status_code} {resp.text}"
    body = resp.json()
    assert body.get("id") or body.get("paymentId")
    assert body.get("bookingId") == pending_booking["id"]


def test_get_payment_requires_auth(booking_client):
    assert booking_client.get("/api/v1/payments/pay-anything").status_code == 401
