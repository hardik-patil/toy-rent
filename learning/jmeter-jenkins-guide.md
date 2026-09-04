# JMeter Scripting + Jenkins CI/CD — Tutoring Log

Written from a real tutoring pass on this repo: reviewing and fixing a hand-authored plan
(`loadtest/ToyRentalMixed-60-tps.jmx`), then (later) wiring it into a Jenkins pipeline.
This is **not** a restatement of JMeter theory — that's already covered in
[jmeter-fundamentals.md](jmeter-fundamentals.md) (anatomy, scoping, timers, correlation
basics, listeners, the standard `Common mistakes` list). This doc is the next layer down:
the specific, easy-to-miss bugs a real plan actually had, why each one breaks, and the
idioms that fix them for good.

**Status:** Part 1 (JMeter scripting) complete. Part 2 (Jenkins) not started yet.

---

# Part 1 — JMeter scripting: lessons from a real review

Case study: `loadtest/ToyRentalMixed-60-tps.jmx`, an open-model mixed plan (60 TPS target,
80/12/8 split across browse / detail / booking, JWT correlation, conditional webhook call).
Ambitious and structurally sound — but had five things that would break it outright and
several that would make it silently measure the wrong thing. None of these are exotic;
they're the JMeter equivalents of off-by-one errors, and every one of them is worth
recognizing on sight.

## 1. `${VAR}` typos don't fail loudly

```
ThreadGroup.ramp_time">${RAMP_UP)          <- closing ) instead of }
```

JMeter doesn't validate that `${...}` is balanced at load time in every field — an
unclosed reference like this is just consumed as a literal string. The ramp time becomes
the text `${RAMP_UP)`, JMeter tries to parse it as a number, and you get either a startup
error or a silent `0`. **Lesson:** after any manual XML edit (or hand-typing a `${}` in the
GUI's raw fields), re-open the plan in the GUI once before running non-GUI — the GUI will
often show an obviously-wrong value where the CLI just fails cryptically.

## 2. Nesting `${var}` inside a function call is fragile — use `vars.get()`

Two places in the original plan did this:

```
${__jexl3(System.currentTimeMillis() >= Long.parseLong(${tokenExpiry),)}   <- also has a typo
${__jexl3("${bookingid}" != "NOT_FOUND")}
```

Both patterns *can* work (JMeter resolves `${}` references before handing the string to
the function), but they're brittle: a typo inside the inner reference is invisible until
runtime, and quoting a variable's value directly into JEXL string literals breaks the
moment the value itself contains a quote or an unexpected character. The idiomatic fix
inside a `__jexl3`/`__groovy` expression is to reach into the variable map explicitly:

```
${__jexl3(vars.get("bookingid") != "NOT_FOUND")}
```

**Lesson:** once you're inside a scripting function (`__jexl3`, `__groovy`, JSR223), stop
using `${}` substitution for variables you need *inside* that script — use the script
language's own variable-access API (`vars.get(...)` for JEXL/Groovy). Reserve `${}` for
substituting into plain string fields (paths, headers, JSON bodies).

## 3. A stray comment inside a scripted field is not a comment

```xml
<stringProp name="WhileController.condition">${__jexl3(...)}

/*
First iteration: ...
*/</stringProp>
```

That `/* ... */` block looks like a helpful comment, but it's *inside the field's string
value* — JMeter (or the JEXL engine, or Groovy) sees it as part of the expression to
evaluate. Sometimes it's silently ignored, sometimes it's a parse error, and either way
it's not documentation, it's a landmine. **Lesson:** every JMeter element with a scripted
field has a separate, real comment mechanism — the `Comments` field in the GUI, stored as
`TestElement.comments` in the XML. Put your explanation there, never inside the
expression string.

## 4. `assume_success=true` on an assertion defeats the assertion

```xml
<ResponseAssertion ...>
  <boolProp name="Assertion.assume_success">true</boolProp>
  ...
</ResponseAssertion>
```

This flag marks the **sample** successful *regardless* of whether the assertion passes —
it's for the rare case where you want to run an assertion for its extracted side-effects
(none here) without letting it fail the sample. Combined with a real correctness check
(`response code is 201 or 409`), it silently converts every 500/timeout into "pass" in
your results. **Lesson:** don't reach for `assume_success` to make a "flexible" assertion —
express the flexibility *in the assertion itself* (a regex/set of acceptable values), and
leave `assume_success` false so a genuine failure still counts as one.

## 5. "Contains" in a JMeter Response Assertion is regex, not substring

I got this wrong in an earlier pass of this same review and want it on record: JMeter's
Response Assertion test types are **Matches** (full-string regex match), **Contains**
(regex *search* — `Pattern.matcher(x).find()`), **Equals** (literal string equality), and
**Substring** (literal substring, no regex). So `test_type=2` ("Contains") with pattern
`^(201|409)$` is already correct — the anchors make it behave like a full match even
though the engine is technically doing a "find". Don't assume "Contains" means "no regex"
— check the JMeter docs (or just test it) before changing an assertion type.

## 6. A CSV Data Set with a header row needs `ignoreFirstLine=true`

```
data/toy_ids.csv:
  toyId
  toy-bulk-46244
  ...
```

With `ignoreFirstLine=false`, the **first row read is the header string itself** — so the
first request that draws from this CSV gets `toyId=toyId` (a 404), and the first browse
request got `category=category&ageGroup=ageGroup` (matches nothing). This produced a real,
previously-mysterious finding earlier in this project's load-test analysis ("why do the
first requests look like they have literal placeholder values instead of real data?") —
the answer was exactly this flag. **Lesson:** any CSV Data Set backed by a file with a
header row needs `ignoreFirstLine=true`; it's independently `true`/`false`, unrelated to
whether `variableNames` is set.

## 7. Absolute paths break the moment the plan leaves your machine

```
C:\Users\USER\Documents\toy-rent\loadtest\data\toy_ids.csv
```

Works for you, breaks in Docker, in Jenkins, on a teammate's machine, or if the repo is
ever cloned to a different path. JMeter resolves a **relative** CSV/script path against the
`.jmx` file's own directory (both GUI and `-n` CLI), so `data/toy_ids.csv` (with the `.jmx`
at `loadtest/ToyRentalMixed-60-tps.jmx`) works everywhere without a `-J` override. This
matters *specifically* for the Jenkins track — a plan with baked-in absolute paths cannot
run on a CI agent at all.

