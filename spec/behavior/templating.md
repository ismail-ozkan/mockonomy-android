# Behavior: Template Engine

> Fixtures: `spec/fixtures/templating/`

This document defines how `{{ ... }}` expressions in the response body are expanded. It
runs after matching: which response is selected is the subject of `matching.md`; how the
selected body is produced is the subject of this document.

---

## Where it applies

Only to the **response body.** The status code, headers and latency do not go through the
template engine.

This is a deliberate boundary, not a technical obstacle: if a real use case for templates in
headers shows up, the scope can be extended. Not extending it today keeps the contract small
and easy to implement identically on every platform.

---

## Syntax

| Written as | Meaning |
|---|---|
| `{{ expression }}` | Evaluate and insert the result with **JSON-safe escaping** |
| `{{{ expression }}}` | Evaluate and insert the result **raw** |

Leading and trailing whitespace inside the expression is dropped: `{{ uuid }}` and
`{{uuid}}` are the same.

Text without `{{` is not processed at all and is returned as is.

### What escaping means

A value inserted with `{{ }}` is made safe to place *inside* a JSON string: `"` → `\"`,
`\` → `\\`, line break → `\n`, characters below 0x20 → `\uXXXX`. **No** quotes are added
around the value; quotes are the responsibility of whoever writes the template.

```
template   {"name": "{{query.name}}"}
query      name = Ada "Lovelace"
result     {"name": "Ada \"Lovelace\""}
```

Written with `{{{ }}}`, the same request would produce broken JSON. That is why the default
is escaped: the cheap side of a mistake.

Raw insertion is needed when the value itself is JSON:

```
template   {"user": {{{body.user}}}}
```

---

## Helpers

| Helper | Arguments | Value |
|---|---|---|
| `path.<name>` | – | A captured path parameter |
| `query.<name>` | – | The **first** value of a query parameter |
| `header.<name>` | – | The **first** value of a request header |
| `body.<a.b.c>` | – | A value from the request body, by dot notation |
| `uuid` | – | A UUID v4 |
| `now` | optional pattern | The time — ISO-8601 without a pattern |
| `timestamp` | – | Unix epoch, in seconds |
| `random.int` | min max | An integer in the range, **both ends included** |
| `random.string` | length | A `[A-Za-z0-9]` string |
| `random.bool` | – | `true` or `false` |

Helper names are **case-sensitive**: `{{UUID}}` is an unknown expression. Only in the
`header.<name>` lookup is the header name matched case-insensitively — that is how HTTP
defines headers.

### `body.<a.b.c>` — dot notation

The body is parsed as JSON and the keys are followed in order. Array indices are **not
supported**.

A string value is inserted without quotes; anything else in its JSON representation:

```
body    {"user":{"name":"Ada","tags":["a","b"],"age":36}}

{{body.user.name}}   → Ada
{{body.user.age}}    → 36
{{body.user.tags}}   → ["a","b"]
```

The same notation is used by the rule engine (`rules.md`). Making users learn two syntaxes
would be unfair.

### `now` and the time zone

Time is **always UTC**. Without a pattern the output is ISO-8601 with second precision:
`2026-03-14T15:09:26Z`.

With a pattern it is interpreted as a Unicode date pattern (`{{now "yyyy-MM-dd"}}`,
`{{now "HH:mm:ss"}}`). The device's local time zone is not used: the same configuration must
produce the same output on two devices.

---

## Missing values: empty string

If the helper is recognized but there is no data, the result is **an empty string.**

```
request   GET /users/42          (no query)
template  {"q": "{{query.q}}"}
result    {"q": ""}
```

The same holds for a missing header, a missing path parameter, a body that is not JSON and a
dot path that is not found. In these cases the engine **does not crash.**

---

## Unrecognized expressions are left as they are

In all of the following the `{{ ... }}` block passes to the output **completely untouched**:

| Situation | Example |
|---|---|
| Unknown helper | `{{nope}}`, `{{UUID}}` |
| Empty expression | `{{}}`, `{{ }}` |
| Wrong number of arguments | `{{uuid 5}}`, `{{random.int 1}}` |
| Non-numeric argument | `{{random.int a b}}` |
| Invalid range / length | `{{random.int 10 1}}`, `{{random.string -1}}` |
| A date pattern that cannot be parsed | `{{now "qqq"}}` |
| Unclosed block | `{{uuid` |
| Empty key | `{{query.}}` |

Why: the user's real data may contain `{{`. Turning something we do not recognize into an
empty string would **delete data**; crashing would make the mock server unusable. Both are
worse than leaving it as it is.

On the editor side these expressions are marked with a warning — the engine is the only
place that stays silent.

---

## Output is not scanned again

A value produced by a helper is **not processed again** as a template.

```
request   GET /?q={{uuid}}
template  {"q":"{{query.q}}"}
result    {"q":"{{uuid}}"}
```

Letting request data run templates is unwanted even on the user's own device: the mock's
output would come under the control of whoever sends the request.

---

## Determinism

`uuid`, `now`, `timestamp` and `random.*` vary by nature. The engine does not produce these
values directly; it reads them from the **clock** and **source of randomness** it is given.
In production these are the system clock and default randomness; in tests, a fixed instant
and a seeded generator.

This is what makes the engine testable at all, and adding it later would have meant going
through every test already written.

### Why random helpers have no golden file

Fixtures are platform-neutral ([spec/README.md](../README.md), rule 3). The **output
sequence** of a generator with a fixed seed, however, is platform-specific: Kotlin's
`Random(42)` and a Swift generator with the same seed produce different numbers. Such a
fixture would not prove that two engines behave differently — only that two languages use
different generators.

So there is **no** golden file for `uuid` and `random.*`; each platform verifies these
helpers by *shape* in its own unit tests (UUID v4 format, range bounds, length, alphabet).
Fixtures cover `now` and `timestamp` with a fixed clock — those are platform-independent.

---

## Cost

The template is parsed again on every request; there is no compiled template cache. The
mock server has a low load, and an unmeasured optimization would only make maintenance
harder. Bodies without `{{` are not scanned at all.

---

## Known limitations

- An argument such as `{{random.string 100000000}}` can exhaust memory. Since it is the
  user's own configuration on their own device, this is a user error rather than a security
  hole; no protection is implemented.
- There are no conditions, loops or nested helper calls inside templates. If a need
  appears, it comes with a separate decision.

---

## Out of scope

- Fake data helpers such as `{{faker.*}}`
- User-defined helpers
- Templates in headers or the status code

---

## Fixture coverage

Each row below corresponds to a file under `spec/fixtures/templating/`. A fixture file may
carry an optional `now` field: the instant the template engine sees. Without it
`2026-03-14T15:09:26Z` is used.

| Rule | Fixture |
|---|---|
| A path parameter is written into the body | `path-param` |
| Query and header reading, case-insensitive header name | `query-and-header` |
| Reading from the body with dot notation | `body-dot-notation` |
| JSON escaping by default | `json-escape-default` |
| `{{{ }}}` raw insertion | `raw-insertion` |
| A missing value becomes an empty string | `missing-value-empty` |
| An unrecognized expression is left as it is | `unknown-expression-verbatim` |
| Output is not scanned again | `no-recursion` |
| `now` and `timestamp` with a fixed clock | `fixed-clock` |
