package com.fireshare.tweet

import androidx.compose.material3.SnackbarHostState
import com.fireshare.tweet.viewmodel.TweetFeedViewModel
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideTweetFeedViewModel(): TweetFeedViewModel {
        return TweetFeedViewModel()
    }

//    @Provides
//    fun provideTweetViewModel(tweet: Tweet): TweetViewModel {
//        return TweetViewModel(tweet)
//    }

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
