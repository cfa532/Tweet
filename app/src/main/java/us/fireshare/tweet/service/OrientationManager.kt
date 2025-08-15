package us.fireshare.tweet.service

import android.app.Activity
import android.content.pm.ActivityInfo
import timber.log.Timber

/**
 * Utility class to manage orientation changes dynamically
 * Allows locking to portrait by default and enabling rotation in full-screen mode
 */
object OrientationManager {
    
    /**
     * Lock the activity to portrait orientation
     */
    fun lockToPortrait(activity: Activity) {
        Timber.d("OrientationManager: Locking to portrait")
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
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
     * Lock to landscape orientation
     */
    fun lockToLandscape(activity: Activity) {
        Timber.d("OrientationManager: Locking to landscape")
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    }
}
