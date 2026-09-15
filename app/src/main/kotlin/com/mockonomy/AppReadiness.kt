package com.mockonomy

import javax.inject.Inject
import javax.inject.Singleton

/**
 * The "the first screen can be drawn with real data" signal — it decides when the splash
 * screen goes away (see [MainActivity]).
 *
 * Why a separate type: the environment list is read from disk, and until the read
 * finished the first frame was drawn with an **empty** list; the user briefly saw the
 * "No environments yet" empty state flash by. The signal comes from the side that already
 * reads the list — having [MainActivity] do its own read would mean reading the same file
 * twice on every launch.
 *
 * One-way: it becomes `true` once and never goes back. There is no case where the splash
 * should reappear.
 */
@Singleton
class AppReadiness @Inject constructor() {

    @Volatile
    var isReady: Boolean = false
        private set

    fun markReady() {
        isReady = true
    }
}
