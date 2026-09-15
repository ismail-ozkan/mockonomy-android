# Behavior: Rule Engine

> Fixtures: `spec/fixtures/rules/`

Once a route is selected, rules decide which response is served. The selection algorithm
itself is in `matching.md` §5; this document defines when **a single rule** passes and how
rules are combined.

---

## Anatomy of a rule

```
target    QUERY | HEADER | BODY | PATH_PARAM | REQUEST_NUMBER    what to look at
key       parameter/header name or body path
operator  EQUALS | CONTAINS | REGEX | EXISTS                     the comparison
value     the text to compare with (ignored for EXISTS)
invert    inverts the result
```

Evaluation has two steps: first the **values read** from the target are found, then the
operator is applied.

---

## 1. Reading values

| Target | From | If not found |
|---|---|---|
| `QUERY` | **All** values of the query parameter named `key` | missing |
| `HEADER` | **All** values of the header named `key`, name matched case-insensitively | missing |
| `PATH_PARAM` | The `key` parameter captured by the matched route | missing |
| `BODY` | The whole raw body if `key` is empty, otherwise the value at the dot-notation path | missing |

**"Missing" is not the same as "empty".** In a request that sends `?q=`, `q` **exists** and
its value is the empty string; when it is not sent at all, it is missing. `EXISTS` tells the
two apart.

### Multiple values: one is enough

In a `?tag=a&tag=b` request `tag` carries two values. The rule passes if **any** of the
values passes the operator.

This deliberately differs from the template engine: `{{query.tag}}` has to produce a single
text, so it takes the **first** value, while the rule engine answers a yes/no question and
can look at all of them. The same data is asked two different questions.

### `BODY` and dot notation

`key` is a body path: `user.address.city`. It is the **same** notation as `{{body.a.b.c}}`
in the template engine (`templating.md`) — making users learn two syntaxes would be unfair.
Array indices are not supported.

- If the body is not JSON, dot notation silently does not match; it **does not crash**
- If `key` is empty, the raw body text is used — this is what `CONTAINS` needs for
  plain-text bodies
- A string value is read without quotes; anything else in its JSON representation

---

## 2. Operators

| Operator | Passes if |
|---|---|
| `EQUALS` | The value read is **exactly** `value` (case-sensitive) |
| `CONTAINS` | The value read contains `value` as a substring |
| `REGEX` | The `value` pattern matches **anywhere** in the value read |
| `EXISTS` | The target has a value (its content is not inspected, `value` is ignored) |

When no value is found, `EQUALS`, `CONTAINS`, `REGEX` and `EXISTS` all **fail**. Something
that does not exist is not even equal to the empty string.

### Why `REGEX` matches partially

A full match can be requested by writing `^` and `$`; the opposite is not possible. Partial
matching leaves both options to the user.

### Invalid regex

A pattern that does not compile is **a user error**: the rule returns `false`, the engine
does not crash, and the editor shows a warning.

In this single case `invert` is **not applied.** Otherwise a broken pattern with
`invert: true` would silently become a rule that *passes* — a typo would invisibly flip the
rule. Failing the rule is the cheap side of the mistake.

---

## 3. `invert`

Inverts the rule's result. `EXISTS` + `invert` means "this field is **missing**"; when no
value is found, `EQUALS` + `invert` passes.

The only exception is the invalid regex case above.

---

## 4. Combining rules

`rulesOperator`:

| Value | The response is eligible if |
|---|---|
| `AND` | **All** rules pass |
| `OR` | **At least one** rule passes |

If the rule list is empty, the response does **not take part** in rule-based selection; it
can only be served as the fallback (`matching.md` §5).

---

## 5. The match trace

The engine can optionally return the reasoning behind its decision:

```
✓ Matched route:     GET /users/:id
  Skipped routes:    GET /users/me   (path did not match)
                     GET /users      (segment count differs)

✓ Served:            "404 – not found"
  Evaluated rules:
    ✓ query.simulate  EQUALS  "missing"   → passed (read: "missing")
    ✗ header.X-Debug  EXISTS              → failed (no value)
  Operator: OR → passed
```

The most common question in a mock tool is "why did I get this response?". Answering it
inside the product (the log detail screen) beats answering it in documentation.

The trace is **optional** and producing it has a cost: it is only collected when requested.
When it is not requested the engine behaves identically — the trace never changes the
decision, it only records it.

What the trace carries:

| Field | Content |
|---|---|
| Effective path | The path after the prefix was stripped; missing if the prefix did not match |
| Attempted routes | For each, the skip reason or that it matched |
| Matched route | The id of the selected route |
| Evaluated rules | The rule, the value read, the result, and a note if any |
| Served response | The id of the selected response |
| Fallback used | Whether the response was selected by rules or as the default |

Skip reasons: disabled, method mismatch, path mismatch, a less specific candidate.

---

## Known limitations

- A regex with catastrophic backtracking can lock up the mock server. Since it is the user's
  own configuration on their own device, this is a user error rather than a security hole;
  no protection is implemented.
- Regex syntax is that of the platform's own engine. Simple patterns behave the same in
  Kotlin and Swift; advanced features (lookbehind, named group syntax) may differ. Fixtures
  deliberately use simple patterns.

---

## The `REQUEST_NUMBER` target

Reads the number of requests made to this route (1-based); `key` is ignored. The value is
the counter itself, and like other targets it is compared with `EQUALS` / `CONTAINS` /
`REGEX` and can be combined with other rules through `AND` / `OR`. The target **always has a
value** (the request's number), so `EXISTS` always passes.

The counter and its reset points are defined in `spec/behavior/sequential-response.md` — it
is fed by **the same** counter as `SEQUENTIAL` selection. `REQUEST_NUMBER` is the rule-based,
and therefore more flexible, way to get sequential behavior: "this on the third request,
that on the others".

## Out of scope

- The `COOKIE` target. When the engine meets it, it **does not stay silent but fails
  loudly**: silently ignoring it would send the user hunting for a bug in the wrong place
  for hours.
- Parentheses / nested logic between rules — only a flat `AND` / `OR`
- Template expressions inside rules

---

## Fixture coverage

Each row below corresponds to a file under `spec/fixtures/rules/`.

| Rule | Fixture |
|---|---|
| `QUERY` + `EQUALS` | `query-equals` |
| `HEADER` + `EXISTS`, case-insensitive name | `header-exists` |
| `BODY` + dot notation + `EQUALS` | `body-dot-equals` |
| `BODY` + empty key + `CONTAINS` (plain text) | `body-raw-contains` |
| `PATH_PARAM` + `REGEX` | `path-param-regex` |
| `invert` flips the result | `invert` |
| `AND`: one failing rule drops the response | `and-operator` |
| `OR`: one passing rule selects the response | `or-operator` |
| The default is returned when no rule passes | `fallback-to-default` |
| A response without rules does not take part in rule-based selection | `unruled-response-not-candidate` |
| An invalid regex fails the rule and `invert` is not applied | `invalid-regex` |
| One of multiple values is enough | `multi-value-any-match` |
| A missing value passes no operator | `absent-value-fails` |
