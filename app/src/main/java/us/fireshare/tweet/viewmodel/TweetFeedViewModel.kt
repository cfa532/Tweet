package us.fireshare.tweet.viewmodel

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.mutableLongStateOf
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import timber.log.Timber
import us.fireshare.tweet.HproseInstance
import us.fireshare.tweet.HproseInstance.appUser
import us.fireshare.tweet.HproseInstance.dao
import us.fireshare.tweet.HproseInstance.getAlphaIds
import us.fireshare.tweet.HproseInstance.getUser
import us.fireshare.tweet.HproseInstance.loadCachedTweets
import us.fireshare.tweet.R
import us.fireshare.tweet.TweetApplication.Companion.applicationScope
import us.fireshare.tweet.datamodel.CachedUser
import us.fireshare.tweet.datamodel.MimeiId
import us.fireshare.tweet.datamodel.Tweet
import us.fireshare.tweet.datamodel.TweetActionListener
import us.fireshare.tweet.service.SnackbarController
import us.fireshare.tweet.service.SnackbarEvent
import us.fireshare.tweet.service.UploadTweetWorker
import javax.inject.Inject

@HiltViewModel
class TweetFeedViewModel @Inject constructor() : ViewModel()
{
    /**
     * tweetActionListener adds new tweet to the tweet list
     * of UserViewModel, so that new tweet appears in both viewModel.
     * */
    lateinit var tweetActionListener: TweetActionListener

    companion object {
        private const val TWEET_COUNT = 30
    }
    private val _tweets = MutableStateFlow<List<Tweet>>(emptyList())
    val tweets: StateFlow<List<Tweet>> get() = _tweets.asStateFlow()

    // Remember the current viewable tweet's mid, to keep a scrollable position.
    private val _scrollPosition = MutableStateFlow(Pair(0, 0))
    val scrollPosition: StateFlow<Pair<Int, Int>> get() = _scrollPosition.asStateFlow()
    private val _isScrolling = MutableStateFlow(false)
    val isScrolling: StateFlow<Boolean> get() = _isScrolling.asStateFlow()

    fun updateScrollPosition(scrollPosition: Pair<Int, Int>) {
        _scrollPosition.value = scrollPosition
    }
    fun setScrollingState(isScrolling: Boolean) {
        _isScrolling.value = isScrolling
    }

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
    private var startRank = mutableLongStateOf(0)   // most recent tweet
    private var endRank = mutableLongStateOf(startRank.longValue + TWEET_COUNT)

    init {
        // init tweet feed. Need to disable loadNewer and loadOlderTweets()
        // to prevent duplicated loading of tweets.
        viewModelScope.launch(Dispatchers.IO) {
            _tweets.value = emptyList()
            refresh(startRank.longValue, endRank.longValue)
            // reset startTime, keep endTime as is.
            startRank.longValue = 0
            initState.value = false
        }
    }

    /**
     * Called after login or logout(). Update current user's following list and tweets.
     * When new following is added or removed, _followings will be updated also.
     * */
    suspend fun refresh(
        startRank: Long,
        endRank: Long = startRank + TWEET_COUNT
    ) {
        dao.insertOrUpdateCachedUser(CachedUser(appUser.mid, appUser))
        getTweets(startRank, endRank)
    }

    suspend fun loadNewerTweets() {
        // prevent unnecessary run at first load when number of tweets are small
        if (initState.value) return
        try {
            _isRefreshingAtTop.value = true
            startRank.longValue = 0
            val endRank = startRank.longValue + TWEET_COUNT
            Timber.tag("loadNewerTweets")
                .d("start=${startRank.longValue}, end=$endRank")
            getTweets(startRank.longValue, endRank)
        } finally {
            _isRefreshingAtTop.value = false
        }
    }

    suspend fun loadOlderTweets() {
        if (initState.value) return
        try {
            _isRefreshingAtBottom.value = true
            val startRank = endRank.longValue
            endRank.longValue = startRank + TWEET_COUNT
            Timber.tag("loadOlderTweets")
                .d("start=$startRank, end=${endRank.longValue}")
            getTweets(startRank, endRank.longValue)
        } finally {
            _isRefreshingAtBottom.value = false // Ensure state is reset
        }
    }

    // Define a custom scope to ensure tweet deletion job not cancelled.
    private suspend fun getTweets(
        startRank: Long,   // starting backward to retrieve tweets.
        endRank: Long, // earlier in time, therefore smaller timestamp
    ) {
        /**
         * Show cached tweets before loading from net.
         * */
        val cachedTweets = loadCachedTweets(startRank, endRank)
        _tweets.update { currentTweets ->
            val allTweets = (cachedTweets + currentTweets)
                .filterNot { it.isPrivate }
                .distinctBy { it.mid }
                .sortedByDescending { it.timestamp }
            allTweets
        }
        /**
         * Load tweet from network
         * */
        HproseInstance.getTweetList(
            appUser,
            startRank,
            endRank,
        ).collect { newTweets ->
            _tweets.update { currentTweets ->
                // Order is important!! newTweets take priority over currentTweets
                val mergedTweets = (newTweets + currentTweets)
                    .filterNot { it.isPrivate }
                    .distinctBy { it.mid }
                    .sortedByDescending { it.timestamp }
                mergedTweets
            }
        }
    }

    // load tweets of the user during the time span.
    private suspend fun getTweets(userId: MimeiId) {
        try {
            getUser(userId)?.let { user ->
                HproseInstance.getTweetListByRank(
                    user,
                    0,
                ).collect { newTweets ->
                    _tweets.update { list ->
                        val mergedTweets = (newTweets + list)
                            .filterNot { it.isPrivate }
                            .distinctBy { it.mid }
                            .sortedByDescending { it.timestamp }
                        mergedTweets
                    }
                }
            }
        } catch (e: Exception) {
            Timber.tag("GetTweets").e(e, "Error fetching tweets for user: $userId")
        }
    }

    /**
     * When appUser toggles following status on a User, update followings list.
     * Remove it if unfollowing, add it if following. Update tweet feed list at the same time.
     * */
    suspend fun updateFollowingsTweets(userId: MimeiId, isFollowing: Boolean) {
        if (isFollowing) {
            // add the tweets of a user after following it.
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

    fun reset() {
        tweets.value.forEach {
            dao.deleteCachedTweet(it.mid)
        }
        _tweets.value = emptyList()
        _followings.value = getAlphaIds()
        startRank.longValue = 0
        endRank.longValue = startRank.longValue + TWEET_COUNT  // 30 days
    }

    /**
     * Add tweet to Feed list and user's viewModel when new tweet uploaded.
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

    // order of deletion is critical here.
    fun delTweet(
        navController: NavController,
        tweetId: MimeiId,
        callback: () -> Unit
    ) {
        // update UI first for better user experience.
        applicationScope.launch(IO) {
            dao.deleteCachedTweet(tweetId)
            _tweets.update { currentTweets ->
                currentTweets.filterNot { it.mid == tweetId }
            }
            tweetActionListener.onTweetDeleted(tweetId)     // userViewModel function
            HproseInstance.delTweet(tweetId)
            callback()
        }
        applicationScope.launch(Main) {
            if (navController.currentDestination?.route?.contains("TweetDetail") == true) {
                navController.popBackStack()
            }
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
                                    // notify user the result of tweet upload
                                    Toast.makeText(context, context.getString(R.string.tweet_uploaded), Toast.LENGTH_SHORT).show()
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