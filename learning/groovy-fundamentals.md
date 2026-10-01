# Groovy Fundamentals — from Basics to JSR223 and Jenkins

A from-scratch Groovy tutorial: the language itself, then the two places it actually runs
in this project — JSR223 elements in `loadtest/*.jmx` and the `loadtest/Jenkinsfile`
pipeline. Every example past the "basics" section is either lifted verbatim from a real
`.jmx`/`Jenkinsfile` in this repo or written in the same style, so you can go find the real
thing afterward. Companion docs: [jmeter-fundamentals.md](jmeter-fundamentals.md) (the
JMeter element tree these scripts live inside) and
[jenkinsfile-fundamentals.md](jenkinsfile-fundamentals.md) (the declarative pipeline
skeleton Groovy is embedded in). Ends with an interview-questions section.

---

## 1. What Groovy actually is

Groovy is a JVM language — it compiles to the same bytecode as Java, runs on the same JVM,
and can call any Java class directly (`new FileOutputStream(...)`, `System.currentTimeMillis()`
both appear in this repo's own scripts). Think of it as **"Java with the ceremony stripped
out, plus a scripting layer bolted on."** Three things explain almost every Groovy-vs-Java
difference you'll hit:

1. **Optional typing.** `def x = 5` or `int x = 5` — both work, interchangeably, in the same
   method. `def` means "infer it" (really: "treat as `Object`" at compile time, resolved at
   runtime) — it's not "no type", it's "don't make me write the type."
2. **Everything is an expression, parens/semicolons are optional.** `if (x) println "yes"` is
   valid — no semicolon, no braces needed for a one-liner, parens on `println` are optional
   because it's really a method call and Groovy allows dropping parens for the last argument
   in many contexts.
3. **It's forgiving about null and truthiness** (`if (list)` is false for an empty list,
   null, 0, empty string — see Gotcha #1 below) and ships a large standard library of
   convenience methods grafted onto Java's own classes (`"a,b,c".split(",")`,
   `[1,2,3].collect { it * 2 }`, `file.eachLine { ... }`) that plain Java doesn't have.

This is **exactly why JSR223 (see §3) defaults to Groovy over plain Java scripting**: you
get the full Java standard library and JVM performance, but can write `vars.put("x", "1")`
instead of needing a class, a `public static void main`, and explicit imports for
one-off glue code.

---

## 2. Syntax basics

### Variables and types

```groovy
def name = "Priya"          // def = infer type (Object at compile time)
String phone = "9821012345" // explicit type still works, and is checked at runtime
int count = 5
def price = 449.0           // BigDecimal for decimal literals by default (not double!)
def isAvailable = true
def nothing = null
```

**Gotcha:** Groovy's default decimal literal type is `java.math.BigDecimal`, not Java's
`double`. This matters if you're doing money math (as `ReportService.java` does, with
`BigDecimal` throughout) — Groovy's default actually matches Java's intent here, unlike
most scripting languages.

### Strings

```groovy
def single = 'no interpolation here, ${name} prints literally'
def double_ = "interpolation works: ${name}, phone ${phone}"
def gstring = "simple var too: $name"          // $var works without braces if unambiguous
def multiline = """
  line one
  line two: ${1 + 1}
"""
```

Double-quoted strings with `${...}` or `$var` are **GStrings** (`groovy.lang.GString`), not
`java.lang.String` — they usually behave identically, but `someGString == someString` can
surprise you (they `.equals()` each other fine, but `GString.getClass()` isn't `String`).
Single-quoted strings are always plain `String`, never interpolated — use them for anything
with literal `$` or `{}` you don't want evaluated (e.g. a JSON template you're about to
substitute into by hand).

### Collections — lists and maps are literals, not constructor calls

```groovy
def toys = ["toy-001", "toy-002", "toy-003"]       // List<String> (really ArrayList)
def toy  = [id: "toy-001", name: "LEGO Technic", weeklyPrice: 449.00]  // Map (LinkedHashMap)

toys << "toy-004"                 // append (same as toys.add(...))
toys.each { println it }          // 'it' = the implicit single-argument name in a closure
toy.name                          // map property access — same as toy['name'] or toy.get('name')
def cheap = toys.findAll { t -> t.startsWith("toy-00") }
def prices = [449, 199, 299].collect { it * 4 }   // [1796, 796, 1196] — like .map() elsewhere
```

### Control flow

