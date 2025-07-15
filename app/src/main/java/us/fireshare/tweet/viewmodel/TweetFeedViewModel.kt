package us.fireshare.tweet.viewmodel

import android.content.Context
import android.net.Uri
import android.widget.Toast
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
import kotlin.math.max
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

import us.fireshare.tweet.datamodel.TweetEvent
import us.fireshare.tweet.datamodel.TweetNotificationCenter
import us.fireshare.tweet.service.SnackbarController
import us.fireshare.tweet.service.SnackbarEvent
import us.fireshare.tweet.service.UploadTweetWorker
import javax.inject.Inject

@HiltViewModel
class TweetFeedViewModel @Inject constructor() : ViewModel()
{
    companion object {
        private const val TWEET_COUNT = 20
    }
    private val _tweets = MutableStateFlow<List<Tweet>>(emptyList())
    val tweets: StateFlow<List<Tweet>> get() = _tweets.asStateFlow()

    // Indicate the first time TweeFeed screen is loading.
    var initState = MutableStateFlow(true)      // initial load state

    init {
        // Load initial tweets when ViewModel is created
        viewModelScope.launch(Dispatchers.IO) {
            refresh(0)
            initState.value = false
        }
    }

    /**
     * Called after login or logout(). Update current user's following list and tweets.
     * When new following is added or removed, _followings will be updated also.
     * */
    suspend fun refresh(pageNumber: Int = 0)
    {
        if (!appUser.isGuest())
            dao.insertOrUpdateCachedUser(CachedUser(appUser.mid, appUser))
        getTweets(pageNumber)
    }

    /**
     * Simple function to fetch tweets for a specific page number.
     * TweetListView manages pagination logic internally.
     */
    suspend fun fetchTweets(pageNumber: Int) {
        getTweets(pageNumber)
    }

    /**
     * Given a page number to load tweets of user's followings.
     * */
    private suspend fun getTweets(
        pageNumber: Int,   // page number for pagination (0, 1, 2, etc.)
        count: Int = TWEET_COUNT,
    ) {
        Timber.tag("getTweets").d("Loading page $pageNumber with count $count, current tweets: ${_tweets.value.size}")
        
        /**
         * Show cached tweets before loading from net.
         * */
        val cachedTweets = loadCachedTweets(pageNumber * count, count)
        Timber.tag("getTweets").d("Loaded ${cachedTweets.size} cached tweets for page $pageNumber")
        
        if (appUser.isGuest()) {
            // show tweets of administrator only
            val defaultUserId = getAlphaIds().first()
            _tweets.update { currentTweets ->
                val allTweets = (cachedTweets + currentTweets)
                    // only show default tweets to guest
                    .filter { tweet: Tweet -> tweet.authorId == defaultUserId }
                    .distinctBy { tweet: Tweet -> tweet.mid }
                    .sortedByDescending { tweet: Tweet -> tweet.timestamp }
                Timber.tag("getTweets").d("Guest: Updated tweets from ${currentTweets.size} to ${allTweets.size}")
                allTweets
            }
            getTweets(defaultUserId)
        } else {
            // Handle cached tweets - always merge, never replace
            _tweets.update { currentTweets ->
                val allTweets = (cachedTweets + currentTweets)
                    .distinctBy { tweet: Tweet -> tweet.mid }
                    .sortedByDescending { tweet: Tweet -> tweet.timestamp }
                Timber.tag("getTweets").d("Cached: Updated tweets from ${currentTweets.size} to ${allTweets.size}")
                allTweets
            }
            
            /**
             * Load tweet feed from network
             * */
            val newTweets = HproseInstance.getTweetFeed(
                appUser,
                pageNumber,
                count,
            )
            
            Timber.tag("getTweets").d("Received ${newTweets.size} new tweets for page $pageNumber")
            if (newTweets.isNotEmpty()) {
                Timber.tag("getTweets").d("First tweet: ${newTweets.first().mid}, Last tweet: ${newTweets.last().mid}")
            }
            
            // Always merge new tweets with existing ones, never replace
            _tweets.update { currentTweets ->
                val mergedTweets = (currentTweets + newTweets)
                    .distinctBy { tweet: Tweet -> tweet.mid }
                    .sortedByDescending { tweet: Tweet -> tweet.timestamp }
                Timber.tag("getTweets").d("Network: Updated tweets from ${currentTweets.size} to ${mergedTweets.size}")
                mergedTweets
            }
            
            /**
             * Check for new tweets of followings.
             * */
            val followingTweets = HproseInstance.getTweetFeed(
                appUser,
                pageNumber,
                count,
                "update_following_tweets"
            )
            
            Timber.tag("getTweets").d("Received ${followingTweets.size} following tweets for page $pageNumber")
            
            // Always merge following tweets with existing ones
            _tweets.update { currentTweets ->
                val mergedTweets = (currentTweets + followingTweets)
                    .distinctBy { tweet: Tweet -> tweet.mid }
                    .sortedByDescending { tweet: Tweet -> tweet.timestamp }
                Timber.tag("getTweets").d("Following: Updated tweets from ${currentTweets.size} to ${mergedTweets.size}")
                mergedTweets
            }
        }
    }

