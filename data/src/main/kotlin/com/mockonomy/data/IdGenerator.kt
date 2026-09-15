package com.mockonomy.data

import kotlin.random.Random

/**
 * `spec/schema/environment-v1.md`: "a UUID is recommended but not required" —
 * `java.util.UUID` is deliberately not used here; like `:engine`, `:data` follows the
 * Kotlin Multiplatform readiness rules. Kotlin stdlib's [Random] is enough.
 *
 * Visibility is `public`: [EnvironmentStore] deliberately has no `create()` method —
 * creating a new environment works by the caller (e.g. a ViewModel) using this
 * generator and passing the result to [EnvironmentStore.save].
 */
object IdGenerator {
    private const val HEX_CHARS = "0123456789abcdef"

    fun generate(prefix: String): String = "$prefix-${randomHex(16)}"

    private fun randomHex(length: Int): String {
        val bytes = Random.nextBytes((length + 1) / 2)
        return buildString(length) {
            for (byte in bytes) {
                val value = byte.toInt() and 0xFF
                append(HEX_CHARS[value ushr 4])
                append(HEX_CHARS[value and 0x0F])
            }
        }.take(length)
    }
}
