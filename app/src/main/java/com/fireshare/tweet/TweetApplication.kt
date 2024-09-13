package com.fireshare.tweet

import android.app.Application
import androidx.lifecycle.LifecycleOwner
import com.fireshare.tweet.datamodel.User
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

@HiltAndroidApp
class TweetApplication : Application() {
    lateinit var initJob: Deferred<Unit>

    override fun onCreate() {
        super.onCreate()
        preferencesHelper = PreferencesHelper(this)
        initJob = CoroutineScope(Dispatchers.IO).async {
            HproseInstance.init(this@TweetApplication, preferencesHelper)
        }
    }

    companion object {
        lateinit var preferencesHelper: PreferencesHelper
    }
}

object AppContainer {
    var users: MutableSet<User> = emptySet<User>().toMutableSet()
}