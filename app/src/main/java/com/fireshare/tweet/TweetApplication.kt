package com.fireshare.tweet

import android.app.Application
import com.fireshare.tweet.datamodel.User
import dagger.hilt.android.HiltAndroidApp

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