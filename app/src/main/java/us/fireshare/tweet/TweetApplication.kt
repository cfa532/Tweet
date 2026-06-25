package us.fireshare.tweet

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.media3.common.util.UnstableApi
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
import timber.log.Timber
import us.fireshare.tweet.datamodel.BlackList
import us.fireshare.tweet.datamodel.User
import us.fireshare.tweet.service.BadgeStateManager
import us.fireshare.tweet.service.CleanUpWorker
import us.fireshare.tweet.service.MainFeedCheckWorker
import us.fireshare.tweet.service.MessageCheckWorker
import us.fireshare.tweet.service.SystemNotificationManager
import us.fireshare.tweet.widget.ImageCacheManager
import us.fireshare.tweet.widget.VideoManager
import java.util.concurrent.TimeUnit

@HiltAndroidApp
class TweetApplication : Application(), ComponentCallbacks2 {
    companion object {
        // Use SupervisorJob to prevent one child's failure from cancelling others
        val applicationScope = CoroutineScope(SupervisorJob() + IO)
    }

    private val appLifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            // App has come to foreground
            Timber.tag("AppLifecycle").d("App came to foreground, online=${HproseInstance.isOnline.value}")
            MainFeedCheckWorker.reschedule(this@TweetApplication)
            if (!HproseInstance.isOnline.value) {
                Timber.tag("AppLifecycle").d("Offline: skipping appUser refresh")
                return
            }
            applicationScope.launch {
                try {
                    // Only refresh if appUser is properly initialized (has baseUrl) and is not a guest
                    if (!HproseInstance.appUser.isGuest() &&
                        !HproseInstance.appUser.baseUrl.isNullOrBlank()) {
                        val refreshedUser = HproseInstance.fetchUser(
                            HproseInstance.appUser.mid,
                            baseUrl = "",  // Force IP re-resolution
                            forceRefresh = true
                        )
                        if (refreshedUser != null && !refreshedUser.baseUrl.isNullOrBlank()) {
                            User.updateUserInstance(refreshedUser, true)
                            HproseInstance.appUser = User.getInstance(refreshedUser.mid)
                            Timber.tag("AppLifecycle").d("✅ AppUser refreshed successfully on foreground - avatar: ${refreshedUser.avatar}")
                        } else {
                            Timber.tag("AppLifecycle").w("Failed to refresh appUser on foreground")
                        }
                    } else {
                        Timber.tag("AppLifecycle").d("Skipping appUser refresh - user is guest or not initialized (baseUrl: ${HproseInstance.appUser.baseUrl})")
                    }
                } catch (e: Exception) {
                    Timber.tag("AppLifecycle").e(e, "Error refreshing appUser on foreground")
                }
            }
        }

        override fun onStop(owner: LifecycleOwner) {
            // App has gone to background
            Timber.tag("AppLifecycle").d("App went to background")
        }
    }

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        androidx.media3.common.util.Log.setLogLevel(androidx.media3.common.util.Log.LOG_LEVEL_ALL)
        androidx.media3.common.util.Log.setLogStackTraces(true)

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

        // Register lifecycle observer to refresh appUser when app comes to foreground
        ProcessLifecycleOwner.get().lifecycle.addObserver(appLifecycleObserver)

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

        // Schedule main feed checks. This is a self-rescheduling one-time worker
        // because WorkManager periodic jobs cannot run every 5 minutes.
        MainFeedCheckWorker.reschedule(this)

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
                Timber.w("Memory warning: BACKGROUND (level $level) - Releasing inactive video players")
                VideoManager.handleMemoryPressure(level, releaseVisibleFeedPlayers = false)
            }
            level >= TRIM_MEMORY_UI_HIDDEN -> {
                Timber.w("Memory warning: UI_HIDDEN (level $level) - Preserving visible feed video players")
                VideoManager.handleMemoryPressure(level, releaseVisibleFeedPlayers = false)
            }
            else -> {
                Timber.w("Memory warning: Unknown level $level - Releasing inactive video players")
                VideoManager.handleMemoryPressure(level, releaseVisibleFeedPlayers = false)
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
        // Match iOS behavior: clear image cache on low memory (similar to iOS emergency cleanup).
        // This is the truly aggressive path; normal UI_HIDDEN/BACKGROUND trims preserve
        // visible feed players so foreground resume does not return to cleared black surfaces.
        Timber.w("Memory warning: onLowMemory - Clearing image cache and releasing video players")
        ImageCacheManager.clearMemoryCache()
        VideoManager.handleMemoryPressure(level = -1, releaseVisibleFeedPlayers = true)
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
 * Log tree for release builds that prints all logs like debug builds.
 * */
class ReleaseTree : Timber.Tree() {
    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        // Print all logs to logcat like debug builds
        val logTag = tag ?: "ReleaseTree"
        when (priority) {
            android.util.Log.VERBOSE -> android.util.Log.v(logTag, message, t)
            android.util.Log.DEBUG -> android.util.Log.d(logTag, message, t)
            android.util.Log.INFO -> android.util.Log.i(logTag, message, t)
            android.util.Log.WARN -> android.util.Log.w(logTag, message, t)
            android.util.Log.ERROR -> android.util.Log.e(logTag, message, t)
            android.util.Log.ASSERT -> android.util.Log.wtf(logTag, message, t)
        }
    }
}