    /**
     * Given an user Id, load its tweets during a range.
     * */
    private suspend fun getTweets(userId: MimeiId) {
        try {
            getUser(userId)?.let { user ->
                val newTweets = HproseInstance.getTweetListByRank(
                    user,
                    0,
                )
                _tweets.update { list ->
                    val mergedTweets = (newTweets + list)
                        .filterNot { tweet: Tweet -> tweet.isPrivate }
                        .distinctBy { tweet: Tweet -> tweet.mid }
                        .sortedByDescending { tweet: Tweet -> tweet.timestamp }
                    mergedTweets
                }
            }
        } catch (e: Exception) {
            Timber.tag("GetTweets").e(e, "Error fetching tweets for user: $userId")
        }
    }

    /**
     * When appUser toggles following status on a User, update tweet feed correspondingly.
     * Remove it if unfollowing, add it if following.
     * */
    suspend fun updateFollowingsTweets(userId: MimeiId, isFollowing: Boolean) {
        if (isFollowing) {
            // add the tweets of a user after following it.
            getTweets(userId)
        } else {
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
    }

    // order of deletion is critical here.
    fun delTweet(
        navController: NavController,
        tweetId: MimeiId,
        callback: () -> Unit
    ) {
        // Remove manual UI updates - let notification system handle them
        applicationScope.launch(IO) {
            dao.deleteCachedTweet(tweetId)
            // Use deleteTweet which posts notifications instead of delTweet
            HproseInstance.deleteTweet(tweetId)
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

                                    // Tweet will be added via notification system
                                    // addTweetToFeed(tweet) // Removed - use notifications instead
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

    /**
     * Listen to tweet notifications and update the feed accordingly
     */
    fun startListeningToNotifications() {
        viewModelScope.launch {
            TweetNotificationCenter.events.collect { event ->
                when (event) {
                    is TweetEvent.TweetUploaded -> {
                        // Add new tweet to the beginning of the feed
                        _tweets.value = listOf(event.tweet) + _tweets.value
                    }
                    is TweetEvent.TweetDeleted -> {
                        // Remove tweet from feed
                        _tweets.value = _tweets.value.filter { it.mid != event.tweetId }
                    }
                    is TweetEvent.CommentUploaded -> {
                        // Update comment count for parent tweet
                        _tweets.value = _tweets.value.map { tweet ->
                            if (tweet.mid == event.parentTweet.mid) {
                                tweet.copy(commentCount = event.parentTweet.commentCount)
                            } else {
                                tweet
                            }
                        }
                    }
                    is TweetEvent.CommentDeleted -> {
                        // Decrease comment count for parent tweet
                        _tweets.value = _tweets.value.map { tweet ->
                            if (tweet.mid == event.parentTweetId) {
                                tweet.copy(commentCount = max(0, tweet.commentCount - 1))
                            } else {
                                tweet
                            }
                        }
                    }
                    is TweetEvent.TweetLiked -> {
                        // Update like status and count
                        _tweets.value = _tweets.value.map { tweet ->
                            if (tweet.mid == event.tweet.mid) {
                                event.tweet
                            } else {
                                tweet
                            }
                        }
                    }
                    is TweetEvent.TweetBookmarked -> {
                        // Update bookmark status and count
                        _tweets.value = _tweets.value.map { tweet ->
                            if (tweet.mid == event.tweet.mid) {
                                event.tweet
                            } else {
                                tweet
                            }
                        }
                    }
                    is TweetEvent.TweetRetweeted -> {
                        // Add retweet to feed
                        _tweets.value = listOf(event.retweet) + _tweets.value
                    }
                    is TweetEvent.TweetUpdated -> {
                        // Update existing tweet
                        _tweets.value = _tweets.value.map { tweet ->
                            if (tweet.mid == event.tweet.mid) {
                                event.tweet
                            } else {
                                tweet
                            }
                        }
                    }
                }
            }
        }
    }
}