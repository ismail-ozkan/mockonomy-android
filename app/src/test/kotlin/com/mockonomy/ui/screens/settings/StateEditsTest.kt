package com.mockonomy.ui.screens.settings

import com.mockonomy.engine.model.Environment
import com.mockonomy.engine.model.HttpMethod
import com.mockonomy.engine.model.Route
import com.mockonomy.engine.model.RouteResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StateEditsTest {

    private fun response(id: String, whenState: String? = null, setState: String? = null) =
        RouteResponse(id = id, statusCode = 200, whenState = whenState, setState = setState)

    private fun environment(
        states: List<String> = emptyList(),
        initialState: String? = null,
        responses: List<RouteResponse> = listOf(response("resp")),
    ) = Environment(
        schemaVersion = Environment.CURRENT_SCHEMA_VERSION,
        id = "env-a", name = "A", port = Environment.DEFAULT_PORT,
        states = states, initialState = initialState,
        routes = listOf(Route(id = "r1", method = HttpMethod.GET, path = "/x", responses = responses)),
    )

    @Test
    fun `adding a state appends it`() {
        val result = environment(states = listOf("a")).withStateAdded("b")
        assertEquals(listOf("a", "b"), result.states)
    }

    @Test
    fun `renaming updates the initialState reference`() {
        val result = environment(states = listOf("logged_out"), initialState = "logged_out")
            .withStateRenamed(0, "loggedOut")
        assertEquals(listOf("loggedOut"), result.states)
        assertEquals("loggedOut", result.initialState)
    }

    @Test
    fun `renaming updates whenState and setState references`() {
        val result = environment(
            states = listOf("logged_out", "logged_in"),
            responses = listOf(
                response("r-a", whenState = "logged_out", setState = "logged_in"),
                response("r-b", whenState = "logged_in"),
            ),
        ).withStateRenamed(0, "out")

        val responses = result.routes.single().responses
        assertEquals("out", responses[0].whenState)
        assertEquals("logged_in", responses[0].setState)
        assertEquals("logged_in", responses[1].whenState)
    }

    @Test
    fun `deleting clears references and shifts initialState`() {
        val result = environment(
            states = listOf("logged_out", "logged_in"),
            initialState = "logged_out",
            responses = listOf(
                response("r-a", whenState = "logged_out", setState = "logged_in"),
                response("r-b", whenState = "logged_in"),
            ),
        ).withStateDeleted(0) // deleting logged_out

        assertEquals(listOf("logged_in"), result.states)
        // initialState was the deleted state → it fell back to the first remaining state
        assertEquals("logged_in", result.initialState)
        val responses = result.routes.single().responses
        // the whenState pointing at logged_out was cleared; setState=logged_in was kept
        assertNull(responses[0].whenState)
        assertEquals("logged_in", responses[0].setState)
        assertEquals("logged_in", responses[1].whenState)
    }

    @Test
    fun `deleting the last state makes initialState null`() {
        val result = environment(states = listOf("only"), initialState = "only").withStateDeleted(0)
        assertEquals(emptyList(), result.states)
        assertNull(result.initialState)
    }

    @Test
    fun `setting initialState`() {
        val result = environment(states = listOf("a", "b")).withInitialState("b")
        assertEquals("b", result.initialState)
    }
}
