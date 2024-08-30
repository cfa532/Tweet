package com.fireshare.tweet

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import com.fireshare.tweet.datamodel.MimeiId
import com.fireshare.tweet.datamodel.Tweet
import com.fireshare.tweet.datamodel.User
import com.fireshare.tweet.viewmodel.TweetViewModel
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class TweetApplication : Application(), ViewModelStoreOwner {

    override val viewModelStore: ViewModelStore by lazy { ViewModelStore() }

    override fun onCreate() {
        super.onCreate()
    }

    override fun onTerminate() {
        super.onTerminate()
        viewModelStore.clear()
    }
}

object AppContainer {
    var users: MutableSet<User> = emptySet<User>().toMutableSet()
}

interface TweetKey {
    val tweetId: MimeiId
}

class TweetKeyImpl(override val tweetId: MimeiId) : TweetKey