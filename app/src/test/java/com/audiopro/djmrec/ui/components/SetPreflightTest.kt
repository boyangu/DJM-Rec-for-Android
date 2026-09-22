package com.audiopro.djmrec.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SetPreflightTest {

    private fun checks(
        doNotDisturbWanted: Boolean = true,
        doNotDisturbAccessGranted: Boolean = false,
        batteryUnrestricted: Boolean = false,
        keepScreenOn: Boolean = false,
    ) = outstandingSetChecks(
        doNotDisturbWanted, doNotDisturbAccessGranted, batteryUnrestricted, keepScreenOn
    )

    @Test
    fun `a phone that is fully set up is prompted for nothing`() {
        assertTrue(
            checks(doNotDisturbAccessGranted = true, batteryUnrestricted = true, keepScreenOn = true).isEmpty()
        )
    }

    @Test
    fun `turning the Do Not Disturb setting off stops the app asking for access`() {
        // Declining a feature must not leave a permanent prompt on the recorder screen.
        assertTrue(SetCheck.DoNotDisturb !in checks(doNotDisturbWanted = false))
    }

    @Test
    fun `risks are offered before conveniences`() {
        // Do Not Disturb protects a set already running; the screen setting only saves a tap.
        assertEquals(
            listOf(SetCheck.DoNotDisturb, SetCheck.BatteryUnrestricted, SetCheck.ScreenAwake),
            checks()
        )
    }

    @Test
    fun `each outstanding item is listed exactly once`() {
        val outstanding = checks()
        assertEquals(outstanding.size, outstanding.distinct().size)
    }

    @Test
    fun `a granted item drops out of the list`() {
        assertEquals(
            listOf(SetCheck.BatteryUnrestricted, SetCheck.ScreenAwake),
            checks(doNotDisturbAccessGranted = true)
        )
        assertEquals(
            listOf(SetCheck.DoNotDisturb, SetCheck.ScreenAwake),
            checks(batteryUnrestricted = true)
        )
    }

    @Test
    fun `every check has text to show`() {
        SetCheck.entries.forEach { check ->
            assertTrue(check.title.isNotBlank(), "$check has no title")
            assertTrue(check.detail.isNotBlank(), "$check has no detail")
            assertTrue(check.action.isNotBlank(), "$check has no action label")
        }
    }
}
