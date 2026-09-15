# Mockonomy Automation API

Control mock scenarios from your test suite. Activate an environment, pin a route
to a specific response, set the state machine, reset counters, and **verify what
your app actually sent** — all over HTTP, or from the `adb` shell.

This is what makes Mockonomy more than an on-device Mockoon: the same test code
sets up the mock and asserts against it, with no shared backend.

> This document matches the implementation; the snippets are copy-paste runnable.

---

## 1. Turn it on (it's off by default)

The automation API is **disabled by default**. Any app on the device can reach
`127.0.0.1`, so it is only active when you deliberately enable it.

**In the app:** top bar → the plug icon (Automation) → *Enable automation API*.

There you can also:
- Copy the **base URL** of the running server (`http://127.0.0.1:<port>/__mockonomy`).
- Toggle **Redact sensitive headers in reads** (on by default — masks
  `Authorization`, `Cookie`, etc. in `GET /logs`).
- Set an optional **shared token**. When set, every HTTP request must send it as
  the `X-Mockonomy-Token` header. (ADB broadcasts are not token-gated — `adb`
  already implies local shell access.)

The same toggle gates both the HTTP API and the ADB broadcast receiver.

---

## 2. Reaching the server

The server binds to **loopback only** (`127.0.0.1`). How you reach it depends on
where your test code runs:

| Test runs on… | How to reach Mockonomy |
|---|---|
| The device (Espresso / instrumented) | Directly: `http://127.0.0.1:<port>` |
| The host (Appium, pytest, CI script) | `adb forward tcp:<port> tcp:<port>` first, then `http://127.0.0.1:<port>` |
| `adb shell` | Directly (curl on device), or broadcasts (§6) |

The port is whatever the running environment uses (default `8080`). The app under
test still needs a cleartext network-security config for `127.0.0.1` — see
[getting-started.md](getting-started.md).

---

## 3. HTTP endpoints

All endpoints live under `/__mockonomy` at the server root — **not** affected by
an environment's `prefix`, and checked *before* mock matching.

Base: `http://127.0.0.1:<port>/__mockonomy`

### Setup (before a test)

| Method & path | Body | Does |
|---|---|---|
| `POST /environments/{key}/activate` | — | Make an environment the running one (hot-swap on the current port) |
| `PUT /routes/{key}/response` | `{"key":"not-found"}` | Pin a route to always serve one response |
| `POST /state` | `{"state":"logged_in"}` | Set the named-state machine's active state |
| `POST /reset` | — | Reset request counters, active state and response overrides |
| `DELETE /logs` | — | Clear the running environment's request log |

### Verification (after a test)

| Method & path | Does |
|---|---|
| `GET /logs?limit=50&method=POST&path=/orders` | Recorded requests (newest first), filtered |
| `GET /health` | Server status: running, environment, active state, port |
| `GET /environments` | All environments with an `active` flag |

`{key}` matches an environment/route by its `key` field, falling back to its `id`.
Give your routes and responses readable `key`s so tests never touch UUIDs.

### Addressing a response

`PUT /routes/{key}/response` takes the response by `key` (preferred) or `id`:

```json
{ "key": "not-found" }
```

The pin survives every request until you `POST /reset`, activate a different
environment, or restart the server. While pinned, rules, sequential order and
`whenState` are all bypassed for that route (the response's own `setState` still
applies).

---

## 4. Responses and errors

Success is `200` with `{"message":"..."}`.

Errors are always JSON (never an HTML error page), with a stable `error` code and,
when relevant, the list of valid options:

```json
{
  "error": "response_not_found",
  "message": "No response with key or id 'nope' in route 'get-user'.",
  "available": ["ok", "not-found"]
}
```

| HTTP | `error` | When |
|---|---|---|
| 404 | `admin_api_disabled` | Automation API is off |
| 401 | `invalid_token` | Token set but missing/wrong `X-Mockonomy-Token` |
| 404 | `environment_not_found` | No environment with that key/id |
| 404 | `route_not_found` | No route with that key/id in the running environment |
| 404 | `response_not_found` | No response with that key/id in the route |
| 400 | `missing_response_ref` | `PUT response` body had no `key`/`id` |
| 400 | `unknown_state` | State not defined in the environment |
| 400 | `invalid_body` | Body was not valid JSON |
| 409 | `no_environment` | No server is running |

