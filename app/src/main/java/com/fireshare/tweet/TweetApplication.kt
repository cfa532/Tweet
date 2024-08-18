package com.fireshare.tweet

import android.app.Application
import com.fireshare.tweet.datamodel.User
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class TweetApplication : Application() {

    val appContainer = AppContainer()

    override fun onCreate() {
        super.onCreate()
    }
}

class AppContainer {
    var users: MutableSet<User> = emptySet<User>().toMutableSet()
}
