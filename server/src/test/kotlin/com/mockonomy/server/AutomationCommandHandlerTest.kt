package com.mockonomy.server

import com.mockonomy.engine.model.Environment
import com.mockonomy.engine.model.HttpMethod
import com.mockonomy.engine.model.Route
import com.mockonomy.engine.model.RouteResponse
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Unit test of the automation command logic, independent of HTTP/ADB transport: the
 * handler is tested directly on a fake [AdminControl]. The HTTP mapping is verified end
 * to end in [AdminApiIntegrationTest].
 */
class AutomationCommandHandlerTest {

    private fun env() = Environment(
        schemaVersion = Environment.CURRENT_SCHEMA_VERSION,
        id = "env-greet", key = "greet", name = "Greet", port = 0,
        states = listOf("logged_out", "logged_in"), initialState = "logged_out",
        routes = listOf(
            Route(
                id = "route-greet", key = "greet", method = HttpMethod.GET, path = "/greet",
                responses = listOf(
                    RouteResponse(id = "r-ok", key = "ok", statusCode = 200, body = "{}", isDefault = true),
                    RouteResponse(id = "r-nf", key = "not-found", statusCode = 404, body = "{}"),
                ),
            ),
        ),
    )

    private fun handler(control: FakeAdminControl, environments: List<Environment> = listOf(env())): AutomationCommandHandler {
        val envStore = FakeEnvironmentStore().apply { runBlocking { environments.forEach { save(it) } } }
        return AutomationCommandHandler(control, envStore, FakeRequestLogStore())
    }

    @Test
    fun `setResponse pins the route`() = runBlocking {
        val control = FakeAdminControl(env())
        val result = handler(control).handle(AutomationCommand.SetResponse("greet", "not-found"))
        assertIs<AutomationResult.Ok>(result)
        assertEquals("r-nf", control.overrides["route-greet"])
    }

    @Test
    fun `setResponse with an unknown response gives NotFound plus the existing ones`() = runBlocking {
        val control = FakeAdminControl(env())
        val result = handler(control).handle(AutomationCommand.SetResponse("greet", "nope"))
        val nf = assertIs<AutomationResult.NotFound>(result)
        assertEquals("response_not_found", nf.code)
        assertTrue("ok" in nf.available && "not-found" in nf.available)
    }

    @Test
    fun `setResponse with an unknown route gives NotFound`() = runBlocking {
        val control = FakeAdminControl(env())
        val nf = assertIs<AutomationResult.NotFound>(handler(control).handle(AutomationCommand.SetResponse("ghost", "ok")))
        assertEquals("route_not_found", nf.code)
    }

    @Test
    fun `setState with a valid state`() = runBlocking {
        val control = FakeAdminControl(env())
        assertIs<AutomationResult.Ok>(handler(control).handle(AutomationCommand.SetState("logged_in")))
        assertEquals("logged_in", control.currentState)
    }

    @Test
    fun `setState with an unknown state gives BadRequest plus the existing ones`() = runBlocking {
        val control = FakeAdminControl(env())
        val bad = assertIs<AutomationResult.BadRequest>(handler(control).handle(AutomationCommand.SetState("ghost")))
        assertEquals("unknown_state", bad.code)
        assertTrue("logged_out" in bad.available)
    }

    @Test
    fun `reset resets the session`() = runBlocking {
        val control = FakeAdminControl(env())
        assertIs<AutomationResult.Ok>(handler(control).handle(AutomationCommand.Reset))
        assertTrue(control.wasReset)
    }

    @Test
    fun `activate switches the running env and applies the optional state`() = runBlocking {
        val other = env().copy(id = "env-other", key = "other", name = "Other", states = listOf("a", "b"), initialState = "a")
        val control = FakeAdminControl(env())
        val result = handler(control, listOf(env(), other)).handle(AutomationCommand.Activate("other", state = "b"))
        assertIs<AutomationResult.Ok>(result)
        assertEquals("env-other", control.currentEnvironment?.id)
        assertEquals("b", control.currentState)
    }

    @Test
    fun `activate with an unknown env gives NotFound`() = runBlocking {
        val control = FakeAdminControl(env())
        val nf = assertIs<AutomationResult.NotFound>(handler(control).handle(AutomationCommand.Activate("ghost")))
        assertEquals("environment_not_found", nf.code)
    }

    @Test
    fun `activate with an invalid state gives BadRequest`() = runBlocking {
        val control = FakeAdminControl(env())
        val bad = assertIs<AutomationResult.BadRequest>(handler(control).handle(AutomationCommand.Activate("greet", state = "ghost")))
        assertEquals("unknown_state", bad.code)
    }

    @Test
    fun `mutations give NoEnvironment while no server is running`() = runBlocking {
        val control = FakeAdminControl(null) // no server
        assertIs<AutomationResult.NoEnvironment>(handler(control).handle(AutomationCommand.Reset))
        assertIs<AutomationResult.NoEnvironment>(handler(control).handle(AutomationCommand.SetState("logged_in")))
        assertIs<AutomationResult.NoEnvironment>(handler(control).handle(AutomationCommand.ClearLogs))
        // activate: the env is found but no server is running → NoEnvironment (starting is not reliable)
        assertIs<AutomationResult.NoEnvironment>(handler(control).handle(AutomationCommand.Activate("greet")))
    }
}

/** A fake runtime control surface for testing the handler without HTTP. */
internal class FakeAdminControl(private var env: Environment?) : AdminControl {
    var currentStateBacking: String? = env?.initialState
    val overrides = mutableMapOf<String, String>()
    var wasReset = false

    override val currentEnvironment: Environment? get() = env
    override val currentState: String? get() = currentStateBacking
    override suspend fun port(): Int? = env?.let { 0 }
    override fun setState(state: String) { currentStateBacking = state }
    override fun resetSession() { wasReset = true; currentStateBacking = env?.initialState; overrides.clear() }
    override fun overrideResponse(routeId: String, responseId: String) { overrides[routeId] = responseId }
    override suspend fun activate(environment: Environment) {
        env = environment
        currentStateBacking = environment.initialState
    }
}
