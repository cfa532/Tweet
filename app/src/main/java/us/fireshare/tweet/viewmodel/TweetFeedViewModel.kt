package us.fireshare.tweet.viewmodel

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import androidx.work.BackoffPolicy
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
import us.fireshare.tweet.HproseInstance.fetchUser
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
    
    // Track if ViewModel has been initialized to prevent race conditions
    private var isInitialized = false

    init {
        // Start listening to notifications immediately when ViewModel is created
        startListeningToNotifications() // Will be updated with context later
        
        // Don't load tweets immediately - wait for explicit initialization
        // This prevents race conditions with HproseInstance initialization
    }
    
    /**
     * Initialize the ViewModel after HproseInstance is ready.
     * This should be called from the UI layer after app initialization completes.
     */
    fun initialize() {
        if (isInitialized) {
            return
        }
        isInitialized = true
        viewModelScope.launch(IO) {
            try {
                waitForAppUser()
                if (appUser.baseUrl != null) {
                    // BaseUrl available, try to load tweets from server
                    refresh(0)
                } else {
                    // BaseUrl not available, load cached tweets only
                    Timber.tag("TweetFeedViewModel").w("AppUser baseUrl not initialized, loading cached tweets only")
                    loadCachedTweetsOnly()
                }
            } catch (e: Exception) {
                Timber.tag("TweetFeedViewModel").e(e, "Error during ViewModel initialization, loading cached tweets")
                // Even on error, try to load cached tweets
                try {
                    loadCachedTweetsOnly()
                } catch (cacheError: Exception) {
                    Timber.tag("TweetFeedViewModel").e(cacheError, "Failed to load cached tweets")
                    _tweets.value = emptyList()
                }
            } finally {
                initState.value = false
            }
        }
    }

    /**
     * Load cached tweets when server is not available
     */
    private suspend fun loadCachedTweetsOnly() {
        try {
            val cachedTweets = loadCachedTweets(0, TW_CONST.PAGE_SIZE)
            _tweets.value = cachedTweets
                .distinctBy { it.mid }
                .sortedByDescending { it.timestamp }
            Timber.tag("TweetFeedViewModel").d("Loaded ${cachedTweets.size} cached tweets")
        } catch (e: Exception) {
            Timber.tag("TweetFeedViewModel").e(e, "Failed to load cached tweets")
            _tweets.value = emptyList()
        }
    }
    
    /**
     * Reset the ViewModel state for logout or user changes.
     * This clears all data and allows re-initialization.
     * Don't clear tweet cache on logout - cache persists per user and is cleared periodically or manually (matches iOS behavior)
     */
    fun reset() {
        isInitialized = false
        
        // Don't clear cached tweets - cache persists per user and is cleared periodically or manually
        // This matches iOS behavior where cache is not cleared on logout
        
        _tweets.value = emptyList()
        initState.value = true
        
        // Re-initialize after a short delay to ensure appUser state is updated
        viewModelScope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(100) // Small delay to ensure state consistency
            initialize()
        }
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

    private suspend fun waitForAppUser(timeoutMillis: Long = 10000L) {
        val startTime = System.currentTimeMillis()
        Timber.tag("TweetFeedViewModel").d("Waiting for appUser.baseUrl to be available (timeout: ${timeoutMillis}ms)")
        while (appUser.baseUrl.isNullOrBlank() && System.currentTimeMillis() - startTime < timeoutMillis) {
            kotlinx.coroutines.delay(200)
        }
        val elapsed = System.currentTimeMillis() - startTime
        if (appUser.baseUrl.isNullOrBlank()) {
            Timber.tag("TweetFeedViewModel").w("Timeout waiting for appUser.baseUrl after ${elapsed}ms")
        } else {
            Timber.tag("TweetFeedViewModel").d("appUser.baseUrl became available after ${elapsed}ms: ${appUser.baseUrl}")
        }
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
            // For guest users: call getTweetsByUser() to fetch tweets
            val alphaIds = getAlphaIds()
            if (alphaIds.isEmpty()) {
                Timber.tag("TweetFeedViewModel").w("No alpha IDs configured, returning empty list for guest user")
                return emptyList()
            }
            val defaultUserId = alphaIds.first()
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
            // For regular users: call getTweetFeed() to get tweets from backend
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
            fetchUser(userId)?.let { user ->
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
     * 
     * When following: Fetch tweets directly from the user's node immediately.
     * The server will eventually add tweetIds to appuser's tweetlist, but that takes time.
     * When unfollowing: Remove all tweets of this user from the local feed.
     * */
    suspend fun updateFollowingsTweets(userId: MimeiId, isFollowing: Boolean) {
        if (isFollowing) {
            // add the tweets of a user after following it.
            // Fetch directly from user's node since server update takes time
            getTweets(userId)
        } else {
            // remove all tweets of this user from list after unfollowing it.
            _tweets.update { currentTweets ->
                currentTweets.filterNot { it.authorId == userId }
            }
        }
    }

    /**
     * Rollback tweet feed changes when follow operation fails.
     * If following failed: Remove tweets that were added.
     * If unfollowing failed: We can't restore removed tweets, so this is a no-op.
     * */
    suspend fun rollbackFollowingsTweets(userId: MimeiId, attemptedIsFollowing: Boolean) {
        if (attemptedIsFollowing) {
            // Following failed - remove tweets that were optimistically added
            _tweets.update { currentTweets ->
                currentTweets.filterNot { it.authorId == userId }
            }
            Timber.tag("rollbackFollowingsTweets").d("Rolled back tweets for user: $userId (following failed)")
        } else {
            // Unfollowing failed - we can't restore removed tweets without fetching from server
            // This is a limitation, but the user can refresh their feed manually
            Timber.tag("rollbackFollowingsTweets").d("Unfollowing failed for user: $userId, but cannot restore removed tweets")
        }
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
    // If deletion fails, restore the tweet and throw exception
    suspend fun delTweet(
        navController: NavController,
        tweetId: MimeiId,
        userViewModel: UserViewModel? = null,
        callback: () -> Unit,
    ) {
        // Save tweet state BEFORE removal for potential restoration
        val tweetToDelete = _tweets.value.find { it.mid == tweetId }
        val wasInTweetFeed = tweetToDelete != null
        
        // Track which UserViewModel lists contain this tweet
        val wasInUserTweets = userViewModel?.tweets?.value?.any { it.mid == tweetId } == true
        val wasInPinnedTweets = userViewModel?.pinnedTweets?.value?.any { it.mid == tweetId } == true
        val wasInFavorites = userViewModel?.favorites?.value?.any { it.mid == tweetId } == true
        val wasInBookmarks = userViewModel?.bookmarks?.value?.any { it.mid == tweetId } == true
        
        // Get tweet from UserViewModel if not in TweetFeedViewModel
        var finalTweetToDelete = tweetToDelete
        if (finalTweetToDelete == null && userViewModel != null) {
            finalTweetToDelete = userViewModel.tweets.value.find { it.mid == tweetId }
                ?: userViewModel.pinnedTweets.value.find { it.mid == tweetId }
                ?: userViewModel.favorites.value.find { it.mid == tweetId }
                ?: userViewModel.bookmarks.value.find { it.mid == tweetId }
        }

        Timber.tag("TweetFeedViewModel").d("Starting optimistic deletion of tweet $tweetId")

        // STEP 1: OPTIMISTIC UPDATE - Remove tweet from UI immediately
        withContext(Main) {
            removeTweet(tweetId)
            userViewModel?.removeTweetFromAllLists(tweetId)
            Timber.tag("TweetFeedViewModel").d("Tweet $tweetId removed from UI (optimistic)")
        }

        // STEP 2: POST DELETION NOTIFICATION IMMEDIATELY (Optimistic)
        // This ensures ALL ViewModels listening will remove the tweet instantly
        // TweetViewModel will set tweetDeleted=true, causing TweetDetailScreen to navigate away
        val authorId = finalTweetToDelete?.authorId ?: appUser.mid
        Timber.tag("TweetFeedViewModel").d("Posting optimistic TweetDeleted notification for $tweetId")
        TweetNotificationCenter.post(
            TweetEvent.TweetDeleted(
                tweetId,
                authorId
            )
        )

        // NOTE: No need to navigate here - TweetDetailScreen observes tweetDeleted state
        // and will navigate away automatically when TweetViewModel receives the notification

        // STEP 3: Delete from local cache
        try {
            dao.deleteCachedTweet(tweetId)
            Timber.tag("TweetFeedViewModel").d("Tweet $tweetId deleted from local cache")
        } catch (e: Exception) {
            Timber.tag("TweetFeedViewModel").w(e, "Failed to delete tweet from cache")
        }

        // STEP 4: Delete from backend
        var deletionFailed = false
        var errorMessage: String? = null
        try {
            Timber.tag("TweetFeedViewModel").d("Attempting backend deletion of tweet $tweetId")
            val deletedTweetId = HproseInstance.deleteTweet(tweetId)
            if (deletedTweetId != null) {
                // Success - backend confirmed deletion
                Timber.tag("TweetFeedViewModel").d("Backend deletion successful for $deletedTweetId")
            } else {
                deletionFailed = true
                errorMessage = "Backend returned null"
                Timber.tag("TweetFeedViewModel").w("Backend deleteTweet returned null for tweetId $tweetId")
            }
        } catch (e: Exception) {
            deletionFailed = true
            errorMessage = e.message ?: "Unknown error"
            Timber.tag("TweetFeedViewModel").e(e, "Backend deletion failed: ${e.message}")
        }
        
        // STEP 5: If deletion failed, restore the tweet
        if (deletionFailed && finalTweetToDelete != null) {
            Timber.tag("TweetFeedViewModel").w("Deletion failed, restoring tweet $tweetId")
            
            // Try to fetch fresh version from server
            var restoredTweet: Tweet? = null
            try {
                restoredTweet = HproseInstance.fetchTweet(finalTweetToDelete.mid, finalTweetToDelete.authorId)
                Timber.tag("TweetFeedViewModel").d("Fetched fresh tweet from server for restoration")
            } catch (e: Exception) {
                Timber.tag("TweetFeedViewModel").w(e, "Failed to fetch tweet from server, using cached version")
            }
            
            val tweetToRestore = restoredTweet ?: finalTweetToDelete
            
            // Restore on Main thread to ensure immediate UI update
            withContext(Main) {
                // Restore in TweetFeedViewModel if it was there
                if (wasInTweetFeed) {
                    _tweets.update { current ->
                        if (current.any { it.mid == tweetToRestore.mid }) {
                            Timber.tag("TweetFeedViewModel").d("Tweet already in list, skipping restoration")
                            current
                        } else {
                            val restored = (current + tweetToRestore).distinctBy { it.mid }.sortedByDescending { it.timestamp }
                            Timber.tag("TweetFeedViewModel").d("Restored tweet to TweetFeedViewModel, now ${restored.size} tweets")
                            restored
                        }
                    }
                }
                
                // Restore in UserViewModel if it was in any lists
                if (userViewModel != null && (wasInUserTweets || wasInPinnedTweets || wasInFavorites || wasInBookmarks)) {
                    userViewModel.restoreTweetToLists(tweetToRestore, wasInUserTweets, wasInPinnedTweets, wasInFavorites, wasInBookmarks)
                    Timber.tag("TweetFeedViewModel").d("Restored tweet to UserViewModel lists")
                }
            }
            
            // POST RESTORATION NOTIFICATION so all listeners can restore the tweet
            Timber.tag("TweetFeedViewModel").d("Posting TweetRestored notification for $tweetId")
            TweetNotificationCenter.post(TweetEvent.TweetRestored(tweetToRestore))
            
            throw Exception("Failed to delete tweet: $errorMessage")
        }
        
        Timber.tag("TweetFeedViewModel").d("Tweet deletion completed successfully")
        callback()
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
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                2_000L, // 2 seconds
                java.util.concurrent.TimeUnit.MILLISECONDS
            )
            .build()

        val workManager = WorkManager.getInstance(context)
        workManager.enqueue(uploadRequest)
        val workId = uploadRequest.id.toString()

        // Take persistent URI permissions to survive app restarts and process changes
        attachments?.forEach { uri ->
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: Exception) {
                Timber.tag("TweetFeedViewModel").w("Failed to take persistable permission for URI: $uri")
            }
        }

        // Check if there are video attachments and show background upload message
        val hasVideoAttachments = attachments?.any { uri ->
            try {
                val mimeType = context.contentResolver.getType(uri)
                mimeType?.startsWith("video/") == true
            } catch (e: Exception) {
                false
            }
        } ?: false

        if (hasVideoAttachments) {
            // Show toast immediately informing user about background upload
            Toast.makeText(
                context,
                context.getString(R.string.tweet_uploading_background),
                Toast.LENGTH_SHORT
            ).show()
        }

        // Save incomplete upload for potential resume
        val incompleteUpload = HproseInstance.IncompleteUpload(
            workId = workId,
            tweetContent = content,
            attachmentUris = attachments?.map { it.toString() } ?: emptyList(),
            isPrivate = isPrivate,
            timestamp = System.currentTimeMillis()
        )
        HproseInstance.saveIncompleteUpload(context, incompleteUpload)

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
        applicationScope.launch {
            try {
                TweetNotificationCenter.events.collect { event ->
                    when (event) {
                        is TweetEvent.TweetUploaded -> {
                            Timber.tag("TweetFeedViewModel").d("Received TweetUploaded notification for tweet: ${event.tweet.mid}, author: ${event.tweet.authorId}, current user: ${appUser.mid}")

                            // Add new tweet to the beginning of the feed
                            val tweetWithAuthor = event.tweet

                            // Show success toast if it's the current user's tweet
                            val context = notificationContextRef?.get()
                            if (tweetWithAuthor.authorId == appUser.mid && context != null) {
                                Timber.tag("TweetFeedViewModel").d("Showing success toast for tweet: ${tweetWithAuthor.mid}")
                                withContext(Main) {
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.tweet_uploaded),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                                
                                // Cache the new tweet for mainfeed under appUser.mid
                                TweetCacheManager.saveTweet(tweetWithAuthor, appUser.mid)
                            }

                            // Update on main thread to ensure UI updates
                            withContext(Main) {
                                // Check if tweet already exists to avoid duplicates
                                val existingTweetIds = _tweets.value.map { it.mid }.toSet()
                                if (tweetWithAuthor.mid !in existingTweetIds) {
                                    _tweets.value = listOf(tweetWithAuthor) + _tweets.value
                                    Timber.tag("TweetFeedViewModel")
                                        .d("Tweet added: ${tweetWithAuthor.mid} by ${tweetWithAuthor.author?.username}")
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
                            // Remove tweet from feed if it still exists (in case optimistic removal failed)
                            withContext(Main) {
                                _tweets.update { currentTweets ->
                                    val beforeCount = currentTweets.size
                                    val filteredTweets = currentTweets.filterNot { it.mid == event.tweetId }
                                    val afterCount = filteredTweets.size
                                    
                                    if (beforeCount > afterCount) {
                                        Timber.tag("TweetFeedViewModel")
                                            .d("Removed tweet ${event.tweetId} from feed via notification (${beforeCount} -> ${afterCount})")
                                    } else {
                                        Timber.tag("TweetFeedViewModel")
                                            .d("Tweet ${event.tweetId} not found in feed (already removed optimistically)")
                                    }
                                    filteredTweets
                                }
                            }
                        }

                        is TweetEvent.TweetRestored -> {
                            // Restore tweet to feed after failed deletion
                            withContext(Main) {
                                _tweets.update { currentTweets ->
                                    if (!currentTweets.any { it.mid == event.tweet.mid }) {
                                        val restored = (listOf(event.tweet) + currentTweets).distinctBy { it.mid }.sortedByDescending { it.timestamp }
                                        Timber.tag("TweetFeedViewModel")
                                            .d("Restored tweet ${event.tweet.mid} to feed after failed deletion")
                                        restored
                                    } else {
                                        Timber.tag("TweetFeedViewModel")
                                            .d("Tweet ${event.tweet.mid} already in feed, skipping restoration")
                                        currentTweets
                                    }
                                }
                            }
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
                                // Check cache first before using appUser as fallback
                                val cachedAuthor = TweetCacheManager.getCachedUser(event.retweet.authorId)
                                event.retweet.copy(author = cachedAuthor ?: appUser)
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

                        is TweetEvent.UserDataUpdated -> {
                            // User data updates are handled in UserViewModel
                            Timber.tag("TweetFeedViewModel").d("Ignoring UserDataUpdated event")
                        }

                        is TweetEvent.FeedResetRequested -> {
                            Timber.tag("TweetFeedViewModel")
                                .d("Feed reset requested due to ${event.reason}, resetting feed")
                            reset()
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