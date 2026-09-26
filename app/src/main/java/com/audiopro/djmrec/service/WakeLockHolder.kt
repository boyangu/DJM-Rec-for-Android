package com.audiopro.djmrec.service

import android.os.PowerManager
import java.util.concurrent.TimeUnit

/** The partial wake lock that keeps capture running with the screen off. */
class WakeLockHolder(private val powerManager: PowerManager) {
    private var lock: PowerManager.WakeLock? = null

    /**
     * Acquires the lock, or pushes its safety timeout out again if already held. It is not
     * reference-counted, and the health tick calls this for the whole session, so the 6 h
     * ceiling never expires under a long set while the service lives; if the process dies the
     * lock dies with it.
     */
    @Synchronized
    fun renew() {
        val held = lock ?: powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "djmrec:recording")
            .apply { setReferenceCounted(false) }
            .also { lock = it }
        held.acquire(TIMEOUT_MS)
    }

    @Synchronized
    fun release() {
        lock?.let { if (it.isHeld) it.release() }
        lock = null
    }

    private companion object {
        val TIMEOUT_MS = TimeUnit.HOURS.toMillis(6)
    }
}
