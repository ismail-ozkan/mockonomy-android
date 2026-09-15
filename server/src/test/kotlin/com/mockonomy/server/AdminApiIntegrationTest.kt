package com.mockonomy.server

import com.mockonomy.data.AdminApiSettings
import com.mockonomy.data.EnvironmentStore
import com.mockonomy.data.EnvironmentSummary
import com.mockonomy.data.REDACTED_VALUE
import com.mockonomy.data.log.RequestLog
import com.mockonomy.data.log.RequestLogStore
import com.mockonomy.data.log.RequestLogSummary
import com.mockonomy.engine.model.Environment
import com.mockonomy.engine.model.HeaderPair
import com.mockonomy.engine.model.HttpMethod
import com.mockonomy.engine.model.Route
import com.mockonomy.engine.model.RouteResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * End-to-end verification of the automation admin API: a real Ktor CIO server and real
 * HTTP. Room does not run on the JVM, so in-memory fakes of [EnvironmentStore] and
 * [RequestLogStore] are used.
 */
class AdminApiIntegrationTest {

    private val settings = MutableStateFlow(AdminApiSettings(enabled = true))
    private val envStore = FakeEnvironmentStore()
    private val logStore = FakeRequestLogStore()
    private val server = MockServer(
        logSink = logStore,
        adminApi = AdminApi(settings, envStore, logStore),
    )
    private val client = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build()

    @AfterTest
    fun tearDown() {
        if (server.isRunning) server.stop()
    }

    // --- fixtures ---

    private fun greetEnv() = Environment(
        schemaVersion = Environment.CURRENT_SCHEMA_VERSION,
        id = "env-greet", key = "greet", name = "Greet", port = 0,
        states = listOf("logged_out", "logged_in"), initialState = "logged_out",
        headers = listOf(HeaderPair("Content-Type", "application/json")),
        routes = listOf(
            Route(
                id = "route-greet", key = "greet", method = HttpMethod.GET, path = "/greet",
                responses = listOf(
                    RouteResponse(id = "r-ok", key = "ok", statusCode = 200, body = """{"m":"hi"}""", isDefault = true),
                    RouteResponse(id = "r-nf", key = "not-found", statusCode = 404, body = """{"m":"nope"}"""),
                ),
            ),
        ),
    )

    private fun start(vararg environments: Environment): String = runBlocking {
        environments.forEach { envStore.save(it) }
        server.start(environments.first(), port = 0)
        "http://127.0.0.1:${server.resolvedPort()}"
    }

    private fun get(url: String, token: String? = null) = client.newCall(
        Request.Builder().url(url).apply { token?.let { header(AdminApi.TOKEN_HEADER, it) } }.build(),
    ).execute()

    private fun send(method: String, url: String, body: String? = null) = client.newCall(
        Request.Builder().url(url)
            .method(method, body?.toRequestBody() ?: if (method in setOf("POST", "PUT", "DELETE")) ByteArray(0).toRequestBody() else null)
            .build(),
    ).execute()

    // --- security gate ---

    @Test
    fun `all endpoints return 404 while admin is off`() {
        settings.value = AdminApiSettings(enabled = false)
        val base = start(greetEnv())
        assertEquals(404, get("$base/__mockonomy/health").code)
        assertEquals(404, send("POST", "$base/__mockonomy/reset").code)
        assertEquals(404, get("$base/__mockonomy/environments").code)
    }

    @Test
    fun `with a token set a wrong token gets 401 and the right token 200`() {
        settings.value = AdminApiSettings(enabled = true, token = "secret")
        val base = start(greetEnv())
        assertEquals(401, get("$base/__mockonomy/health").code)
        assertEquals(401, get("$base/__mockonomy/health", token = "wrong").code)
        assertEquals(200, get("$base/__mockonomy/health", token = "secret").code)
    }

    // --- health & environments ---

    @Test
    fun `health reports the running environment`() {
        val base = start(greetEnv())
        val body = get("$base/__mockonomy/health").body!!.string()
        assertContains(body, "\"running\":true")
        assertContains(body, "\"environment\":\"Greet\"")
        assertContains(body, "\"activeState\":\"logged_out\"")
    }

    @Test
    fun `environment list marks the active one`() {
        val other = greetEnv().copy(id = "env-other", key = "other", name = "Other")
        val base = start(greetEnv(), other)
        val body = get("$base/__mockonomy/environments").body!!.string()
        assertContains(body, "\"key\":\"greet\"")
        assertContains(body, "\"key\":\"other\"")
        // The running "greet" is active, "other" is not
        assertContains(body, "\"name\":\"Greet\",\"port\":0,\"active\":true")
        assertContains(body, "\"name\":\"Other\",\"port\":0,\"active\":false")
    }

    // --- forced override ---

    @Test
    fun `PUT response pins the route and reset releases it`() {
        val base = start(greetEnv())
        // Default (200) at first
        assertEquals(200, get("$base/greet").code)
        // Pin → 404
        val put = send("PUT", "$base/__mockonomy/routes/greet/response", """{"key":"not-found"}""")
        assertEquals(200, put.code)
        assertEquals(404, get("$base/greet").code)
        assertEquals(404, get("$base/greet").code)
        // Reset → default again
        assertEquals(200, send("POST", "$base/__mockonomy/reset").code)
        assertEquals(200, get("$base/greet").code)
    }

