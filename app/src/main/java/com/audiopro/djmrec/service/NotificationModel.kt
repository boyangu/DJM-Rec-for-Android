package com.audiopro.djmrec.service

import java.util.Locale

/**
 * What the recording notification says and offers, decided without Android so it can be tested.
 * [RecordingNotifications] turns it into a real notification.
 */
data class NotificationModel(
    val title: Title,
    val text: Text,
    /** Recording or paused: offer pause/resume and "Save & close" instead of "Stop & close". */
    val recording: Boolean,
    val paused: Boolean,
) {
    sealed interface Title {
        data object Saving : Title
        data object Paused : Title
        data class Recording(val deviceLabel: String) : Title
        data class Connected(val deviceLabel: String) : Title
    }

    sealed interface Text {
        data class Elapsed(val elapsed: String, val signal: Boolean) : Text
        data object SignalReady : Text
        data object WaitingForSignal : Text
    }

    companion object {
        fun from(
            recording: Boolean,
            paused: Boolean,
            saving: Boolean,
            elapsedMillis: Long,
            signalPresent: Boolean,
            deviceLabel: String,
        ): NotificationModel {
            val active = recording || paused
            val title = when {
                saving -> Title.Saving
                paused -> Title.Paused
                active -> Title.Recording(deviceLabel)
                else -> Title.Connected(deviceLabel)
            }
            val text = when {
                active -> Text.Elapsed(formatElapsed(elapsedMillis), signalPresent && !paused)
                signalPresent -> Text.SignalReady
                else -> Text.WaitingForSignal
            }
            return NotificationModel(title, text, recording = active, paused = paused)
        }

        fun formatElapsed(millis: Long): String {
            val totalSeconds = millis / 1000
            val hours = totalSeconds / 3600
            val minutes = (totalSeconds % 3600) / 60
            val seconds = totalSeconds % 60
            return if (hours > 0) {
                String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
            } else {
                String.format(Locale.US, "%02d:%02d", minutes, seconds)
            }
        }
    }
}
