package com.fireshare.tweet

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class TweetApplication : Application() {

    override fun onCreate() {
        super.onCreate()
    }
}