package us.fireshare.tweet.datamodel

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * Events for tweet and comment operations
 */
sealed class TweetEvent {
    data class TweetUploaded(val tweet: Tweet) : TweetEvent()
    data class TweetDeleted(val tweetId: String, val authorId: String) : TweetEvent()
    data class CommentUploaded(val comment: Tweet, val parentTweet: Tweet) : TweetEvent()
    data class CommentDeleted(val commentId: String, val parentTweetId: String) : TweetEvent()
    data class TweetUpdated(val tweet: Tweet) : TweetEvent()
    data class TweetLiked(val tweet: Tweet, val isLiked: Boolean) : TweetEvent()
    data class TweetBookmarked(val tweet: Tweet, val isBookmarked: Boolean) : TweetEvent()
    data class TweetRetweeted(val originalTweet: Tweet, val retweet: Tweet) : TweetEvent()
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
        _events.emit(event)
    }
    
    /**
     * Post an event without suspending (for use in non-suspend contexts)
     */
    fun postAsync(event: TweetEvent) {
        kotlinx.coroutines.GlobalScope.launch {
            _events.emit(event)
        }
    }
} 