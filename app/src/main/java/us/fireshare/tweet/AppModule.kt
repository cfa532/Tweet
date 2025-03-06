package us.fireshare.tweet

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import us.fireshare.tweet.chat.ChatRepository
import us.fireshare.tweet.chat.ChatSessionRepository
import us.fireshare.tweet.datamodel.ChatDatabase
import us.fireshare.tweet.datamodel.ChatMessageDao
import us.fireshare.tweet.datamodel.ChatSessionDao
import us.fireshare.tweet.navigation.SharedViewModel
import us.fireshare.tweet.service.SearchViewModel
import us.fireshare.tweet.viewmodel.BottomBarViewModel
import us.fireshare.tweet.viewmodel.TweetFeedViewModel
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideSearchViewModel(): SearchViewModel {
        return SearchViewModel()
    }

    @Provides
    @Singleton
    fun provideTweetFeedViewModel(): TweetFeedViewModel {
        return TweetFeedViewModel()
    }

    @Provides
    @Singleton
    fun provideShareViewModel(): SharedViewModel {
        return SharedViewModel()
    }

    @Provides
    @Singleton
    fun provideBadgeViewModel(): BottomBarViewModel {
        return BottomBarViewModel()
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

