package com.fireshare.tweet

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import com.fireshare.tweet.datamodel.MimeiId
import com.fireshare.tweet.datamodel.Tweet
import com.fireshare.tweet.viewmodel.TweetFeedViewModel
import com.fireshare.tweet.viewmodel.TweetViewModel
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ActivityComponent
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(ActivityComponent::class)
object ActivityModule {
    @Provides
    fun provideViewModelStoreOwner(activity: ComponentActivity): ViewModelStoreOwner {
        return activity
    }
}

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideTweetFeedViewModel(): TweetFeedViewModel {
        return TweetFeedViewModel()
    }

    @Provides
    fun provideTweetKey(): TweetKey {
        // Replace with your actual logic to create a TweetKey instance
        return TweetKeyImpl("placeholder")
    }

    @Provides
    @Singleton
    fun provideViewModelStoreOwner(application: Application): ViewModelStoreOwner {
        return application as TweetApplication
    }

    @Provides
    fun provideTweetViewModel(tweetKey: TweetKey, viewModelStoreOwner: ViewModelStoreOwner): TweetViewModel {
        return TweetViewModel(tweetKey, viewModelStoreOwner)
    }

    @Singleton
    class TweetViewModelFactory(
        private val tweetKey: TweetKey,
        private val viewModelStoreOwner: ViewModelStoreOwner
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(TweetViewModel::class.java)) {
                @Suppress("UNCHECKED_CAST")
                return TweetViewModel(tweetKey, viewModelStoreOwner) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }

//    @Singleton
//    class TweetViewModelFactory @Inject constructor(
//        private val tweet: Tweet
//    ) : ViewModelProvider.Factory {
//        override fun <T : ViewModel> create(modelClass: Class<T>): T {
//            if (modelClass.isAssignableFrom(TweetViewModel::class.java)) {
//                @Suppress("UNCHECKED_CAST")
//                return TweetViewModel(tweet) as T
//            }
//            throw IllegalArgumentException("Unknown ViewModel class")
//        }
//    }
}
