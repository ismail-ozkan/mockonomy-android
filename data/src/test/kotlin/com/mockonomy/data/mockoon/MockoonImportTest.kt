package com.mockonomy.data.mockoon

import com.mockonomy.data.openapi.ImportPreview
import com.mockonomy.data.openapi.ImportResult
import com.mockonomy.engine.model.HttpMethod
import com.mockonomy.engine.model.RuleOperator
import com.mockonomy.engine.model.RuleTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MockoonImportTest {

    private fun preview(json: String): ImportPreview {
        val result = MockoonImport.parse(json)
        assertTrue(result is ImportResult.Success, "unexpected failure: $result")
        return result.preview
    }

    private fun resource(path: String): String =
        checkNotNull(javaClass.getResourceAsStream(path)) { "missing resource: $path" }.readBytes().decodeToString()

    // --- format detection ---

    @Test
    fun `mockoon file is recognized`() {
        assertTrue(MockoonImport.looksLikeMockoon(resource("/mockoon/generate-mock-data.json")))
        assertTrue(MockoonImport.looksLikeMockoon(resource("/mockoon/mixed.json")))
    }

    @Test
    fun `openapi and garbage files are not mockoon`() {
        assertFalse(MockoonImport.looksLikeMockoon("""{"openapi":"3.0.0","paths":{"/a":{}}}"""))
        assertFalse(MockoonImport.looksLikeMockoon("""{"swagger":"2.0"}"""))
        assertFalse(MockoonImport.looksLikeMockoon("not json at all"))
        assertFalse(MockoonImport.looksLikeMockoon("""{"name":"x"}"""))
    }

    // --- synthetic mixed file: every skip/report path ---

    @Test
    fun `environment-wide name port prefix latency`() {
        val preview = preview(resource("/mockoon/mixed.json"))
        assertEquals("Mixed", preview.title)
        assertEquals("/api", preview.prefix)
        assertEquals(25, preview.defaultLatencyMs)
    }

    @Test
    fun `only http routes are imported`() {
        val preview = preview(resource("/mockoon/mixed.json"))
        // http: widgets, login, now, file. Skipped: crud, ws, connect(method).
        assertEquals(
            listOf("/widgets/:id", "/login", "/now", "/file"),
            preview.routes.map { it.path },
        )
    }

    @Test
    fun `crud ws and unsupported method are reported`() {
        val issues = preview(resource("/mockoon/mixed.json")).issues
        assertTrue(issues.any { "CRUD" in it.reason }, issues.toString())
        assertTrue(issues.any { "WebSocket" in it.reason }, issues.toString())
        assertTrue(issues.any { "connect" in it.reason && "method" in it.reason }, issues.toString())
    }

    @Test
    fun `supported rules are imported and others skipped`() {
        val widgets = preview(resource("/mockoon/mixed.json")).routes.single { it.path == "/widgets/:id" }

        // First response: the query=full match arrives as a single rule.
        val full = widgets.responses.single { it.statusCode == 200 }
        val rule = full.rules.single()
        assertEquals(RuleTarget.QUERY, rule.target)
        assertEquals("mode", rule.key)
        assertEquals(RuleOperator.EQUALS, rule.operator)

        // Second response: cookie and array_includes drop out, regex_i survives (REGEX).
        val denied = widgets.responses.single { it.statusCode == 404 }
        val survivor = denied.rules.single()
        assertEquals(RuleTarget.BODY, survivor.target)
        assertEquals(RuleOperator.REGEX, survivor.operator)

        val issues = preview(resource("/mockoon/mixed.json")).issues
        assertTrue(issues.any { "target 'cookie'" in it.reason }, issues.toString())
        assertTrue(issues.any { "operator 'array_includes'" in it.reason }, issues.toString())
        assertTrue(issues.any { "case-insensitive" in it.reason }, issues.toString())
    }

    @Test
    fun `header with an empty key is dropped`() {
        val full = preview(resource("/mockoon/mixed.json"))
            .routes.single { it.path == "/widgets/:id" }
            .responses.single { it.statusCode == 200 }
        assertEquals(listOf("Content-Type"), full.headers.map { it.key })
    }

    @Test
    fun `a single default response is kept`() {
        val widgets = preview(resource("/mockoon/mixed.json")).routes.single { it.path == "/widgets/:id" }
        assertEquals(1, widgets.responses.count { it.isDefault })
        assertEquals(200, widgets.responses.single { it.isDefault }.statusCode)
    }

    @Test
    fun `response mode is dropped and reported`() {
        val issues = preview(resource("/mockoon/mixed.json")).issues
        assertTrue(issues.any { "SEQUENTIAL" in it.reason }, issues.toString())
    }

    @Test
    fun `unsupported templates are reported per route but known templates are not`() {
        val issues = preview(resource("/mockoon/mixed.json")).issues
        // faker → warning; {{now}} is recognized by us → no warning.
        assertTrue(issues.any { it.location == "POST /login" && "template" in it.reason }, issues.toString())
        assertFalse(issues.any { it.location == "GET /now" }, issues.toString())
    }

    @Test
    fun `external file body is imported empty and reported`() {
        val preview = preview(resource("/mockoon/mixed.json"))
        val file = preview.routes.single { it.path == "/file" }
        assertEquals("", file.responses.single().body)
        assertTrue(preview.issues.any { "external file" in it.reason }, preview.issues.toString())
    }

    @Test
    fun `global headers cors and proxy are reported as not imported`() {
        val issues = preview(resource("/mockoon/mixed.json")).issues
        assertTrue(issues.any { "Environment-level default headers" in it.reason }, issues.toString())
        assertTrue(issues.any { "CORS" in it.reason }, issues.toString())
        assertTrue(issues.any { "Proxy" in it.reason }, issues.toString())
    }

    @Test
    fun `a file without routes fails loudly`() {
        val result = MockoonImport.parse("""{"lastMigration":29,"name":"Empty","routes":[]}""")
        assertTrue(result is ImportResult.Failure, "expected a failure: $result")
        assertTrue("no routes" in result.message)
    }

    // --- real-world file (Mockoon tutorial example) ---

    @Test
    fun `real mockoon example is imported`() {
        val preview = preview(resource("/mockoon/generate-mock-data.json"))

        assertEquals("Tutorial - Generate mock data", preview.title)
        assertEquals("", preview.prefix)

        assertEquals(
            listOf("/posts", "/geoData", "/products", "/users", "/inventory/:type"),
            preview.routes.map { it.path },
        )
        assertEquals(
            listOf(HttpMethod.GET, HttpMethod.GET, HttpMethod.GET, HttpMethod.POST, HttpMethod.GET),
            preview.routes.map { it.method },
        )

        // The real file uses faker throughout → every route should get a template warning.
        assertTrue(preview.issues.any { it.location == "GET /posts" && "template" in it.reason }, preview.issues.toString())
        // The global Content-Type header and CORS should be reported as not imported.
        assertTrue(preview.issues.any { "Environment-level default headers" in it.reason }, preview.issues.toString())
        assertTrue(preview.issues.any { "CORS" in it.reason }, preview.issues.toString())

        // geoData's text/csv header must be kept.
        val geo = preview.routes.single { it.path == "/geoData" }
        assertEquals("text/csv", geo.responses.single().headers.single { it.key == "Content-Type" }.value)
    }
}
