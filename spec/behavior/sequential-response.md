# Behavior: Sequential Responses and the Request Counter

> Schema: [spec/schema/environment-v2.md](../schema/environment-v2.md)
>
> This document defines `responseMode: SEQUENTIAL` selection and the **per-route request
> counter** that feeds the `REQUEST_NUMBER` rule target.

## Request counter

- Every route has a **request counter**: each request that matches the route increments it
  by one. It is 1-based — the first request to a route is number `1`.
- The counter lives **in memory** and is not written to the environment file.
- The counter is incremented once, right after the route matches and **before** response
  selection. The same number feeds both the `SEQUENTIAL` index and the `REQUEST_NUMBER`
  rule; it is never incremented twice for one request.
- A request that matches nothing (no route matched) increments no counter.

### Reset points

All counters are reset:

1. **When the server starts**
2. **When an environment is activated** — switching to a different environment. (A
   configuration edit / hot reload of the same environment does not reset them, so a
   running test sequence is not disturbed.)
3. **On `POST /__mockonomy/reset`**

Test isolation depends on these resets: every test must be able to start from its own
known state.

## `responseMode: SEQUENTIAL`

The route's responses are returned **in list order**, based on the counter. The Nth request
gets the Nth response.

- **Rules are ignored.** In `SEQUENTIAL` mode response rules are not evaluated, and
  `isDefault` plays no role either. Users who need rule-based sequential behavior use the
  `REQUEST_NUMBER` target — it is more flexible anyway.
- **End-of-list behavior** is chosen per route with `sequentialBehavior`:
  - `STAY_ON_LAST` *(default)* — stays on the last response. The motivation is polling:
    `202 pending` → `202 pending` → `200 done` → then `200 done` forever.
  - `CYCLE` — starts over from the beginning.

Index calculation (N = request number, S = number of responses):

| Behavior | Index |
|---|---|
| `STAY_ON_LAST` | `min(N − 1, S − 1)` |
| `CYCLE` | `(N − 1) mod S` |

### Example

A route with 3 responses (`201`, `202`, `200`):

| Request | `STAY_ON_LAST` | `CYCLE` |
|---|---|---|
| 1 | 201 | 201 |
| 2 | 202 | 202 |
| 3 | 200 | 200 |
| 4 | 200 | 201 |
| 5 | 200 | 202 |

## The `REQUEST_NUMBER` rule target

Used in `RULES` mode (details: [rules.md](rules.md)). It reads the counter as a string;
`EQUALS "3"` matches on the third request, while `CONTAINS` / `REGEX` work as general string
matching. That way scenarios `SEQUENTIAL` cannot express, such as "a different response only
on the 3rd request, the same one otherwise", can be built with the rule engine.

## `RANDOM`

Not supported yet; when a `RANDOM` route is selected the engine fails loudly
(`UnsupportedMockFeatureException`).

## Match trace

`MatchTrace` in the `explain` output:
- `requestNumber` — the number of requests made to this route (`null` if no route matched)
- `sequentialIndex` — the list index of the selected response in `SEQUENTIAL` mode
- A `REQUEST_NUMBER` rule appears in the trace like any other rule evaluation; `readValue`
  is the counter's current value.

## Fixture coverage

Sequential behavior is observed across a **sequence** of requests. It is verified in the
`:engine` unit tests (`SequentialResponseTest`): STAY_ON_LAST `1,2,3,3,3`, CYCLE `1,2,3,1,2`,
`REQUEST_NUMBER EQUALS 3`, reset, and independent per-route counters. The multi-step
fixture format (`steps`, see `spec/fixtures/README.md`) can express these sequences as well;
it is currently used for the state machine.
