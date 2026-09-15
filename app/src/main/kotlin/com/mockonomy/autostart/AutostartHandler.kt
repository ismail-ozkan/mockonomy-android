package com.mockonomy.autostart

import com.mockonomy.AppReadiness
import com.mockonomy.data.EnvironmentStore
import com.mockonomy.data.EnvironmentSummary
import com.mockonomy.server.MockServerController
import com.mockonomy.server.ServerLauncher
import javax.inject.Inject
import javax.inject.Singleton

/**
 * When the app is started with `am start -n com.mockonomy/.MainActivity --es env <key>`,
 * activates the given environment and starts the server — for CI and ADB automation.
 *
 * Since MainActivity is in the foreground, `startForegroundService` from here is not hit
 * by the Android 12+ background service restriction. The environment is found by `key`
 * or `id`; if it is not found it is silently ignored (the CI script will see the error in
 * its broadcast/HTTP checks anyway).
 *
 * Idempotent for the same environment that is already running — bringing MainActivity
 * back (`onNewIntent`) does not restart the server. With multiple servers it does not stop
 * the others: it **adds** the given environment.
 */
@Singleton
class AutostartHandler @Inject constructor(
    private val environmentStore: EnvironmentStore,
    private val serverController: MockServerController,
    private val serverLauncher: ServerLauncher,
    private val readiness: AppReadiness,
) {

    suspend fun handle(envKey: String) {
        // On first launch seeding the example environment may not be done yet; wait until
        // ready — otherwise list() returns empty and the CI script fails.
        while (!readiness.isReady) kotlinx.coroutines.delay(READY_POLL_MS)

        val target = environmentStore.list()
            .filterIsInstance<EnvironmentSummary.Valid>()
            .firstOrNull { it.environment.key == envKey || it.environment.id == envKey }
            ?.environment ?: return

        if (serverController.isRunning(target.id)) return
        serverLauncher.start(target.id)
    }

    private companion object {
        const val READY_POLL_MS = 50L
    }
}
