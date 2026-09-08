"""End-to-end: register -> login -> browse -> book -> webhook -> CONFIRMED.

Run just this file with:  pytest tests/api/test_booking_flow_e2e.py
"""

import random
import uuid

import pytest

from helpers import is_jwt, random_phone, spread_date_range

pytestmark = [pytest.mark.e2e, pytest.mark.mutating, pytest.mark.slow]

SEED_TOY_IDS = [f"toy-00{n}" for n in range(1, 9)]

_ADDRESS = {
    "area": "Kharghar",
    "flat": "B-204",
    "building": "Neelkanth Heights",
    "city": "Navi Mumbai",
    "pincode": "410210",
}


def test_booking_flow(booking_client, toy_client, admin_booking_client, config, poll):
    # 1. register
    phone = random_phone()
    password = "pytest-pw-123"
    reg = booking_client.post(
        "/api/v1/customers/register",
        json={
            "name": f"E2E {uuid.uuid4().hex[:8]}",
            "phone": phone,
            "email": f"e2e-{uuid.uuid4().hex[:8]}@example.com",
            "password": password,
            **_ADDRESS,
        },
    )
    assert reg.status_code == 201, reg.text
    customer_id = reg.json()["id"]

    # 2. login
    login = booking_client.post(
        "/api/v1/customers/login", json={"phone": phone, "password": password}
    )
    assert login.status_code == 200
    token = login.json()["accessToken"]
    assert is_jwt(token)
    customer = booking_client.with_token(token)

    # 3. browse a toy + 4. find a (toy, window) that is actually free — prior
    #    runs leave CONFIRMED bookings that block ranges, so a random pick can be
    #    unavailable.
    toy_id = start = end = None
    for _ in range(12):
        candidate = random.choice(SEED_TOY_IDS)
        detail = toy_client.get(f"/api/v1/toys/{candidate}")
        assert detail.status_code == 200
        s, e = spread_date_range(config.booking_start_offset_days)
        avail = toy_client.get(
            f"/api/v1/toys/{candidate}/availability", params={"from": s, "to": e}
        )
        assert avail.status_code == 200
        if avail.json()["available"] is True:
            toy_id, start, end = candidate, s, e
            break
    assert toy_id, "no seed toy had a free window after 12 tries"

    # 5. create booking
    created = customer.post(
        "/api/v1/bookings",
        json={
            "toyId": toy_id,
            "startDate": start,
            "endDate": end,
            "rentalType": "WEEKLY",
            "deliveryFlat": "B-204",
            "deliveryBuilding": "Neelkanth Heights",
            "deliveryArea": "Kharghar",
            "deliveryCity": "Navi Mumbai",
            "deliveryPincode": "410210",
        },
    )
    assert created.status_code == 201, created.text
    booking = created.json()
    assert booking["status"] == "PENDING"
    booking_id = booking["id"]
    order_id = booking["razorpayOrderId"]
    assert order_id

    # 6. owner can read it, still PENDING
    got = customer.get(f"/api/v1/bookings/{booking_id}")
    assert got.status_code == 200 and got.json()["status"] == "PENDING"

    # 7. Razorpay webhook (no auth)
    wh = booking_client.post(
        "/api/v1/payments/webhook",
        json={
            "razorpay_order_id": order_id,
            "razorpay_payment_id": "pay_mock456",
            "razorpay_signature": "mock_signature_abc",
        },
    )
    assert wh.status_code == 200

    # 8. poll until CONFIRMED
    def _confirmed():
        r = customer.get(f"/api/v1/bookings/{booking_id}")
        return r.json() if r.status_code == 200 and r.json()["status"] == "CONFIRMED" else None

    final = poll(_confirmed)
    assert final["paymentStatus"] == "SUCCESS"

    # 9. shows in the customer's own bookings
    mine = customer.get("/api/v1/customers/me/bookings", params={"page": 0, "size": 50})
    assert mine.status_code == 200
    assert any(
        b["id"] == booking_id and b["status"] == "CONFIRMED"
        for b in mine.json()["content"]
    )

    # 10. shows in the admin CONFIRMED list
    admin_rows = admin_booking_client.get(
        "/api/v1/admin/bookings", params={"status": "CONFIRMED", "size": 200}
    )
    assert admin_rows.status_code == 200
    assert any(b["id"] == booking_id for b in admin_rows.json()["content"])
    assert final["customerId"] == customer_id
