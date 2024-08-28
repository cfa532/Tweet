package com.fireshare.tweet

import android.app.Application
import com.fireshare.tweet.datamodel.Tweet
import com.fireshare.tweet.datamodel.User
import com.fireshare.tweet.viewmodel.TweetViewModel
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class TweetApplication : Application() {
    override fun onCreate() {
        super.onCreate()
    }
}

object AppContainer {
    var users: MutableSet<User> = emptySet<User>().toMutableSet()
}
