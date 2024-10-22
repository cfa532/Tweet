package com.fireshare.tweet.viewmodel

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.mutableLongStateOf
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
import com.fireshare.tweet.TweetApplication
import com.fireshare.tweet.datamodel.MimeiId
import com.fireshare.tweet.datamodel.TW_CONST
import com.fireshare.tweet.datamodel.Tweet
import com.fireshare.tweet.datamodel.TweetActionListener
import com.fireshare.tweet.service.SnackbarController
import com.fireshare.tweet.service.SnackbarEvent
import com.fireshare.tweet.service.UploadTweetWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class TweetFeedViewModel @Inject constructor() : ViewModel()
{
    lateinit var tweetActionListener: TweetActionListener

    companion object {
        private const val THIRTY_DAYS_IN_MILLIS = 2_592_000_000L
        private const val SEVEN_DAYS_IN_MILLIS = 648_000_000L
        private const val ONE_DAY_IN_MILLIS = 86_400_000L
    }
    private val _tweets = MutableStateFlow<List<Tweet>>(emptyList())
    val tweets: StateFlow<List<Tweet>> get() = _tweets.asStateFlow()

    // get all followings of current user, and load tweets.
    private val _followings = MutableStateFlow<List<MimeiId>>(emptyList())
    private val followings: StateFlow<List<MimeiId>> get() = _followings.asStateFlow()

    var initState = MutableStateFlow(true)

    private val _isRefreshingAtTop = MutableStateFlow(false)
    val isRefreshingAtTop: StateFlow<Boolean> get() = _isRefreshingAtTop.asStateFlow()
    private val _isRefreshingAtBottom = MutableStateFlow(false)
    val isRefreshingAtBottom: StateFlow<Boolean> get() = _isRefreshingAtBottom.asStateFlow()

    // current time, end time is earlier in time, therefore smaller timestamp
    private var startTimestamp = mutableLongStateOf(System.currentTimeMillis())
    private var endTimestamp = mutableLongStateOf(System.currentTimeMillis() - THIRTY_DAYS_IN_MILLIS)  // 30 days

    init {
        viewModelScope.launch(Dispatchers.IO) {
            refresh()
            initState.value = false
        }
    }

    /**
     * Called after login or logout(). Update current user's following list and tweets.
     * When new following is added or removed, _followings will be updated also.
     * */
    fun refresh() {
        _followings.value = emptyList()
        // get current user's following list
        _followings.value = HproseInstance.getFollowings(appUser) ?: emptyList()
        // add default public tweets
        _followings.update { list -> (list + HproseInstance.getAlphaIds()).toSet().toList() }
        // remember to watch oneself.
        if (appUser.mid != TW_CONST.GUEST_ID && !_followings.value.contains(appUser.mid))
            _followings.update { list -> list + appUser.mid }
        Timber.tag("Refresh()").d(followings.value.toString())

        startTimestamp.longValue = System.currentTimeMillis()
        endTimestamp.longValue = startTimestamp.longValue - THIRTY_DAYS_IN_MILLIS
        getTweets(startTimestamp.longValue, endTimestamp.longValue)
    }

    fun loadNewerTweets() {
        // prevent unnecessary run at first load when number of tweets are small
        if (initState.value) return

        _isRefreshingAtTop.value = true
        startTimestamp.longValue = System.currentTimeMillis()
        val endTimestamp = startTimestamp.longValue - ONE_DAY_IN_MILLIS
        Timber.tag("loadNewerTweets")
            .d("startTimestamp=${startTimestamp.longValue}, endTimestamp=$endTimestamp")
        getTweets(startTimestamp.longValue, endTimestamp)
        _isRefreshingAtTop.value = false
    }

    fun loadOlderTweets() {
        if (initState.value) return

        _isRefreshingAtBottom.value = true
        val startTimestamp = endTimestamp.longValue
        endTimestamp.longValue = startTimestamp - SEVEN_DAYS_IN_MILLIS
        Timber.tag("loadOlderTweets")
            .d("startTimestamp=$startTimestamp, endTimestamp=${endTimestamp.longValue}")
        getTweets(startTimestamp, endTimestamp.longValue)
        _isRefreshingAtBottom.value = false
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val networkDispatcher = Dispatchers.IO.limitedParallelism(4)
    private fun getTweets(
        startTimestamp: Long,
        sinceTimestamp: Long,        // earlier in time, therefore smaller timestamp
    ) {
        val batchSize = 10 // Adjust batch size as needed

        followings.value.chunked(batchSize).forEach { batch ->
            viewModelScope.launch(networkDispatcher) {
                try {
                    batch.flatMap { userId ->
                        async {
                            try {
                                val tweetsList = getUserBase(userId)?.let {
                                    HproseInstance.getTweetList(it, startTimestamp, sinceTimestamp)
                                } ?: emptyList()

                                // Update _tweets with tweetsList immediately
                                _tweets.update { currentTweets ->
                                    (currentTweets + tweetsList).distinctBy { it.mid }
                                        .sortedByDescending { it.timestamp }
                                }

                                tweetsList // Return tweetsList for further processing if needed
                            } catch (e: Exception) {
                                Timber.tag("GetTweets in TweetFeedVM")
                                    .e(e, "Error fetching tweets for user: $userId")
                                // remove the userId from cached user list, the app will try to
                                // reacquire the user information
                                HproseInstance.removeUser(userId)
                                emptyList()
                            }
                        }.await()
                    }
                } catch (e: Exception) {
                    Timber.tag("GetTweets").e(e, "Error fetching tweets")
                    // in catastrophic events, such as lost of a service node,
                    // try to reacquire a valid serving node.
                    ioScope.launch {
                        HproseInstance.initAppEntry(TweetApplication.preferenceHelper)
                        refresh()
                    }
                }
            }
        }
    }

    private fun getTweets(userId: MimeiId) {
        ioScope.launch {
            try {
                val tweetsList = getUserBase(userId)?.let {
                    HproseInstance.getTweetList(it, startTimestamp.longValue, endTimestamp.longValue)
                } ?: return@launch
                _tweets.update { currentTweets ->
                    (currentTweets + tweetsList).distinctBy { it.mid }
                        .sortedByDescending { it.timestamp }
                }
            } catch (e: Exception) {
                Timber.tag("GetTweets").e(e, "Error fetching tweets for user: $userId")
            }
        }
    }

    fun addTweet(newTweet: Tweet) {
        _tweets.update { currentTweets -> listOf(newTweet) + currentTweets }
        tweetActionListener.onTweetAdded(newTweet)
    }

    // Define a custom scope to ensure tweet deletion job not cancelled.
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun delTweet(tweetId: MimeiId) {
        ioScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    HproseInstance.delTweet(tweetId) { tid ->
                        _tweets.update { currentTweets ->
                            currentTweets.filterNot { it.mid == tid }
                        }
                        tweetActionListener.onTweetDeleted(tweetId)
                    }
                }
            } catch (e: Exception) {
                Timber.tag("DelTweet").e(e, "Error deleting tweet")
            }
        }
    }

    /**
     * When appUser toggles following state on a User, update followings list.
     * Remove it if unfollowing, add it if following.
     * */
    fun updateFollowings(userId: MimeiId) {
        if (_followings.value.contains(userId)) {
            _followings.update { list -> list - userId }
            // remove all tweets of this user from list
            _tweets.update { currentTweets ->
                currentTweets.filterNot { it.author?.mid == userId }
            }
        } else {
            _followings.update { list -> list + userId }
            getTweets(userId)
        }
    }

    fun clearTweets() {
        _tweets.value = emptyList()
    }

    /**
     * If original tweet is null, forward the original tweet, otherwise the tweet itself.
     * Tweet object itself is updated in HproseInstance.toggleRetweet()
     * */
    fun toggleRetweet(tweet: Tweet, updateTweet: (Tweet) -> Unit) {
        ioScope.launch {
            val t = if (tweet.originalTweet != null) tweet.originalTweet!! else tweet
            HproseInstance.toggleRetweet(t, this@TweetFeedViewModel) { newTweet ->
                updateTweet(newTweet)
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
                            try {
                                val outputData = workInfo.outputData
                                val json = outputData.getString("tweet")
                                // Handle the success and update UI
                                val tweet = json?.let { Json.decodeFromString<Tweet>(it) }
                                Timber.tag("UploadTweet").d("Tweet uploaded successfully: $tweet")
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
                            } catch (e: Exception) {
                                Timber.tag("UploadTweet").e("$e")
                            }
                        }
                        WorkInfo.State.FAILED -> {
                            // Handle the failure and update UI
                            Timber.tag("UploadTweet").e("Tweet upload failed")
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
                            Timber.tag("UploadTweet").d("Tweet upload in progress")
                        }
                        else -> {
                            // Handle other states if necessary
                        }
                    }
                }
            }
    }
}