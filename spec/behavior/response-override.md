# Behavior: Forced Response Override

> This document defines **pinning a route to a specific response** through the automation
> admin API's `PUT /__mockonomy/routes/{key}/response` endpoint. Only the engine behavior is
> defined here; the HTTP layer is documented in `docs/automation-api.md`.

## Purpose

A test may want a route to return **the same response for the whole test** ("in this
scenario `/users/:id` always returns `not-found`"). This is a more direct control than
writing rules or setting up states, and it is the most common need of a test setup
(`@Before`).

## Model

The runtime state of a running environment (`MockSession`) carries an optional **response
override** per route: `routeId → responseId`.

- The override lives **in memory** and is not written to the environment file (it is
  runtime data, like counters and the state).
- Resolving the address (`{key}` → id → response `key`/id) is **the admin layer's** job; the
  engine only keeps the resolved `routeId → responseId` pair. The engine does not know keys.

## Effect on selection

After a route matches, the override is checked **before** response selection:

1. If there is an override for that route and the response it points at still exists on the
   route, **that response is served directly.** Rules, the `SEQUENTIAL` order and the
   `whenState` filter are **skipped** — an override is the strongest selector.
2. If the response the override points at no longer exists on the route (e.g. it was
   deleted by a hot reload), the override is ignored and normal selection
   (rules/sequential/state) applies. The engine does not crash.
3. Without an override, behavior is unchanged.

### `setState` still applies

The override skips selection, but the served response's own `setState` is applied as
usual — the response behaves as defined. (A pinned response usually has no `setState`;
having one is a deliberate definition.)

### The request counter still increments

Because the route matched, the request counter increments regardless of the override. A
test that removes the override and returns to `SEQUENTIAL` mode will see that the counter
kept moving in the meantime — use `POST /__mockonomy/reset` for full control.

## Reset

Overrides are reset at the same moments as counters and the state (`MockSession.reset`):
server start, activating a different environment, and `POST /__mockonomy/reset`. That way
every test starts from its own known state.

## Match trace

`MatchTrace.forcedResponseId` in the `explain` output is the id of the pinned response if an
override was applied, otherwise `null`. "Why did I get this response?" is answered on the
override side too: not rules/sequential/state, but pinning by hand.

## Fixture coverage

An override is runtime state set up by an HTTP admin call; the single-shot golden fixture
format cannot express it. It is verified in the `:engine` unit tests
(`ResponseOverrideTest`) and end to end in the admin API integration tests
(`AdminApiIntegrationTest`).
