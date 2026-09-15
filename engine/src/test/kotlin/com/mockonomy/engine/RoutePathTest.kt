package com.mockonomy.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RoutePathTest {

    @Test
    fun `plain path is valid`() {
        assertNull(RoutePath.validate("/users"))
    }

    @Test
    fun `root path is valid`() {
        assertNull(RoutePath.validate("/"))
    }

    @Test
    fun `parameterized path is valid`() {
        assertNull(RoutePath.validate("/users/:id/posts/:postId"))
    }

    @Test
    fun `trailing slash is valid - matching ignores it too`() {
        assertNull(RoutePath.validate("/users/"))
    }

    @Test
    fun `empty path`() {
        assertEquals(PathProblem.Empty, RoutePath.validate(""))
    }

    @Test
    fun `missing leading slash`() {
        assertEquals(PathProblem.MissingLeadingSlash, RoutePath.validate("users"))
    }

    @Test
    fun `path containing whitespace`() {
        assertEquals(PathProblem.ContainsWhitespace, RoutePath.validate("/user list"))
    }

    @Test
    fun `empty segment in the middle`() {
        assertEquals(PathProblem.EmptySegment, RoutePath.validate("/users//posts"))
    }

    @Test
    fun `parameter without a name`() {
        assertEquals(PathProblem.EmptyParameterName, RoutePath.validate("/users/:"))
    }

    @Test
    fun `parameter name with invalid characters`() {
        assertEquals(PathProblem.InvalidParameterName, RoutePath.validate("/users/:id-x"))
    }

    @Test
    fun `same parameter name twice`() {
        assertEquals(PathProblem.DuplicateParameterName, RoutePath.validate("/users/:id/posts/:id"))
    }

    @Test
    fun `query string is not part of the path`() {
        assertEquals(PathProblem.InvalidParameterName, RoutePath.validate("/users?active=true"))
    }

    @Test
    fun `colon in the middle of a segment is invalid`() {
        assertEquals(PathProblem.InvalidParameterName, RoutePath.validate("/users/id:x"))
    }
}
