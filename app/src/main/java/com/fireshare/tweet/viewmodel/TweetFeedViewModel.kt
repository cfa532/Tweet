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
import com.fireshare.tweet.HproseInstance.getUser
import com.fireshare.tweet.HproseInstance.tweetCache
import com.fireshare.tweet.R
import com.fireshare.tweet.datamodel.MimeiId
import com.fireshare.tweet.datamodel.TW_CONST
import com.fireshare.tweet.datamodel.Tweet
import com.fireshare.tweet.datamodel.TweetActionListener
import com.fireshare.tweet.datamodel.UserData
import com.fireshare.tweet.service.SnackbarController
import com.fireshare.tweet.service.SnackbarEvent
import com.fireshare.tweet.service.UploadTweetWorker
import com.fireshare.tweet.widget.Gadget.splitJson
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import timber.log.Timber
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@HiltViewModel
class TweetFeedViewModel @Inject constructor() : ViewModel()
{
    /**
     * tweetActionListener adds new tweet to the tweet list
     * of UserViewModel, so that new tweet appears in both viewModel.
     * */
    lateinit var tweetActionListener: TweetActionListener

    companion object {
        private const val THIRTY_DAYS_IN_MILLIS = 2 * 2_592_000_000L
        private const val SEVEN_DAYS_IN_MILLIS = 648_000_000L
        private const val ONE_DAY_IN_MILLIS = 86_400_000L
    }
    private val _tweets = MutableStateFlow<List<Tweet>>(emptyList())
    val tweets: StateFlow<List<Tweet>> get() = _tweets.asStateFlow()

    // get all followings of current user, and load tweets.
    private val _followings = MutableStateFlow<List<MimeiId>>(emptyList())
    private val followings: StateFlow<List<MimeiId>> get() = _followings.asStateFlow()

    // Indicate the first time TweeFeed screen is loading.
    var initState = MutableStateFlow(true)      // initial load state

    private val _isRefreshingAtTop = MutableStateFlow(false)
    val isRefreshingAtTop: StateFlow<Boolean> get() = _isRefreshingAtTop.asStateFlow()
    private val _isRefreshingAtBottom = MutableStateFlow(false)
    val isRefreshingAtBottom: StateFlow<Boolean> get() = _isRefreshingAtBottom.asStateFlow()

    // current time, end time is earlier in time, therefore smaller than current time.
    private var startTimestamp = mutableLongStateOf(System.currentTimeMillis())
    private var endTimestamp = mutableLongStateOf(System.currentTimeMillis() - THIRTY_DAYS_IN_MILLIS)  // 30 days

    init {
        // init tweet feed. Need to disable loadNewer and loadOlderTweets() to prevent
        // duplicated loading of tweets.
        viewModelScope.launch(Dispatchers.IO) {
            refresh()
            delay(2000)
            initState.value = false
        }
    }

    /**
     * Called after login or logout(). Update current user's following list and tweets.
     * When new following is added or removed, _followings will be updated also.
     * */
    suspend fun refresh() {
        // get cached followings and load cached tweets.
        val cachedFollowings = tweetCache.tweetDao().getCachedFollowings()?: return
        if (cachedFollowings.isNotEmpty()) {
            splitJson(cachedFollowings)?.let {
                startTimestamp.longValue = System.currentTimeMillis()
                endTimestamp.longValue = startTimestamp.longValue - THIRTY_DAYS_IN_MILLIS
                getTweets(startTimestamp.longValue, endTimestamp.longValue, it)
            }
        }

        // get followings from server and load tweets not cached.
        _followings.value = HproseInstance.getFollowings(appUser) ?: emptyList()
        // add default system users' tweets and remember to watch oneself.
        _followings.update { list -> (list + HproseInstance.getAlphaIds() ).toSet().toList() }
        if (appUser.mid !== TW_CONST.GUEST_ID)
            _followings.update { list -> (list + appUser.mid ).toSet().toList() }
        getTweets(startTimestamp.longValue, endTimestamp.longValue, followings.value)

        // update cached following list of the user
        val userData = UserData(userId = appUser.mid, followings = followings.value)
        tweetCache.tweetDao().insertOrUpdateUserData(userData)
    }

    suspend fun loadNewerTweets() {
        // prevent unnecessary run at first load when number of tweets are small
        if (initState.value) return
        _isRefreshingAtTop.value = true
        try {
            startTimestamp.longValue = System.currentTimeMillis()
            val endTimestamp = startTimestamp.longValue - ONE_DAY_IN_MILLIS
            Timber.tag("loadNewerTweets")
                .d("startTimestamp=${startTimestamp.longValue}, endTimestamp=$endTimestamp")
            getTweets(startTimestamp.longValue, endTimestamp, followings.value)
        } finally {
            _isRefreshingAtTop.value = false
        }
    }

