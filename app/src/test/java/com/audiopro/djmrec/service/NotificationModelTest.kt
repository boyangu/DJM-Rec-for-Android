package com.audiopro.djmrec.service

import com.audiopro.djmrec.service.NotificationModel.Text
import com.audiopro.djmrec.service.NotificationModel.Title
import kotlin.test.*

class NotificationModelTest {
    private fun model(
        recording: Boolean = false,
        paused: Boolean = false,
        saving: Boolean = false,
        elapsedMillis: Long = 0,
        signal: Boolean = false,
    ) = NotificationModel.from(recording, paused, saving, elapsedMillis, signal, "DDJ-FLX10")

    @Test fun monitoringNamesTheMixerAndOffersStopAndClose() {
        val monitoring = model(signal = true)
        assertEquals(Title.Connected("DDJ-FLX10"), monitoring.title)
        assertEquals(Text.SignalReady, monitoring.text)
        assertFalse(monitoring.recording)
        assertEquals(Text.WaitingForSignal, model().text)
    }

    @Test fun recordingShowsElapsedAndSignal() {
        val recording = model(recording = true, elapsedMillis = 83_000, signal = true)
        assertEquals(Title.Recording("DDJ-FLX10"), recording.title)
        assertEquals(Text.Elapsed("01:23", signal = true), recording.text)
        assertTrue(recording.recording)
        assertFalse(recording.paused)
    }

    @Test fun pausedHidesTheSignalAndOffersResume() {
        val paused = model(paused = true, elapsedMillis = 5_000, signal = true)
        assertEquals(Title.Paused, paused.title)
        assertEquals(Text.Elapsed("00:05", signal = false), paused.text)
        assertTrue(paused.recording)
        assertTrue(paused.paused)
    }

    @Test fun savingOverridesTheTitle() {
        assertEquals(Title.Saving, model(recording = true, saving = true).title)
        assertEquals(Title.Saving, model(saving = true).title)
    }

    @Test fun longSetsShowHours() {
        assertEquals("00:00", NotificationModel.formatElapsed(999))
        assertEquals("59:59", NotificationModel.formatElapsed(3_599_000))
        assertEquals("01:00:00", NotificationModel.formatElapsed(3_600_000))
        assertEquals("05:12:07", NotificationModel.formatElapsed((5 * 3600 + 12 * 60 + 7) * 1000L))
    }
}
