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
@InstallIn(SingletonComponent::class)
object AppModule {

//    @Provides
//    fun provideTweetViewModel(tweet: Tweet): TweetViewModel {
//        return TweetViewModel(tweet)
//    }

    @Provides
    @Singleton
    fun provideTweetFeedViewModel(): TweetFeedViewModel {
        return TweetFeedViewModel()
    }

//    @Provides
//    fun provideViewModelStoreOwner(application: Application): ViewModelStoreOwner {
//        return application as TweetApplication
//    }

//    class TweetViewModelFactory(
//        private val tweet: Tweet,
//    ) : ViewModelProvider.Factory {
//        override fun <T : ViewModel> create(modelClass: Class<T>): T {
//            if (modelClass.isAssignableFrom(TweetViewModel::class.java)) {
//                @Suppress("UNCHECKED_CAST")
//                return TweetViewModel(tweet) as T
//            }
//            throw IllegalArgumentException("Unknown ViewModel class")
//        }
//    }

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
