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
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import com.google.gson.Gson
import timber.log.Timber
import us.fireshare.tweet.HproseInstance
import us.fireshare.tweet.HproseInstance.appUser
import us.fireshare.tweet.HproseInstance.dao
import us.fireshare.tweet.HproseInstance.getAlphaIds
import us.fireshare.tweet.HproseInstance.getUser
import us.fireshare.tweet.HproseInstance.loadCachedTweets
import us.fireshare.tweet.R
import us.fireshare.tweet.TweetApplication.Companion.applicationScope
import us.fireshare.tweet.datamodel.MimeiId
import us.fireshare.tweet.datamodel.TW_CONST
import us.fireshare.tweet.datamodel.Tweet
import us.fireshare.tweet.service.UploadTweetWorker
import us.fireshare.tweet.service.SnackbarController
import us.fireshare.tweet.service.SnackbarEvent
import us.fireshare.tweet.datamodel.TweetEvent
import us.fireshare.tweet.datamodel.TweetNotificationCenter
import javax.inject.Inject
import us.fireshare.tweet.datamodel.TweetCacheManager

@HiltViewModel
class TweetFeedViewModel @Inject constructor() : ViewModel() {
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
        
        // Start listening to notifications immediately when ViewModel is created
        startListeningToNotifications()
    }

    /**
     * Called after login or logout(). Update current user's following list and tweets.
     * When new following is added or removed, _followings will be updated also.
     * */
    suspend fun refresh(pageNumber: Int = 0) {
        if (!appUser.isGuest())
            TweetCacheManager.saveUser(appUser)
        fetchTweets(pageNumber)
    }

    /**
     * Simple function to fetch tweets for a specific page number.
     * TweetListView manages pagination logic internally.
     * Returns List<Tweet?> including null elements from the backend.
     */
    suspend fun fetchTweets(
        pageNumber: Int,   // page number for pagination (0, 1, 2, etc.)
        pageSize: Int = TW_CONST.PAGE_SIZE,   // page size to be loaded.
    ): List<Tweet?> {
        Timber.tag("fetchTweets")
            .d("Loading page $pageNumber with count $pageSize, current tweets: ${_tweets.value.size}")

        /**
         * Show cached tweets before loading from net.
         * */
        val cachedTweets = loadCachedTweets(pageNumber * pageSize, pageSize)
        Timber.tag("fetchTweets").d("Loaded ${cachedTweets.size} cached tweets for page $pageNumber")

        if (appUser.isGuest()) {
            // show tweets of administrator only
            val defaultUserId = getAlphaIds().first()
            Timber.tag("fetchTweets").d("Guest user: defaultUserId = $defaultUserId")
            _tweets.update { currentTweets ->
                val allTweets = (cachedTweets + currentTweets)
                    // only show default tweets to guest
                    .filter { tweet: Tweet -> tweet.authorId == defaultUserId }
                    .distinctBy { tweet: Tweet -> tweet.mid }
                    .sortedByDescending { tweet: Tweet -> tweet.timestamp }
                Timber.tag("fetchTweets")
                    .d("Guest: Updated tweets from ${currentTweets.size} to ${allTweets.size}")
                allTweets
            }
            val result = getTweets(defaultUserId, pageNumber)
            Timber.tag("fetchTweets")
                .d("Guest: After fetchTweets, _tweets.size = ${_tweets.value.size}")
            return result
        } else {
            // Immediately merge cached tweets if they're not already in the list
            _tweets.update { currentTweets ->
                val currentTweetIds = currentTweets.map { it.mid }.toSet()
                val newCachedTweets = cachedTweets.filter { it.mid !in currentTweetIds }

                if (newCachedTweets.isNotEmpty()) {
                    val allTweets = (currentTweets + newCachedTweets)
                        .distinctBy { tweet: Tweet -> tweet.mid }
                        .sortedByDescending { tweet: Tweet -> tweet.timestamp }
                    Timber.tag("fetchTweets")
                        .d("Cached: Added ${newCachedTweets.size} new cached tweets, updated from ${currentTweets.size} to ${allTweets.size}")
                    allTweets
                } else {
                    Timber.tag("fetchTweets")
                        .d("Cached: No new cached tweets to add, keeping ${currentTweets.size} tweets")
                    currentTweets
                }
            }

            /**
             * Load tweet feed from network
             * */
            val tweetsWithNulls = HproseInstance.getTweetFeed(
                appUser,
                pageNumber,
                pageSize,
            )

            // Filter out null elements and get valid tweets
            val validTweets = tweetsWithNulls.filterNotNull()

            Timber.tag("fetchTweets")
                .d("Received ${tweetsWithNulls.size} tweets (${validTweets.size} valid) for page $pageNumber")
            if (validTweets.isNotEmpty()) {
                Timber.tag("fetchTweets")
                    .d("First tweet: ${validTweets.first().mid}, Last tweet: ${validTweets.last().mid}")
            }

            // Always merge new tweets with existing ones, never replace
            _tweets.update { currentTweets ->
                val currentTweetIds = currentTweets.map { it.mid }.toSet()
                val trulyNewTweets = validTweets.filter { it.mid !in currentTweetIds }

                if (trulyNewTweets.isNotEmpty()) {
                    val mergedTweets = (currentTweets + trulyNewTweets)
                        .distinctBy { tweet: Tweet -> tweet.mid }
                        .sortedByDescending { tweet: Tweet -> tweet.timestamp }
                    Timber.tag("fetchTweets")
                        .d("Network: Added ${trulyNewTweets.size} new tweets, updated from ${currentTweets.size} to ${mergedTweets.size}")
                    mergedTweets
                } else {
                    Timber.tag("fetchTweets")
                        .d("Network: No new tweets to add, keeping ${currentTweets.size} tweets")
                    currentTweets
                }
            }

            /**
             * Check for new tweets of followings when page number is 0
             * */
            if (pageNumber == 0) {
                val followingTweetsWithNulls = HproseInstance.getTweetFeed(
                    appUser,
                    pageNumber,
                    pageSize,
                    "update_following_tweets"
                )

                // Filter out null elements and get valid tweets
                val followingTweets = followingTweetsWithNulls.filterNotNull()

                Timber.tag("fetchTweets")
                    .d("New ${followingTweetsWithNulls.size} following tweets (${followingTweets.size} valid) for page $pageNumber")

                // Always merge following tweets with existing ones
                _tweets.update { currentTweets ->
                    val currentTweetIds = currentTweets.map { it.mid }.toSet()
                    val trulyNewFollowingTweets =
                        followingTweets.filter { it.mid !in currentTweetIds }

                    if (trulyNewFollowingTweets.isNotEmpty()) {
                        val mergedTweets = (currentTweets + trulyNewFollowingTweets)
                            .distinctBy { tweet: Tweet -> tweet.mid }
                            .sortedByDescending { tweet: Tweet -> tweet.timestamp }
                        Timber.tag("fetchTweets")
                            .d("Following: Added ${trulyNewFollowingTweets.size} new following tweets, updated from ${currentTweets.size} to ${mergedTweets.size}")
                        mergedTweets
                    } else {
                        Timber.tag("fetchTweets")
                            .d("Following: No new following tweets to add, keeping ${currentTweets.size} tweets")
                        currentTweets
                    }
                }
            }
            return tweetsWithNulls
        }
    }

    /**
     * Given an user Id, load its tweets on a page.
     * */
    private suspend fun getTweets(userId: MimeiId, pageNumber: Int = 0): List<Tweet?> {
        try {
            getUser(userId)?.let { user ->
                val tweetsWithNulls = HproseInstance.getTweetsByUser(
                    user,
                    pageNumber,
                )
                // Filter out null elements and get valid tweets
                val validTweets = tweetsWithNulls.filterNotNull()

                Timber.tag("getTweets")
                    .d("Received ${tweetsWithNulls.size} tweets (${validTweets.size} valid) for user: $userId")

                _tweets.update { list ->
                    val beforeFilter = validTweets + list
                    val afterPrivateFilter =
                        beforeFilter.filterNot { tweet: Tweet -> tweet.isPrivate }
                    val mergedTweets = afterPrivateFilter
                        .distinctBy { tweet: Tweet -> tweet.mid }
                        .sortedByDescending { tweet: Tweet -> tweet.timestamp }

                    Timber.tag("getTweets")
                        .d("getTweets update: beforeFilter=${beforeFilter.size}, afterPrivateFilter=${afterPrivateFilter.size}, final=${mergedTweets.size}")
                    mergedTweets
                }
                return tweetsWithNulls
            }
        } catch (e: Exception) {
            Timber.tag("GetTweets").e(e, "Error fetching tweets for user: $userId")
        }
        return emptyList()
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

    /**
     * Clear the tweet list when user changes
     */
    fun clearTweets() {
        _tweets.value = emptyList()
    }

    /**
     * Remove a tweet from the list when it becomes unavailable (e.g., original tweet deleted)
     * This is used for optimistic updates and immediate UI feedback.
     */
    fun removeTweet(tweetId: MimeiId) {
        _tweets.update { currentTweets ->
            val filteredTweets = currentTweets.filterNot { it.mid == tweetId }
            val removedCount = currentTweets.size - filteredTweets.size
            if (removedCount > 0) {
                Timber.tag("TweetFeedViewModel")
                    .d("Removed tweet $tweetId from UI, updated from ${currentTweets.size} to ${filteredTweets.size} tweets")
            } else {
                Timber.tag("TweetFeedViewModel")
                    .d("Tweet $tweetId not found in current list (${currentTweets.size} tweets)")
            }
            filteredTweets
        }
    }

    // Optimistic deletion: Remove from UI immediately, then delete from backend
    fun delTweet(
        navController: NavController,
        tweetId: MimeiId,
        callback: () -> Unit,
        userViewModel: us.fireshare.tweet.viewmodel.UserViewModel? = null
    ) {
        // OPTIMISTIC UPDATE: Remove from UI and cache immediately for instant feedback
        Timber.tag("TweetFeedViewModel").d("Optimistic deletion: Removing tweet $tweetId from UI immediately")
        removeTweet(tweetId)
        
        // Also remove from UserViewModel lists if provided (for profile screen)
        userViewModel?.removeTweetFromAllLists(tweetId)
        
        // Navigate back immediately for better UX
        applicationScope.launch(Main) {
            if (navController.currentDestination?.route?.contains("TweetDetail") == true) {
                navController.popBackStack()
            }
        }
        
        // Perform actual deletion in background
        applicationScope.launch(IO) {
            try {
                // Delete from local cache first
                dao.deleteCachedTweet(tweetId)
                Timber.tag("TweetFeedViewModel").d("Deleted tweet $tweetId from local cache")
                
                // Delete from backend
                HproseInstance.deleteTweet(tweetId)
                Timber.tag("TweetFeedViewModel").d("Successfully deleted tweet $tweetId from backend")
                
                // Call callback on main thread
                withContext(Main) {
                    callback()
                }
            } catch (e: Exception) {
                // If backend deletion fails, we could potentially restore the tweet
                // For now, just log the error since the user already sees it as deleted
                Timber.tag("TweetFeedViewModel").e(e, "Failed to delete tweet $tweetId from backend: ${e.message}")
                
                // Still call callback to complete the UI flow
                withContext(Main) {
                    callback()
                }
            }
        }
    }

    /**
     * Use WorkManager to update tweet. When the upload succeeds, a message will be sent back.
     * Show a snackbar to inform user of the result.
     * */
    fun uploadTweet(
        context: Context,
        content: String,
        attachments: List<Uri>?,
        isPrivate: Boolean = false
    ) {
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
                                val gson = Gson().newBuilder()
                                    .excludeFieldsWithoutExposeAnnotation()
                                    .create()
                                val tweet = json?.let { gson.fromJson(it, Tweet::class.java) }
                                Timber.tag("UploadTweet").d("Tweet uploaded successfully: $tweet")
                                if (tweet != null) {
                                    tweet.author = appUser
                                    Timber.tag("UploadTweet")
                                        .d("Tweet author set to: ${tweet.author?.username}")

                                    // Tweet will be added via notification system
                                    // addTweetToFeed(tweet) // Removed - use notifications instead
                                    // notify user the result of tweet upload
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.tweet_uploaded),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                } else {
                                    Timber.tag("UploadTweet")
                                        .e("Tweet is null after deserialization")
                                }
                            } catch (e: Exception) {
                                Timber.tag("UploadTweet").e("$e")
                            }
                        }

                        WorkInfo.State.FAILED -> {
                            // Handle the failure and update UI
                            Timber.tag("UploadTweet").e("Tweet upload failed")
                            Toast.makeText(
                                context,
                                context.getString(R.string.tweet_failed),
                                Toast.LENGTH_LONG
                            ).show()
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
        Timber.tag("TweetFeedViewModel").d("Starting to listen to notifications")
        applicationScope.launch {
            try {
                Timber.tag("TweetFeedViewModel").d("Notification listener coroutine started")
                Timber.tag("TweetFeedViewModel")
                    .d("About to start collecting events from TweetNotificationCenter")
                TweetNotificationCenter.events.collect { event ->
                    Timber.tag("TweetFeedViewModel").d("Received notification event: $event")
                    when (event) {
                        is TweetEvent.TweetUploaded -> {
                            // Add new tweet to the beginning of the feed
                            val tweetWithAuthor = event.tweet
                            Timber.tag("TweetFeedViewModel")
                                .d("Received TweetUploaded notification for tweet: ${event.tweet.mid}")
                            Timber.tag("TweetFeedViewModel")
                                .d("Current tweets count: ${_tweets.value.size}")
                            
                            // Update on main thread to ensure UI updates
                            withContext(Main) {
                                // Check if tweet already exists to avoid duplicates
                                val existingTweetIds = _tweets.value.map { it.mid }.toSet()
                                if (tweetWithAuthor.mid !in existingTweetIds) {
                                    _tweets.value = listOf(tweetWithAuthor) + _tweets.value
                                    Timber.tag("TweetFeedViewModel")
                                        .d("Updated tweets count: ${_tweets.value.size}")
                                    Timber.tag("TweetFeedViewModel")
                                        .d("Tweet added to feed: ${tweetWithAuthor.mid} by ${tweetWithAuthor.author?.username}")
                                } else {
                                    Timber.tag("TweetFeedViewModel")
                                        .d("Tweet already exists in feed: ${tweetWithAuthor.mid}")
                                }
                            }
                        }

                        is TweetEvent.TweetDeleted -> {
                            // Remove tweet from feed
                            Timber.tag("TweetFeedViewModel").d("Received TweetDeleted notification for tweet: ${event.tweetId}")
                            Timber.tag("TweetFeedViewModel").d("Current tweets count: ${_tweets.value.size}")
                            withContext(Main) {
                                // Find the deleted tweet to check if it's a retweet
                                val deletedTweet = _tweets.value.find { it.mid == event.tweetId }
                                val isRetweet = deletedTweet?.originalTweetId != null
                                val originalTweetId = deletedTweet?.originalTweetId
                                
                                // Remove the deleted tweet from feed
                                _tweets.value = _tweets.value.filter { it.mid != event.tweetId }
                                
                                // If it was a retweet, decrease the retweet count of the original tweet
                                if (isRetweet && originalTweetId != null) {
                                    _tweets.value = _tweets.value.map { tweet ->
                                        if (tweet.mid == originalTweetId) {
                                            val newRetweetCount = max(0, tweet.retweetCount - 1)
                                            Timber.tag("TweetFeedViewModel").d("Decreased retweet count for original tweet ${originalTweetId} from ${tweet.retweetCount} to $newRetweetCount")
                                            tweet.copy(retweetCount = newRetweetCount)
                                        } else {
                                            tweet
                                        }
                                    }
                                }
                                
                                Timber.tag("TweetFeedViewModel").d("Updated tweets count: ${_tweets.value.size}")
                                if (isRetweet) {
                                    Timber.tag("TweetFeedViewModel").d("Retweet deleted: ${event.tweetId}, original tweet: $originalTweetId")
                                }
                            }
                        }

                        is TweetEvent.CommentUploaded -> {
                            // Update comment count for parent tweet
                            withContext(Main) {
                                _tweets.value = _tweets.value.map { tweet ->
                                    if (tweet.mid == event.parentTweet.mid) {
                                        tweet.copy(commentCount = event.parentTweet.commentCount)
                                    } else {
                                        tweet
                                    }
                                }
                            }
                        }

                        is TweetEvent.CommentDeleted -> {
                            // Decrease comment count for parent tweet
                            withContext(Main) {
                                _tweets.value = _tweets.value.map { tweet ->
                                    if (tweet.mid == event.parentTweetId) {
                                        tweet.copy(commentCount = max(0, tweet.commentCount - 1))
                                    } else {
                                        tweet
                                    }
                                }
                            }
                        }

                        is TweetEvent.TweetLiked -> {
                            // Update like status and count
                            withContext(Main) {
                                _tweets.value = _tweets.value.map { tweet ->
                                    if (tweet.mid == event.tweet.mid) {
                                        event.tweet
                                    } else {
                                        tweet
                                    }
                                }
                            }
                        }

                        is TweetEvent.TweetBookmarked -> {
                            // Update bookmark status and count
                            withContext(Main) {
                                _tweets.value = _tweets.value.map { tweet ->
                                    if (tweet.mid == event.tweet.mid) {
                                        event.tweet
                                    } else {
                                        tweet
                                    }
                                }
                            }
                        }

                        is TweetEvent.TweetRetweeted -> {
                            // Add retweet to feed
                            // Ensure the author is set correctly
                            val retweetWithAuthor = if (event.retweet.author != null) {
                                event.retweet
                            } else {
                                event.retweet.copy(author = appUser)
                            }
                            withContext(Main) {
                                // Check if retweet already exists to avoid duplicates
                                val existingTweetIds = _tweets.value.map { it.mid }.toSet()
                                if (retweetWithAuthor.mid !in existingTweetIds) {
                                    _tweets.value = listOf(retweetWithAuthor) + _tweets.value
                                    Timber.tag("TweetFeedViewModel").d("Retweet added to feed: ${retweetWithAuthor.mid}")
                                } else {
                                    Timber.tag("TweetFeedViewModel").d("Retweet already exists in feed: ${retweetWithAuthor.mid}")
                                }
                            }
                        }

                        is TweetEvent.TweetUpdated -> {
                            // Update existing tweet
                            withContext(Main) {
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
            } catch (e: kotlinx.coroutines.CancellationException) {
                // This is expected when the ViewModel is destroyed
                Timber.tag("TweetFeedViewModel").d("Notification listener cancelled: ${e.message}")
            } catch (e: Exception) {
                Timber.tag("TweetFeedViewModel")
                    .e(e, "Error in notification listener: ${e.message}")
            }
        }
    }
}