## 8. File I/O inside the load path corrupts your own measurements

The original plan had a JSR223 PostProcessor that wrote a CSV row to disk on every
"toy detail" request — a data-harvesting script that had leaked into the load path. Two
problems: post-processor execution time is counted **inside** its parent Transaction
Controller's sample time, so every detail-transaction's latency now includes a disk write;
and the disk I/O itself adds contention on the load-generator machine, especially when
JMeter and the system under test share one laptop (see `jmeter-fundamentals.md`'s mistake
#5). **Lesson:** if you need to harvest data *from* responses during a test, that's a
separate, one-off script/plan — never a component the load plan's timing depends on.

## 9. Don't stack two throughput-control mechanisms for one goal

The plan had **both**:
- A `ThroughputController` (percent-executions style) deciding which of three branches
  (browse/detail/booking) an iteration runs — 80/12/8.
- A separate `PreciseThroughputTimer` *inside each branch*, each pacing that branch to an
  absolute rate computed from the overall target TPS.

These don't compose cleanly — one gates *which* work happens, the other paces *when* work
happens, and running both means you can no longer reason about what rate you actually
achieved. **Fix applied:** one `PreciseThroughputTimer` at Thread-Group scope (placed after
the login block, so it doesn't throttle login itself — timer scope is "everything that
executes after this point, in this and nested scopes"), left the three
`ThroughputController`s to do only the mix-shaping. One mechanism per job.

**Also caught:** the percentages summed to 104% (browse 80 + detail 12 + booking 12,
though the last one was *named* "8%"). A percent-style `ThroughputController` set silently
tolerates not summing to 100 — it doesn't error, it just gives you a mix you didn't intend.
Always add the percentages by hand when reviewing one of these.

## 10. Size the thread pool for the throughput you want (Little's Law)

`THREADS=10` targeting `TPS=60` cannot work, regardless of how the timers are configured:

```
threads_needed ≈ target_TPS × (avg_response_time + think_time)
```

At roughly 1.2s per iteration, 60 TPS needs on the order of 70+ concurrent threads just to
have enough in flight; 10 threads physically cap you around 8 TPS. A `PreciseThroughputTimer`
can only *slow down* a thread pool that has more capacity than the target — it cannot
manufacture concurrency the thread group doesn't have. **Lesson:** when a throughput target
isn't being hit, check thread count against Little's Law before suspecting the timer
configuration.

## 11. Building a token-refresh loop correctly (`Once Only` + `While`)

The realistic pattern for an API whose tokens expire (this app's don't — 24h TTL, see
`JwtTokenService.EXPIRY_SECONDS` — but most real ones do, on the order of 5–60 minutes):

```
Once Only Controller                  (runs exactly once per thread)
  └─ JSR223 Sampler: vars.put("tokenExpiry", "0")

While Controller                      (condition checked every Thread-Group iteration)
  condition: currentTimeMillis() >= Long.parseLong(vars.get("tokenExpiry"))
  └─ login sampler
     └─ JSON Extractor: token = $.accessToken
     └─ Response Assertion: 200
     └─ JSR223 PostProcessor: vars.put("tokenExpiry", now + <ttl-in-ms>)
```

The subtle bug this fixes: the *init* (`tokenExpiry = "0"`) must live in its **own**
`Once Only Controller`, separate from the `While`. If the reset ran on every iteration
(e.g. as a bare sibling sampler with no Once-Only wrapper), the condition would be true on
*every* iteration and you'd log in every single time instead of once per TTL window. Once
seeded, the `While` loop is cheap on every iteration it doesn't fire (one boolean check),
and fires the body exactly once per TTL window: true → login → new expiry set → condition
re-checked → false → loop exits for this pass.

## 12. Naming and consistency review is part of the review

Small things that add up in a report you'll actually read at 2am during an incident:
- Name every sampler for what it does (`"HTTP Request"` tells you nothing in a 500-row
  report; `"login"` does).
- Keep `TransactionController.parent=true` consistent across parallel branches, or your
  "transactions" table has some entries nested and others not, for no principled reason.
- `Content-Type` at the plan level, `Authorization` scoped to only the samplers that need
  it (added *after* login, not at the top) — don't send a bearer token to endpoints that
  don't need one.

---

# Part 2 — Jenkins CI/CD pipeline

*Not started yet.* Plan when we get there (from the original roadmap):

1. Why perf tests in CI; what "pass/fail" means for a load test
2. Jenkinsfile basics: stages, agents, `sh`, artifacts, `post`
3. Getting JMeter onto the agent (Docker image vs installed tool vs Performance Plugin)
4. The pipeline: checkout → params → run non-GUI → publish HTML → **gate** on thresholds
   (error %, p95, throughput floor)
5. Parameterised builds & trending (Performance Plugin / Backend Listener →
   InfluxDB+Grafana)
6. Build `loadtest/Jenkinsfile` + a `run.sh` wrapper for this repo, using
   `ToyRentalMixed-60-tps.jmx` as the plan under CI

---

# Reference — the fixed plan's structure

```
Test Plan (UDVs: HOST, TOY_PORT, BOOKING_PORT, THREADS, CUST_PHONE, CUST_PASSWORD,
                 RAMP_UP, TEST_DURATION, TPS, THROUGHPUT_PER_HOUR)
├─ HTTP Request Defaults, HTTP Header Manager (Content-Type)
├─ CSV Data Set × 2 (browse_params.csv, toy_ids.csv — relative paths, ignoreFirstLine=true)
└─ Thread Group "Mixed Users" (${THREADS}, ${RAMP_UP}, ${TEST_DURATION}, scheduler=true)
   ├─ Once Only Controller → JSR223: init tokenExpiry=0
   ├─ While Controller (relogin every ~10 min) → login → extract token → set tokenExpiry
   ├─ Precise Throughput Timer (${THROUGHPUT_PER_HOUR}, single, total rate)
   ├─ Throughput Controller 80% → TC_Browse → BROWSE → assert 200 → think 100-300ms
   ├─ Throughput Controller 12% → TC_Detail → 2× GET → assert 200 each → think 50-150ms
   └─ Throughput Controller  8% → TC_Booking → availability → POST bookings → assert
      201|409 → extract bookingid/orderid → If(bookingid found) → webhook → think 50-150ms
```

Run:

```bash
jmeter -n -t loadtest/ToyRentalMixed-60-tps.jmx \
  -JTPS=60 -JTEST_DURATION=300 -JTHREADS=150 -JRAMP_UP=10 \
  -l loadtest/results/mixed60.jtl -e -o loadtest/results/mixed60-report
```