```groovy
if (status == "SUCCESS") {
    println "done"
} else if (status == "FAILED") {
    println "failed"
} else {
    println "still generating"
}

for (int i = 0; i < 3; i++) { println i }   // classic Java-style for still works
for (toy in toys) { println toy }           // Groovy-style for-in
toys.each { println it }                    // idiomatic Groovy — prefer this

// switch supports more than Java's: ranges, lists, classes, regex
def grade = 85
switch (grade) {
    case 90..100: println "A"; break
    case [80, 81, 82, 83, 84, 85, 86, 87, 88, 89]: println "B"; break
    default: println "C or below"
}
```

### Closures — the single most important Groovy feature to understand

A closure is an anonymous block of code you can pass around, like a lambda, but with its
own quirks:

```groovy
def square = { int x -> x * x }
square(5)                          // 25 — closures are callable directly

def greet = { name -> "Hello, $name" }
["Priya", "Rahul"].each { greet(it) }   // 'it' is the default parameter name if you omit one

def add = { a, b -> a + b }
add(2, 3)                          // 5, multiple params work the same way
```

Closures used in this repo's own JSR223 scripts are implicit — every time you see
`vars.get("x")` inside a JSR223 script body, that whole script body is itself effectively a
closure-like block with the bound variables (`vars`, `props`, `log`, ...) injected — see §3.

### Methods

```groovy
def addTax(double amount, double rate = 0.18) {   // default parameter value
    return amount * (1 + rate)
}
addTax(1000)          // 1180.0 — rate defaults
addTax(1000, 0.05)    // 1050.0

// 'return' is optional — last expression's value is returned implicitly
def square(x) { x * x }
```

### The Elvis operator and safe navigation — used constantly in real Groovy code

```groovy
def name = customer?.name              // safe navigation: null if customer is null, no NPE
def label = someValue ?: "default"     // Elvis: someValue if truthy, else "default"
```

`PYTEST_MARKERS = "${params.PYTEST_MARKERS ?: 'smoke or read_only'}"` in this repo's own
`loadtest/Jenkinsfile` (line 58) is exactly this pattern — see §4's walkthrough of that
exact line.

---

## 3. Groovy in JSR223 (JMeter)

### What JSR223 actually is

JSR 223 is the **Java spec for "a standard way to plug any scripting language into a JVM
application"** — `javax.script.ScriptEngine`. JMeter ships JSR223 Sampler / PreProcessor /
PostProcessor / Assertion / Timer / Listener elements, each of which runs **arbitrary code
in whatever scripting language you pick** (dropdown: `groovy`, `beanshell`, `javascript`,
...). **Always pick Groovy** — it's compiled (cached after first run, unlike the
interpreted BeanShell/Nashorn options) and is by far the fastest and most capable choice in
modern JMeter. Every script element in this repo's `.jmx` files sets
`<stringProp name="scriptLanguage">groovy</stringProp>`.

### The implicit bindings — what's "already in scope" inside a JSR223 script

You don't import or declare these — JMeter injects them into every JSR223 script's
namespace automatically:

| Binding | What it is | Example |
|---|---|---|
| `vars` | Thread-local variables (`JMeterVariables`) — same `${myVar}` you'd reference elsewhere in the plan | `vars.put("token", "abc")`, `vars.get("token")` |
| `props` | **JVM-global** properties (`JMeterProperties`) — unlike `vars`, these cross Thread Group boundaries | `props.put("adminToken", vars.get("adminToken"))` |
| `log` | SLF4J logger, writes to `jmeter.log` | `log.info("got token: " + token)` |
| `prev` | The previous sampler's result (`SampleResult`) — only in Post-Processors/Assertions | `prev.getResponseCode()`, `prev.getResponseDataAsString()` |
| `sampler` | The sampler config itself (Pre-Processors) | rarely needed |
| `ctx` | `JMeterContext` — thread num, current sampler, etc. | `ctx.getThreadNum()` |
| `SampleResult` | The *current* sampler's result — only inside a **JSR223 Sampler** itself, settable | `SampleResult.setSuccessful(false)` |
| `args`/`Parameters` | Whatever you typed in the element's own "Parameters" field, space-split | rare |

**`vars` vs `props` is the single most important distinction to get right.** `vars` is
per-thread — Thread A and Thread B each have their own copy, and a `setUp Thread Group`'s
`vars` never reach a regular `Thread Group`'s `vars` at all (separate Thread Group = separate
threads). `props` is one shared map for the whole JVM.