The `GET /logs` shape (one entry):

```json
{
  "count": 1,
  "logs": [{
    "id": "log_…", "timestampMs": 1690000000000,
    "method": "POST", "path": "/orders", "query": "",
    "statusCode": 201, "durationMs": 3,
    "requestHeaders": [{"key":"Authorization","value":"***REDACTED***"}],
    "requestBody": "{\"productId\":\"abc\"}",
    "responseHeaders": [{"key":"Content-Type","value":"application/json"}],
    "responseBody": "{\"id\":\"o-1\"}",
    "matchedRouteId": "route-orders", "matchedResponseId": "r-created"
  }]
}
```

`GET /logs` filters: `limit` (default 50, max 500), `method` (case-insensitive),
`path` (exact). Redaction applies to request and response headers when
*Redact sensitive headers in reads* is on.

---

## 5. curl

```bash
BASE=http://127.0.0.1:8080/__mockonomy
# TOKEN=your-token        # only if you set one; then add: -H "X-Mockonomy-Token: $TOKEN"

curl -s "$BASE/health"
curl -s -X POST "$BASE/environments/checkout/activate"
curl -s -X PUT  "$BASE/routes/get-user/response" -d '{"key":"not-found"}'
curl -s -X POST "$BASE/state" -d '{"state":"logged_in"}'
curl -s -X POST "$BASE/reset"
curl -s "$BASE/logs?method=POST&path=/orders&limit=20"
curl -s -X DELETE "$BASE/logs"
```

---

## 6. ADB broadcast

For setup **before the test process starts**, from the shell. Gated by the same
Automation setting. The result appears in `am broadcast` output
(`Broadcast completed: result=…, data=…`), so CI can check it.

```bash
R=com.mockonomy/.automation.AutomationBroadcastReceiver

adb shell am broadcast -a com.mockonomy.automation.ACTIVATE     -n $R --es env auth --es state login_fail
adb shell am broadcast -a com.mockonomy.automation.SET_RESPONSE -n $R --es route get-user --es response not-found
adb shell am broadcast -a com.mockonomy.automation.SET_STATE    -n $R --es state logged_in
adb shell am broadcast -a com.mockonomy.automation.RESET        -n $R
adb shell am broadcast -a com.mockonomy.automation.CLEAR_LOGS   -n $R
```

| Action | Required extras | Optional |
|---|---|---|
| `…ACTIVATE` | `env` | `state` |
| `…SET_RESPONSE` | `route`, `response` | — |
| `…SET_STATE` | `state` | — |
| `…RESET` | — | — |
| `…CLEAR_LOGS` | — | — |

Result codes: `-1` = OK · `1` = rejected (not found / bad / no environment) ·
`2` = automation disabled · `3` = unknown action or missing extra.

### Broadcasts and multiple servers

Environments run side by side, each on its own port. A broadcast carries no
port, so **state-changing commands apply to every running server**:
`SET_STATE`, `SET_RESPONSE`, `RESET` and `CLEAR_LOGS` run on all of them. The
command succeeds if it applied to at least one server — a state or route only
defined in one environment is not an error — and the result data names both the
servers it applied to and the ones it skipped:

```
result=-1, data="Applied to 1 of 2 servers. Auth: Active state set to 'login_fail'.
Skipped 1: Payments: State 'login_fail' is not defined in 'Payments'."
```

> **Careful:** `RESET` clears counters, state and overrides on *every* running
> server, including one another test is using. When you need to target a single
> environment, use the HTTP API instead — there the port *is* the target
> (`http://127.0.0.1:<port>/__mockonomy/...`).

`ACTIVATE` is the exception, because swapping a socket onto another environment
is a single-socket operation. It picks its target in this order: the server
already running that environment (no-op), then the server holding the port the
target environment asks for, then the only running server. With several
candidates it refuses with `ambiguous_activate_target` rather than swapping the
wrong socket silently.

> **Known limit:** a broadcast does **not start** the server. On Android 12+,
> starting a foreground service from the background is unreliable, so the receiver
> only *configures* a server that is already running (otherwise every command
> returns `no_environment`). The working order:
>
> ```bash
> adb shell am start -n com.mockonomy/.MainActivity   # open the app; start the server (▶)
> adb shell am broadcast -a com.mockonomy.automation.ACTIVATE -n $R --es env auth
> ```

