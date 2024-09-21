package com.fireshare.tweet

import android.content.Context
import androidx.room.Room
import com.fireshare.tweet.chat.ChatRepository
import com.fireshare.tweet.chat.ChatSessionRepository
import com.fireshare.tweet.datamodel.ChatDatabase
import com.fireshare.tweet.datamodel.ChatMessageDao
import com.fireshare.tweet.datamodel.ChatSessionDao
import com.fireshare.tweet.viewmodel.BadgeViewModel
import com.fireshare.tweet.viewmodel.TweetFeedViewModel
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
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

    @Provides
    @Singleton
    fun provideBadgeViewModel(): BadgeViewModel {
        return BadgeViewModel()
    }

    @Provides
    @Singleton
    fun provideChatDatabase(@ApplicationContext context: Context): ChatDatabase {
        return Room.databaseBuilder(
            context.applicationContext,
            ChatDatabase::class.java,
            "chat_database"
        ).build()
    }

    @Provides
    fun provideChatSessionDao(database: ChatDatabase): ChatSessionDao {
        return database.chatSessionDao()
    }

    @Provides
    fun provideChatMessageDao(database: ChatDatabase): ChatMessageDao {
        return database.chatMessageDao()
    }

    @Provides
    fun provideChatRepository(chatMessageDao: ChatMessageDao): ChatRepository {
        return ChatRepository(chatMessageDao)
    }

    @Provides
    @Singleton
    fun provideChatSessionRepository(
        chatSessionDao: ChatSessionDao,
        chatMessageDao: ChatMessageDao
    ): ChatSessionRepository {
        return ChatSessionRepository(chatSessionDao, chatMessageDao)
    }
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
