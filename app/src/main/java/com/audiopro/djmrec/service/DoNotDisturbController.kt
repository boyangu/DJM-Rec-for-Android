package com.audiopro.djmrec.service

import android.app.NotificationManager
import android.content.Context
import android.util.Log

/**
 * Silences calls and notifications for the length of a recording.
 *
 * The filter used is ALARMS rather than NONE: an incoming call is the thing that actually ruins a
 * set -- Android hands audio focus to telephony and a full-screen call UI lands on top of the
 * transport controls -- while an alarm the user deliberately set is not ours to swallow.
 *
 * All the decisions live in [DoNotDisturbPolicy]; this is just the Android glue. Every call into
 * the platform is guarded, because notification policy access can be revoked at any moment and a
 * SecurityException raised here would take down a recording that is otherwise running perfectly.
 */
class DoNotDisturbController(private val context: Context) {

    private val policy = DoNotDisturbPolicy(
        silentFilter = NotificationManager.INTERRUPTION_FILTER_ALARMS,
        unrestrictedFilter = NotificationManager.INTERRUPTION_FILTER_ALL,
    )

    private val manager: NotificationManager?
        get() = runCatching { context.getSystemService(NotificationManager::class.java) }.getOrNull()

    /** True once the user has granted Do Not Disturb access in Android settings. */
    fun isAccessGranted(): Boolean =
        runCatching { manager?.isNotificationPolicyAccessGranted == true }.getOrDefault(false)

    /** True while the phone is silenced by this app, so the UI can say so. */
    val isEngaged: Boolean get() = policy.isEngaged

    fun engage(enabled: Boolean) {
        val target = policy.onRecordingStarted(enabled, isAccessGranted(), readFilter()) ?: return
        applyFilter(target, "engaged for recording")
    }

    fun release() {
        val target = policy.onRecordingStopped(isAccessGranted(), readFilter()) ?: return
        applyFilter(target, "restored after recording")
    }

    /**
     * UNKNOWN on failure is the useful answer rather than a guess: it matches neither the
     * unrestricted filter (so we decline to engage) nor anything we could have applied (so we
     * decline to restore), which is the conservative outcome in both directions.
     */
    private fun readFilter(): Int = runCatching {
        manager?.currentInterruptionFilter ?: NotificationManager.INTERRUPTION_FILTER_UNKNOWN
    }.getOrDefault(NotificationManager.INTERRUPTION_FILTER_UNKNOWN)

    private fun applyFilter(filter: Int, reason: String) {
        val manager = manager ?: return
        runCatching { manager.setInterruptionFilter(filter) }
            .onSuccess { Log.i(TAG, "Do Not Disturb $reason (filter=$filter)") }
            .onFailure { Log.w(TAG, "Could not change Do Not Disturb ($reason)", it) }
    }

    private companion object {
        const val TAG = "DoNotDisturb"
    }
}
