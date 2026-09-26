package com.audiopro.djmrec.service

import com.audiopro.djmrec.domain.CaptureSessionParams
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Carries a [CaptureSessionParams] from whoever starts the recording service to its
 * `onStartCommand`, which only receives the id in the Intent.
 *
 * In-process on purpose: the USB fd inside the params is only valid in this process, and the
 * service is not sticky, so no start Intent is ever redelivered after process death.
 */
class CaptureSessionHandoff {
    private val nextId = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, CaptureSessionParams>()

    /** Stores [params] until [take]n and returns the id to put in the start Intent. */
    fun offer(params: CaptureSessionParams): Long {
        val id = nextId.getAndIncrement()
        pending[id] = params
        return id
    }

    /** Removes and returns the params offered under [id], or null if already taken or dropped. */
    fun take(id: Long): CaptureSessionParams? = pending.remove(id)

    /** Forgets [id] without using it, e.g. when the Intent carrying it could not be sent. */
    fun drop(id: Long) {
        pending.remove(id)
    }

    companion object {
        const val NO_ID = 0L
    }
}
