# Environment Schema — v1

> `schemaVersion: 1`
>
> Defines the format of the file stored on the device as `filesDir/environments/<id>.json`,
> which is also the exported file. Version 2 adds fields on top of this document; see
> [environment-v2.md](environment-v2.md).

## General rules

- Encoding **UTF-8**, line ending `\n`
- Unknown fields are **ignored** (`ignoreUnknownKeys`) — forward compatibility
- Missing optional fields get their default values
- The "Default" column shows the value applied **when the file is read**. Required fields
  have no default; the initial values the app uses when it creates a new environment (e.g.
  8080 for the port) are not the subject of this table
- `id` fields are opaque strings, unique within their scope. A UUID is recommended but not
  required; test fixtures use readable strings.
- All names and enum values on the wire are English

## Environment (root object)

| Field | Type | Required | Default | Phase | Description |
|---|---|---|---|---|---|
| `schemaVersion` | int | ✅ | — | 0 | `1` in this version |
| `id` | string | ✅ | — | 0 | Unique identifier |
| `name` | string | ✅ | — | 0 | Name shown to the user |
| `port` | int | ✅ | — | 0 | `1024`–`65535` |
| `prefix` | string | ❌ | `""` | 0 | Prefix applied to every route |
| `defaultLatencyMs` | int | ❌ | `0` | 0 | Base latency added to every response |
| `cors` | bool | ❌ | `false` | 1 | Permissive CORS headers + an `OPTIONS` preflight response |
| `headers` | HeaderPair[] | ❌ | `[]` | 0 | Default headers added to every response |
| `proxy` | ProxyConfig | ❌ | `null` | 3 | See ProxyConfig |
| `routes` | Route[] | ✅ | — | 0 | May be empty |

**There is no `enabled` field.** Whether an environment is running is runtime state, not
configuration: it is kept in the app state, not in the file. When the file is shared,
"running" would mean nothing to the recipient. (Several environments can run at the same
time; that does not change the absence of this field.)

### `prefix` normalization
Leading and trailing `/` characters are trimmed. `"/api/v1/"`, `"api/v1"` and `"api/v1/"`
mean the same thing. An empty string means there is no prefix.

## Route

| Field | Type | Required | Default | Phase | Description |
|---|---|---|---|---|---|
| `id` | string | ✅ | — | 0 | Unique identifier |
| `method` | HttpMethod | ✅ | — | 0 | Normalized to upper case |
| `path` | string | ✅ | — | 0 | Must start with `/` |
| `enabled` | bool | ❌ | `true` | 0 | Skipped during matching if `false` |
| `responseMode` | ResponseMode | ❌ | `RULES` | 2 | See the enum table |
| `responses` | RouteResponse[] | ✅ | — | 0 | At least one element |

### `path` syntax
- Starts with `/`
- Segments are separated by `/`
- A segment of the form `:name` is a **path parameter** and matches exactly one non-empty
  segment
- Wildcards (`*`) are not supported

## RouteResponse

| Field | Type | Required | Default | Phase | Description |
|---|---|---|---|---|---|
| `id` | string | ✅ | — | 0 | Unique identifier |
| `label` | string | ❌ | `""` | 1 | Name shown in the UI, e.g. `"404 – not found"` |
| `statusCode` | int | ✅ | — | 0 | `100`–`599` |
| `headers` | HeaderPair[] | ❌ | `[]` | 0 | Override environment headers |
| `bodyType` | BodyType | ❌ | `INLINE` | 1 | `INLINE` or `FILE` |
| `body` | string | ❌ | `""` | 0 | Used when `bodyType = INLINE` |
| `bodyFile` | string\|null | ❌ | `null` | 1 | File name when `bodyType = FILE` |
| `latencyMs` | int | ❌ | `0` | 0 | **Added** to `defaultLatencyMs` |
| `isDefault` | bool | ❌ | `false` | 0 | The fallback response |
| `rulesOperator` | RulesOperator | ❌ | `AND` | 2 | How rules are combined |
| `rules` | Rule[] | ❌ | `[]` | 2 | If empty, the response does not take part in rule-based selection (`behavior/matching.md` §5) |

