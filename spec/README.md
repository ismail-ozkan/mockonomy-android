# spec/ — Cross-Platform Behavior Contract

This folder defines **what** the Mockonomy mock engine must do. It contains no
platform-specific code.

## Why it exists

The engine is meant to be implemented more than once — in Kotlin for Android today, and
in Swift for iOS later. This folder is the only guarantee that every implementation gives
the same answer to the same input.

If code comments, tests and design decisions are scattered across platforms, the engines
silently drift apart. The files here are the single source of truth.

## Layout

```
spec/
  schema/       Configuration file format (versioned)
  behavior/     The engine's behavior rules — a specification for humans
  fixtures/     Golden test data — run by machines
```

`behavior/` answers *what should happen*; `fixtures/` **proves** it. They only make sense
together: a fixture without a specification cannot be understood, and a specification
without a fixture has not been verified.

## Working rules

1. **Spec first, then code.** If a behavior is unclear, clarify the file here first; then
   make every platform follow it.
2. **Every behavior rule has at least one fixture.** Writing a rule without a fixture is
   the same as not writing the rule.
3. **Fixtures stay platform-neutral.** No Kotlin- or Swift-specific concept (class names,
   API shapes, null semantics) goes into a fixture. Fixtures describe *observable output*:
   which response is served for a given environment and request.
4. **Tag by phase.** Every fixture carries a `phase` field. A test runner skips fixtures
   for engine capabilities it does not implement yet, and reports the skips.
5. **A breaking change means a new schema version.** Files under `schema/` are versioned;
   the file for an older version is never deleted.

## Test runners

| Platform | Location |
|---|---|
| Kotlin / JVM | `:engine` test source set (`SpecFixtureRunnerTest`) |
| Swift | Planned, together with an iOS implementation |

Runners **read the files under `fixtures/`** — the content is never embedded in code.
Adding a fixture should mean adding a test without changing code.

## Contents

| File | Covers |
|---|---|
| `schema/environment-v1.md` | Configuration file format, version 1 |
| `schema/environment-v2.md` | Version 2: automation keys, state machine, sequential responses |
| `behavior/matching.md` | Request matching, specificity, response selection, the 404 fallback |
| `behavior/templating.md` | `{{ ... }}` expressions in response bodies |
| `behavior/rules.md` | Response rules and the match trace |
| `behavior/sequential-response.md` | Sequential responses and the request counter |
| `behavior/state-machine.md` | Named states: `whenState` / `setState` |
| `behavior/response-override.md` | Forced response overrides from the automation API |
| `behavior/path-parameterization.md` | Suggesting `:param` paths when creating mocks from logs |
| `fixtures/` | Golden files for matching, rules, templating and the state machine |

## Language

Everything in this folder is written in English, and everything on the wire (field names,
enum values, error messages, JSON content) is English too.
