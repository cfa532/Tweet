package us.fireshare.tweet.viewmodel

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.exoplayer.ExoPlayer
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.google.gson.Gson
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
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
import us.fireshare.tweet.datamodel.UserActions
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
        
        if (currentTweet.originalTweetId != null && currentTweet.originalAuthorId != null) {
            // Check if this is a pure retweet (no content/attachments) or a quoted tweet (has content/attachments)
            if (currentTweet.content.isNullOrEmpty() && currentTweet.attachments.isNullOrEmpty()) {
                // Pure retweet - refresh the original tweet that the user actually sees
                // Since we don't store the original tweet in the ViewModel, we trigger a reload
                // by updating the tweet state, which will cause the UI to reload the original tweet
                _tweetState.value = currentTweet.copy(timestamp = currentTweet.timestamp)
            } else {
                // Quoted tweet - refresh the quoting tweet itself (the one with content/attachments)
                refreshTweet()
            }
        } else {
            // This is an original tweet - refresh it directly
            refreshTweet()
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
                // Quoted tweet - use fetchTweet without caching (for non-feed contexts)
                HproseInstance.fetchTweet(currentTweet.originalTweetId!!, currentTweet.originalAuthorId!!, shouldCache = false)
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
        tweetFeedViewModel: TweetFeedViewModel
    ) {
        val gson = Gson()
        val data = workDataOf(
            "tweet" to gson.toJson(tweetState.value),
            "isChecked" to isCheckedToTweet.value,
            "content" to content,
            "attachmentUris" to attachments?.map { it.toString() }?.toTypedArray()
        )
        val uploadRequest = OneTimeWorkRequest.Builder(UploadCommentWorker::class.java)
            .setInputData(data)
            .build()
        val workManager = WorkManager.getInstance(context)
        workManager.enqueue(uploadRequest)

        // notify the user that comment is uploaded
        Toast.makeText(context, context.getString(R.string.upload_comment), Toast.LENGTH_SHORT).show()

        // Observe the work status
        workManager.getWorkInfoByIdLiveData(uploadRequest.id)
            .observe(context as LifecycleOwner) { workInfo ->
                if (workInfo != null) {
                    when (workInfo.state) {
                        WorkInfo.State.SUCCEEDED -> {
                            try {
                                val outputData = workInfo.outputData
                                val json = outputData.getString("commentedTweet") ?: return@observe
                                Timber.tag("UploadComment").d("Comment added successfully: $json")
                                // Handle the success and update UI
                                val map = gson.fromJson(json, Map::class.java) as Map<*, *>

                                // UI updates are now handled by the notification system
                                // The notification will be posted by HproseInstance.uploadComment()
                                Timber.tag("UploadComment").d("Comment upload succeeded, notification will update UI")

                                // the comment is also posted as an tweet.
                                if (map["retweet"].toString() != "null") {
                                    val retweet = gson.fromJson(map["retweet"].toString(), Tweet::class.java)
                                    retweet.author = appUser
                                    // Retweet will be added via notification system
                                    // tweetFeedViewModel.addTweetToFeed(retweet) // Removed - use notifications instead
                                }
                            } catch (e: Exception) {
                                Timber.tag("UploadComment").e("${e.message}")
                                Toast.makeText(context, context.getString(R.string.comment_failed), Toast.LENGTH_SHORT).show()
                            }
                        }

                        WorkInfo.State.FAILED -> {
                            // Handle the failure and update UI
                            Timber.tag("UploadTweet").e("Tweet upload failed")
                            Toast.makeText(context, context.getString(R.string.comment_failed), Toast.LENGTH_SHORT).show()
                        }

                        WorkInfo.State.RUNNING -> {
                            // Optionally, show a progress indicator
                            Timber.tag("UploadTweet").d("Tweet upload in progress")
                        }

                        else -> {
                            // Handle other states if necessary
                        }
                    }
                }
            }
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
    suspend fun likeTweet(
        updateAppUser: (Tweet, Boolean) -> Unit     // callback to update current user's account.
    ) {
        val isFavorite = tweetState.value.favorites?.get(UserActions.FAVORITE) ?: false
        _tweetState.value.favorites?.set(UserActions.FAVORITE, ! isFavorite)
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
    suspend fun bookmarkTweet(updateAppUser: (Tweet, Boolean) -> Unit) {
        val hasBookmarked = tweetState.value.favorites?.get(UserActions.BOOKMARK) ?: false
        _tweetState.value.favorites?.set(UserActions.BOOKMARK, ! hasBookmarked)
        _tweetState.value = tweetState.value.copy(
            bookmarkCount = if (hasBookmarked) max(0, tweetState.value.bookmarkCount - 1)
            else tweetState.value.bookmarkCount + 1,
        )
        updateAppUser(tweetState.value, ! hasBookmarked)

        /**
         * Overwrite in-memory bookmark with result from database call that persists the change.
         * */
        _tweetState.value = HproseInstance.toggleBookmark(tweetState.value)
    }

    /**
     * Perform a retweet action and update the UI immediately for better user experience.
     * */
    suspend fun retweetTweet() {
        // Update retweet count and status immediately for better UX
        val hasRetweeted = tweetState.value.favorites?.get(UserActions.RETWEET) ?: false
        _tweetState.value.favorites?.set(UserActions.RETWEET, !hasRetweeted)
        _tweetState.value = tweetState.value.copy(
            retweetCount = if (hasRetweeted) max(0, tweetState.value.retweetCount - 1)
            else tweetState.value.retweetCount + 1,
        )
        
        // Perform the actual retweet operation
        try {
            HproseInstance.retweet(tweetState.value)
        } catch (e: Exception) {
            // Revert the UI changes if the retweet failed
            _tweetState.value.favorites?.set(UserActions.RETWEET, hasRetweeted)
            _tweetState.value = tweetState.value.copy(
                retweetCount = if (hasRetweeted) tweetState.value.retweetCount + 1
                else max(0, tweetState.value.retweetCount - 1),
            )
            throw e
        }
    }

    fun increaseRetweetCount() {
        _tweetState.value = tweetState.value.copy(retweetCount = tweetState.value.retweetCount + 1)
    }

    /**
     * Update retweet account on the original tweet after retweet is deleted.
     * */
    suspend fun updateRetweetCount(tweet: Tweet, retweetId: MimeiId, flag: Int) {
        HproseInstance.updateRetweetCount(tweet, retweetId, flag)?.let {
            _tweetState.value = it
        }
    }

    /**
     * Listen to tweet notifications and update the tweet detail accordingly
     */
    fun startListeningToNotifications() {
        viewModelScope.launch {
            try {
                TweetNotificationCenter.events.collect { event ->
                    when (event) {
                        is TweetEvent.CommentUploaded -> {
                            // Only handle if this is the parent tweet for the comment
                            if (event.parentTweet.mid == tweetState.value.mid) {
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
                        is TweetEvent.TweetLiked -> {
                            // Update like status if this is the same tweet
                            if (event.tweet.mid == tweetState.value.mid) {
                                _tweetState.value = event.tweet
                            }
                        }
                        is TweetEvent.TweetBookmarked -> {
                            // Update bookmark status if this is the same tweet
                            if (event.tweet.mid == tweetState.value.mid) {
                                _tweetState.value = event.tweet
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
