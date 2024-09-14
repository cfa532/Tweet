package com.fireshare.tweet.viewmodel

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.mutableLongStateOf
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkInfo
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
import kotlinx.serialization.json.Json
import javax.inject.Inject

@HiltViewModel
class TweetFeedViewModel @Inject constructor() : ViewModel()
{
    private val _tweets = MutableStateFlow<List<Tweet>>(emptyList())
    val tweets: StateFlow<List<Tweet>> get() = _tweets.asStateFlow()

    // get all followings of current user, and load tweets.
    private val _followings = MutableStateFlow<List<MimeiId>>(emptyList())
    private val followings: StateFlow<List<MimeiId>> get() = _followings.asStateFlow()

    private var startTimestamp = mutableLongStateOf(System.currentTimeMillis())     // current time
    private var endTimestamp = mutableLongStateOf(System.currentTimeMillis() - 1000 * 60 * 60 * 72)     // previous time

    init {
        viewModelScope.launch(Dispatchers.IO) {
            _followings.value = HproseInstance.getFollowings(appUser) ?: emptyList()
            if (! followings.value.contains(appUser.mid))
                _followings.update { newList -> newList + appUser.mid }     // always follow oneself

            val list = HproseInstance.getAlphaIds().filter {
                !followings.value.contains(it)
            }
            _followings.update { newList -> newList + list }        // add default ones

//        getTweets(startTimestamp.longValue, endTimestamp.longValue)
            getTweets(startTimestamp.longValue)
        }
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
        // remove it from Mimei database
        viewModelScope.launch(Dispatchers.IO) {
            HproseInstance.delTweet(tweetId) { tid ->
                // remove it from the stateFlow
                _tweets.update { currentTweets ->
                    currentTweets.filterNot { it.mid == tid }
                }
            }
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

    fun getTweets(
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

        val workManager = WorkManager.getInstance(context)
        workManager.enqueue(uploadRequest)

        // Observe the work status
        workManager.getWorkInfoByIdLiveData(uploadRequest.id)
            .observe(context as LifecycleOwner) { workInfo ->
                if (workInfo != null) {
                    when (workInfo.state) {
                        WorkInfo.State.SUCCEEDED -> {
                            val outputData = workInfo.outputData
                            val json = outputData.getString("tweet")
                            Log.d("UploadTweet", "Tweet uploaded successfully: $json")
                            // Handle the success and update UI
                            val tweet = json?.let { Json.decodeFromString<Tweet>(it) }
                            if (tweet != null) {
                                tweet.author = appUser
                                addTweet(tweet)
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
}