package com.fireshare.tweet

import android.app.Application
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.fireshare.tweet.datamodel.User
import com.fireshare.tweet.service.CleanUpWorker
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.TimeUnit

@HiltAndroidApp
class TweetApplication : Application(){
    lateinit var initJob: Deferred<Unit>

    override fun onCreate() {
        super.onCreate()
        preferenceHelper = PreferenceHelper(this)
        initJob = CoroutineScope(Dispatchers.IO).async {
            HproseInstance.init(this@TweetApplication, preferenceHelper)
        }
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        } else {
            // Plant a custom tree for release builds
            Timber.plant(ReleaseTree())
        }

        // Initialize WorkManager
//        WorkManager.initialize(this, Configuration.Builder().build())

        // Schedule the CleanUpWorker
        val cleanUpRequest = PeriodicWorkRequestBuilder<CleanUpWorker>(1, TimeUnit.DAYS)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "CleanUpOldTweets",
            ExistingPeriodicWorkPolicy.KEEP,
            cleanUpRequest
        )
    }

    companion object {
        lateinit var preferenceHelper: PreferenceHelper
    }

//    @Inject
//    lateinit var workerFactory: HiltWorkerFactory

//    , Configuration.Provider
//    override val workManagerConfiguration: Configuration
//        get() = Configuration.Builder()
//            .setWorkerFactory(workerFactory)
//            .build()
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
        val scope = CoroutineScope(IO)
        scope.launch {
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