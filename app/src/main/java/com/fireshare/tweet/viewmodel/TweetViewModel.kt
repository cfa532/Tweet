package com.fireshare.tweet.viewmodel

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import com.fireshare.tweet.HproseInstance.increaseRetweetCount
import com.fireshare.tweet.HproseInstance.tweetCache
import com.fireshare.tweet.datamodel.CachedTweet
import com.fireshare.tweet.datamodel.MimeiFileType
import com.fireshare.tweet.datamodel.MimeiId
import com.fireshare.tweet.datamodel.Tweet
import com.fireshare.tweet.service.UploadCommentWorker
import com.fireshare.tweet.widget.createExoPlayer
import com.google.gson.Gson
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

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
    val tweetAttachments = tweet.attachments

    private val exoPlayers = mutableMapOf<String, ExoPlayer>()
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
    fun refreshTweet() {
        viewModelScope.launch(Dispatchers.IO) {
            HproseInstance.refreshTweet(tweet.mid, tweet.authorId)?.let { tweet ->
                if (tweet.originalTweetId != null)
                    HproseInstance.getTweet(tweet.originalTweetId!!, tweet.originalAuthorId!!)?.let {
                        tweet.originalTweet = it
                    }
                _tweetState.value = tweet
            }
        }
    }

    /**
     * when composing a comment, also post it as a tweet or not.
     * */
    val isCheckedToTweet = mutableStateOf(false)
    fun onCheckedChange(value: Boolean) {
        isCheckedToTweet.value = value
    }

    fun loadComments(tweet: Tweet, pageNumber: Number = 0) {
        viewModelScope.launch(Dispatchers.IO) {
            _comments.value = HproseInstance.getComments(tweet)?.map {
                it.author = HproseInstance.getUserBase(it.authorId)
                it
            } ?: emptyList()
            _comments.update { list ->
                list.sortedByDescending { it.timestamp }
            }
        }
    }

    fun updateTweet(tweet: Tweet) {
        _tweetState.value = tweet.copy()
    }

    fun delComment(commentId: MimeiId) {
        viewModelScope.launch(Dispatchers.IO) {
            HproseInstance.delComment(tweetState.value, commentId) { tid ->
                _comments.update { currentComments ->
                    currentComments.filterNot { it.mid == tid }
                }
                updateTweet(tweet.copy(commentCount = _comments.value.size))
            }
        }
    }
    // add new Comment object to its parent Tweet
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

        // Observe the work status
        workManager.getWorkInfoByIdLiveData(uploadRequest.id)
            .observe(context as LifecycleOwner) { workInfo ->
                if (workInfo != null) {
                    when (workInfo.state) {
                        WorkInfo.State.SUCCEEDED -> {
                            try {
                                val outputData = workInfo.outputData
                                val json = outputData.getString("comment") ?: return@observe
                                Timber.tag("UploadComment").d("Tweet uploaded successfully: $json")
                                // Handle the success and update UI
                                val map = gson.fromJson(json, Map::class.java) as Map<*, *>

                                var comment = gson.fromJson(map["comment"].toString(), Tweet::class.java)
                                comment = comment.copy(author = appUser)
                                Timber.tag("UploadComment").d("Comment: $comment")
                                _comments.update { list -> listOf(comment) + list }

                                // parent tweet with the new comment.
                                val newTweet = gson.fromJson(map["newTweet"].toString(), Tweet::class.java)
                                Timber.tag("UploadComment").d("Updated tweet: $newTweet")
                                _tweetState.value = newTweet

                                // the comment also posted as tweet.
                                val retweet =
                                    map["retweet"]?.let { gson.fromJson(it.toString(), Tweet::class.java) }
                                if (retweet != null) {
                                    retweet.originalTweet = newTweet
                                    retweet.originalTweet!!.author = newTweet.author
                                    tweetFeedViewModel.addTweet(retweet)

                                    viewModelScope.launch(Dispatchers.IO) {
                                        increaseRetweetCount(tweet, retweet.mid)?.let { t ->
                                            updateTweet(t.copy(author = tweetState.value.author))

                                            // update cached tweet in the database.
                                            tweetCache.tweetDao().updateCachedTweet(
                                                CachedTweet(tweet.mid, Gson().toJson(t))
                                            )
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                Timber.tag("UploadComment").e("${e.message}")
                            }
                        }

                        WorkInfo.State.FAILED -> {
                            // Handle the failure and update UI
                            Timber.tag("UploadTweet").e("Tweet upload failed")
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

//    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    fun shareTweet(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            /**
             * Call to checkUpgrade() also returns a map of environmental variables,
             * which includes environment variables of the App.
             * */
            val map = HproseInstance.checkUpgrade() ?: return@launch
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
    }

    fun likeTweet() {
        viewModelScope.launch(Dispatchers.IO) {
            val tweet = HproseInstance.likeTweet(tweetState.value)
            _tweetState.value = tweet
        }
    }

    fun bookmarkTweet() {
        viewModelScope.launch(Dispatchers.IO) {
            val tweet = HproseInstance.bookmarkTweet(tweetState.value)
            _tweetState.value = tweet
        }
    }
}
