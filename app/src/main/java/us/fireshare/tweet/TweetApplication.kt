package us.fireshare.tweet

import android.app.Application
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject
import timber.log.Timber
import us.fireshare.tweet.datamodel.BlackList
import us.fireshare.tweet.datamodel.User
import us.fireshare.tweet.service.CleanUpWorker
import us.fireshare.tweet.service.MessageCheckWorker
import us.fireshare.tweet.service.BadgeStateManager
import us.fireshare.tweet.widget.FullScreenVideoManager
import us.fireshare.tweet.widget.VideoManager
import java.util.concurrent.TimeUnit

@HiltAndroidApp
class TweetApplication : Application(){
    companion object {
        // Use SupervisorJob to prevent one child's failure from cancelling others
        val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
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

        // Start video memory monitoring
        VideoManager.startMemoryMonitoring()
        
        // Initialize BlackList with database
        BlackList.initialize(this)
    }

    override fun onTerminate() {
        super.onTerminate()
        // Stop video memory monitoring
        VideoManager.stopMemoryMonitoring()
        // Release all video players to prevent memory leaks
        VideoManager.releaseAllVideos()
        // Release full screen video player
        FullScreenVideoManager.release()
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

object AppContainer {
    var users: MutableSet<User> = emptySet<User>().toMutableSet()
}