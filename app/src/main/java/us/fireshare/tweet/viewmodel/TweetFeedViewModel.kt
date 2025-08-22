package us.fireshare.tweet.viewmodel

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
import us.fireshare.tweet.datamodel.TweetCacheManager
import us.fireshare.tweet.datamodel.TweetEvent
import us.fireshare.tweet.datamodel.TweetNotificationCenter
import us.fireshare.tweet.service.UploadTweetWorker
import java.lang.ref.WeakReference
import javax.inject.Inject
import kotlin.math.max

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
        startListeningToNotifications() // Will be updated with context later
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

    private var followingTweetsJob: Job? = null

    /**
     * Simple function to fetch tweets for a specific page number.
     * TweetListView manages pagination logic internally.
     * Returns List<Tweet?> including null elements from the backend.
     */
    suspend fun fetchTweets(
        pageNumber: Int,   // page number for pagination (0, 1, 2, etc.)
        pageSize: Int = TW_CONST.PAGE_SIZE,   // page size to be loaded.
    ): List<Tweet?> {
        /**
         * Show cached tweets before loading from net.
         * */
        val cachedTweets = loadCachedTweets(pageNumber * pageSize, pageSize)

        if (appUser.isGuest()) {
            // show tweets of administrator only
            val defaultUserId = getAlphaIds().first()
            _tweets.update { currentTweets ->
                val allTweets = (cachedTweets + currentTweets)
                    // only show default tweets to guest
                    .filter { tweet: Tweet -> tweet.authorId == defaultUserId }
                    .distinctBy { tweet: Tweet -> tweet.mid }
                    .sortedByDescending { tweet: Tweet -> tweet.timestamp }
                allTweets
            }
            val result = getTweets(defaultUserId, pageNumber)
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
                    allTweets
                } else {
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

            // Always merge new tweets with existing ones, never replace
            _tweets.update { currentTweets ->
                val currentTweetIds = currentTweets.map { it.mid }.toSet()
                val trulyNewTweets = validTweets.filter { it.mid !in currentTweetIds }

                if (trulyNewTweets.isNotEmpty()) {
                    val mergedTweets = (currentTweets + trulyNewTweets)
                        .distinctBy { tweet: Tweet -> tweet.mid }
                        .sortedByDescending { tweet: Tweet -> tweet.timestamp }
                    mergedTweets
                } else {
                    currentTweets
                }
            }

            /**
             * Check for new tweets of followings when page number is 0
             * Run in separate coroutine to avoid blocking main tweet loading
             * Use applicationScope to ensure it continues even if composable leaves scope
             * Added request deduplication to prevent multiple simultaneous requests
             * 
             * PERFORMANCE OPTIMIZATION NOTE:
             * Consider combining this with the main tweet feed call for better performance:
             * 
             * // Option 1: Backend modification (recommended)
             * val combinedResponse = HproseInstance.getCombinedTweetFeed(appUser, pageNumber, pageSize)
             * // Process both mainTweets and followingTweets from single response
             * 
             * // Option 2: Client-side concurrent calls
             * val deferredMain = async { HproseInstance.getTweetFeed(...) }
             * val deferredFollowing = async { HproseInstance.getTweetFeed(..., "update_following_tweets") }
             * val mainTweets = deferredMain.await()
             * val followingTweets = deferredFollowing.await()
             * // Process both results together
             * */
            if (pageNumber == 0 && followingTweetsJob?.isActive != true) {
                followingTweetsJob = applicationScope.launch(Dispatchers.IO) {
                    try {
                        val followingTweetsWithNulls = HproseInstance.getTweetFeed(
                            appUser,
                            pageNumber,
                            pageSize,
                            "update_following_tweets"
                        )

                        // Filter out null elements and get valid tweets
                        val followingTweets = followingTweetsWithNulls.filterNotNull()

                        // Always merge following tweets with existing ones
                        withContext(Dispatchers.Main) {
                            _tweets.update { currentTweets ->
                                // Use Set for O(1) lookup performance
                                val currentTweetIds = currentTweets.map { it.mid }.toSet()
                                val trulyNewFollowingTweets =
                                    followingTweets.filter { it.mid !in currentTweetIds }

                                if (trulyNewFollowingTweets.isNotEmpty()) {
                                    val mergedTweets = (currentTweets + trulyNewFollowingTweets)
                                        .distinctBy { it.mid }
                                        .sortedByDescending { it.timestamp }
                                    mergedTweets
                                } else {
                                    currentTweets
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Timber.tag("TweetFeedViewModel").e(e, "Error loading following tweets: ${e.message}")
                        // Consider implementing retry logic here
                    } finally {
                        followingTweetsJob = null
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

                _tweets.update { list ->
                    val beforeFilter = validTweets + list
                    val afterPrivateFilter =
                        beforeFilter.filterNot { tweet: Tweet -> tweet.isPrivate }
                    val mergedTweets = afterPrivateFilter
                        .distinctBy { tweet: Tweet -> tweet.mid }
                        .sortedByDescending { tweet: Tweet -> tweet.timestamp }

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
            if (it.mid != null) {   // deal with corrupted data
                dao.deleteCachedTweet(it.mid)
            }
        }
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
    suspend fun delTweet(
        navController: NavController,
        tweetId: MimeiId,
        userViewModel: UserViewModel? = null,
        callback: () -> Unit,
    ) {
        // Check if this is a retweet and get original tweet info
        val tweetToDelete = _tweets.value.find { it.mid == tweetId }
        val originalTweetId = tweetToDelete?.originalTweetId

        // OPTIMISTIC UPDATE: Remove tweet immediately
        removeTweet(tweetId)

        // Update user's tweet count if it's the current user's tweet
        if (tweetToDelete?.authorId == appUser.mid) {
            appUser = appUser.copy(tweetCount = max(0, appUser.tweetCount - 1))
            TweetCacheManager.saveUser(appUser)
        }

        // Optimistically decrease retweet count if this is a retweet and in the current list
        if (originalTweetId != null) {
            _tweets.value = _tweets.value.map { tweet ->
                if (tweet.mid == originalTweetId) {
                    val updatedOriginalTweet = tweet.copy(retweetCount = max(0, tweet.retweetCount - 1))
                    applicationScope.launch {
                        // Post TweetUpdated notification to update individual TweetViewModel instances
                        TweetNotificationCenter.post(TweetEvent.TweetUpdated(updatedOriginalTweet))
                    }
                    updatedOriginalTweet
                } else {
                    tweet
                }
            }
        }

        // Also remove from UserViewModel lists if provided (for profile screen)
        userViewModel?.removeTweetFromAllLists(tweetId)

        // Navigate back immediately for better UX
        applicationScope.launch(Main) {
            if (navController.currentDestination?.route?.contains("TweetDetail") == true) {
                navController.popBackStack()
            }
        }

        // Perform actual deletion in background
        try {
            // Delete from local cache first
            dao.deleteCachedTweet(tweetId)

            // Delete from backend
            HproseInstance.deleteTweet(tweetId)
            callback()
        } catch (e: Exception) {
            // If backend deletion fails, log the error and show toast
            Timber.tag("TweetFeedViewModel")
                .e(e, "Failed to delete tweet $tweetId from backend: ${e.message}")
            
            // Show error toast to user
            withContext(Main) {
                val context = notificationContextRef?.get()
                if (context != null) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.delete_failed),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    /**
     * Use WorkManager to update tweet. When the upload succeeds, a message will be sent back.
     * Toast messages are shown when notifications are received.
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

        // No need to observe work status; UI will update via notification system
    }

    private var notificationContextRef: WeakReference<Context>? = null
    private var isListeningToNotifications = false

    /**
     * Set the context for showing toast messages in notifications
     */
    fun setNotificationContext(context: Context) {
        notificationContextRef = WeakReference(context)
    }

    /**
     * Listen to tweet notifications and update the feed accordingly
     */
    fun startListeningToNotifications(context: Context? = null) {
        if (context != null) {
            notificationContextRef = WeakReference(context)
        }

        // Prevent multiple listeners
        if (isListeningToNotifications) {
            Timber.tag("TweetFeedViewModel").d("Already listening to notifications, skipping")
            return
        }

        isListeningToNotifications = true
        Timber.tag("TweetFeedViewModel")
            .d("TweetFeedViewModel instance starting to listen to notifications")
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
                                .d("TweetFeedViewModel received TweetUploaded notification for tweet: ${event.tweet.mid}")
                            Timber.tag("TweetFeedViewModel")
                                .d("Current tweets count: ${_tweets.value.size}")

                            // Show success toast if it's the current user's tweet
                            val context = notificationContextRef?.get()
                            if (tweetWithAuthor.authorId == appUser.mid && context != null) {
                                Timber.tag("TweetFeedViewModel")
                                    .d("Showing tweet upload success toast for tweet: ${event.tweet.mid}")
                                withContext(Main) {
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.tweet_uploaded),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }

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

                                    // Update user's tweet count if it's the current user's tweet
                                    if (tweetWithAuthor.authorId == appUser.mid) {
                                        appUser = appUser.copy(tweetCount = appUser.tweetCount + 1)
                                        withContext(IO) {
                                            TweetCacheManager.saveUser(appUser)
                                        }
                                        Timber.tag("TweetFeedViewModel")
                                            .d("Updated user tweet count to: ${appUser.tweetCount}")
                                    }
                                } else {
                                    Timber.tag("TweetFeedViewModel")
                                        .d("Tweet already exists in feed: ${tweetWithAuthor.mid}")
                                }
                            }
                        }

                        is TweetEvent.TweetUploadFailed -> {
                            // Show failure toast
                            val context = notificationContextRef?.get()
                            if (context != null) {
                                withContext(Main) {
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.tweet_failed),
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                            Timber.tag("TweetFeedViewModel")
                                .e("Tweet upload failed: ${event.error}")
                        }

                        is TweetEvent.TweetDeleted -> {
                            // Tweet is already removed optimistically, just log
                            Timber.tag("TweetFeedViewModel")
                                .d("Received TweetDeleted notification for tweet: ${event.tweetId} (already removed optimistically)")
                        }

                        is TweetEvent.CommentUploaded -> {
                            // Show success toast if it's the current user's comment
                            val context = notificationContextRef?.get()
                            if (event.comment.authorId == appUser.mid && context != null) {
                                withContext(Main) {
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.comment_uploaded),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }

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

                        is TweetEvent.CommentUploadFailed -> {
                            // Show failure toast
                            val context = notificationContextRef?.get()
                            if (context != null) {
                                withContext(Main) {
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.comment_failed),
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                            Timber.tag("TweetFeedViewModel")
                                .e("Comment upload failed: ${event.error}")
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
                            // Update like status and count (for other users' actions)
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
                            // Update bookmark status and count (for other users' actions)
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
                                    Timber.tag("TweetFeedViewModel")
                                        .d("Retweet added to feed: ${retweetWithAuthor.mid}")
                                } else {
                                    Timber.tag("TweetFeedViewModel")
                                        .d("Retweet already exists in feed: ${retweetWithAuthor.mid}")
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

                        // Chat events - ignore in TweetFeedViewModel
                        is TweetEvent.ChatMessageSent -> {
                            // Chat events are handled in ChatViewModel
                            Timber.tag("TweetFeedViewModel").d("Ignoring ChatMessageSent event")
                        }

                        is TweetEvent.ChatMessageSendFailed -> {
                            // Chat events are handled in ChatViewModel
                            Timber.tag("TweetFeedViewModel")
                                .d("Ignoring ChatMessageSendFailed event")
                        }

                        is TweetEvent.ChatMessageReceived -> {
                            // Chat events are handled in ChatViewModel
                            Timber.tag("TweetFeedViewModel").d("Ignoring ChatMessageReceived event")
                        }

                        is TweetEvent.ChatSessionUpdated -> {
                            // Chat events are handled in ChatViewModel
                            Timber.tag("TweetFeedViewModel").d("Ignoring ChatSessionUpdated event")
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