package us.fireshare.tweet.datamodel

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Events for tweet, comment, and chat operations
 */
sealed class TweetEvent {
    data class TweetUploaded(val tweet: Tweet) : TweetEvent()
    data class TweetUploadFailed(val error: String) : TweetEvent()
    data class TweetDeleted(val tweetId: String, val authorId: String) : TweetEvent()
    data class CommentUploaded(val comment: Tweet, val parentTweet: Tweet) : TweetEvent()
    data class CommentUploadFailed(val error: String) : TweetEvent()
    data class CommentDeleted(val commentId: String, val parentTweetId: String) : TweetEvent()
    data class TweetUpdated(val tweet: Tweet) : TweetEvent()
    data class TweetLiked(val tweet: Tweet, val isLiked: Boolean) : TweetEvent()
    data class TweetBookmarked(val tweet: Tweet, val isBookmarked: Boolean) : TweetEvent()
    data class TweetRetweeted(val originalTweet: Tweet, val retweet: Tweet) : TweetEvent()
    data class UserDataUpdated(val user: User) : TweetEvent()
    
    // Chat events
    data class ChatMessageSent(val message: ChatMessage) : TweetEvent()
    data class ChatMessageSendFailed(val error: String) : TweetEvent()
    data class ChatMessageReceived(val message: ChatMessage) : TweetEvent()
    data class ChatSessionUpdated(val sessionId: String, val hasNews: Boolean) : TweetEvent()
}

/**
 * Notification center for tweet events across threads
 */
object TweetNotificationCenter {
    private val _events = MutableSharedFlow<TweetEvent>(replay = 0)
    val events = _events.asSharedFlow()
    
    /**
     * Post an event to all listeners
     */
    suspend fun post(event: TweetEvent) {
        Timber.tag("TweetNotificationCenter").d("Emitting event: $event")
        _events.emit(event)
        Timber.tag("TweetNotificationCenter").d("Event emitted successfully")
    }
    
    /**
     * Post an event without suspending (for use in non-suspend contexts)
     */
    @OptIn(DelicateCoroutinesApi::class)
    fun postAsync(event: TweetEvent) {
        kotlinx.coroutines.GlobalScope.launch {
            _events.emit(event)
        }
    }
} 