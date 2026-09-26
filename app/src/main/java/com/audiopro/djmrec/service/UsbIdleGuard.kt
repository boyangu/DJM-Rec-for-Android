package com.audiopro.djmrec.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.SystemClock
import android.util.Log

/**
 * Keeps the phone out of its deepest idle states while raw USB capture runs, by holding a silent
 * low-latency output stream open on the built-in speaker.
 *
 * Why: on a Sony XQ-EC72 with a DDJ-FLX10 (2026-09-26) the USB host controller lost 0.96
 * isochronous packets a second with the screen on and 6.1 a second once the battery saver screen
 * had dimmed it, every one a bus-level error and the capture thread never late. That is the SoC's
 * low-power states pushing DMA latency past the 125 us service interval (an xHCI "missed service
 * error"). Android has no public latency-QoS API, but the audio HAL holds one whenever a
 * low-latency stream is active, which is what keeps AAudio-based recorders steady. This borrows it.
 *
 * Always on for raw USB capture. Confirmed on the same phone: 219 s of capture, 2:47 of it on the
 * battery saver screen, with zero lost packets; and the moment it was switched off in an earlier
 * run the losses came back at 3.7 a second.
 *
 * Everything the track does happens on its own thread: [start] and [stop] only flip a flag, so a
 * stalled audio system can never hold up the service's main thread. The track is pinned to the
 * built-in speaker and not a single byte is written until Android confirms that is where it
 * landed; on any other route, or a USB one in particular (the mixer's playback interface belongs
 * to the libusb session), it is released untouched.
 */
class UsbIdleGuard(private val context: Context) {
    @Volatile private var worker: Thread? = null

    @Synchronized
    fun start() {
        if (worker != null) return
        worker = Thread({ run() }, "usb-idle-guard").apply { isDaemon = true; start() }
    }

    @Synchronized
    fun stop() {
        val running = worker ?: return
        worker = null
        running.interrupt()
    }

    private fun stillWanted() = worker === Thread.currentThread() && !Thread.currentThread().isInterrupted

    private fun run() {
        val audioManager = context.getSystemService(AudioManager::class.java) ?: return
        val speaker = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        if (speaker == null) {
            status("not started: no built-in speaker to pin to")
            return
        }
        val minBytes = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val track = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .setBufferSizeInBytes(maxOf(minBytes, SAMPLE_RATE * FRAME_BYTES / 20))
                .build()
        }.getOrElse { error ->
            status("not started: could not create the track: ${error.message}")
            return
        }
        try {
            if (track.state != AudioTrack.STATE_INITIALIZED) {
                status("not started: track failed to initialise")
                return
            }
            if (!track.setPreferredDevice(speaker)) {
                status("not started: could not pin the track to the speaker")
                return
            }
            track.setVolume(0f)
            track.play()

            // Routing is decided at play(). Refuse to feed the track until Android says it went
            // to the speaker; a wrong route is released without a single write.
            val deadline = SystemClock.uptimeMillis() + ROUTE_WAIT_MS
            var routed: AudioDeviceInfo? = null
            while (routed == null && SystemClock.uptimeMillis() < deadline && stillWanted()) {
                routed = track.routedDevice
                if (routed == null) Thread.sleep(20)
            }
            if (routed == null || !routeAcceptable(routed.type)) {
                status("not started: routed to ${routed?.productName ?: "nowhere"} (type ${routed?.type})")
                return
            }
            status("playing silence to ${routed.productName} (type ${routed.type}), " +
                "performance mode ${track.performanceMode}, buffer ${track.bufferSizeInFrames} frames")

            // Non-blocking writes, so the loop always notices stop() within a few milliseconds
            // even if the output stalls.
            val zeros = ByteArray(SAMPLE_RATE * FRAME_BYTES / 100) // 10 ms
            while (stillWanted()) {
                val written = track.write(zeros, 0, zeros.size, AudioTrack.WRITE_NON_BLOCKING)
                if (written < 0) {
                    status("stopped: write failed ($written)")
                    return
                }
                Thread.sleep(if (written < zeros.size) 5 else 1)
            }
        } catch (_: InterruptedException) {
            // stop() asked us to go.
        } finally {
            runCatching { track.pause(); track.flush(); track.stop() }
            track.release()
            if (!lastStatus.startsWith("not started") && !lastStatus.startsWith("stopped")) status("stopped")
        }
    }

    private fun status(text: String) {
        lastStatus = text
        if (text.startsWith("playing") || text == "stopped") Log.i(TAG, "USB idle guard: $text")
        else Log.w(TAG, "USB idle guard: $text")
    }

    companion object {
        private const val TAG = "UsbIdleGuard"

        /** What the guard last did, for the diagnostic report; logcat forgets within a minute. */
        @Volatile var lastStatus: String = "never started"
            private set
        private const val SAMPLE_RATE = 48_000
        private const val FRAME_BYTES = 4 // 16-bit stereo
        private const val ROUTE_WAIT_MS = 1_000L

        /** False for any USB output: the mixer's playback interface belongs to the libusb session. */
        fun routeAcceptable(deviceType: Int): Boolean = deviceType !in setOf(
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY, AudioDeviceInfo.TYPE_USB_HEADSET
        )
    }
}
