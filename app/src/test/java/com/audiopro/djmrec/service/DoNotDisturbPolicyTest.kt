package com.audiopro.djmrec.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The whole point of this policy is what it *declines* to do, so most of these tests assert that
 * nothing happens. Getting any of them wrong means the app quietly changes a system-wide setting
 * the user chose, or fails to put its own change back.
 */
class DoNotDisturbPolicyTest {

    // Mirror of NotificationManager's constants; the policy never sees the real ones.
    private val all = 1
    private val priority = 2
    private val none = 3
    private val alarms = 4

    private fun policy() = DoNotDisturbPolicy(silentFilter = alarms, unrestrictedFilter = all)

    @Test
    fun `silences the phone for a recording and puts it back afterwards`() {
        val policy = policy()
        assertEquals(alarms, policy.onRecordingStarted(enabled = true, accessGranted = true, currentFilter = all))
        assertTrue(policy.isEngaged)
        assertEquals(all, policy.onRecordingStopped(accessGranted = true, currentFilter = alarms))
        assertFalse(policy.isEngaged)
    }

    @Test
    fun `does nothing when the setting is off`() {
        assertNull(policy().onRecordingStarted(enabled = false, accessGranted = true, currentFilter = all))
    }

    @Test
    fun `does nothing without Do Not Disturb access`() {
        assertNull(policy().onRecordingStarted(enabled = true, accessGranted = false, currentFilter = all))
    }

    @Test
    fun `leaves a Do Not Disturb mode the user set alone`() {
        // The user's own setting may be stricter than ours, and it is not ours to replace. It also
        // must not be "restored" to unrestricted when the recording ends.
        listOf(priority, none, alarms).forEach { existing ->
            val policy = policy()
            assertNull(policy.onRecordingStarted(enabled = true, accessGranted = true, currentFilter = existing))
            assertFalse(policy.isEngaged)
            assertNull(policy.onRecordingStopped(accessGranted = true, currentFilter = existing))
        }
    }

    @Test
    fun `engaging twice does not flap the filter`() {
        // Recording state is re-emitted on pause, resume and re-delivered service intents.
        val policy = policy()
        assertEquals(alarms, policy.onRecordingStarted(enabled = true, accessGranted = true, currentFilter = all))
        assertNull(policy.onRecordingStarted(enabled = true, accessGranted = true, currentFilter = alarms))
        assertNull(policy.onRecordingStarted(enabled = true, accessGranted = true, currentFilter = alarms))
    }

    @Test
    fun `releasing without having engaged does nothing`() {
        val policy = policy()
        assertNull(policy.onRecordingStopped(accessGranted = true, currentFilter = all))
        // Not even when the phone happens to be silenced -- that silence is not ours.
        assertNull(policy.onRecordingStopped(accessGranted = true, currentFilter = none))
    }

    @Test
    fun `does not undo a change somebody else made during the recording`() {
        val policy = policy()
        policy.onRecordingStarted(enabled = true, accessGranted = true, currentFilter = all)
        // The user tightened it to total silence mid-set; that is now their setting, not ours.
        assertNull(policy.onRecordingStopped(accessGranted = true, currentFilter = none))
        assertFalse(policy.isEngaged)
    }

    @Test
    fun `losing access mid-recording clears the engaged flag without touching the phone`() {
        val policy = policy()
        policy.onRecordingStarted(enabled = true, accessGranted = true, currentFilter = all)
        assertNull(policy.onRecordingStopped(accessGranted = false, currentFilter = alarms))
        assertFalse(policy.isEngaged)
        // A later recording may engage again once access is back.
        assertEquals(alarms, policy.onRecordingStarted(enabled = true, accessGranted = true, currentFilter = all))
    }

    @Test
    fun `a second recording silences the phone again`() {
        val policy = policy()
        policy.onRecordingStarted(enabled = true, accessGranted = true, currentFilter = all)
        policy.onRecordingStopped(accessGranted = true, currentFilter = alarms)
        assertEquals(alarms, policy.onRecordingStarted(enabled = true, accessGranted = true, currentFilter = all))
    }
}
