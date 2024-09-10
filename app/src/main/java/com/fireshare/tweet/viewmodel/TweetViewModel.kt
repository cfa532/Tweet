package com.fireshare.tweet.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.fireshare.tweet.HproseInstance
import com.fireshare.tweet.datamodel.Tweet
import com.fireshare.tweet.service.UploadCommentWorker
import com.fireshare.tweet.service.UploadTweetWorker
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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@HiltViewModel(assistedFactory = TweetViewModel.TweetViewModelFactory::class)
class TweetViewModel @AssistedInject constructor(
    @Assisted private val tweet: Tweet,
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
    fun uploadComment( context: Context, content: String, isChecked: Boolean, attachments: List<Uri>? = null ) {
        val data = workDataOf(
            "tweet" to Json.encodeToString(tweetState.value),
            "isChecked" to isChecked,
            "tweetContent" to content,
            "attachmentUris" to attachments?.map { it.toString() }?.toTypedArray()
        )
        val uploadRequest = OneTimeWorkRequest.Builder(UploadCommentWorker::class.java)
            .setInputData(data)
            .build()
        WorkManager.getInstance(context).enqueue(uploadRequest)
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
