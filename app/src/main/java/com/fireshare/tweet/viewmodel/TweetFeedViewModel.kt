package com.fireshare.tweet.viewmodel

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.compose.material3.Snackbar
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.core.content.ContextCompat.getString
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.fireshare.tweet.HproseInstance
import com.fireshare.tweet.HproseInstance.appUser
import com.fireshare.tweet.HproseInstance.getUserBase
import com.fireshare.tweet.R
import com.fireshare.tweet.datamodel.MimeiId
import com.fireshare.tweet.datamodel.TW_CONST
import com.fireshare.tweet.datamodel.Tweet
import com.fireshare.tweet.navigation.NavTweet
import com.fireshare.tweet.service.SnackbarAction
import com.fireshare.tweet.service.SnackbarController
import com.fireshare.tweet.service.SnackbarEvent
import com.fireshare.tweet.service.UploadTweetWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.delay

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

    companion object {
        private const val THIRTY_DAYS_IN_MILLIS = 2_592_000_000L
        private const val SEVEN_DAYS_IN_MILLIS = 648_000_000L
        private const val ONE_DAY_IN_MILLIS = 86_400_000L
    }

    // called after login or logout(). Update current user's following list within both calls.
    fun refresh() {
//        _tweets.value = emptyList()
        viewModelScope.launch(Dispatchers.IO) {
            _followings.value = HproseInstance.getFollowings(appUser) ?: emptyList()
            _followings.update { list -> (list + HproseInstance.getAlphaIds()).toSet().toList() }
            if (appUser.mid != TW_CONST.GUEST_ID && !_followings.value.contains(appUser.mid))
                _followings.update { list -> list + appUser.mid }   // remember to watch oneself.

            startTimestamp = System.currentTimeMillis()
            endTimestamp = startTimestamp - THIRTY_DAYS_IN_MILLIS
            Log.d("TweetFeedVM.refresh", "${followings.value}")
            getTweets(startTimestamp, endTimestamp)
            _isRefreshing.value = false
        }
    }

    fun loadNewerTweets() {
        println("At top already")
        _isRefreshing.value = true
//        val endTimestamp = startTimestamp
        startTimestamp = System.currentTimeMillis()
        val endTimestamp = startTimestamp - ONE_DAY_IN_MILLIS
        Log.d("TweetFeedVM.loadNewerTweets", "startTimestamp=$startTimestamp, endTimestamp=$endTimestamp")
        getTweets(startTimestamp, endTimestamp)
        _isRefreshing.value = false
    }
    suspend fun loadOlderTweets() {
        println("At bottom already")
        _isRefreshingAtBottom.value = true
        val startTimestamp = endTimestamp
        endTimestamp = startTimestamp - SEVEN_DAYS_IN_MILLIS
        Log.d("TweetFeedVM.loadOlderTweets", "startTimestamp=$startTimestamp, endTimestamp=$endTimestamp")
        getTweets(startTimestamp, endTimestamp)
        delay(500)
        _isRefreshingAtBottom.value = false
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val networkDispatcher = Dispatchers.IO.limitedParallelism(4)
    private fun getTweets(
        startTimestamp: Long,
        sinceTimestamp: Long        // earlier in time, therefore smaller timestamp
    ) {
        val batchSize = 10 // Adjust batch size as needed
        followings.value.chunked(batchSize).forEach { batch ->
            viewModelScope.launch(networkDispatcher) {
                try {
                    val newTweets = batch.flatMap { userId ->
                        async {
                            try {
                                getUserBase(userId)?.let {
                                    HproseInstance.getTweetList(it, startTimestamp, sinceTimestamp)
                                } ?: emptyList()
                            } catch (e: Exception) {
                                Log.e("GetTweets", "Error fetching tweets for user: $userId", e)
                                emptyList()
                            }
                        }.await()
                    }

                    _tweets.update { currentTweets ->
                        val updatedTweets = currentTweets.toMutableList()
                        newTweets.forEach { newTweet ->
                            val existingTweetIndex = updatedTweets.indexOfFirst { it.mid == newTweet.mid }
                            if (existingTweetIndex != -1) {
                                updatedTweets[existingTweetIndex] = newTweet // Replace existing tweet
                            } else {
                                updatedTweets.add(newTweet) // Add new tweet
                            }
                        }
                        updatedTweets.distinctBy { it.mid }.sortedByDescending { it.timestamp }
                    }
                } catch (e: Exception) {
                    Log.e("GetTweets", "Error fetching tweets", e)
                }
            }
        }
    }

    fun addTweet(tweet: Tweet) {
        appUser.tweetCount += 1
        _tweets.update { currentTweets -> listOf(tweet) + currentTweets }
    }

    fun delTweet(tweetId: MimeiId) {
        viewModelScope.launch(Dispatchers.IO) {
            HproseInstance.delTweet(tweetId) { tid ->
                _tweets.update { currentTweets ->
                    appUser.tweetCount -= 1
                    currentTweets.filterNot { it.mid == tid }
                }
            }
        }
    }

    fun toggleRetweet(tweet: Tweet, updateTweetViewModel: (Tweet) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            // tweet object is updated in toggleRetweet()
            // if this is a retweet with comments, only forward the original tweet.
            val t = if (tweet.originalTweet != null) tweet.originalTweet!! else tweet
            HproseInstance.toggleRetweet( t, this@TweetFeedViewModel ) { newTweet ->
                updateTweetViewModel(newTweet)
            }
        }
    }

    /**
     * Use WorkManager to update tweet. When the upload succeeds, a message will be sent back.
     * Show a snackbar to inform user of the result.
     * */
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
                            // Handle the success and update UI
                            val tweet = json?.let { Json.decodeFromString<Tweet>(it) }
                            Log.d("UploadTweet", "Tweet uploaded successfully: $tweet")
                            if (tweet != null) {
                                tweet.author = appUser
                                // to add tweet in background involves problem with viewModel
                                // in tweet list. Do NOT update tweet list, just inform user.
                                addTweet(tweet)
                                (context as? LifecycleOwner)?.lifecycleScope?.launch {
                                    SnackbarController.sendEvent(
                                        event = SnackbarEvent(
                                            message = context.getString(R.string.tweet_uploaded)
                                        )
                                    )
                                }
                            }
                        }
                        WorkInfo.State.FAILED -> {
                            // Handle the failure and update UI
                            Log.e("UploadTweet", "Tweet upload failed")
                            (context as? LifecycleOwner)?.lifecycleScope?.launch {
                                SnackbarController.sendEvent(
                                    event = SnackbarEvent(
                                        message = context.getString(R.string.tweet_failed)
                                    )
                                )
                            }
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