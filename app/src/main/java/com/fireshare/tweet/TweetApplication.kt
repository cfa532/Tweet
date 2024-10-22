package com.fireshare.tweet

import android.app.Application
import android.util.Log
import com.fireshare.tweet.datamodel.User
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import org.json.JSONObject
import timber.log.Timber

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
            Timber.plant(ReleaseTree())
//            Timber.plant(Timber.DebugTree())
        } else {
            // Plant a custom tree for release builds
            Timber.plant(ReleaseTree())
        }
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
        HproseInstance.logging(logEntry.toString())

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