package us.fireshare.tweet

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject
import timber.log.Timber
import us.fireshare.tweet.datamodel.BlackList
import us.fireshare.tweet.service.BadgeStateManager
import us.fireshare.tweet.widget.ImageCacheManager
import us.fireshare.tweet.service.CleanUpWorker
import us.fireshare.tweet.service.MessageCheckWorker
import us.fireshare.tweet.service.SystemNotificationManager
import us.fireshare.tweet.widget.VideoManager
import java.util.concurrent.TimeUnit

@HiltAndroidApp
class TweetApplication : Application(), ComponentCallbacks2 {
    companion object {
        // Use SupervisorJob to prevent one child's failure from cancelling others
        val applicationScope = CoroutineScope(SupervisorJob() + IO)
    }

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        } else {
            // Plant a custom tree for release builds
            Timber.plant(ReleaseTree())
        }
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_AUTO_BATTERY)

        // Initialize BadgeStateManager for launcher badge support
        BadgeStateManager.initialize(this)
        
        // Initialize notification channels for system bar notifications
        SystemNotificationManager.initializeChannels(this)

        // Schedule the CleanUpWorker
        val cleanUpRequest = PeriodicWorkRequestBuilder<CleanUpWorker>(1, TimeUnit.DAYS)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "CleanUpOldTweets",
            ExistingPeriodicWorkPolicy.KEEP,
            cleanUpRequest
        )

        // Schedule message check worker
        val messageCheckRequest = PeriodicWorkRequestBuilder<MessageCheckWorker>(15, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "MessageCheck",
            ExistingPeriodicWorkPolicy.KEEP,
            messageCheckRequest
        )

        // Video memory monitoring removed - now relies on system memory warnings only
        
        // Initialize BlackList with database
        BlackList.initialize(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        
        when (level) {
            TRIM_MEMORY_UI_HIDDEN -> {
                // App UI is hidden, but user might return quickly - preserve ALL cache
                Timber.d("Memory warning: UI_HIDDEN - Preserving ALL cache for quick return")
                // Do not clear any cache when UI is hidden
            }
            TRIM_MEMORY_BACKGROUND -> {
                // App is in background, but user might return - preserve ALL cache
                Timber.d("Memory warning: BACKGROUND - Preserving ALL cache for potential return")
                // Do not clear any cache when app goes to background
            }
            TRIM_MEMORY_RUNNING_MODERATE -> {
                // System is running low on memory - clear some cache but preserve user experience
                Timber.w("Memory warning: RUNNING_MODERATE - System memory pressure, clearing minimal cache")
                clearMinimalVideoAndImageCaches()
            }
            TRIM_MEMORY_RUNNING_LOW -> {
                // System is running low on memory - clear more cache
                Timber.w("Memory warning: RUNNING_LOW - System memory pressure, clearing moderate cache")
                clearPartialVideoAndImageCaches()
            }
            TRIM_MEMORY_RUNNING_CRITICAL -> {
                // System is running critically low on memory - clear significant cache
                Timber.w("Memory warning: RUNNING_CRITICAL - System critically low on memory, clearing significant cache")
                clearSignificantVideoAndImageCaches()
            }
            TRIM_MEMORY_COMPLETE -> {
                // System is about to kill the app - clear all cache
                Timber.w("Memory warning: COMPLETE - System about to kill app, clearing all cache")
                clearAllVideoAndImageCaches()
            }
            else -> {
                // For any other memory levels, only clear cache if it's a critical system warning
                if (level >= TRIM_MEMORY_RUNNING_MODERATE) {
                    Timber.w("Memory warning: Level $level - System memory pressure, clearing partial caches")
                    clearPartialVideoAndImageCaches()
                } else {
                    Timber.d("Memory warning: Level $level - Non-critical, preserving cache")
                }
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Handle configuration changes if needed
    }

    override fun onLowMemory() {
        super.onLowMemory()
        // This is called when the system is running very low on memory
        // and is about to kill background processes
        Timber.w("Memory warning: onLowMemory - System about to kill background processes")
        clearPartialVideoAndImageCaches()
    }

    private fun clearMinimalVideoAndImageCaches() {
        // Clear only 10% of inactive video and image caches for minimal impact
        try {
            VideoManager.clearMinimalInactiveVideos()
            applicationScope.launch {
                ImageCacheManager.clearMinimalCachedImages(this@TweetApplication)
            }
            Timber.d("Cleared 10%% of video and image caches due to moderate memory pressure (tweets preserved)")
        } catch (e: Exception) {
            Timber.e("Error clearing minimal video and image caches: ${e.message}")
        }
    }

    private fun clearPartialVideoAndImageCaches() {
        // Clear 30% of video and image caches (keep tweets)
        try {
            VideoManager.clearInactiveVideos()
            // Clear 30% of image cache
            applicationScope.launch {
                ImageCacheManager.clearPartialCachedImages(this@TweetApplication)
            }
            // Note: Tweet cache is intentionally left alone as it's small
            Timber.d("Cleared 30%% of video and image caches due to memory pressure (tweets preserved)")
        } catch (e: Exception) {
            Timber.e("Error clearing partial video and image caches: ${e.message}")
        }
    }

    private fun clearSignificantVideoAndImageCaches() {
        // Clear 60% of video and image caches for significant memory pressure
        try {
            VideoManager.clearSignificantInactiveVideos()
            applicationScope.launch {
                ImageCacheManager.clearSignificantCachedImages(this@TweetApplication)
            }
            Timber.d("Cleared 60%% of video and image caches due to critical memory pressure (tweets preserved)")
        } catch (e: Exception) {
            Timber.e("Error clearing significant video and image caches: ${e.message}")
        }
    }

    private fun clearAllVideoAndImageCaches() {
        // Clear all video and image caches when system is about to kill the app
        try {
            VideoManager.releaseAllVideos()
            applicationScope.launch {
                ImageCacheManager.clearAllCachedImages(this@TweetApplication)
            }
            Timber.d("Cleared ALL video and image caches due to system about to kill app (tweets preserved)")
        } catch (e: Exception) {
            Timber.e("Error clearing all video and image caches: ${e.message}")
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        // Release all video players to prevent memory leaks
        VideoManager.releaseAllVideos()
        // Release full screen video player
        VideoManager.releaseFullScreenPlayer()
        // Cancel the scope when the application is terminating (rare in modern Android)
        applicationScope.cancel()
    }
}

/**
 * Gather error log from production release builds.
 * */
class ReleaseTree : Timber.Tree() {
    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {

        // Only log WARN, ERROR, and WTF levels in release builds
        if (priority == Log.VERBOSE || priority == Log.DEBUG || priority == Log.INFO) {
            return
        }
        val logEntry = JSONObject().apply {
            put("timestamp", System.currentTimeMillis())
            put("level", priorityToString(priority))
            put("tag", tag)
            put("message", message)
            t?.let { put("exception", it.toString()) }
        }
        CoroutineScope(IO).launch {
            HproseInstance.logging(logEntry.toString())
        }

        // Log to your crash reporting tool or any other logging service
        // For example, using Firebase Crashlytics:
        // FirebaseCrashlytics.getInstance().log(message)
        // if (t != null) {
        //     FirebaseCrashlytics.getInstance().recordException(t)
        // }

        // You can also log to a file or a remote server here
    }

    private fun priorityToString(priority: Int): String {
        return when (priority) {
            Log.ERROR -> "ERROR"
            Log.WARN -> "WARN"
            Log.INFO -> "INFO"
            Log.DEBUG -> "DEBUG"
            Log.VERBOSE -> "VERBOSE"
            else -> "UNKNOWN"
        }
    }
}