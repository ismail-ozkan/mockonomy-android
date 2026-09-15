# Behavior: Request Matching

> Fixtures: `spec/fixtures/matching/`

This document defines which response answers an incoming HTTP request. The steps are
ordered; once a step reaches a decision, later steps are not consulted.

---

## Algorithm

```
serve(environment, request) → response

1. Prefix stripping
2. Finding candidate routes
3. No candidates: fallback
4. Route selection among the candidates
5. Response selection within the route
6. Latency calculation
7. Header merging
8. Body resolution
```

---

## 1. Prefix stripping

If `environment.prefix` is not empty, the request path must start with that prefix. If it
does not, **there is no match** (go to step 3).

If it does, the prefix is removed and the rest is used as the *effective path*.

```
prefix = "api/v1"
request  /api/v1/users/42   → effective path  /users/42
request  /users/42          → no match
```

Prefix comparison is **segment-based**: with `prefix = "api"`, `/apixyz` does not match.

---

## 2. Finding candidate routes

A route is a candidate if and only if:

1. `route.enabled` is `true`
2. `route.method` equals the request method
3. the `route.path` pattern matches the effective path

### Method comparison
The request method is normalized to upper case, then compared exactly. `get` and `GET`
are the same.

### Path matching
1. Both the pattern and the effective path are split on `/`; leading and trailing empty
   segments are dropped. This **makes a trailing slash meaningless**: `/users` and
   `/users/` are the same.
2. If the segment counts differ there is no match. Wildcards are not supported.
3. Segments are compared one by one:
   - A pattern segment starting with `:` is a **path parameter**; it matches any
     non-empty segment and its value is captured
   - Otherwise the comparison is **literal** and **case-sensitive** (paths are
     case-sensitive per RFC 3986)
4. Every segment is percent-decoded before comparison. Captured path parameter values are
   carried in decoded form too.

```
pattern  /users/:id     path  /users/42              → matches, id = "42"
pattern  /users/:id     path  /users/42/orders       → no match (segment count)
pattern  /users/:id     path  /users/                → no match (empty segment)
pattern  /Search        path  /search                → no match (case sensitivity)
pattern  /search/:q     path  /search/hello%20world  → matches, q = "hello world"
```

The query string is **not part of** path matching.

---

## 3. No candidates: fallback

| Situation | Result |
|---|---|
| The proxy is on | The request is forwarded to the proxy target |
| Otherwise | The default 404 |

### Default 404 response

```
statusCode: 404
headers:    Content-Type: application/json
latencyMs:  0
body:
```
```json
{"error":"no_match","message":"No route matched GET /unknown"}
```

The `message` field has the form `"No route matched " + METHOD + " " + path`. `path` is
the original request path **before** prefix stripping.

The default 404 is a synthetic response, not a route response. Therefore the
environment's `defaultLatencyMs` and `headers` are **not applied** to it — it only carries
the `Content-Type` header above.

---

## 4. Route selection: specificity

If there are several candidates, **the more specific one** wins.

Specificity is decided by comparing segments from left to right: at the first position
where two patterns differ, a literal segment beats a path parameter.

```
/users/me      ← wins
/users/:id
```

```
/a/:x/c        ← wins (segments 1 and 2 are the same kind in both patterns;
/a/:x/:y          the first difference is segment 3, where the literal wins)
```

If the patterns are equally specific, **the first route in the `routes` list wins.** List
order is therefore meaningful, and the UI should make that clear to the user.

---

## 5. Response selection

One element is picked from the selected route's `responses` list.

1. Responses **with rules** are evaluated in list order; the **first** one whose rules
   pass is selected *(see `rules.md`)*
2. If none passes, the response with `isDefault: true`
3. If there is none, the **first** response in the list

A response with an empty `rules` list does **not take part** in step 1. A response without
rules is only served through step 2 or 3.

The `isDefault` response is served in step 2 even if its own rules do not pass: the
default's job is to be the fallback.

#### Why a response without rules does not count as "always passing"

An earlier version of this document said the opposite: a response without rules always
passes, which makes every response after it unreachable. That broke a behavior users
already relied on.

The "Switch response" screen gives `isDefault` to the selected response and tells the user
"this is the response this route serves". A user who switched to the second response in the
list would start getting the first one again as soon as rules existed — without writing a
single rule. Adding a rule engine must not change behavior for people who write no rules.

No capability is lost: "a catch-all response in the middle of the list" is exactly what
`isDefault` means.

### Other modes
`responseMode: SEQUENTIAL` and the `whenState` filter change this step; see
`sequential-response.md` and `state-machine.md`. A forced override from the automation API
bypasses it entirely; see `response-override.md`.

---

## 6. Latency calculation

```
total latency = environment.defaultLatencyMs + response.latencyMs
```

Latency is applied **before** the response is written. It is not applied to the default
404 (see step 3).

---

## 7. Header merging

1. `environment.headers` are applied
2. `response.headers` override them — if the same `key` exists, the response wins

The comparison is **case-insensitive** (HTTP header names are case-insensitive).

---

## 8. Body resolution

| `bodyType` | Source |
|---|---|
| `INLINE` | `response.body` |
| `FILE` | The content of the file referenced by `bodyFile` |

The body then goes through the template engine (`templating.md`).

---

## Out of scope

The following are **not** part of this specification:

- Wildcard paths (`/files/*`)
- Regex-based path patterns
- Content negotiation (picking a response by the `Accept` header)
- HTTP/2, WebSocket, SSE

---

## Fixture coverage

Each row below corresponds to a file under `spec/fixtures/matching/`. The runner skips
fixtures whose `meta.phase` is greater than the implemented phase and reports the skips.

| Rule | Fixture | Phase |
|---|---|---|
| Exact path match | `exact-path` | 0 |
| Method mismatch | `method-mismatch` | 0 |
| Method is case-insensitive | `method-case-insensitive` | 0 |
| Path is case-sensitive | `path-case-sensitive` | 0 |
| Prefix stripping | `prefix` | 0 |
| Prefix segment boundary (`api` ≠ `/apixyz`) | `prefix-segment-boundary` | 0 |
| Trailing slash is meaningless | `trailing-slash` | 0 |
| Segment count mismatch | `segment-count-mismatch` | 0 |
| Disabled route is skipped | `disabled-route` | 0 |
| `isDefault` selection | `default-response-selected` | 0 |
| First response when no default | `first-response-when-no-default` | 0 |
| Header merging and override | `header-override` | 0 |
| Latency sum | `latency-sum` | 0 |
| The 404 is not affected by the environment | `not-found-ignores-environment` | 0 |
| Path parameter capture | `path-param` | 1 |
| Specificity precedence | `static-wins-over-param` | 1 |
| Specificity — first differing position (`/a/:x/c` > `/a/:x/:y`) | `specificity-beyond-first-segment` | 1 |
| Percent-decoding | `url-encoding` | 1 |

### Relation to the Kotlin tests

`RequestMatcherTest` in `:engine` also tests most of these behaviors. The duplication is
deliberate and serves two different purposes:

- **Kotlin tests** are the fast inner loop; their failure messages are more readable and
  they cover platform-specific edge cases (e.g. a method outside the schema).
- **Fixtures** are the cross-platform contract; they are what another implementation (such
  as a future iOS engine) runs as is.

When a behavior changes, **this document** is updated first, then the fixture, then each
platform's implementation.
