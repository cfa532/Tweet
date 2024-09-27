package com.fireshare.tweet.viewmodel

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat.startActivity
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.fireshare.tweet.HproseInstance
import com.fireshare.tweet.TweetApplication
import com.fireshare.tweet.datamodel.MimeiId
import com.fireshare.tweet.datamodel.Tweet
import com.fireshare.tweet.service.UploadCommentWorker
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
import kotlinx.serialization.json.Json

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

    private val _comments = MutableStateFlow<List<Tweet>>(emptyList())
    val comments: StateFlow<List<Tweet>> get() = _comments.asStateFlow()

    val isCheckedToTweet = mutableStateOf(false)
    fun onCheckedChange(value: Boolean) {
        isCheckedToTweet.value = value
    }

    fun savePreviousRoute(route: String) {
        savedStateHandle["previousRoute"] = route
    }

    fun getPreviousRoute(): String? {
        return savedStateHandle["previousRoute"]
    }

    fun loadComments(tweet: Tweet, pageNumber: Number = 0) {
        viewModelScope.launch(Dispatchers.IO) {
            _comments.value = HproseInstance.getComments(tweet)?.map {
                it.author = HproseInstance.getUserBase(it.authorId)
                it
            } ?: emptyList()
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
        val originTweet = if (isCheckedToTweet.value && tweetState.value.originalTweet != null) {
            tweetState.value.originalTweet
        } else tweetState.value
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
                            val outputData = workInfo.outputData
                            val json = outputData.getString("comment") ?: return@observe
//                            Log.d("UploadComment", "Tweet uploaded successfully: $json")
                            // Handle the success and update UI
                            val map = Json.decodeFromString<Map<String, String?>>(json)

                            // the comment also posted as tweet.
                            val retweet = if (isCheckedToTweet.value) {
                                map["retweet"]?.let { Json.decodeFromString<Tweet>(it) }
                            } else null

                            Log.d("UploadComment", "ReTweet: $retweet")
                            val comment = Json.decodeFromString<Tweet>(map["comment"]!!)

                            Log.d("UploadComment", "Comment: $comment")
                            _comments.update { list -> listOf(comment) + list }

                            // parent tweet with the new comment.
                            val newTweet = Json.decodeFromString<Tweet>(map["newTweet"]!!)
                            Log.d("UploadComment", "Updated tweet: $newTweet")
                            _tweetState.value = newTweet

                            if (retweet != null) {
                                retweet.originalTweet = comment
                                retweet.originalTweet!!.author = comment.author
                                tweetFeedViewModel.addTweet(retweet)
                            }
                        }

                        WorkInfo.State.FAILED -> {
                            // Handle the failure and update UI
                            Log.e("UploadTweet", "Tweet upload failed")
                        }

                        WorkInfo.State.RUNNING -> {
                            // Optionally, show a progress indicator
                            Log.d("UploadTweet", "Tweet upload in progress")
                        }

                        else -> {
                            // Handle other states if necessary
                        }
                    }
                }
            }
    }

    fun shareTweet(context: Context) {
        val baseUrl = TweetApplication.preferenceHelper.getAppUrl() ?: return
        val segs = baseUrl.split(".")
        val domain = if (segs.size >= 2) {
            "${segs[segs.size - 2]}.${segs.last()}"
        } else {
            baseUrl // Or provide a default value
        }
        val fallback = "?fallback=http://twbe.$domain/entry=render&ver=last&tid=${tweet.mid}"
        val deepLink = "http://$domain/tweet/${tweet.mid}$fallback"
        val sendIntent: Intent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, deepLink)
            type = "text/plain"
        }

        val shareIntent = Intent.createChooser(sendIntent, null)
        context.startActivity(shareIntent, null)
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