**Real example from this repo** (`loadtest/plans/month-end-report.jmx`) — exactly this
problem, solved exactly this way:
```groovy
// in setUp Thread Group, right after extracting the admin JWT into vars.adminToken:
props.put("adminToken", vars.get("adminToken"))
```
The `Trigger` and `tearDown - Poll` Thread Groups that need that token are **separate**
Thread Groups from `setUp` — their `vars` starts empty. They read it back with
`${__property(adminToken)}` (a JMeter function, not Groovy) in a Header Manager, since
`props` (unlike `vars`) is visible everywhere.

### JSR223 PreProcessor / PostProcessor / Sampler / Assertion — what each one is for

```groovy
// PreProcessor: runs BEFORE its sampler. Typical use: build a timestamp, seed a variable.
// loadtest/ToyRentalMixed-60-tps.jmx's "init tokenExpiry" (a JSR223 Sampler used Once-Only, same idea):
vars.put("tokenExpiry", "0")
```

```groovy
// PostProcessor: runs AFTER its sampler, has `prev` (the response) available.
// loadtest/ToyRentalMixed-60-tps.jmx's "set tokenExpiry +10min":
vars.put("tokenExpiry", (System.currentTimeMillis() + 600000).toString())
```

```groovy
// PostProcessor reading the previous response and failing the SAMPLE explicitly.
// loadtest/Regression_toyRental.jmx — also shows real file I/O from inside a script:
def responseCode = prev.getResponseCode()

if (responseCode != '200') {
    AssertionResult.setFailure(true)
    AssertionResult.setFailureMessage('Expected 200, but got: ' + responseCode)
} else {
    file = new FileOutputStream("toys_mrp" + ctx.getThreadNum() + ".csv", true)
    printStream = new PrintStream(file, true, "UTF-8")
    printStream.println(vars.get("toyId") + "," + vars.get("mrp"))
    printStream.close()
    file.close()
}
```
Note `new FileOutputStream(...)` — straight Java I/O, no Groovy-specific file API needed
(Groovy *does* have nicer file helpers like `new File(path).append(text)`, but plain Java
classes always work too, since Groovy compiles to the same bytecode).

```groovy
// JSR223 Sampler used as a pure assertion/gate, not to make an HTTP call at all.
// loadtest/plans/month-end-report.jmx's "Assert report reached SUCCESS":
def status = vars.get("reportStatus")
def rid = vars.get("reportId")
SampleResult.setResponseData("reportId=" + rid + " finalStatus=" + status, "UTF-8")
if (status == "SUCCESS") {
    SampleResult.setSuccessful(true)
    SampleResult.setResponseMessage("Report " + rid + " reached SUCCESS")
} else {
    SampleResult.setSuccessful(false)
    SampleResult.setResponseMessage("Report did not reach SUCCESS - last status=" + status)
}
```
This pattern — a JSR223 Sampler with no real network call, just logic that sets
`SampleResult.setSuccessful(...)` — is how you turn "a sequence of conditions across several
earlier samplers" into one clean pass/fail row in the results, instead of scattering
Response Assertions everywhere.

### `${__groovy(...)}` vs a JSR223 element — two different ways to run Groovy

A JSR223 element is a whole tree node with its own script body. `${__groovy(expression)}` is
a **JMeter function** — usable *inline*, anywhere a JMeter field accepts `${...}`, most
often in a **While Controller's condition** or an **If Controller's condition**. The
expression inside still runs as Groovy, with the same `vars`/`props`/`log` bindings
available.

**Real example and a real bug this repo already hit** — `loadtest/ToyRentalMixed-60-tps.jmx`:
```groovy
${__groovy(System.currentTimeMillis() >= Long.parseLong(vars.get("tokenExpiry")),)}
```
(trailing comma is JMeter's function-argument syntax, not Groovy — `__groovy` takes an
optional second arg for where to store the result, left empty here.)

This exists because an **earlier version used `${__jexl3(...)}` instead** — a different,
much more restricted expression language JMeter also supports — and silently broke:
JMeter 5.6.3's bundled Commons JEXL3 **fails silently on any static-method call**
(`System.currentTimeMillis()`, `Long.parseLong(...)`) — no exception, the call just resolves
to empty string, so the While Controller's condition never matched the literal `"false"` it
checks for, and the loop never exited (documented in full in `CLAUDE.md`'s Known Bugs table,
2026-09-05 entry). **Rule of thumb for this project: use `__groovy`, not `__jexl3`/`__jexl2`,
for any condition that calls a static Java method** — `vars.get(...)` alone works fine in
`__jexl3`, static calls don't.

### JSR223 performance note

