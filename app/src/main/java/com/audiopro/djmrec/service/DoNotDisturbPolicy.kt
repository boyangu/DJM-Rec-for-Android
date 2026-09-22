package com.audiopro.djmrec.service

/**
 * Decides when the recorder may put the phone into Do Not Disturb for the duration of a set, and
 * when it must put the setting back.
 *
 * Deliberately free of Android types: the rules here are all about *restraint*, and restraint is
 * exactly the kind of thing that is easy to get wrong and easy to unit test.
 *
 *  - Never engage when the phone is already in some Do Not Disturb mode. The user chose that, it
 *    may well be stricter than ours, and silently replacing it would be an act of vandalism.
 *  - Never restore unless the filter is still exactly the one we set. If it changed while the set
 *    was running then somebody else owns it now, and writing our remembered value back would undo
 *    their change instead of ours.
 *  - Engaging twice, or releasing without having engaged, must both do nothing. Recording state
 *    can be re-emitted (pause and resume, a re-delivered service intent) and the phone's
 *    interruption filter must not flap in response.
 */
class DoNotDisturbPolicy(
    private val silentFilter: Int,
    private val unrestrictedFilter: Int,
) {
    private var appliedFilter: Int? = null
    private var filterToRestore: Int = unrestrictedFilter

    /** True while the phone is in a Do Not Disturb mode this object turned on. */
    val isEngaged: Boolean get() = appliedFilter != null

    /**
     * @return the interruption filter to apply, or null to leave the phone exactly as it is.
     */
    fun onRecordingStarted(enabled: Boolean, accessGranted: Boolean, currentFilter: Int): Int? {
        if (appliedFilter != null) return null
        if (!enabled || !accessGranted) return null
        if (currentFilter != unrestrictedFilter) return null
        filterToRestore = currentFilter
        appliedFilter = silentFilter
        return silentFilter
    }

    /**
     * @return the interruption filter to restore, or null to leave the phone exactly as it is.
     *
     * The engaged flag is cleared either way. Losing Do Not Disturb access mid-set leaves the
     * phone silenced, because putting it back is precisely the thing we no longer have permission
     * to do; the user can clear it from the system shade like any other Do Not Disturb.
     */
    fun onRecordingStopped(accessGranted: Boolean, currentFilter: Int): Int? {
        val applied = appliedFilter ?: return null
        appliedFilter = null
        if (!accessGranted) return null
        if (currentFilter != applied) return null
        return filterToRestore
    }
}
