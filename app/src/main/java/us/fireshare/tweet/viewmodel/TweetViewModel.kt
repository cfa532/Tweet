package us.fireshare.tweet.viewmodel

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import java.lang.ref.WeakReference
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.exoplayer.ExoPlayer
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import us.fireshare.tweet.HproseInstance
import us.fireshare.tweet.HproseInstance.appUser
import us.fireshare.tweet.R
import us.fireshare.tweet.datamodel.MimeiFileType
import us.fireshare.tweet.datamodel.MimeiId
import us.fireshare.tweet.datamodel.Tweet
import us.fireshare.tweet.datamodel.TweetEvent
import us.fireshare.tweet.datamodel.TweetNotificationCenter
import us.fireshare.tweet.service.UploadCommentWorker
import us.fireshare.tweet.widget.createExoPlayer
import java.lang.Integer.max

@HiltViewModel(assistedFactory = TweetViewModel.TweetViewModelFactory::class)
class TweetViewModel @AssistedInject constructor(
    @Assisted private val tweet: Tweet,
    private val savedStateHandle: SavedStateHandle
) : ViewModel()
{
    @AssistedFactory
    interface TweetViewModelFactory {
        fun create(tweet: Tweet): TweetViewModel
    }
    private val _tweetState = MutableStateFlow(tweet)
    val tweetState: StateFlow<Tweet> get() = _tweetState.asStateFlow()

    private val _attachments = MutableStateFlow(tweet.attachments)
    val attachments: StateFlow<List<MimeiFileType>?> get() = _attachments.asStateFlow()

    private val _comments = MutableStateFlow<List<Tweet>>(emptyList())
    val comments: StateFlow<List<Tweet>> get() = _comments.asStateFlow()

    private val exoPlayers = mutableMapOf<String, ExoPlayer>()
    // remember current video playback position after configuration changes.
    private val playbackPositions = mutableMapOf<String, Long>()

    fun getExoPlayer(url: String, context: Context): ExoPlayer {
        return exoPlayers.getOrPut(url) {
            createExoPlayer(context, url).also { player ->
                val position = savedStateHandle.get<Long>("playbackPosition_$url") ?: 0L
                player.seekTo(position)
                playbackPositions[url] = position
            }
        }
    }
    fun savePlaybackPosition(url: String, position: Long) {
        playbackPositions[url] = position
        savedStateHandle["playbackPosition_$url"] = position
    }
    fun releaseAllPlayers() {
        exoPlayers.values.forEach { it.release() }
        exoPlayers.clear()
    }
    fun stopPlayer(url: String) {
        exoPlayers[url]?.playWhenReady = false  // have to set it here, otherwise won't work.
    }

    init {
        /**
         * Usually a tweet object has been well initialized in the tweet feed list.
         * However if invoked by Deeplink, the tweet object has to be initiated separately.
         * */
        if (tweetState.value.author == null) {
            viewModelScope.launch(Dispatchers.IO) {
                HproseInstance.fetchTweet(tweet.mid, tweet.authorId, shouldCache = false)?.let { tweet ->
                    _tweetState.value = tweet
                }
            }
        }
    }
    /**
     * Reload Tweet from database instead of cache.
     * */
    suspend fun refreshTweet() {
        HproseInstance.refreshTweet(tweet.mid, tweet.authorId)?.let { refreshedTweet ->
            refreshedTweet
        }
    }

    /**
     * Refresh the appropriate tweet based on whether this is a retweet or not
     */
    suspend fun refreshTweetAndOriginal() {
        val currentTweet = tweetState.value
        
        try {
            if (currentTweet.originalTweetId != null && currentTweet.originalAuthorId != null) {
                // Check if this is a pure retweet (no content/attachments) or a quoted tweet (has content/attachments)
                if (currentTweet.content.isNullOrEmpty() && currentTweet.attachments.isNullOrEmpty()) {
                    // Pure retweet - refresh the original tweet that the user actually sees
                    // Since we don't store the original tweet in the ViewModel, we trigger a reload
                    // by updating the tweet state, which will cause the UI to reload the original tweet
                    _tweetState.value = currentTweet.copy(timestamp = currentTweet.timestamp)
                } else {
                    // Quoted tweet - refresh the quoting tweet itself (the one with content/attachments)
                    HproseInstance.fetchTweet(currentTweet.mid, currentTweet.authorId, shouldCache = true)?.let { fetchedTweet ->
                        // Only update if the fetched tweet has valid content
                        if (fetchedTweet.content != null || !fetchedTweet.attachments.isNullOrEmpty()) {
                            _tweetState.value = fetchedTweet
                        }
                    }
                }
            } else {
                // This is an original tweet - refresh it directly
                HproseInstance.fetchTweet(currentTweet.mid, currentTweet.authorId, shouldCache = true)?.let { fetchedTweet ->
                    // Only update if the fetched tweet has valid content
                    if (fetchedTweet.content != null || !fetchedTweet.attachments.isNullOrEmpty()) {
                        _tweetState.value = fetchedTweet
                    }
                }
            }
        } catch (e: Exception) {
            // Log error but don't update state to prevent content from disappearing
            Timber.tag("TweetViewModel").e(e, "Error refreshing tweet ${currentTweet.mid}")
        }
    }

    /**
     * Load the original tweet if this tweet is a retweet
     */
    suspend fun loadOriginalTweet(): Tweet? {
        val currentTweet = tweetState.value
        return if (currentTweet.originalTweetId != null && currentTweet.originalAuthorId != null) {
            // For pure retweets, use refreshTweet to get the latest data
            // For quoted tweets, use fetchTweet since we're just displaying the original as a quote
            if (currentTweet.content.isNullOrEmpty() && currentTweet.attachments.isNullOrEmpty()) {
                // Pure retweet - get fresh data
                HproseInstance.refreshTweet(currentTweet.originalTweetId!!, currentTweet.originalAuthorId!!)?.let { refreshedTweet ->
                    // Preserve author information
                    if (refreshedTweet.author != null) {
                        refreshedTweet
                    } else {
                        val author = HproseInstance.getUser(currentTweet.originalAuthorId!!)
                        refreshedTweet.copy(author = author)
                    }
                }
            } else {
                // Quoted tweet - use fetchTweet with caching
                HproseInstance.fetchTweet(currentTweet.originalTweetId!!, currentTweet.originalAuthorId!!, shouldCache = true)
            }
        } else {
            null
        }
    }

    /**
     * Load the original tweet if this tweet is a retweet (for feed context - with caching)
     */
    suspend fun loadOriginalTweetForFeed(): Tweet? {
        val currentTweet = tweetState.value
        return if (currentTweet.originalTweetId != null && currentTweet.originalAuthorId != null) {
            // For pure retweets, use refreshTweet to get the latest data
            // For quoted tweets, use fetchTweet with caching for feed context
            if (currentTweet.content.isNullOrEmpty() && currentTweet.attachments.isNullOrEmpty()) {
                // Pure retweet - get fresh data
                HproseInstance.refreshTweet(currentTweet.originalTweetId!!, currentTweet.originalAuthorId!!)?.let { refreshedTweet ->
                    // Preserve author information
                    if (refreshedTweet.author != null) {
                        refreshedTweet
                    } else {
                        val author = HproseInstance.getUser(currentTweet.originalAuthorId!!)
                        refreshedTweet.copy(author = author)
                    }
                }
            } else {
                // Quoted tweet - use fetchTweet with caching for feed context
                HproseInstance.fetchTweet(currentTweet.originalTweetId!!, currentTweet.originalAuthorId!!, shouldCache = true)
            }
        } else {
            null
        }
    }

    /**
     * when composing a comment, also post it as a tweet or not.
     * */
    val isCheckedToTweet = mutableStateOf(false)
    fun onCheckedChange(value: Boolean) {
        isCheckedToTweet.value = value
    }

    suspend fun loadComments(tweet: Tweet, pageNumber: Number = 0) {
        _comments.value = HproseInstance.getComments(tweet)?.map {
            it.author = HproseInstance.getUser(it.authorId)
            it
        } ?: emptyList()
        _comments.update { list ->
            list.sortedByDescending { it.timestamp }
        }
    }

    suspend fun delComment(commentId: MimeiId) {
        // Remove manual UI updates - let notification system handle them
        HproseInstance.delComment(tweetState.value, commentId) {
            // Callback is kept for backward compatibility but UI updates are handled by notifications
        }
    }
    // add new Comment object to its parent Tweet. The code runs on Main thread.
    fun uploadComment(
        context: Context,
        content: String,
        attachments: List<Uri>? = null,
    ) {
        val data = workDataOf(
            "tweetId" to tweetState.value.mid,
            "authorId" to tweetState.value.authorId,
            "isCheckedToTweet" to isCheckedToTweet.value,
            "content" to content,   // content for new comment
            "attachmentUris" to attachments?.map { it.toString() }?.toTypedArray()
        )
        val uploadRequest = OneTimeWorkRequest.Builder(UploadCommentWorker::class.java)
            .setInputData(data)
            .build()
        val workManager = WorkManager.getInstance(context)
        workManager.enqueue(uploadRequest)

        // No need to observe work status - UI will update via notification system
    }

    suspend fun shareTweet(context: Context) {
        /**
         * Call to checkUpgrade() also returns a map of environmental variables,
         * which includes environment variables of the App.
         * */
        val map = HproseInstance.checkUpgrade() ?: return
        val deepLink = "http://${map["domain"]}/tweet/${tweet.mid}/${tweet.authorId}"
//            val deepLink = "${tweet.author?.baseUrl}/entry?mid=$appId&ver=last#/tweet/" +
//                    "${tweet.mid}/${tweet.authorId}"
        val sendIntent: Intent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, deepLink)
            type = "text/plain"
        }
        val shareIntent = Intent.createChooser(sendIntent, null)
        context.startActivity(shareIntent, null)
    }

    /**
     * Update favorite count and icon right away for better user experience.
     * */
    suspend fun toggleFavorite(
        updateAppUser: (Tweet, Boolean) -> Unit     // callback to update current user's account.
    ) {
        val isFavorite = tweetState.value.isFavorite
        _tweetState.value.isFavorite = ! isFavorite
        _tweetState.value = tweetState.value.copy(
            favoriteCount = if (isFavorite) max(0, tweetState.value.favoriteCount - 1)
            else tweetState.value.favoriteCount + 1,
        )
        updateAppUser(tweetState.value, ! isFavorite)
        /**
         * Overwrite in-memory favorites with result from database call that persists the change.
         * */
        _tweetState.value = HproseInstance.toggleFavorite(tweetState.value)
    }

    /**
     * Update bookmark count and icon right away for better user experience.
     * */
    suspend fun toggleBookmark(updateAppUser: (Tweet, Boolean) -> Unit) {
        val hasBookmarked = tweetState.value.isBookmarked
        _tweetState.value.isBookmarked = ! hasBookmarked
        _tweetState.value = tweetState.value.copy(
            bookmarkCount = if (hasBookmarked) max(0, tweetState.value.bookmarkCount - 1)
            else tweetState.value.bookmarkCount + 1,
        )
        updateAppUser(tweetState.value, ! hasBookmarked)

        /**
         * Overwrite in-memory bookmark with result from database call that persists the change.
         * If backend fails, the original value will be restored.
         * */
        _tweetState.value = HproseInstance.toggleBookmark(tweetState.value)
    }

    /**
     * Perform a retweet action and update the UI immediately for better user experience.
     * */
    suspend fun retweetTweet() {
        // Update retweet count and status immediately for better UX
        val hasRetweeted = tweetState.value.isRetweeted
        _tweetState.value.isRetweeted = ! hasRetweeted
        _tweetState.value = tweetState.value.copy(
            retweetCount = if (hasRetweeted) max(0, tweetState.value.retweetCount - 1)
            else tweetState.value.retweetCount + 1,
        )
        
        // Perform the actual retweet operation
        try {
            HproseInstance.retweet(tweetState.value)
        } catch (e: Exception) {
            // Revert the UI changes if the retweet failed
            _tweetState.value.isRetweeted = hasRetweeted
            _tweetState.value = tweetState.value.copy(
                retweetCount = if (hasRetweeted) tweetState.value.retweetCount + 1
                else max(0, tweetState.value.retweetCount - 1),
            )
            throw e
        }
    }

    /**
     * Update retweet account on the original tweet after retweet is deleted.
     * */
    suspend fun updateRetweetCount(tweet: Tweet, retweetId: MimeiId, flag: Int) {
        HproseInstance.updateRetweetCount(tweet, retweetId, flag)?.let {
            _tweetState.value = it
        }
    }

    private var notificationContextRef: WeakReference<Context>? = null
    private val shownToastComments = mutableSetOf<String>() // Track which comments have shown toast
    
    companion object {
        // No longer needed - Toast handling moved to TweetFeedViewModel
    }

    /**
     * Set the context for showing toast messages in notifications
     */
    fun setNotificationContext(context: Context) {
        notificationContextRef = WeakReference(context)
    }

    /**
     * Listen to tweet notifications and update the tweet detail accordingly
     */
    fun startListeningToNotifications(context: Context? = null) {
        if (context != null) {
            notificationContextRef = WeakReference(context)
        }
        viewModelScope.launch {
            try {
                Timber.tag("TweetViewModel").d("Starting notification listener for tweet ${tweetState.value.mid}")
                TweetNotificationCenter.events.collect { event ->
                    when (event) {
                        is TweetEvent.CommentUploaded -> {
                            // Only handle if this is the parent tweet for the comment
                            if (event.parentTweet.mid == tweetState.value.mid) {
                                Timber.tag("TweetViewModel").d("CommentUploaded event received for tweet ${tweetState.value.mid}, comment ${event.comment.mid}, author ${event.comment.authorId}, current user ${appUser.mid}")
                                
                                // Update the tweet state with new comment count
                                _tweetState.value = event.parentTweet
                                
                                // Add the new comment to the comments list
                                _comments.update { currentComments ->
                                    (listOf(event.comment) + currentComments)
                                        .distinctBy { it.mid }
                                        .sortedByDescending { it.timestamp }
                                }
                            }
                        }

                        is TweetEvent.CommentUploadFailed -> {
                            // Show failure toast
                            val context = notificationContextRef?.get()
                            if (context != null) {
                                withContext(Main) {
                                    Toast.makeText(context, context.getString(R.string.comment_failed), Toast.LENGTH_SHORT).show()
                                }
                            }
                            Timber.tag("TweetViewModel").e("Comment upload failed: ${event.error}")
                        }
                        is TweetEvent.CommentDeleted -> {
                            // Only handle if this is the parent tweet for the comment
                            if (event.parentTweetId == tweetState.value.mid) {
                                // Remove the comment from the comments list
                                _comments.update { currentComments ->
                                    currentComments.filterNot { it.mid == event.commentId }
                                }
                                
                                // Update comment count
                                _tweetState.value = tweetState.value.copy(
                                    commentCount = max(0, tweetState.value.commentCount - 1)
                                )
                            }
                        }
                        is TweetEvent.TweetUpdated -> {
                            // Update tweet if this is the same tweet
                            if (event.tweet.mid == tweetState.value.mid) {
                                _tweetState.value = event.tweet
                            }
                        }
                        else -> {
                            // Handle other events if needed
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // This is expected when the ViewModel is destroyed
                Timber.tag("TweetViewModel").d("Notification listener cancelled: ${e.message}")
            } catch (e: Exception) {
                Timber.tag("TweetViewModel").e(e, "Error in notification listener: ${e.message}")
            }
        }
    }
}
