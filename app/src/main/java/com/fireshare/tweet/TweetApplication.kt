package com.fireshare.tweet

import android.app.Application
import com.fireshare.tweet.datamodel.MimeiId
import com.fireshare.tweet.datamodel.User
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

interface TweetKey {
    val tweetId: MimeiId
}

class TweetKeyImpl(override val tweetId: MimeiId) : TweetKey