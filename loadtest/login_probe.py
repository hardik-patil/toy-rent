#!/usr/bin/env python3
"""
login_probe.py - fire N concurrent POST /customers/login requests and report the
latency distribution + error count. Deliberately tiny and dependency-free: a
controlled, repeatable probe for the login path in isolation (no browse mix),
used for before/after measurement of the bottleneck-#1 fix.

  python loadtest/login_probe.py --concurrency 60 --rounds 3
"""
import argparse, json, statistics as st, time, urllib.request, urllib.error
from concurrent.futures import ThreadPoolExecutor

def one(url, body, timeout):
    data = json.dumps(body).encode()
    req = urllib.request.Request(url, data=data, headers={"Content-Type": "application/json"})
    t = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            r.read()
            return (time.perf_counter() - t) * 1000, r.status, None
    except urllib.error.HTTPError as e:
        return (time.perf_counter() - t) * 1000, e.code, None
    except Exception as e:
        return (time.perf_counter() - t) * 1000, None, type(e).__name__

def pct(xs, p):
    xs = sorted(xs); return xs[min(len(xs) - 1, int(round(p / 100 * len(xs))))]

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://localhost:8082")
    ap.add_argument("--phone", default="9821012345")
    ap.add_argument("--password", default="password")
    ap.add_argument("--concurrency", type=int, default=60)
    ap.add_argument("--rounds", type=int, default=3)
    ap.add_argument("--timeout", type=float, default=15.0)
    ap.add_argument("--gap", type=float, default=5.0, help="seconds between rounds")
    a = ap.parse_args()
    url = f"{a.base}/api/v1/customers/login"
    body = {"phone": a.phone, "password": a.password}

    all_ok = []
    for rnd in range(1, a.rounds + 1):
        with ThreadPoolExecutor(max_workers=a.concurrency) as ex:
            t0 = time.perf_counter()
            res = list(ex.map(lambda _: one(url, body, a.timeout), range(a.concurrency)))
            wall = time.perf_counter() - t0
        lat = [r[0] for r in res]
        ok = [r[0] for r in res if r[1] == 200]
        errs = [r for r in res if r[1] != 200]
        all_ok += ok
        codes = {}
        for r in res:
            k = r[2] or r[1]
            codes[k] = codes.get(k, 0) + 1
        print(f"round {rnd}: n={a.concurrency} wall={wall:.1f}s ok={len(ok)} "
              f"err={len(errs)} codes={codes}")
        print(f"  ms  mean={st.mean(lat):.0f}  p50={pct(lat,50):.0f}  p90={pct(lat,90):.0f}  "
              f"p95={pct(lat,95):.0f}  p99={pct(lat,99):.0f}  max={max(lat):.0f}")
        if rnd < a.rounds:
            time.sleep(a.gap)

    if all_ok:
        print(f"\nall successful rounds combined (n={len(all_ok)}): "
              f"mean={st.mean(all_ok):.0f}  p50={pct(all_ok,50):.0f}  p95={pct(all_ok,95):.0f}  "
              f"p99={pct(all_ok,99):.0f}  max={max(all_ok):.0f}")

if __name__ == "__main__":
    main()