    @Test
    fun `unknown response key gives 404 and the existing keys`() {
        val base = start(greetEnv())
        val res = send("PUT", "$base/__mockonomy/routes/greet/response", """{"key":"nope"}""")
        assertEquals(404, res.code)
        val body = res.body!!.string()
        assertContains(body, "response_not_found")
        assertContains(body, "\"ok\"")
        assertContains(body, "\"not-found\"")
    }

    // --- state ---

    @Test
    fun `POST state changes the active state and an unknown state gets 400`() {
        val base = start(greetEnv())
        assertEquals(200, send("POST", "$base/__mockonomy/state", """{"state":"logged_in"}""").code)
        assertContains(get("$base/__mockonomy/health").body!!.string(), "\"activeState\":\"logged_in\"")

        val bad = send("POST", "$base/__mockonomy/state", """{"state":"ghost"}""")
        assertEquals(400, bad.code)
        assertContains(bad.body!!.string(), "unknown_state")
    }

    // --- logs ---

    @Test
    fun `GET logs filters recorded requests by method and path`() {
        val base = start(greetEnv())
        get("$base/greet") // GET /greet is recorded
        val body = get("$base/__mockonomy/logs?method=GET&path=/greet").body!!.string()
        assertContains(body, "\"path\":\"/greet\"")
        assertContains(body, "\"method\":\"GET\"")
        // A filter that matches nothing returns empty
        val none = get("$base/__mockonomy/logs?path=/nope").body!!.string()
        assertContains(none, "\"count\":0")
    }

    @Test
    fun `GET logs masks sensitive headers`() {
        val base = start(greetEnv())
        client.newCall(
            Request.Builder().url("$base/greet").header("Authorization", "Bearer topsecret").build(),
        ).execute()
        val body = get("$base/__mockonomy/logs").body!!.string()
        assertContains(body, REDACTED_VALUE)
        assertFalse(body.contains("topsecret"))
    }

    @Test
    fun `DELETE logs clears them`() {
        val base = start(greetEnv())
        get("$base/greet")
        assertEquals(200, send("DELETE", "$base/__mockonomy/logs").code)
        assertContains(get("$base/__mockonomy/logs").body!!.string(), "\"count\":0")
    }

    // --- activate ---

    @Test
    fun `activate switches the running environment`() {
        val other = Environment(
            schemaVersion = Environment.CURRENT_SCHEMA_VERSION,
            id = "env-other", key = "other", name = "Other", port = 0,
            headers = listOf(HeaderPair("Content-Type", "application/json")),
            routes = listOf(
                Route(
                    id = "route-bye", method = HttpMethod.GET, path = "/bye",
                    responses = listOf(RouteResponse(id = "r-bye", statusCode = 200, body = """{"m":"bye"}""", isDefault = true)),
                ),
            ),
        )
        val base = start(greetEnv(), other)
        // greet runs at first: /bye does not exist (404), /greet does
        assertEquals(404, get("$base/bye").code)
        assertEquals(200, get("$base/greet").code)
        // switch to other
        assertEquals(200, send("POST", "$base/__mockonomy/environments/other/activate").code)
        // Now /bye works and /greet is gone
        assertEquals(200, get("$base/bye").code)
        assertEquals(404, get("$base/greet").code)
        assertContains(get("$base/__mockonomy/health").body!!.string(), "\"environment\":\"Other\"")
    }

    @Test
    fun `activating an unknown environment gives 404 and the existing keys`() {
        val base = start(greetEnv())
        val res = send("POST", "$base/__mockonomy/environments/ghost/activate")
        assertEquals(404, res.code)
        val body = res.body!!.string()
        assertContains(body, "environment_not_found")
        assertContains(body, "\"greet\"")
    }
}

// --- fakes (in memory; Room is not available on the JVM) — also used by MockServerControllerTest ---

internal class FakeEnvironmentStore : EnvironmentStore {
    private val map = LinkedHashMap<String, Environment>()
    override val saved: Flow<String> = emptyFlow()
    override suspend fun list(): List<EnvironmentSummary> =
        map.values.map { EnvironmentSummary.Valid(it) }
    override suspend fun load(id: String): Environment? = map[id]
    override suspend fun save(environment: Environment) { map[environment.id] = environment }
    override suspend fun delete(id: String) { map.remove(id) }
}

internal class FakeRequestLogStore : RequestLogStore {
    private val logs = mutableListOf<RequestLog>()
    override val isRecording: Boolean = true
    override fun record(log: RequestLog) { synchronized(logs) { logs.add(0, log) } }
    override fun observeSummaries(environmentId: String): Flow<List<RequestLogSummary>> =
        MutableStateFlow(
            synchronized(logs) {
                logs.filter { it.environmentId == environmentId }
                    .map { RequestLogSummary(it.id, it.timestampMs, it.method, it.path, it.query, it.statusCode, it.durationMs) }
            },
        )
    override suspend fun find(id: String): RequestLog? = synchronized(logs) { logs.firstOrNull { it.id == id } }
    override suspend fun findAll(ids: List<String>): List<RequestLog> =
        synchronized(logs) { logs.filter { it.id in ids } }
    override suspend fun clear(environmentId: String) {
        synchronized(logs) { logs.removeAll { it.environmentId == environmentId } }
    }
}
