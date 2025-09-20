package us.fireshare.tweet.viewmodel

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.exoplayer.ExoPlayer
import androidx.work.BackoffPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
import java.lang.ref.WeakReference

@HiltViewModel(assistedFactory = TweetViewModel.TweetViewModelFactory::class)
class TweetViewModel @AssistedInject constructor(
    @Assisted private val tweet: Tweet,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {
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

    fun getAudioPlayer(url: String, context: Context): ExoPlayer {
        return exoPlayers.getOrPut(url) {
            createExoPlayer(context, url).also { player ->
                val position = savedStateHandle.get<Long>("playbackPosition_$url") ?: 0L
                player.seekTo(position)
                playbackPositions[url] = position
            }
        }
    }

    fun updateRetweetCount(tweet: Tweet) {
        _tweetState.value = tweetState.value.copy(retweetCount = tweet.retweetCount + 1)
        _tweetState.value = tweetState.value.copy(retweetCount = tweet.retweetCount - 1)
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
                HproseInstance.refreshTweet(tweet.mid, tweet.authorId)?.let { tweet ->
                    _tweetState.value = tweet
                }
            }
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
                    // Quoted tweet - refresh both the quoting tweet and the original tweet
                    HproseInstance.refreshTweet(currentTweet.mid, currentTweet.authorId)
                        ?.let { refreshedTweet ->
                            // Only update if the refreshed tweet has valid content
                            if (refreshedTweet.content != null || !refreshedTweet.attachments.isNullOrEmpty()) {
                                _tweetState.value = refreshedTweet
                            }
                        }

                    // Also refresh the original tweet that's displayed as a quote
                    HproseInstance.refreshTweet(
                        currentTweet.originalTweetId!!,
                        currentTweet.originalAuthorId!!
                    )?.let { originalTweet ->
                        Timber.tag("TweetViewModel")
                            .d("Refreshed original tweet for quoted tweet: ${originalTweet.mid}")
                    }
                }
            } else {
                // This is an original tweet - refresh it directly
                HproseInstance.refreshTweet(currentTweet.mid, currentTweet.authorId)
                    ?.let { refreshedTweet ->
                        // Only update if the refreshed tweet has valid content
                        if (refreshedTweet.content != null || !refreshedTweet.attachments.isNullOrEmpty()) {
                            _tweetState.value = refreshedTweet
                        }
                    }
            }
        } catch (e: Exception) {
            // Log error but don't update state to prevent content from disappearing
            Timber.tag("TweetViewModel").e(e, "Error refreshing tweet ${currentTweet.mid}")
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
        val newComments = HproseInstance.getComments(tweet, pageNumber.toInt())?.map {
            it.author = HproseInstance.getUser(it.authorId)
            it
        } ?: emptyList()

        // Always merge new comments with existing ones, keeping newly fetched ones over existing duplicates
        _comments.update { currentComments ->
            val mergedComments = newComments + currentComments.filter { it.mid !in newComments.map { new -> new.mid } }
            val finalComments = mergedComments.sortedByDescending { it.timestamp }
            Timber.tag("TweetViewModel").d("Merged to ${finalComments.size} total comments")
            finalComments
        }
    }

    suspend fun delComment(commentId: MimeiId) {
        // Remove manual UI updates - let notification system handle them
        HproseInstance.delComment(tweetState.value, commentId) {
            // Callback is kept for backward compatibility but UI updates are handled by notifications
        }
    }

    /**
     * Delete a comment with optimistic updates for better UX
     */
    suspend fun deleteComment(commentId: MimeiId, comment: Tweet) {
        try {
            // Optimistically remove the comment from the UI immediately
            optimisticallyRemoveComment(commentId)

            // Make the backend call
            delComment(commentId)
        } catch (e: Exception) {
            // If backend call fails, revert the optimistic update
            Timber.tag("TweetViewModel").e(e, "Error deleting comment: ${e.message}")
            revertCommentRemoval(comment)
            throw e // Re-throw to let UI handle the error if needed
        }
    }

    /**
     * Optimistically remove a comment from the UI immediately
     */
    fun optimisticallyRemoveComment(commentId: MimeiId) {
        _comments.update { currentComments ->
            currentComments.filterNot { it.mid == commentId }
        }

        // Update comment count
        _tweetState.value = tweetState.value.copy(
            commentCount = max(0, tweetState.value.commentCount - 1)
        )
    }

    /**
     * Revert optimistic comment removal (add comment back to UI)
     */
    fun revertCommentRemoval(comment: Tweet) {
        _comments.update { currentComments ->
            (currentComments + comment)
                .distinctBy { it.mid }
                .sortedByDescending { it.timestamp }
        }

        // Restore comment count
        _tweetState.value = tweetState.value.copy(
            commentCount = tweetState.value.commentCount + 1
        )
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
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                10_000L, // 10 seconds
                java.util.concurrent.TimeUnit.MILLISECONDS
            )
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

        // Generate share content based on tweet title or first 40 characters of content
        val shareContent = when {
            !tweet.title.isNullOrBlank() -> tweet.title!!
            !tweet.content.isNullOrBlank() -> {
                val content = tweet.content!!
                if (content.length <= 40) {
                    content
                } else {
                    "${content.take(40)}..."
                }
            }
            else -> ""
        }

        val textToShare = if (shareContent.isNotEmpty()) {
            "$shareContent\n\n$deepLink"
        } else {
            deepLink
        }

        val sendIntent: Intent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, textToShare)
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
        _tweetState.value.isFavorite = !isFavorite
        _tweetState.value = tweetState.value.copy(
            favoriteCount = if (isFavorite) max(0, tweetState.value.favoriteCount - 1)
            else tweetState.value.favoriteCount + 1,
        )

        /**
         * Get the actual server response and update with real data.
         * */
        val updatedTweet = HproseInstance.toggleFavorite(tweetState.value)

        // Check if the operation failed (if the tweet state didn't change)
        if (updatedTweet.isFavorite == isFavorite) {
            // Revert optimistic changes on failure
            _tweetState.value.isFavorite = isFavorite
            _tweetState.value = tweetState.value.copy(
                favoriteCount = if (isFavorite) tweetState.value.favoriteCount + 1
                else max(0, tweetState.value.favoriteCount - 1),
            )
            // Show error toast
            notificationContextRef?.get()?.let { context ->
                android.widget.Toast.makeText(
                    context,
                    "Failed to update favorite",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        } else {
            _tweetState.value = updatedTweet
            // Update UserViewModel with the updated appUser data from server
            updateAppUser(updatedTweet, updatedTweet.isFavorite)
        }
    }

    /**
     * Update bookmark count and icon right away for better user experience.
     * */
    suspend fun toggleBookmark(updateAppUser: (Tweet, Boolean) -> Unit) {
        val hasBookmarked = tweetState.value.isBookmarked
        _tweetState.value.isBookmarked = !hasBookmarked
        _tweetState.value = tweetState.value.copy(
            bookmarkCount = if (hasBookmarked) max(0, tweetState.value.bookmarkCount - 1)
            else tweetState.value.bookmarkCount + 1,
        )

        /**
         * Get the actual server response and update with real data.
         * If backend fails, the original value will be restored.
         * */
        val updatedTweet = HproseInstance.toggleBookmark(tweetState.value)

        // Check if the operation failed (if the tweet state didn't change)
        if (updatedTweet.isBookmarked == hasBookmarked) {
            // Revert optimistic changes on failure
            _tweetState.value.isBookmarked = hasBookmarked
            _tweetState.value = tweetState.value.copy(
                bookmarkCount = if (hasBookmarked) tweetState.value.bookmarkCount + 1
                else max(0, tweetState.value.bookmarkCount - 1),
            )
            // Show error toast
            notificationContextRef?.get()?.let { context ->
                android.widget.Toast.makeText(
                    context,
                    "Failed to update bookmark",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        } else {
            _tweetState.value = updatedTweet
            // Update UserViewModel with the updated appUser data from server
            updateAppUser(updatedTweet, updatedTweet.isBookmarked)
        }
    }

    /**
     * Perform a retweet action and update the UI immediately for better user experience.
     * */
    suspend fun retweetTweet() {
        // Optimistic update of retweet count and status immediately for better UX
        val currentCount = tweetState.value.retweetCount
        _tweetState.value = tweetState.value.copy(
            retweetCount = currentCount + 1,
        )

        // Perform the actual retweet operation
        try {
            HproseInstance.retweet(tweetState.value)
        } catch (e: Exception) {
            // Revert the UI changes if the retweet failed
            _tweetState.value = tweetState.value.copy(
                retweetCount = currentCount,
            )
            throw e
        }
    }

    private var notificationContextRef: WeakReference<Context>? = null

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
                Timber.tag("TweetViewModel")
                    .d("Starting notification listener for tweet ${tweetState.value.mid}")
                TweetNotificationCenter.events.collect { event ->
                    when (event) {
                        is TweetEvent.CommentUploaded -> {
                            // Only handle if this is the parent tweet for the comment
                            if (event.parentTweet.mid == tweetState.value.mid) {
                                Timber.tag("TweetViewModel")
                                    .d("CommentUploaded event received for tweet ${tweetState.value.mid}, comment ${event.comment.mid}, author ${event.comment.authorId}, current user ${appUser.mid}")

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
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.comment_failed),
                                        Toast.LENGTH_LONG
                                    ).show()
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
