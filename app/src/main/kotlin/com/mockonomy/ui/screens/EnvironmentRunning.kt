package com.mockonomy.ui.screens

import com.mockonomy.server.MockServerController
import com.mockonomy.server.ServerLauncher

/**
 * Stops the environment if it is running, starts it otherwise — **only this environment**.
 * Several environments can run side by side. Both the row button on the list screen and
 * Start/Stop in the environment detail app bar must behave the same way — the rule lives
 * here in a single copy.
 *
 * The decision is read directly from [MockServerController]: it is the single owner of the
 * set of running servers, and the `StateFlow` ViewModels keep for observation does not stay
 * current without a live collector.
 */
fun toggleEnvironmentRunning(
    id: String,
    serverController: MockServerController,
    serverLauncher: ServerLauncher,
) {
    if (serverController.isRunning(id)) serverLauncher.stop(id) else serverLauncher.start(id)
}
