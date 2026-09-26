package com.audiopro.djmrec.storage

import android.content.Context
import com.audiopro.djmrec.audio.AudioEngine
import com.audiopro.djmrec.audio.RecordingFormat
import com.audiopro.djmrec.audio.SavedRecording
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The file side of one recording: the MediaStore output, the crash-recovery journal, periodic
 * checkpoints and WAV part rolls, and finalizing on stop. The native encoder writes the audio;
 * this owns which file it writes to and what the journal says about it.
 *
 * Every method locks the writer. [checkpointIfDue] runs on the monitor thread and [finish] on
 * an IO thread; both do file I/O, so neither belongs on the main thread.
 */
class RecordingWriter(private val context: Context) {

    sealed interface StartResult {
        data object Started : StartResult
        data class Failed(val message: String) : StartResult
    }

    sealed interface CheckpointResult {
        data object Ok : CheckpointResult
        /** A new WAV part was started. [problem] is set if the switch was not fully journaled. */
        data class Rolled(val problem: String?) : CheckpointResult
        data class Failed(val message: String) : CheckpointResult
    }

    /** [saved] is the published file, present only when [complete]. */
    data class FinishResult(val saved: SavedRecording?, val complete: Boolean)

    private var output: PendingRecordingOutput? = null
    private var sessionId: String? = null
    private var format = RecordingFormat.WAV
    private var partIndex = 0
    private var partStartedElapsed = 0L
    private var lastCheckpointRealtime = 0L

    /** Creates the output and journal and starts the native encoder writing to it. */
    @Synchronized
    fun start(format: RecordingFormat, sampleRate: Int, bitDepth: Int, deviceLabel: String): StartResult {
        val id = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val created = RecordingOutputManager.create(context, id, format, 1)
            ?: return StartResult.Failed("Failed to create recording in Music/DJMRec")
        val journalStarted = runCatching {
            RecordingSessionStore.begin(context, id, format, sampleRate, bitDepth, deviceLabel, created.toRecord())
        }.isSuccess
        if (!journalStarted) {
            RecordingOutputManager.abandon(context, created)
            return StartResult.Failed("Failed to create crash-recovery journal")
        }
        val started = AudioEngine.startRecordingFd(created.descriptor.fd, format.nativeValue)
        runCatching { created.descriptor.close() }
        if (!started) {
            RecordingOutputManager.abandon(context, created)
            RecordingSessionStore.complete(context)
            return StartResult.Failed("Failed to start ${format.name} encoder")
        }
        output = created
        sessionId = id
        this.format = format
        partIndex = 1
        partStartedElapsed = 0L
        lastCheckpointRealtime = 0L
        return StartResult.Started
    }

    /** Undoes a [start] whose recording never got going (the OS refused foreground promotion). */
    @Synchronized
    fun abandonStart() {
        AudioEngine.stopRecording()
        output?.let { RecordingOutputManager.abandon(context, it) }
        RecordingSessionStore.complete(context)
        clear()
    }

    /** Flushes a recoverable checkpoint every [CHECKPOINT_INTERVAL_MS] and rolls long WAVs. */
    @Synchronized
    fun checkpointIfDue(nowRealtime: Long): CheckpointResult {
        if (output == null) return CheckpointResult.Ok
        if (nowRealtime - lastCheckpointRealtime < CHECKPOINT_INTERVAL_MS) return CheckpointResult.Ok
        lastCheckpointRealtime = nowRealtime
        val partBytes = AudioEngine.checkpointRecording()
        if (partBytes < 0) {
            return CheckpointResult.Failed("Could not checkpoint recording. File finalized at last safe point.")
        }
        val journalSaved = runCatching {
            RecordingSessionStore.checkpoint(context, AudioEngine.getElapsedMillis())
        }.isSuccess
        if (!journalSaved) {
            return CheckpointResult.Failed("Could not save recovery checkpoint. Recording finalized safely.")
        }
        if (format == RecordingFormat.WAV && RecordingStoragePolicy.shouldRollWav(partBytes)) {
            return rollWavPart()
        }
        return CheckpointResult.Ok
    }

    private fun rollWavPart(): CheckpointResult {
        val id = sessionId ?: return CheckpointResult.Ok
        val previous = output ?: return CheckpointResult.Ok
        val nextIndex = partIndex + 1
        val next = RecordingOutputManager.create(context, id, RecordingFormat.WAV, nextIndex)
            ?: return CheckpointResult.Failed("Could not create next WAV part. Recording finalized safely.")
        val rolled = AudioEngine.rollRecordingFd(next.descriptor.fd, RecordingFormat.WAV.nativeValue)
        runCatching { next.descriptor.close() }
        if (!rolled) {
            RecordingOutputManager.abandon(context, next)
            return CheckpointResult.Failed("Could not continue WAV recording. Current part finalized safely.")
        }

        val elapsed = AudioEngine.getElapsedMillis()
        val partJournaled = runCatching { RecordingSessionStore.addPart(context, next.toRecord()) }.isSuccess
        val previousFinalized = RecordingOutputManager.finalize(context, previous, elapsed - partStartedElapsed)
        if (previousFinalized) RecordingSessionStore.markFinalized(context, previous.uri)
        output = next
        partIndex = nextIndex
        partStartedElapsed = elapsed
        return CheckpointResult.Rolled(
            when {
                !partJournaled -> "Could not journal next WAV part. Recording stopped safely."
                !previousFinalized -> "Previous WAV part could not be published. Recording stopped safely."
                else -> null
            }
        )
    }

    /** Stores a track marker at the current position in the current part; returns the new count. */
    @Synchronized
    fun addMarker(): Int? {
        val current = output ?: return null
        return TrackMarkerStore.add(context, current.uri, AudioEngine.getElapsedMillis() - partStartedElapsed)
    }

    /**
     * Stops the encoder, publishes the current part and closes the journal. Blocks on MediaStore:
     * call it off the main thread, except from `onDestroy`, which has nowhere else to run it.
     */
    @Synchronized
    fun finish(): FinishResult {
        val current = output
        val partStart = partStartedElapsed
        val duration = AudioEngine.stopRecording()
        val finalized = try {
            current == null || RecordingOutputManager.finalize(context, current, (duration - partStart).coerceAtLeast(0))
        } catch (e: Exception) {
            false
        }
        if (finalized && current != null) runCatching { RecordingSessionStore.markFinalized(context, current.uri) }
        val complete = finalized && runCatching { RecordingSessionStore.completeIfFinalized(context) }.getOrDefault(false)
        clear()
        val saved = if (complete && current != null) {
            SavedRecording(current.uri, current.displayName, (duration - partStart).coerceAtLeast(0))
        } else {
            null
        }
        return FinishResult(saved, complete)
    }

    private fun clear() {
        output = null
        sessionId = null
        partIndex = 0
        partStartedElapsed = 0L
    }

    private companion object {
        const val CHECKPOINT_INTERVAL_MS = 5_000L
    }
}
