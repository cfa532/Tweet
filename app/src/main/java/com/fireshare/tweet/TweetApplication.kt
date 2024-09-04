package com.fireshare.tweet

import android.app.Application
import android.content.Context
import androidx.compose.ui.platform.LocalContext
import com.fireshare.tweet.datamodel.MimeiId
import com.fireshare.tweet.datamodel.User
import com.fireshare.tweet.network.HproseInstance
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

@HiltAndroidApp
class TweetApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        preferencesHelper = PreferencesHelper(this)
    }

    companion object {
        lateinit var preferencesHelper: PreferencesHelper
    }
}

object AppContainer {
    var users: MutableSet<User> = emptySet<User>().toMutableSet()
}