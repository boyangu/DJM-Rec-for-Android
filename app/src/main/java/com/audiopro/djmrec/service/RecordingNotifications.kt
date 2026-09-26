package com.audiopro.djmrec.service

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.audiopro.djmrec.MainActivity
import com.audiopro.djmrec.R

/** The recording service's one ongoing notification: channel, foreground promotion, updates. */
class RecordingNotifications(private val service: Service) {

    fun createChannel() {
        val channel = NotificationChannelCompat.Builder(CHANNEL_ID, android.app.NotificationManager.IMPORTANCE_LOW)
            .setName(service.getString(R.string.notification_channel_name))
            .setDescription(service.getString(R.string.notification_channel_desc))
            .setShowBadge(false)
            .build()
        NotificationManagerCompat.from(service).createNotificationChannel(channel)
    }

    /**
     * Returns false instead of crashing when the OS refuses foreground promotion. On Android 14+
     * a microphone-type foreground service also needs the app to have been interacted with
     * recently, which a device-attach auto-start can miss; the refusal is a SecurityException
     * from `startForeground()` itself, after the caller's start already succeeded.
     */
    fun startForeground(model: NotificationModel, usbIso: Boolean): Boolean {
        // minSdk is 29 (Q), so the typed overload is always available.
        val foregroundType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (usbIso) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            else ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            0
        }
        return try {
            ServiceCompat.startForeground(service, NOTIFICATION_ID, build(model), foregroundType)
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "startForeground refused by the OS: ${e.message}")
            false
        } catch (e: IllegalStateException) {
            Log.w(TAG, "startForeground refused by the OS: ${e.message}")
            false
        }
    }

    fun update(model: NotificationModel) {
        val canNotify = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(service, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (canNotify) {
            NotificationManagerCompat.from(service).notify(NOTIFICATION_ID, build(model))
        }
    }

    fun stopForeground() {
        ServiceCompat.stopForeground(service, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    private fun build(model: NotificationModel): Notification {
        val title = when (val t = model.title) {
            NotificationModel.Title.Saving -> "Saving your set..."
            NotificationModel.Title.Paused -> service.getString(R.string.notification_title_paused)
            is NotificationModel.Title.Recording -> service.getString(R.string.notification_title_recording, t.deviceLabel)
            is NotificationModel.Title.Connected -> "${t.deviceLabel} connected"
        }
        val text = when (val t = model.text) {
            is NotificationModel.Text.Elapsed ->
                service.getString(R.string.notification_text_elapsed, t.elapsed) + if (t.signal) " | signal" else ""
            NotificationModel.Text.SignalReady -> "USB signal ready"
            NotificationModel.Text.WaitingForSignal -> "Waiting for mixer signal"
        }
        val contentIntent = PendingIntent.getActivity(
            service, 0, Intent(service, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(service, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        if (model.recording) {
            builder.addAction(
                if (model.paused) {
                    NotificationCompat.Action(
                        android.R.drawable.ic_media_play, service.getString(R.string.action_resume),
                        servicePendingIntent(RecordingService.ACTION_RESUME)
                    )
                } else {
                    NotificationCompat.Action(
                        android.R.drawable.ic_media_pause, service.getString(R.string.action_pause),
                        servicePendingIntent(RecordingService.ACTION_PAUSE)
                    )
                }
            )
        }
        builder.addAction(
            NotificationCompat.Action(
                android.R.drawable.ic_menu_close_clear_cancel,
                if (model.recording) "Save & close" else "Stop & close",
                servicePendingIntent(RecordingService.ACTION_STOP_ALL)
            )
        )
        return builder.build()
    }

    private fun servicePendingIntent(action: String): PendingIntent {
        val intent = Intent(service, RecordingService::class.java).setAction(action)
        return PendingIntent.getService(
            service, action.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private companion object {
        const val TAG = "RecordingNotifications"
        const val CHANNEL_ID = "recording_channel"
        const val NOTIFICATION_ID = 1001
    }
}
