package com.fireshare.tweet.viewmodel

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.fireshare.tweet.HproseInstance
import com.fireshare.tweet.HproseInstance.appUser
import com.fireshare.tweet.datamodel.Tweet
import com.fireshare.tweet.service.UploadCommentWorker
import com.fireshare.tweet.service.UploadTweetWorker
import com.google.gson.Gson
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@HiltViewModel(assistedFactory = TweetViewModel.TweetViewModelFactory::class)
class TweetViewModel @AssistedInject constructor(
    @Assisted private val tweet: Tweet,
//    @ApplicationContext private val context: Context
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

    fun loadComments(tweet: Tweet, pageNumber: Number = 0) {
        viewModelScope.launch(Dispatchers.IO) {
            _comments.value = HproseInstance.getComments(tweet).map {
                it.author = HproseInstance.getUserBase(it.authorId)
                it
            }
        }
    }

    fun updateTweet(tweet: Tweet) {
        _tweetState.value = tweet
    }

    // add new Comment object to its parent Tweet
    fun uploadComment(context: Context, content: String, attachments: List<Uri>? = null ) {
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
                            val retweet = if (isCheckedToTweet.value) {
                                map["retweet"]?.let { Json.decodeFromString<Tweet>(it) }
                            } else null

                            Log.d("UploadComment", "ReTweet: $retweet")
                            val comment = Json.decodeFromString<Tweet>(map["comment"]!!)
                            Log.d("UploadComment", "Comment: $comment")
//                            comment.author = appUser
                            _comments.update { list -> listOf(comment) + list }

                            val newTweet = Json.decodeFromString<Tweet>(map["newTweet"]!!)
                            Log.d("UploadComment", "Updated tweet: $newTweet")
                            _tweetState.value = newTweet
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

    fun likeTweet(updateTweetFeed: (Tweet) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            HproseInstance.likeTweet(tweetState.value)
            _tweetState.value = tweet
        }
    }

    fun bookmarkTweet(updateTweetFeed: (Tweet) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            HproseInstance.bookmarkTweet(tweetState.value)
            _tweetState.value = tweet
        }
    }
}
