# Environment Schema — v2

> `schemaVersion: 2`
>
> This file defines the **additions** on top of v1 ([environment-v1.md](environment-v1.md)).
> For rules, fields and validations that did not change, v1 applies; only the
> **differences** are listed here.

## General

- v2 is a **strict superset** of v1: no v1 field was removed or changed meaning. All new
  fields are **optional and have defaults**.
- So a v1 file is a valid v2 file even without the new fields; migration only bumps
  `schemaVersion`, without data loss (see below).
- Forward compatibility is unchanged: unknown fields are ignored (`ignoreUnknownKeys`).

## New fields

### Environment (root object) — additions

| Field | Type | Required | Default | Description |
|---|---|---|---|---|
| `key` | string\|null | ❌ | `null` | A readable address for the automation API. Falls back to `id` when empty. |
| `states` | string[] | ❌ | `[]` | State names of the named state machine. The state feature is off when empty. |
| `initialState` | string\|null | ❌ | `null` | The state that is active when the server starts. Meaningless when `states` is empty. |

`schemaVersion` is now `2`.

### Route — additions

| Field | Type | Required | Default | Description |
|---|---|---|---|---|
| `key` | string\|null | ❌ | `null` | Automation address. Falls back to `id` when empty. |
| `sequentialBehavior` | SequentialBehavior | ❌ | `STAY_ON_LAST` | End-of-list behavior when `responseMode = SEQUENTIAL`. |

The engine **implements** the `SEQUENTIAL` value of the `responseMode` enum from v2 on (it
was defined in v1 but not supported).

### RouteResponse — additions

| Field | Type | Required | Default | Description |
|---|---|---|---|---|
| `key` | string\|null | ❌ | `null` | Automation address. Falls back to `id` when empty. |
| `whenState` | string\|null | ❌ | `null` | Served only while this state is active. Eligible in every state when empty. |
| `setState` | string\|null | ❌ | `null` | Changes the active state to this value **after** being served. |

## New enum

| Enum | Values |
|---|---|
| `SequentialBehavior` | `STAY_ON_LAST` `CYCLE` |

## `key` semantics

- `key` is a readable slug chosen by the user: the address automation code uses instead of
  an opaque id (`PUT /__mockonomy/routes/get-user/response`).
- It may be left empty (`null`); the automation API then accepts the `id` instead.
- Uniqueness is not enforced at the schema level; if two items in the same scope share a
  `key`, the first one wins.

## State machine semantics (summary)

The full behavior is defined in `spec/behavior/state-machine.md`. From the schema's point of
view:

- `whenState` is **an implicit extra rule**: it is applied before regular rule evaluation.
  If empty, the response is eligible in every state.
- `setState` is applied **after** the response is selected.
- The active state is **runtime data**: it lives in memory and is not written to the file.
  Only `states` and `initialState` are in the file.

## Migration: v1 → v2

- Since every new field is optional and has a default, migration involves no data
  transformation; **only `schemaVersion` is bumped from 1 to 2.**
- The path is still actually exercised (`SchemaMigration.migrate`, `:engine`) —
  infrastructure to rely on when a breaking change is needed later.
- The storage layer (`FileEnvironmentStore`, `importEnvironment`) treats a **newer**
  version (greater than 2) as an error and migrates older versions.

## Example (v2 with a state machine)

```json
{
  "schemaVersion": 2,
  "id": "env-auth",
  "key": "auth",
  "name": "Auth API",
  "port": 8080,
  "prefix": "",
  "states": ["logged_out", "logged_in"],
  "initialState": "logged_out",
  "routes": [
    {
      "id": "route-login",
      "key": "login",
      "method": "POST",
      "path": "/auth/login",
      "responses": [
        {
          "id": "resp-login-ok",
          "key": "success",
          "statusCode": 200,
          "whenState": "logged_out",
          "setState": "logged_in",
          "isDefault": true
        }
      ]
    },
    {
      "id": "route-profile",
      "key": "profile",
      "method": "GET",
      "path": "/profile",
      "responses": [
        {
          "id": "resp-401", "key": "unauthorized",
          "statusCode": 401, "whenState": "logged_out", "isDefault": true
        },
        {
          "id": "resp-profile", "key": "profile-body",
          "statusCode": 200, "whenState": "logged_in"
        }
      ]
    }
  ]
}
```
