package com.fireshare.tweet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.fireshare.tweet.datamodel.Tweet
import com.fireshare.tweet.viewmodel.TweetFeedViewModel
import com.fireshare.tweet.viewmodel.TweetViewModel
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

class TweetViewModelFactory @Inject constructor(
    private val tweet: Tweet
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(TweetViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return TweetViewModel(tweet) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    fun provideTweet(): Tweet {
        // Provide a default Tweet instance or fetch it from a repository
        return Tweet(
            authorId = "defaultAuthorId",
            content = "defaultContent"
        )
    }

    @Provides
    @Singleton
    fun provideAppContainer(): AppContainer {
        return AppContainer()
    }

    @Provides
    @Singleton
    fun provideTweetFeedViewModel(): TweetFeedViewModel {
        return TweetFeedViewModel()
    }

    @Provides
    fun provideTweetViewModel(tweet: Tweet): TweetViewModel {
        return TweetViewModel(tweet)
    }
}