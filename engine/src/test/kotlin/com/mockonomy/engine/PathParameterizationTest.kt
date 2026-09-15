package com.mockonomy.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Path parameterization suggestions (`spec/behavior/path-parameterization.md`).
 */
class PathParameterizationTest {

    @Test
    fun `numeric segment is parameterized with a name derived from the previous segment`() {
        val result = PathParameterization.suggest("/users/42")

        assertEquals("/users/:userId", result.suggestedPath)
        assertEquals(1, result.parameters.size)
        val param = result.parameters.single()
        assertEquals("userId", param.name)
        assertEquals("42", param.value)
        assertEquals(1, param.segmentIndex)
    }

    @Test
    fun `UUID segment is parameterized`() {
        val result = PathParameterization.suggest("/orders/7c9e4f1a-1234-4abc-8def-1234567890ab")

        assertEquals("/orders/:orderId", result.suggestedPath)
        assertEquals("orderId", result.parameters.single().name)
    }

    @Test
    fun `multiple parameters are named separately`() {
        val result =
            PathParameterization.suggest("/api/v1/users/42/orders/7c9e4f1a-1234-4abc-8def-1234567890ab")

        assertEquals("/api/v1/users/:userId/orders/:orderId", result.suggestedPath)
        assertEquals(listOf("userId", "orderId"), result.parameters.map { it.name })
        assertEquals(listOf("42", "7c9e4f1a-1234-4abc-8def-1234567890ab"), result.parameters.map { it.value })
    }

    @Test
    fun `word segments are not parameterized - narrow detection`() {
        val result = PathParameterization.suggest("/users/me/settings")

        assertEquals("/users/me/settings", result.suggestedPath)
        assertTrue(result.parameters.isEmpty())
    }

    @Test
    fun `path without parameters is returned unchanged`() {
        val result = PathParameterization.suggest("/health")

        assertEquals("/health", result.suggestedPath)
        assertTrue(result.parameters.isEmpty())
    }

    @Test
    fun `name is id when there is no preceding static segment`() {
        val result = PathParameterization.suggest("/42")

        assertEquals("/:id", result.suggestedPath)
        assertEquals("id", result.parameters.single().name)
    }

    @Test
    fun `two parameters with the same base get non-conflicting names`() {
        // /users/1/users/2 → :userId and :userId2
        val result = PathParameterization.suggest("/users/1/users/2")

        assertEquals("/users/:userId/users/:userId2", result.suggestedPath)
        assertEquals(listOf("userId", "userId2"), result.parameters.map { it.name })
    }

    @Test
    fun `an already parameterized segment is kept`() {
        val result = PathParameterization.suggest("/users/:id/orders/9")

        assertEquals("/users/:id/orders/:orderId", result.suggestedPath)
        assertEquals(listOf("orderId"), result.parameters.map { it.name })
    }
}
