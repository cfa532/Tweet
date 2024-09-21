package com.fireshare.tweet

import android.app.Application
import com.fireshare.tweet.datamodel.User
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async

@HiltAndroidApp
class TweetApplication : Application() {
    lateinit var initJob: Deferred<Unit>

    override fun onCreate() {
        super.onCreate()
        preferenceHelper = PreferenceHelper(this)
        initJob = CoroutineScope(Dispatchers.IO).async {
            HproseInstance.init(this@TweetApplication, preferenceHelper)
        }
    }

    companion object {
        lateinit var preferenceHelper: PreferenceHelper
    }
}

object AppContainer {
    var users: MutableSet<User> = emptySet<User>().toMutableSet()
}