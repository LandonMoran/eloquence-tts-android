package com.xw.vvtts.update

import android.content.Context
import com.xw.vvtts.utils.MirroredPreferences

/**
 * Preference-backed state for update notifications. Mirrors the Morphe Manager
 * model: a master "background notifications" enabled flag, plus an opt-in "use
 * prereleases" flag that controls whether this device subscribes to the dev topic.
 *
 * [NOTIFICATIONS_ENABLED] defaults to true so an installed app subscribes to
 * stable release pings out of the box; prereleases default off, so the dev topic
 * stays dormant unless the user explicitly opts in.
 */
object UpdateNotifPrefs {
    const val PREFS_NAME = "vvtts_update_notif"
    const val NOTIFICATIONS_ENABLED = "update_notifications_enabled"
    const val USE_PRERELEASES = "use_prerelease_updates"
    // Tracks whether the Android 13+ POST_NOTIFICATIONS runtime prompt has
    // already been shown once, so we do not re-ask on every launch when denied.
    const val NOTIF_PERMISSION_PROMPTED = "notif_permission_prompted"

    fun get(context: Context): MirroredPreferences = MirroredPreferences(context, PREFS_NAME)

    fun hasAskedNotifPermission(context: Context): Boolean =
        get(context).getBoolean(NOTIF_PERMISSION_PROMPTED, false)

    fun markNotifPermissionAsked(context: Context) =
        get(context).edit { it.putBoolean(NOTIF_PERMISSION_PROMPTED, true) }

    fun isEnabled(context: Context): Boolean =
        get(context).getBoolean(NOTIFICATIONS_ENABLED, true)

    fun usesPrereleases(context: Context): Boolean =
        get(context).getBoolean(USE_PRERELEASES, false)
}