Set **"Compiled" caching on** (the `cacheKey` field — every JSR223 element in this repo sets
`<stringProp name="cacheKey">true</stringProp>`, the GUI's checkbox for this). Groovy
scripts are compiled to bytecode once and cached by a hash of the script text; without
caching, JMeter would re-invoke the scripting engine fresh per sample, which is dramatically
slower under real load (thousands of iterations/sec) — one of the few places "a script
language is slow" is actually just "you forgot to turn on caching," not an inherent Groovy
cost.

---

## 4. Groovy in Jenkins

### Where Groovy actually appears in a Jenkinsfile

[jenkinsfile-fundamentals.md](jenkinsfile-fundamentals.md) covers the **declarative
pipeline skeleton** (`pipeline { agent / parameters / stages / post }`) in full — that
skeleton is a DSL *on top of* Groovy, deliberately restrictive so you mostly don't need to
know Groovy syntax at all to write one. This section covers the parts where you're
unmistakably writing real Groovy, inside that skeleton.

**1. String interpolation in any Groovy-aware field** — this is Groovy GString syntax
(§2), and it's everywhere in `loadtest/Jenkinsfile`:
```groovy
environment {
    RESULTS_DIR = "loadtest/results/${env.BUILD_NUMBER}"
    VENV        = "${env.WORKSPACE}/.venv-ci"
}
```
`${env.BUILD_NUMBER}` is plain Groovy string interpolation referencing Jenkins' built-in
`env` global variable — nothing Jenkins-specific about the `${...}` syntax itself.

**2. The Elvis operator, for a parameter-with-fallback** — `loadtest/Jenkinsfile` line 58:
```groovy
PYTEST_MARKERS = "${params.PYTEST_MARKERS ?: 'smoke or read_only'}"
```
The comment right above it in the real file explains *why*: `params.X` isn't auto-exported
to `sh` steps, and on the very first build after adding a new parameter, `params.X` is
`null` rather than its declared default — the `?:` fallback covers both "not set yet" and
"declared default" in one expression. This is a real, working example of the Elvis operator
earning its keep, not a toy case.

**3. `when { expression { ... } }` — a Groovy boolean expression gating a whole stage:**
```groovy
stage('API functional tests (pytest gate)') {
    when { expression { params.RUN_API_TESTS } }
    steps { ... }
}
```
`params.RUN_API_TESTS` is a Groovy `Boolean` (from a `booleanParam`); the `expression {}`
block can be *any* Groovy expression that evaluates truthy/falsy — same truthiness rules as
§1's Gotcha #1.

**4. `script { ... }` — the escape hatch into full Scripted Groovy from inside Declarative:**
Not used in this repo's current `Jenkinsfile`, but you'll meet it immediately once a
declarative pipeline needs something the fixed skeleton can't express (a loop over a
dynamic list, a try/catch around one step, a local variable reused across steps):
```groovy
steps {
    script {
        def services = ['toy-service', 'booking-service', 'api-gateway']
        for (svc in services) {
            sh "kubectl rollout status deployment/${svc} -n toy-rental --timeout=180s"
        }
    }
}
```
Inside `script { }`, you're in full **Scripted Pipeline** — arbitrary Groovy, loops,
try/catch, local `def` variables that survive across `sh` calls in the same block. This is
exactly the difference between Declarative ("fill in a form") and Scripted ("write a
program") pipeline styles — and `script {}` is how Declarative lets you drop into Scripted
for just the one part that needs it.

**5. Shell-variable vs Groovy-variable interpolation — the mistake to never make:**
```groovy
sh "echo ${params.THREADS}"      // Groovy interpolates THIS before the shell ever sees it
sh 'echo $THREADS'               // the shell interpolates an env var named THREADS, NOT Groovy
```
`"double-quoted"` Groovy strings interpolate `${...}` at **pipeline-script** time, before the
string is even handed to `sh`. `'single-quoted'` Groovy strings don't interpolate at all —
anything `$`-prefixed inside them is passed through literally for the **shell** to interpret
instead. `loadtest/Jenkinsfile`'s big `sh ''' ... '''` block (the pytest gate) deliberately
uses a single-quoted (non-interpolating) triple-quoted string with `set -eu` and real shell
variables like `$pair`/`$name`/`$url` — mixing Groovy and shell interpolation in the same
block is a very common source of "why did this silently use the wrong value" bugs.

### Jenkins-provided Groovy globals (parallel to JSR223's `vars`/`props`)

| Binding | What it is |
|---|---|
| `env` | Build environment variables (`env.BUILD_NUMBER`, `env.WORKSPACE`, ...) |
| `params` | The `parameters {}` block's values for this build |
| `currentBuild` | The running build itself (`currentBuild.result`, `currentBuild.number`) |
| `sh(...)` / `bat(...)` | Run a shell/batch command — `sh(script: '...', returnStdout: true)` to capture output into a Groovy variable |

---

## 5. Common gotchas (across both contexts)

1. **Groovy truthiness is not Java's.** `if (list)` is `false` for `null`, an empty
   collection, empty string, or `0` — but a non-empty collection, non-zero number, or
   non-empty string is `true`. This is used deliberately all over Groovy code (`if
   (someList) { ... }` instead of `if (someList != null && !someList.isEmpty())`), but it
   means `if (0)` behaves opposite to how a C-family programmer's muscle memory expects
   `if (someNonNullObject)` to behave at all — there is no implicit "truthy object", only
   these specific falsy cases.
2. **`vars` doesn't cross Thread Groups; `props` does** (§3) — the #1 JSR223 state bug in
   this repo's own history.
3. **`__jexl3`/`__jexl2` silently fail on static-method calls; `__groovy` doesn't** (§3) —
   confirmed, documented, already cost a full 15-minute wasted JMeter run once in this
   project (`CLAUDE.md`, 2026-09-05).
4. **Groovy script caching must be explicitly enabled per JSR223 element** (the `cacheKey`
   checkbox) — every script element in this repo already has it on; don't uncheck it.
5. **Single- vs double-quoted strings pick Groovy-time vs shell-time interpolation** inside
   a Jenkinsfile's `sh` step (§4.5) — this is the single most common "Jenkinsfile variable
   didn't expand the way I expected" bug.
6. **`def` isn't dynamic typing, it's type inference** — `def x = "a"; x = 5` is legal (no
   declared type to violate), but this is different from Python-style dynamic typing: a
   variable declared with an *explicit* type (`String x = "a"`) is checked and `x = 5` would
   fail.

---

## 6. Interview questions

**Language fundamentals**

- What's the actual difference between `def` and declaring an explicit type in Groovy? Is
  Groovy statically or dynamically typed?
- What is a GString, and how is it different from a `java.lang.String`? When would that
  difference actually bite you?
- Explain Groovy truthiness. What values are falsy besides `false` and `null`?
- What's the Elvis operator (`?:`) shorthand for? What's the difference between `?:` and
  `?.`?
- What is a closure in Groovy? How is `it` resolved inside one?
- Why does `1.0` in Groovy give you a `BigDecimal`, not a `double`? Where does that matter
  in practice?
- Groovy compiles to JVM bytecode — what does that actually buy you over an interpreted
  scripting language choice in the same slot (e.g. JavaScript/Nashorn)?

**JSR223 / JMeter**

- What's the difference between `vars` and `props` in a JSR223 script, and when would using
  the wrong one cause a bug that's hard to reproduce?
- Why is Groovy almost always the right scripting-language choice for a JSR223 element over
  BeanShell or Javascript, in modern JMeter?
- What does the JSR223 element's "Compiled" / cache-key checkbox actually do, and what's
  the performance consequence of leaving it off under real load?
- In a JSR223 PostProcessor, what is `prev`, and how would you fail the current sample from
  inside the script?
- What's the actual difference between a JSR223 Sampler and a JSR223 PostProcessor — when
  do you reach for one over the other?
- Why does `${__jexl3(System.currentTimeMillis() >= ...)}` fail silently instead of
  throwing, and what's the fix? (A real, previously-encountered bug — see §3.)
- How would you share a value computed in a `setUp Thread Group` with a regular Thread
  Group running afterward?

**Jenkins / Jenkinsfile**

- What's the difference between Declarative and Scripted Jenkins pipelines? When do you
  need `script { }` inside a Declarative pipeline?
- In `sh "echo ${params.X}"` vs `sh 'echo $X'`, which interpolation happens in Groovy and
  which happens in the shell? Why does that distinction matter for a secret or a value with
  special characters?
- Why might `params.SOME_FLAG` be `null` rather than its declared default on the very first
  build after adding that parameter, and how do you defensively code around it?
- What Groovy globals does Jenkins inject into every pipeline script (beyond what the
  declarative skeleton gives you), and what's each one for?
- How would you loop over a dynamic list of services and run a shell command against each
  one inside a Declarative pipeline?

---

See also: [jmeter-fundamentals.md](jmeter-fundamentals.md) for the JMeter element tree
these JSR223 scripts live inside, [jenkinsfile-fundamentals.md](jenkinsfile-fundamentals.md)
for the full declarative pipeline skeleton, and `CLAUDE.md`'s Known Bugs table (2026-09-05
entry) for the `__jexl3` incident referenced in §3 in full.
