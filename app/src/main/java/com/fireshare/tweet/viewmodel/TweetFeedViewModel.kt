package com.fireshare.tweet.viewmodel

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.mutableLongStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.fireshare.tweet.HproseInstance
import com.fireshare.tweet.HproseInstance.appUser
import com.fireshare.tweet.datamodel.MimeiId
import com.fireshare.tweet.datamodel.Tweet
import com.fireshare.tweet.service.UploadTweetWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TweetFeedViewModel @Inject constructor(
) : ViewModel() {
    private val _tweets = MutableStateFlow<List<Tweet>>(emptyList())
    val tweets: StateFlow<List<Tweet>> get() = _tweets.asStateFlow()

    private val _followings = MutableStateFlow<List<MimeiId>>(emptyList())
    private val followings: StateFlow<List<MimeiId>> get() = _followings.asStateFlow()

    private var startTimestamp = mutableLongStateOf(System.currentTimeMillis())     // current time
    private var endTimestamp = mutableLongStateOf(System.currentTimeMillis() - 1000 * 60 * 60 * 72)     // previous time
    init {
        _followings.value = HproseInstance.getFollowings(appUser)
        _followings.update { newList -> newList + appUser.mid }     // always follow oneself and default ones
        val list = HproseInstance.getAlphaIds().filter { !followings.value.contains(it) }
        _followings.update { newList -> newList + list }

//        getTweets(startTimestamp.longValue, endTimestamp.longValue)
        getTweets(startTimestamp.longValue)
    }

    // given a tweet, update its counterpart in Tweet list
    fun updateTweet(tweet: Tweet) {
        _tweets.value = _tweets.value.map {
            if (it.mid == tweet.mid) tweet else it
        }
    }

    fun addTweet(tweet: Tweet) {
        _tweets.update { currentTweets -> listOf(tweet) + currentTweets }
    }

    fun delTweet(tweetId: MimeiId) {
        _tweets.update { currentTweets ->
            currentTweets.filterNot { it.mid == tweetId }
        }
    }

    fun toggleRetweet(tweet: Tweet, updateTweetViewModel: (Tweet) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            // tweet object is updated in toggleRetweet()
            HproseInstance.toggleRetweet( tweet, this@TweetFeedViewModel ) { newTweet ->
                updateTweetViewModel(newTweet)
            }
        }
    }

    private fun getTweets(
        startTimestamp: Long = System.currentTimeMillis(),
        endTimestamp: Long? = null
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            coroutineScope {  // Create a child coroutine scope
                followings.value.forEach { userId ->
                    launch(Dispatchers.IO) {
                        val tweetsList = _tweets.value.filter { it.authorId == userId }.toMutableList()
                        HproseInstance.getTweetList(userId, tweetsList, startTimestamp, endTimestamp)
                        _tweets.update { currentTweets -> currentTweets + tweetsList }
                    }
                }
            }
        }
    }

    fun uploadTweet(context: Context, content: String, attachments: List<Uri>?) {
        val data = workDataOf(
            "tweetContent" to content,
            "attachmentUris" to attachments?.map { it.toString() }?.toTypedArray()
        )
        val uploadRequest = OneTimeWorkRequest.Builder(UploadTweetWorker::class.java)
            .setInputData(data)
            .build()

        WorkManager.getInstance(context).enqueue(uploadRequest)

        // Update UI (optional)
        // You can show a progress indicator while the upload is happening
    }
}