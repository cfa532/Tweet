package com.fireshare.tweet.viewmodel

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
import com.fireshare.tweet.HproseInstance
import com.fireshare.tweet.HproseInstance.appUser
import com.fireshare.tweet.HproseInstance.updateCachedTweet
import com.fireshare.tweet.R
import com.fireshare.tweet.datamodel.MimeiFileType
import com.fireshare.tweet.datamodel.MimeiId
import com.fireshare.tweet.datamodel.Tweet
import com.fireshare.tweet.datamodel.UserFavorites
import com.fireshare.tweet.service.UploadCommentWorker
import com.fireshare.tweet.widget.createExoPlayer
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
                HproseInstance.getTweet(tweet.mid, tweet.authorId)?.let { tweet ->
                    _tweetState.value = tweet
                }
            }
        }
    }
    /**
     * Reload Tweet from database instead of cache.
     * */
    suspend fun refreshTweet() {
        HproseInstance.refreshTweet(tweet.mid, tweet.authorId)?.let { tweet ->
            if (tweet.originalTweetId != null) {
                HproseInstance.getTweet(tweet.originalTweetId!!, tweet.originalAuthorId!!)?.let {
                    tweet.originalTweet = it
                }
            }
            _tweetState.value = tweet
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
        _comments.update { currentComments ->
            currentComments.filterNot { it.mid == commentId }
        }
        _tweetState.value = tweet.copy(commentCount = max(0, tweet.commentCount - 1))

        HproseInstance.delComment(tweetState.value, commentId) {
            _tweetState.value = tweet.copy(commentCount = _comments.value.size)
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

                                val comment = gson.fromJson(map["comment"].toString(), Tweet::class.java)
                                comment.author = appUser
                                Timber.tag("UploadComment").d("Comment: $comment")
                                _comments.update { list -> listOf(comment) + list }

                                // Original tweet with the newly added comment.
                                val updatedTweet = gson.fromJson(map["updatedTweet"].toString(), Tweet::class.java)
                                Timber.tag("UploadComment").d("Update tweet: $updatedTweet")
                                _tweetState.value = updatedTweet

                                // the comment is also posted as an tweet.
                                if (map["retweet"].toString() != "null") {
                                    val retweet = gson.fromJson(map["retweet"].toString(), Tweet::class.java)
                                    retweet.originalTweet = updatedTweet
                                    retweet.author = appUser
                                    tweetFeedViewModel.addTweetToFeed(retweet)

                                    // update cached tweet in the database.
                                    viewModelScope.launch(Dispatchers.IO) {
                                        updateCachedTweet(updatedTweet)
                                    }
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
    suspend fun likeTweet(updateAppUser: (Tweet, Boolean) -> Unit) {
        val hasLiked = tweetState.value.favorites?.get(UserFavorites.LIKE_TWEET) ?: false
        _tweetState.value.favorites?.set(UserFavorites.LIKE_TWEET, ! hasLiked)
        _tweetState.value = tweetState.value.copy(
            likeCount = if (hasLiked) max(0, tweetState.value.likeCount - 1)
            else tweetState.value.likeCount + 1,
        )
        updateAppUser(tweetState.value, ! hasLiked)
        /**
         * Overwrite in-memory favorites with result from database call that persists the change.
         * */
        _tweetState.value = HproseInstance.likeTweet(tweetState.value)
    }

    /**
     * Update bookmark count and icon right away for better user experience.
     * */
    suspend fun bookmarkTweet(updateAppUser: (Tweet, Boolean) -> Unit) {
        val hasBookmarked = tweetState.value.favorites?.get(UserFavorites.BOOKMARK) ?: false
        _tweetState.value.favorites?.set(UserFavorites.BOOKMARK, ! hasBookmarked)
        _tweetState.value = tweetState.value.copy(
            bookmarkCount = if (hasBookmarked) max(0, tweetState.value.bookmarkCount - 1)
            else tweetState.value.bookmarkCount + 1,
        )
        updateAppUser(tweetState.value, ! hasBookmarked)

        /**
         * Overwrite in-memory bookmark with result from database call that persists the change.
         * */
        _tweetState.value = HproseInstance.bookmarkTweet(tweetState.value)
    }

    fun updateRetweetCount() {
        _tweetState.value = tweetState.value.copy(retweetCount = tweetState.value.retweetCount + 1)
    }
}
