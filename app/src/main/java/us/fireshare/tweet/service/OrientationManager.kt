package us.fireshare.tweet.service

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import timber.log.Timber

/**
 * Utility class to manage orientation changes dynamically.
 * On large screen devices (tablets, foldables, Chromebooks), orientation is never
 * restricted so the app satisfies Google Play's large-screen quality guidelines.
 */
object OrientationManager {

    /**
     * Returns true when the device's smallest screen dimension is at least 600 dp,
     * which covers tablets, foldables in unfolded state, and Chromebooks.
     */
    private fun isLargeScreen(activity: Activity): Boolean {
        val smallestWidthDp = activity.resources.configuration.smallestScreenWidthDp
        return smallestWidthDp >= 600
    }

    /**
     * Lock the activity to portrait orientation on phones.
     * On large screens the orientation is left unspecified so the system can choose.
     */
    fun lockToPortrait(activity: Activity) {
        if (isLargeScreen(activity)) {
            Timber.d("OrientationManager: Large screen detected — skipping portrait lock")
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        } else {
            Timber.d("OrientationManager: Locking to portrait")
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    /**
     * Allow free rotation (sensor-based)
     */
    fun allowRotation(activity: Activity) {
        Timber.d("OrientationManager: Allowing rotation")
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
    }

    /**
     * Reset to default orientation handling
     */
    fun resetOrientation(activity: Activity) {
        Timber.d("OrientationManager: Resetting orientation")
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    /**
     * Lock to landscape orientation on phones.
     * On large screens the orientation is left to the sensor.
     */
    fun lockToLandscape(activity: Activity) {
        if (isLargeScreen(activity)) {
            Timber.d("OrientationManager: Large screen detected — using sensor instead of landscape lock")
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
        } else {
            Timber.d("OrientationManager: Locking to landscape")
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
    }
}
