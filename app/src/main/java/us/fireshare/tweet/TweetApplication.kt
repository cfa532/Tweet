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
        
        // Modern Android API (API 34+) only uses UI_HIDDEN and BACKGROUND levels
        // All other TRIM_MEMORY_* constants are deprecated and no longer sent
        when {
            level >= TRIM_MEMORY_BACKGROUND -> {
                // App is in background and may be killed - preserve cache for quick return
                // This is the highest level we'll receive in modern Android
                Timber.d("Memory warning: BACKGROUND (level $level) - Preserving cache for potential return")
                // Do not clear cache when app goes to background to maintain good UX
            }
            level >= TRIM_MEMORY_UI_HIDDEN -> {
                // App UI is hidden, but user might return quickly - preserve ALL cache
                Timber.d("Memory warning: UI_HIDDEN (level $level) - Preserving ALL cache for quick return")
                // Do not clear any cache when UI is hidden
            }
            else -> {
                // Any other levels (shouldn't happen in modern Android, but handle gracefully)
                Timber.d("Memory warning: Unknown level $level - Preserving cache")
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
        // In modern Android, we rely on onTrimMemory for memory management
        // onLowMemory is still called but we preserve cache for better UX
    }

    // Note: Cache clearing methods removed as modern Android (API 34+) 
    // only sends UI_HIDDEN and BACKGROUND memory levels, and we preserve
    // cache for both to maintain good user experience

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