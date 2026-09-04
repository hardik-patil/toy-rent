#!/usr/bin/env python3
"""
booking_probe.py - authenticate once, then fire N concurrent POST /api/v1/bookings
and report the latency distribution + response-code histogram. Controlled,
repeatable probe for the create-booking path (bottleneck #2).

Each concurrent request books a *different* random toy from loadtest/data/toy_ids.csv,
so the run isn't dominated by the pessimistic-lock 409 path (that's exercised by the
JMeter plan; here we want to see the Feign + pool + timeout behaviour).

  python loadtest/booking_probe.py --concurrency 40 --rounds 3
"""
import argparse, csv, json, random, statistics as st, time, urllib.request, urllib.error
from concurrent.futures import ThreadPoolExecutor
from datetime import date, timedelta

def post(url, body, token, timeout):
    data = json.dumps(body).encode()
    h = {"Content-Type": "application/json"}
    if token:
        h["Authorization"] = f"Bearer {token}"
    req = urllib.request.Request(url, data=data, headers=h)
    t = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            r.read()
            return (time.perf_counter() - t) * 1000, r.status
    except urllib.error.HTTPError as e:
        return (time.perf_counter() - t) * 1000, e.code
    except Exception as e:
        return (time.perf_counter() - t) * 1000, type(e).__name__

def pct(xs, p):
    xs = sorted(xs); return xs[min(len(xs) - 1, int(round(p / 100 * len(xs))))]

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://localhost:8082")
    ap.add_argument("--phone", default="9990000001")   # a cust-lt-* row from seed_loadtest_customers.sql
    ap.add_argument("--password", default="password")
    ap.add_argument("--toy-ids", default="loadtest/data/toy_ids.csv")
    ap.add_argument("--concurrency", type=int, default=40)
    ap.add_argument("--rounds", type=int, default=3)
    ap.add_argument("--timeout", type=float, default=30.0)
    ap.add_argument("--gap", type=float, default=6.0)
    a = ap.parse_args()

    # login
    lr = urllib.request.Request(f"{a.base}/api/v1/customers/login",
                                data=json.dumps({"phone": a.phone, "password": a.password}).encode(),
                                headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(lr, timeout=15) as r:
        token = json.loads(r.read())["accessToken"]
    print(f"authenticated as {a.phone}")

    with open(a.toy_ids) as f:
        toy_ids = [row[0] for row in csv.reader(f) if row and row[0] != "toyId"]
    print(f"{len(toy_ids)} toy ids loaded")

    url = f"{a.base}/api/v1/bookings"
    start = (date.today() + timedelta(days=3)).isoformat()
    end = (date.today() + timedelta(days=10)).isoformat()

    def one(_):
        body = {
            "toyId": random.choice(toy_ids),
            "startDate": start, "endDate": end, "rentalType": "WEEKLY",
            "deliveryFlat": "B-204", "deliveryBuilding": "Neelkanth Heights",
            "deliveryArea": "Kharghar", "deliveryCity": "Navi Mumbai", "deliveryPincode": "410210",
        }
        return post(url, body, token, a.timeout)

    all_ok = []
    for rnd in range(1, a.rounds + 1):
        with ThreadPoolExecutor(max_workers=a.concurrency) as ex:
            t0 = time.perf_counter()
            res = list(ex.map(one, range(a.concurrency)))
            wall = time.perf_counter() - t0
        lat = [r[0] for r in res]
        codes = {}
        for r in res:
            codes[r[1]] = codes.get(r[1], 0) + 1
        # 201 = created, 409 = toy already booked for those dates (expected, not an error)
        ok = [r[0] for r in res if r[1] in (201, 409)]
        all_ok += ok
        print(f"round {rnd}: n={a.concurrency} wall={wall:.1f}s codes={codes}")
        print(f"  ms  mean={st.mean(lat):.0f}  p50={pct(lat,50):.0f}  p90={pct(lat,90):.0f}  "
              f"p95={pct(lat,95):.0f}  p99={pct(lat,99):.0f}  max={max(lat):.0f}")
        if rnd < a.rounds:
            time.sleep(a.gap)

    if all_ok:
        print(f"\n2xx/409 combined (n={len(all_ok)}): mean={st.mean(all_ok):.0f}  "
              f"p50={pct(all_ok,50):.0f}  p95={pct(all_ok,95):.0f}  p99={pct(all_ok,99):.0f}  max={max(all_ok):.0f}")

if __name__ == "__main__":
    main()
