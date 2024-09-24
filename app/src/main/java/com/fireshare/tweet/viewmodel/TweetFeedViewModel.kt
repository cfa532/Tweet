package com.fireshare.tweet.viewmodel

import android.content.Context
import android.net.Uri
import android.util.Log
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
import com.fireshare.tweet.datamodel.TW_CONST
import com.fireshare.tweet.datamodel.Tweet
import com.fireshare.tweet.service.UploadTweetWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshingAtTop: StateFlow<Boolean> get() = _isRefreshing.asStateFlow()
    private val _isRefreshingAtBottom = MutableStateFlow(false)
    val isRefreshingAtBottom: StateFlow<Boolean> get() = _isRefreshingAtBottom.asStateFlow()

    // current time, end time is earlier in time, therefore smaller timestamp
    private var startTimestamp = System.currentTimeMillis()
    private var endTimestamp = startTimestamp - java.lang.Long.valueOf(2_592_000_000)  // 30 days

    init {
        _followings.update { list -> list + (appUser.followingList ?: HproseInstance.getAlphaIds()) }
        refresh()
    }

    fun loadNewerTweets() {
        println("At top already")
        _isRefreshing.value = true
        val endTimestamp = startTimestamp
        startTimestamp = System.currentTimeMillis()
        getTweets(startTimestamp, endTimestamp)
        _isRefreshing.value = false
    }
    fun loadOlderTweets() {
        println("At bottom already")
        _isRefreshingAtBottom.value = true
        val startTimestamp = endTimestamp
        endTimestamp = startTimestamp - java.lang.Long.valueOf(648_000_000)  // 7 days
        getTweets(startTimestamp, endTimestamp)
        _isRefreshingAtBottom.value = false
    }

    private fun getTweets(
        startTimestamp: Long = System.currentTimeMillis(),
        endTimestamp: Long? = null      // earlier in time
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            coroutineScope {  // Create a child coroutine scope
                followings.value.forEach { userId ->
                    launch(Dispatchers.IO) {
                        val tweetsList = HproseInstance.getTweetList(userId, startTimestamp, endTimestamp)
                        _tweets.update { currentTweets -> (currentTweets + tweetsList).sortedByDescending { it.timestamp } }
                    }
                }
            }
        }
    }

    // called after login or logout(). Update current user's following list within both calls.
    fun refresh() {
        _tweets.value = emptyList()
        _followings.update { appUser.followingList?.plus(HproseInstance.getAlphaIds()) ?: emptyList() }
        // remember to watch oneself.
        if (appUser.mid != TW_CONST.GUEST_ID) _followings.update { list -> list + appUser.mid }
        _followings.update { currentList ->
            // remove duplication tweets.
            val seenMids = mutableSetOf<MimeiId>()
            currentList.filter { seenMids.add(it) }
        }

        startTimestamp = System.currentTimeMillis()
        endTimestamp = startTimestamp - java.lang.Long.valueOf(2_592_000_000)  // 30 days
        getTweets(startTimestamp, endTimestamp)
        _isRefreshing.value = false
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