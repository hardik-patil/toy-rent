"""
Building blocks for the ToyRental black-box API suite.

    ApiClient            one requests.Session per service, base-URL prefixing,
                         Bearer token, X-Correlation-ID injection
    poll_until           retry a predicate until truthy or a deadline
    random_phone         a 10-digit phone in a reserved block (no seed collisions)
    spread_date_range    a randomised future booking window
    is_jwt               shallow "looks like a JWT" check
    assert_error_contract assert the six-key GlobalExceptionHandler error body

`requests` is imported only here — swapping ApiClient back to stdlib urllib
(the approach in loadtest/*_probe.py) would be a single-file change.
"""

import random
import time
import uuid
from datetime import date, timedelta

import requests

# Success codes the create-booking path can legitimately return: 201 on a fresh
# booking, 409 when the toy is already blocked for that range OR the pessimistic
# lock finds an overlap (booking-service returns 409 for both).
BOOKING_OK_CODES = (201, 409)


class ApiClient:
    """Thin wrapper over a requests.Session bound to one service base URL."""

    def __init__(self, base_url, timeout, token=None):
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout
        self.token = token
        self.session = requests.Session()
        self.last_correlation_id = None

    def with_token(self, token):
        """A sibling client for the same service, carrying a bearer token."""
        return ApiClient(self.base_url, self.timeout, token=token)

    def request(self, method, path, *, correlation_id=None, **kwargs):
        """
        `correlation_id`:
            None   -> generate a fresh uuid4 hex and send it (default)
            False  -> send no X-Correlation-ID header at all
            str    -> send exactly this value
        Returns the raw requests.Response; never raises for HTTP status.
        """
        url = path if path.startswith("http") else f"{self.base_url}{path}"
        headers = dict(kwargs.pop("headers", {}) or {})

        if self.token:
            headers.setdefault("Authorization", f"Bearer {self.token}")

        if correlation_id is False:
            self.last_correlation_id = None
        else:
            cid = correlation_id if correlation_id else uuid.uuid4().hex
            headers.setdefault("X-Correlation-ID", cid)
            self.last_correlation_id = cid

        kwargs.setdefault("timeout", self.timeout)
        return self.session.request(method, url, headers=headers, **kwargs)

    def get(self, path, **kwargs):
        return self.request("GET", path, **kwargs)

    def post(self, path, **kwargs):
        return self.request("POST", path, **kwargs)

    def put(self, path, **kwargs):
        return self.request("PUT", path, **kwargs)

    def delete(self, path, **kwargs):
        return self.request("DELETE", path, **kwargs)


def poll_until(predicate, *, timeout, interval):
    """
    Call `predicate()` until it returns a truthy value or `timeout` seconds pass.
    Returns the truthy value. Raises AssertionError (with the last seen value) on
    timeout. Use for async assertions: webhook -> booking CONFIRMED, etc.
    """
    deadline = time.monotonic() + timeout
    last = None
    while True:
        last = predicate()
        if last:
            return last
        if time.monotonic() >= deadline:
            raise AssertionError(
                f"poll_until timed out after {timeout}s; last value: {last!r}"
            )
        time.sleep(interval)


def random_phone():
    """
    A 10-digit phone starting '70' + 8 random digits. Avoids every phone already
    in use: the V6 seed customer (9821012345), the Postman samples (98765xxxxx),
    and the loadtest customers (9990000xxx).
    """
    return "70" + "".join(str(random.randint(0, 9)) for _ in range(8))


def spread_date_range(start_offset_days, rental_type="WEEKLY", horizon_days=300):
    """
    (start_iso, end_iso) for a booking window that starts at a random day in
    [today + start_offset_days, today + start_offset_days + horizon_days] and
    runs 7 days (WEEKLY) or 30 days (MONTHLY). The random start over a wide
    horizon keeps re-runs from colliding on the same toy+range (which would 409),
    even after many CONFIRMED bookings have accumulated blocked ranges.
    """
    start = date.today() + timedelta(
        days=start_offset_days + random.randint(0, horizon_days)
    )
    span = 30 if rental_type == "MONTHLY" else 7
    end = start + timedelta(days=span)
    return start.isoformat(), end.isoformat()


def is_jwt(value):
    """Three non-empty dot-separated segments — enough to tell a JWT from a stub."""
    if not isinstance(value, str):
        return False
    parts = value.split(".")
    return len(parts) == 3 and all(parts)


def assert_error_contract(resp, *, status=None, error=None, correlation_id=None):
    """
    Assert the standard six-key error body produced by GlobalExceptionHandler in
    both services: {timestamp, status, error, message, correlationId, path}.

    Only valid for responses that actually flow through GlobalExceptionHandler:
    400 VALIDATION_ERROR / INVALID_BOOKING_REQUEST, 404s, 409s, and
    401 INVALID_CREDENTIALS. Spring Security's own 401 (missing/invalid JWT) and
    403 (wrong role), and missing-required-query-param 400s, return a different
    (often empty) body — assert status only for those.
    """
    body = resp.json()
    assert set(body) == {
        "timestamp",
        "status",
        "error",
        "message",
        "correlationId",
        "path",
    }, f"unexpected error body keys: {sorted(body)}"
    assert body["status"] == resp.status_code
    if status is not None:
        assert resp.status_code == status, (
            f"expected HTTP {status}, got {resp.status_code}: {body}"
        )
    if error is not None:
        assert body["error"] == error, f"expected error={error!r}, got {body!r}"
    if correlation_id is not None:
        assert body["correlationId"] == correlation_id
    return body
