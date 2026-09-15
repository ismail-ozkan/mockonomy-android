package com.mockonomy.ui.screens.environmentlist

import com.mockonomy.server.ServerLauncher

/** Answers "was it started/stopped, and with which id" without ever triggering the real service. */
class FakeServerLauncher : ServerLauncher {
    val startedIds = mutableListOf<String>()
    val stoppedIds = mutableListOf<String>()
    var stopAllCallCount = 0
        private set

    override fun start(environmentId: String) {
        startedIds.add(environmentId)
    }

    override fun stop(environmentId: String) {
        stoppedIds.add(environmentId)
    }

    override fun stopAll() {
        stopAllCallCount++
    }
}
