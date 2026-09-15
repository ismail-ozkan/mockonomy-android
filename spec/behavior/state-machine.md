# Behavior: Named State Machine

> Schema: [spec/schema/environment-v2.md](../schema/environment-v2.md)
>
> This document defines the engine behavior of the `states` / `initialState` (environment)
> and `whenState` / `setState` (response) fields.

## Model

An environment has an **active state**. It starts with `initialState`; responses are
filtered by this state and can change it. A whole real session flow can be mocked with a
single environment file:

```
Initial: logged_out
POST /auth/login  → "success" (whenState: logged_out, setState: logged_in) → 200, state=logged_in
GET  /profile     → "unauthorized" (whenState: logged_out) → skipped
                  → "profile"      (whenState: logged_in)  → served
```

## The active state is runtime data

- The active state lives **in memory** and is not written to the environment file. Only
  `states` and `initialState` are in the file.
- Reset points are **the same** as for counters (`sequential-response.md`): server start,
  environment activation, and `POST /__mockonomy/reset`. On every reset the active state
  returns to `initialState`. Test isolation depends on this.
- A configuration edit (hot reload) of the same environment does not reset the state.

## `whenState` — an implicit pre-rule

- `whenState` is an implicit rule applied **before** the regular rule evaluation. If
  `whenState` is empty, the response is eligible in every state.
- A response that does not match the active state is **removed from the candidate list
  entirely**: it takes part in neither rule-based selection nor the fallback (`isDefault` /
  first response). If it has rules, they are **never evaluated**.
- If no eligible response (one matching the state) remains, the route produces no response
  → the engine returns "no match". With the proxy **off** the result is the synthetic 404.
  With the proxy **on** the request is forwarded to the real target (the route matched, but
  there is no eligible response). Users who do not want that should keep a catch-all
  response (empty `whenState`) that is eligible in every state.

Selection among the eligible candidates follows the rules in [matching.md §5](matching.md):
first the first response whose rules pass, then `isDefault`, then the first eligible
response.

## `setState` — a transition after serving

- The served response's `setState` is applied **after the response is selected**; the same
  request's `whenState` filtering was still done against the **old** state.
- If `setState` is empty, the state does not change.

## Relation to `SEQUENTIAL`

In `responseMode: SEQUENTIAL` mode `whenState` is **ignored**, just like rules: responses are
returned in exact list order. `setState`, however, is independent of the mode — the served
response's `setState` is applied in both modes. Users who need state-dependent sequential
behavior use `RULES` + `whenState`.

## Concurrency

State machines are meant for sequential QA flows. Atomic state transitions across concurrent
requests are **not a goal** (keep it simple). Counter access is locked; the active state is a
`@Volatile` field.

## Match trace

`MatchTrace` in the `explain` output explains the state decision — a requirement for this
feature to be usable:

- `activeState` — the state that was active when the request arrived
- `stateSkips` — responses skipped because `whenState` did not match
  (`responseId`, `requiredState`, `activeState`)
- `appliedSetState` — the new state if the served response applied a transition

```
state at request time: logged_in
✗ response "unauthorized"  → skipped (whenState=logged_out, active=logged_in)
✓ response "profile"       → served
  → setState: (none)
```

## Scope

Kept simple: conditional transitions, nested states and transition side effects are **out
of scope**. They can be added if there is demand.

## Fixture coverage

`spec/fixtures/state-machine/session-flow.json` runs a full session flow as a multi-step
fixture: `logged_out → login → logged_in → profile(200) → logout → logged_out →
profile(401)`. The returned response and the next active state are verified at every step.
The steps share the same session (state and counters carry over from step to step).
Response selection details are also unit tested in `StateMachineTest`.
