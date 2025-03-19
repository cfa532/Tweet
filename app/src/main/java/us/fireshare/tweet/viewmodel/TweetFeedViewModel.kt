package us.fireshare.tweet.viewmodel

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.mutableIntStateOf
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import timber.log.Timber
import us.fireshare.tweet.HproseInstance
import us.fireshare.tweet.HproseInstance.appUser
import us.fireshare.tweet.HproseInstance.dao
import us.fireshare.tweet.HproseInstance.getAlphaIds
import us.fireshare.tweet.HproseInstance.getFollowings
import us.fireshare.tweet.HproseInstance.getUser
import us.fireshare.tweet.HproseInstance.loadCachedTweets
import us.fireshare.tweet.R
import us.fireshare.tweet.datamodel.CachedUser
import us.fireshare.tweet.datamodel.MimeiId
import us.fireshare.tweet.datamodel.Tweet
import us.fireshare.tweet.datamodel.TweetActionListener
import us.fireshare.tweet.datamodel.isGuest
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
        private const val THIRTY_DAYS_IN_MILLIS = 648_000_000L  // 2_592_000_000L
        private const val SEVEN_DAYS_IN_MILLIS = 648_000_000L
        private const val ONE_DAY_IN_MILLIS = 86_400_000L
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
    private var startTimestamp = mutableLongStateOf(System.currentTimeMillis())
    private var endTimestamp = mutableLongStateOf(System.currentTimeMillis() - THIRTY_DAYS_IN_MILLIS)  // 30 days
    private var retryCount = mutableIntStateOf(0)

    init {
        // init tweet feed. Need to disable loadNewer and loadOlderTweets()
        // to prevent duplicated loading of tweets.
        viewModelScope.launch(Dispatchers.IO) {
            _tweets.value = emptyList()
            while(retryCount.intValue < 10 && tweets.value.size < 4) {
                refresh(startTimestamp.longValue, endTimestamp.longValue)
                delay(1000)
                retryCount.intValue++
                startTimestamp.longValue = endTimestamp.longValue
                endTimestamp.longValue = startTimestamp.longValue - THIRTY_DAYS_IN_MILLIS
            }
            // reset startTime, keep endTime as is.
            startTimestamp.longValue = System.currentTimeMillis()
            initState.value = false
        }
    }

    /**
     * Called after login or logout(). Update current user's following list and tweets.
     * When new following is added or removed, _followings will be updated also.
     * */
    suspend fun refresh(
        startTime: Long = System.currentTimeMillis(),
        endTime: Long = startTime - THIRTY_DAYS_IN_MILLIS
    ) {
        // get followings from server and load tweets not cached.
        _followings.value = if (appUser.isGuest()) getAlphaIds() else
            getFollowings(appUser)

        // add default system users' tweets and remember to watch oneself.
        _followings.update { list -> (list + getAlphaIds() ).toSet().toList() }
        if (! appUser.isGuest())
            _followings.update { list -> (list + appUser.mid ).toSet().toList() }

        dao.insertOrUpdateCachedUser(CachedUser(appUser.mid, appUser))

        getTweets(startTime, endTime, followings.value)
    }

    suspend fun loadNewerTweets() {
        // prevent unnecessary run at first load when number of tweets are small
        if (initState.value) return
        try {
            _isRefreshingAtTop.value = true
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
        try {
            _isRefreshingAtBottom.value = true
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
        /**
         * Show cached tweets before loading from net.
         * */
        val cachedTweets = loadCachedTweets(startTimestamp, sinceTimestamp)

        _tweets.update { currentTweets ->
            val allTweets = (cachedTweets + currentTweets)
                .filterNot { it.isPrivate }
                .distinctBy { it.mid }
                .sortedByDescending { it.timestamp }
            allTweets
        }

        val batchSize = 10 // Adjust batch size as needed
        followings.chunked(batchSize).forEach { batch ->
            withContext(networkDispatcher) {
                batch.forEach { userId ->
                    try {
                        getUser(userId)?.let { user ->
                            HproseInstance.getTweetList(
                                user,
                                startTimestamp,
                                sinceTimestamp,
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
                    } catch (e: Exception) {
                        Timber.tag("GetTweets in TFVM")
                            .e(e, "Error fetching tweets for user: $userId")
                        HproseInstance.removeCachedUser(userId)
                    }
                }
            }
        }
    }

    // load tweets of the user during the time span.
    private suspend fun getTweets(userId: MimeiId) {
        try {
            getUser(userId)?.let { user ->
                val startTime = System.currentTimeMillis()
                val endTime = this.endTimestamp.longValue
                HproseInstance.getTweetList(
                    user,
                    startTime,
                    endTime,
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

//    suspend fun delTweet(tweet: Tweet, callback: () -> Unit) {
//        // 1. Remove the tweet from TweetFeed right away for better user experience.
//        _tweets.update { currentTweets ->
//            currentTweets.filterNot { it.mid == tweet.mid }
//        }
//        // remove from appUserViewModel's profile feed, favorites, bookmarks,
//        tweetActionListener.onTweetDeleted(tweet.mid)
//
//        // 2. remove cached tweet
//        dao.deleteCachedTweet(tweet.mid)
//
//        // 3, delete tweet mimei from backend.
//        HproseInstance.delTweet(tweet) {
//            callback()  // refresh the original tweet if there is any.
//        }
//    }

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
        startTimestamp = mutableLongStateOf(System.currentTimeMillis())
        endTimestamp = mutableLongStateOf(System.currentTimeMillis() - THIRTY_DAYS_IN_MILLIS)  // 30 days
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
        viewModelScope.launch(IO) {
            dao.deleteCachedTweet(tweetId)
            _tweets.update { currentTweets ->
                currentTweets.filterNot { it.mid == tweetId }
            }
            tweetActionListener.onTweetDeleted(tweetId)     // userViewModel function
            HproseInstance.delTweet(tweetId)
            callback()
        }
        viewModelScope.launch(Main) {
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