    suspend fun loadOlderTweets() {
        if (initState.value) return
        _isRefreshingAtBottom.value = true
        try {
            val startTimestamp = endTimestamp.longValue
            endTimestamp.longValue = startTimestamp - SEVEN_DAYS_IN_MILLIS
            Timber.tag("loadOlderTweets")
                .d("startTimestamp=$startTimestamp, endTimestamp=${endTimestamp.longValue}")
            getTweets(startTimestamp, endTimestamp.longValue, followings.value)
        } finally {
            _isRefreshingAtBottom.value = false // Ensure state is reset
        }
    }

    // Define a custom scope to ensure tweet deletion job not cancelled.
    private val networkDispatcher = Dispatchers.IO.limitedParallelism(4)
    private suspend fun getTweets(
        startTimestamp: Long,
        sinceTimestamp: Long, // earlier in time, therefore smaller timestamp
        followings: List<MimeiId>
    ) {
        val batchSize = 10 // Adjust batch size as needed
        followings.chunked(batchSize).forEach { batch ->
            withContext(networkDispatcher) {
                batch.forEach { userId ->
                    try {
                        getUser(userId)?.let { user ->
                            HproseInstance.getTweetList(
                                user,
                                tweets.value,
                                startTimestamp,
                                sinceTimestamp
                            ).collect { tweets ->
                                _tweets.update { list -> (list + tweets)
                                        .distinctBy { it.mid }
                                        .sortedByDescending { it.timestamp }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Timber.tag("GetTweets in TFVM")
                            .e(e, "Error fetching tweets for user: $userId")
                        HproseInstance.removeCachedUser(userId)
                    }
                }
            }
        }
    }

    private suspend fun getTweets(userId: MimeiId) {
        try {
            getUser(userId)?.let { uid ->
                HproseInstance.getTweetList(uid, tweets.value, startTimestamp.longValue, endTimestamp.longValue)
                    .collect { tweets ->
                        _tweets.update { list -> (list + tweets)
                            .distinctBy { it.mid }
                            .sortedByDescending { it.timestamp } }
                    }
            }
        } catch (e: Exception) {
            Timber.tag("GetTweets").e(e, "Error fetching tweets for user: $userId")
        }
    }

    suspend fun delTweet(tweet: Tweet, updateOriginTweet: () -> Unit) {
        // remove from userViewModel's feed
        tweetActionListener.onTweetDeleted(tweet.mid)
        _tweets.update { currentTweets ->
            currentTweets.filterNot { it.mid == tweet.mid }
        }
        HproseInstance.delTweet(tweet) {
            // If there is an original tweet, update its viewModel.
            updateOriginTweet()
        }
    }

    /**
     * When appUser toggles following state on a User, update followings list.
     * Remove it if unfollowing, add it if following. Update tweet feed list at the same time.
     * */
    suspend fun updateFollowingsTweets(userId: MimeiId, isFollowing: Boolean) {
        if (isFollowing) {
            // Get the tweets of a user after following it.
            _followings.update { list -> list + userId }
            getTweets(userId)
        } else {
            _followings.update { list -> list - userId }
            // remove all tweets of this user from list after unfollowing it.
            _tweets.update { currentTweets ->
                currentTweets.filterNot { it.authorId == userId }
            }
        }
    }

    suspend fun reset() {
        tweets.value.forEach {
            tweetCache.tweetDao().deleteCachedTweetAndRemoveFromMidList(it.mid, it.authorId)
        }
        _tweets.value = emptyList()
        _followings.value = HproseInstance.getAlphaIds()
        startTimestamp = mutableLongStateOf(System.currentTimeMillis())
        endTimestamp = mutableLongStateOf(System.currentTimeMillis() - THIRTY_DAYS_IN_MILLIS)  // 30 days
    }

    /**
     * Add tweet to Feed list and user's viewModel. For display.
     * */
    fun addTweetToFeed(newTweet: Tweet) {
        _tweets.update { currentTweets -> (listOf(newTweet) + currentTweets)
            .distinctBy { it.mid }
            .sortedByDescending { it.timestamp }
        }
        tweetActionListener.onTweetAdded(newTweet)
    }

    /**
     * If original tweet is not null, retweet the original tweet,
     * otherwise retweet the tweet itself.
     * */
    suspend fun addRetweet(tweet: Tweet) {
        val t = if (tweet.originalTweetId != null) tweet.originalTweet!! else tweet
        HproseInstance.retweet(t) {
            addTweetToFeed(it)
        }
    }

    /**
     * Use WorkManager to update tweet. When the upload succeeds, a message will be sent back.
     * Show a snackbar to inform user of the result.
     * */
    fun uploadTweet(context: Context, content: String, attachments: List<Uri>?, isPrivate: Boolean = false) {
        val data = workDataOf(
            "tweetContent" to content,
            "attachmentUris" to attachments?.map { it.toString() }?.toTypedArray(),
            "isPrivate" to isPrivate
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

                                    // add tweet to TweetFeedViewModel's list
                                    addTweetToFeed(tweet)

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