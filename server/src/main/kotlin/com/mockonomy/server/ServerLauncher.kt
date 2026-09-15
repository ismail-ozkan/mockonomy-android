package com.mockonomy.server

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * A thin interface for starting/stopping [MockServerService].
 *
 * Its only purpose is testability: if `EnvironmentListViewModel` injected a `Context` and
 * called `MockServerService.start/stop` directly, a pure-JVM ViewModel test could not be
 * written without the Android framework. A fake implementation of this interface answers
 * "was it started, and with which id" in tests without ever triggering the real service.
 */
interface ServerLauncher {
    fun start(environmentId: String)

    /** Stops only this environment's server; the others keep running. */
    fun stop(environmentId: String)

    /** Stops all of them — "Stop all" in the notification and the environment deletion flow. */
    fun stopAll()
}

class AndroidServerLauncher @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : ServerLauncher {
    override fun start(environmentId: String) {
        MockServerService.start(context, environmentId)
    }

    override fun stop(environmentId: String) {
        MockServerService.stop(context, environmentId)
    }

    override fun stopAll() {
        MockServerService.stop(context, environmentId = null)
    }
}