---

## 7. Espresso / Kotlin (OkHttp)

A small client you can drop into your `androidTest` source set. Because Espresso
runs on the device, `127.0.0.1` needs no port forwarding.

```kotlin
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class Mockonomy(
    private val port: Int = 8080,
    private val token: String? = null,
) {
    private val base = "http://127.0.0.1:$port/__mockonomy"
    private val client = OkHttpClient()

    private fun send(method: String, path: String, body: String? = null): String {
        val builder = Request.Builder().url("$base$path")
            .method(method, body?.toRequestBody())
        token?.let { builder.header("X-Mockonomy-Token", it) }
        client.newCall(builder.build()).execute().use { res ->
            check(res.isSuccessful) { "Mockonomy $method $path -> ${res.code}: ${res.body?.string()}" }
            return res.body?.string().orEmpty()
        }
    }

    fun activate(env: String) = send("POST", "/environments/$env/activate")
    fun setResponse(route: String, response: String) =
        send("PUT", "/routes/$route/response", """{"key":"$response"}""")
    fun setState(state: String) = send("POST", "/state", """{"state":"$state"}""")
    fun reset() = send("POST", "/reset")
    fun clearLogs() = send("DELETE", "/logs")
    fun logs(method: String? = null, path: String? = null): String {
        val q = listOfNotNull(method?.let { "method=$it" }, path?.let { "path=$it" }).joinToString("&")
        return send("GET", "/logs" + if (q.isEmpty()) "" else "?$q")
    }
}
```

### A full example test

```kotlin
@RunWith(AndroidJUnit4::class)
class CheckoutTest {

    private val mock = Mockonomy(port = 8080)

    @Before fun setUp() {
        mock.reset()                              // isolate this test
        mock.activate("checkout")                 // known environment
        mock.setResponse("get-user", "logged-in") // arrange the scenario
    }

    @Test fun placingAnOrder_sendsTheProductId() {
        // ... drive the app UI with Espresso so it calls POST /orders ...

        // assert what the app actually sent (the WireMock verify() equivalent)
        val logs = mock.logs(method = "POST", path = "/orders")
        assertThat(logs).contains("\"count\":1")
        assertThat(logs).contains("\"productId\"")
    }

    @After fun tearDown() {
        mock.clearLogs()
    }
}
```

`reset()` in `@Before` is what makes tests independent: it clears counters (so
`SEQUENTIAL` and `REQUEST_NUMBER` restart at 1), the active state, and any pinned
responses.

---

## 8. Appium — Python and Java

Appium tests run on the host, so forward the port once, then talk plain HTTP.

```bash
adb forward tcp:8080 tcp:8080
```

**Python (`requests`):**

```python
import requests

BASE = "http://127.0.0.1:8080/__mockonomy"

def reset():        requests.post(f"{BASE}/reset").raise_for_status()
def activate(env):  requests.post(f"{BASE}/environments/{env}/activate").raise_for_status()
def set_response(route, response):
    requests.put(f"{BASE}/routes/{route}/response", json={"key": response}).raise_for_status()
def set_state(state):
    requests.post(f"{BASE}/state", json={"state": state}).raise_for_status()
def logs(method=None, path=None):
    params = {k: v for k, v in {"method": method, "path": path}.items() if v}
    return requests.get(f"{BASE}/logs", params=params).json()

# in a test:
reset(); activate("checkout"); set_response("get-user", "not-found")
# ... drive the app ...
assert logs(method="POST", path="/orders")["count"] == 1
```

**Java (OkHttp):** identical to the Kotlin client in §7 — point it at
`127.0.0.1:8080` after `adb forward`.

---

## 9. Notes

- **Isolation:** call `POST /reset` (or the `RESET` broadcast) at the start of
  every test. It resets counters, state and overrides together.
- **Activation ignores the target port:** `activate` swaps the environment on the
  *currently running* socket, so your test client keeps talking to the same port
  across a suite.
- **Verification is the point:** `GET /logs` is the mobile answer to WireMock's
  `verify()` — it lets a test assert that the app made the call, with the right
  method, path and body.
- **Security defaults are on the cheap side of error:** API off by default,
  redaction on by default, token optional. Turn the token on for shared devices.
