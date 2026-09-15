# Fixtures — Golden Test Data

Each fixture is **a single JSON file** that proves a rule from `behavior/`. Every engine
implementation's test runner reads these files and must produce the same result.

## Why a single file

Separate `environment.json` / `request.json` / `expected-response.json` files per fixture
were considered first. A single file was chosen:

- The runner does one read and one parse
- Opening one file is enough to inspect a case
- Adding a fixture means adding one file
- In code review the whole case shows up in a single diff

The cost: the embedded `environment` object is not a configuration file that can be loaded
on its own. If one is ever needed, it is extracted by hand — an acceptable trade-off.

## Format

```json
{
  "meta": {
    "name": "exact-path",
    "description": "Method and path match exactly",
    "phase": 0,
    "specRef": "behavior/matching.md#2-finding-candidate-routes"
  },
  "environment": { "...an environment that follows the schema..." },
  "request": {
    "method": "GET",
    "path": "/ping",
    "query": {},
    "headers": {},
    "body": null
  },
  "expectedResponse": {
    "statusCode": 200,
    "headers": [{ "key": "Content-Type", "value": "application/json" }],
    "body": "{\"status\":\"ok\"}",
    "latencyMs": 0
  },
  "expectedPathParams": {}
}
```

### `meta`

| Field | Type | Required | Description |
|---|---|---|---|
| `name` | string | ✅ | Must match the file name |
| `description` | string | ✅ | One sentence, in English |
| `phase` | int | ✅ | The engine capability level this behavior belongs to |
| `specRef` | string | ❌ | Link to the related `behavior/` section |

### `now`
Optional, top level. The instant the template engine sees, as an ISO-8601 UTC timestamp.
Defaults to `2026-03-14T15:09:26Z`. Fixtures do not cover random helpers
(`templating.md`, "Why random helpers have no golden file").

### `request`

| Field | Type | Required | Description |
|---|---|---|---|
| `method` | string | ✅ | Upper case |
| `path` | string | ✅ | Including the prefix, without the query string |
| `query` | map<string, string[]> | ❌ | Defaults to `{}` |
| `headers` | map<string, string[]> | ❌ | Defaults to `{}` |
| `body` | string \| null | ❌ | Defaults to `null` |

### `expectedResponse`
The **observable response** the engine produces. It is filled in even when nothing
matches — in that case the default 404 from `behavior/matching.md` §3 is expected.

### `expectedPathParams`
Optional. If given, the captured path parameters are compared exactly.

### `steps` (multi-step)
A sequence of requests instead of a single `request`/`expectedResponse`. For behavior that
is only observable across **several requests**, such as sequential responses, the request
counter and the state machine. All steps share **the same** session — state and counters
carry over from step to step; the session starts with `environment.initialState`.

```json
"steps": [
  { "request": { "method": "POST", "path": "/auth/login" },
    "expectedResponse": { "statusCode": 200, "headers": [...], "body": "...", "latencyMs": 0 },
    "expectedStateAfter": "logged_in" }
]
```

Every step contains `request` + `expectedResponse`; `expectedPathParams` and
`expectedStateAfter` (the active state expected after the step) are optional. A fixture uses
either the single-shot form (`request` + `expectedResponse`) or `steps`, never both.

## Runner behavior

1. All `*.json` files under `fixtures/` are found
2. If `meta.phase` is greater than the implemented phase the fixture is **skipped** (not
   counted as a failure)
3. The `environment` is loaded and the `request` is handed to the engine
4. The produced response is compared with `expectedResponse`: `statusCode`, `body` and
   `latencyMs` exactly; `headers` order-independently, with case-insensitive names
5. If `expectedPathParams` is present it is compared too
6. The test name is derived from `meta.name`

The runner **must report** the number of skipped fixtures — skipping silently creates an
illusion of coverage.

## Adding a fixture

- The file name equals `meta.name`, in kebab-case
- The folder follows the related `behavior/` file: `matching/`, `rules/`, `templating/`,
  `state-machine/`
- Add a row to the "Fixture coverage" table in the `behavior/` file
- Keep the environment as small as possible — do not blur what the case proves
- Use readable strings in `id` fields (`route-ping`), not UUIDs

## Folders

| Folder | Covers |
|---|---|
| `matching/` | Request matching, specificity, response selection |
| `rules/` | Response rules |
| `templating/` | Template expressions |
| `state-machine/` | `session-flow` (multi-step) |