### `bodyFile`
Bodies above 100 KB are kept under `filesDir/bodies/<responseId>.json` and referenced here
by file name. On export these bodies are pulled inline, so the exported file is
self-contained; on import, large bodies are moved back into files.

## Rule

| Field | Type | Required | Default | Description |
|---|---|---|---|---|
| `target` | RuleTarget | ✅ | — | What to look at |
| `key` | string | ❌ | `""` | Parameter/header name or body path |
| `operator` | RuleOperator | ✅ | — | How to compare |
| `value` | string | ❌ | `""` | Ignored for `EXISTS` |
| `invert` | bool | ❌ | `false` | Inverts the result |

When `target = BODY`, `key` is a body path in dot notation (`user.address.city`). An empty
`key` points at the whole body. Details: `behavior/rules.md`.

## ProxyConfig

| Field | Type | Required | Default | Description |
|---|---|---|---|---|
| `enabled` | bool | ❌ | `false` | Forward requests that match no route |
| `target` | string | ❌ | `""` | Absolute base URL, e.g. `https://api.example.com` |
| `recordUnmatched` | bool | ❌ | `false` | Record forwarded responses in the request log |
| `ignoreTlsErrors` | bool | ❌ | `false` | For self-signed certificates |

## HeaderPair

| Field | Type | Required |
|---|---|---|
| `key` | string | ✅ |
| `value` | string | ✅ |

The same `key` may appear more than once (multi-value headers).

> The **Phase column** shows the engine capability level at which the field is implemented.
> For fields the schema defines but the engine does not implement, the engine does not stay
> silent; it fails loudly (see `UnsupportedMockFeatureException`).

## Enums

| Enum | Values | Phase |
|---|---|---|
| `HttpMethod` | `GET` `POST` `PUT` `PATCH` `DELETE` `HEAD` `OPTIONS` | 0 |
| `ResponseMode` | `RULES` | 2 |
| | `SEQUENTIAL` | 4 |
| | `RANDOM` | not supported yet |
| `BodyType` | `INLINE` `FILE` | 1 |
| `RuleTarget` | `QUERY` `HEADER` `BODY` `PATH_PARAM` | 2 |
| | `REQUEST_NUMBER` | 4 |
| | `COOKIE` | not supported yet |
| `RuleOperator` | `EQUALS` `CONTAINS` `REGEX` `EXISTS` | 2 |
| `RulesOperator` | `AND` `OR` | 2 |

Values that are not supported yet are **already defined**, but the engine fails loudly when
it meets them — they are never silently ignored.

## Validation rules

An environment file is invalid unless:

1. `schemaVersion` is a known version
2. `port` is in the `1024`–`65535` range
3. Every `route.path` starts with `/`
4. Every route has at least one response
5. Route `id`s are unique within the environment
6. Response `id`s are unique within their route
7. A route has **at most one** response with `isDefault: true`
8. `bodyFile` is not empty when `bodyType = FILE`

## Corrupt file behavior

A file that fails validation does not crash the app; it shows up in the environment list as
a "corrupt file" and the user can delete it.

## Example

```json
{
  "schemaVersion": 1,
  "id": "env-auth",
  "name": "Auth API",
  "port": 8080,
  "prefix": "api/v1",
  "defaultLatencyMs": 0,
  "cors": false,
  "headers": [
    { "key": "Content-Type", "value": "application/json" }
  ],
  "routes": [
    {
      "id": "route-get-user",
      "method": "GET",
      "path": "/users/:id",
      "enabled": true,
      "responses": [
        {
          "id": "resp-found",
          "label": "200 – found",
          "statusCode": 200,
          "headers": [],
          "body": "{\"id\":\"42\",\"name\":\"Test User\"}",
          "latencyMs": 0,
          "isDefault": true
        }
      ]
    }
  ]
}
```
