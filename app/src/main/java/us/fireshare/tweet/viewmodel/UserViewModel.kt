package us.fireshare.tweet.viewmodel

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import us.fireshare.tweet.HproseInstance
import us.fireshare.tweet.HproseInstance.appUser
import us.fireshare.tweet.HproseInstance.fetchUser
import us.fireshare.tweet.HproseInstance.getUserTweetsByType
import us.fireshare.tweet.HproseInstance.loadCachedTweetsByAuthor
import us.fireshare.tweet.HproseInstance.preferenceHelper
import us.fireshare.tweet.R
import us.fireshare.tweet.datamodel.FeedResetReason
import us.fireshare.tweet.datamodel.MimeiId
import us.fireshare.tweet.datamodel.TW_CONST
import us.fireshare.tweet.datamodel.Tweet
import us.fireshare.tweet.datamodel.TweetCacheManager
import us.fireshare.tweet.datamodel.TweetEvent
import us.fireshare.tweet.datamodel.TweetNotificationCenter
import us.fireshare.tweet.datamodel.User
import us.fireshare.tweet.datamodel.UserContentType
import androidx.work.WorkManager
import androidx.work.OneTimeWorkRequest
import androidx.work.BackoffPolicy
import androidx.work.WorkInfo
import androidx.work.workDataOf
import us.fireshare.tweet.service.FollowUserWorker

@HiltViewModel(assistedFactory = UserViewModel.UserViewModelFactory::class)
class UserViewModel @AssistedInject constructor(
    @Assisted val userId: MimeiId
): ViewModel() {
    // Track active work observers to clean up on ViewModel destruction
    private val activeWorkObservers = mutableMapOf<java.util.UUID, androidx.lifecycle.Observer<WorkInfo?>>()
    
    // Track users with pending operations to prevent race conditions
    private val pendingOperations = mutableSetOf<MimeiId>()
    private var _user = MutableStateFlow(User(mid = TW_CONST.GUEST_ID, baseUrl = appUser.baseUrl))
    val user: StateFlow<User> get() = _user.asStateFlow()

    // unpinned tweets
    private val _tweets = MutableStateFlow<List<Tweet>>(emptyList())
    val tweets: StateFlow<List<Tweet>> get() = _tweets.asStateFlow()

    // pinned tweets
    private val _pinnedTweets = MutableStateFlow<List<Tweet>>(emptyList())
    val pinnedTweets: StateFlow<List<Tweet>> get() = _pinnedTweets.asStateFlow()

    private var _followers = MutableStateFlow(emptyList<MimeiId>())
    val followers: StateFlow<List<MimeiId>> get() = _followers.asStateFlow()
    private var _followings = MutableStateFlow(emptyList<MimeiId>())
    val followings: StateFlow<List<MimeiId>> get() = _followings.asStateFlow()
    
    // Signal for follow operation failures - emits userId when operation fails
    private val _followOperationFailed = MutableStateFlow<MimeiId?>(null)
    val followOperationFailed: StateFlow<MimeiId?> get() = _followOperationFailed.asStateFlow()

    var isLoading = MutableStateFlow(false)

    private val _bookmarks = MutableStateFlow<List<Tweet>>(emptyList())
    val bookmarks: StateFlow<List<Tweet>> get() = _bookmarks.asStateFlow()
    private val _favorites = MutableStateFlow<List<Tweet>>(emptyList())
    val favorites: StateFlow<List<Tweet>> get() = _favorites.asStateFlow()

    // Public count variables for UI display
    private val _bookmarksCount = MutableStateFlow(0)
    val bookmarksCount: StateFlow<Int> get() = _bookmarksCount.asStateFlow()

    private val _favoritesCount = MutableStateFlow(0)
    val favoritesCount: StateFlow<Int> get() = _favoritesCount.asStateFlow()

    private val _followersCount = MutableStateFlow(0)
    val followersCount: StateFlow<Int> get() = _followersCount.asStateFlow()

    private val _followingsCount = MutableStateFlow(0)
    val followingsCount: StateFlow<Int> get() = _followingsCount.asStateFlow()

    private val _tweetCount = MutableStateFlow(0)
    val tweetCount: StateFlow<Int> get() = _tweetCount.asStateFlow()

    // variable for login management
    var username = mutableStateOf(appUser.username)
    var password = mutableStateOf("")
    var name = mutableStateOf(appUser.name ?: "")
    var profile = mutableStateOf(appUser.profile ?: "")
    var hostId = mutableStateOf("")
    var cloudDrivePort = mutableStateOf(if (appUser.cloudDrivePort == 0) "" else appUser.cloudDrivePort.toString())
    var domainToShare = mutableStateOf(appUser.domainToShare ?: "")
    var isPasswordVisible = mutableStateOf(false)
    var loginError = mutableStateOf("")

    // Validation error states for showing red text warnings
    var usernameError = mutableStateOf("")
    var passwordError = mutableStateOf("")
    var confirmPasswordError = mutableStateOf("")
    var hostIdError = mutableStateOf("")
    var cloudDrivePortError = mutableStateOf("")

    var initState = MutableStateFlow(true)      // initial load state

    /**
     * Initial load of tweets of an user. Execute only once.
     * */
    suspend fun initLoad() {
        try {
            Timber.tag("initLoad").d("Starting initial load for user: ${user.value.mid}")

            // Load first page (page 0) which includes pinned tweets
            val page0Tweets = getTweets(0)
            
            // Check if page 0 indicates server depletion
            // If server returns fewer than PAGE_SIZE tweets, it's depleted (regardless of null/valid)
            val page0ValidTweets = page0Tweets.filterNotNull()
            if (page0Tweets.size < TW_CONST.PAGE_SIZE) {
                // Server is depleted - returned fewer than a full page
                Timber.tag("initLoad").d("Page 0 returned ${page0Tweets.size} tweets (${page0ValidTweets.size} valid), server depleted - stopping initial load")
            } else {
                // Load additional pages if needed to get at least 5 viewable tweets
                var pageNumber = 1
                var serverDepleted = false
                
                while (tweets.value.size < 5 && pageNumber < 10 && !serverDepleted) {
                    val pageTweets = getTweets(pageNumber)
                    val validTweets = pageTweets.filterNotNull()
                    
                    // Check if server is depleted (returned fewer tweets than PAGE_SIZE)
                    if (pageTweets.size < TW_CONST.PAGE_SIZE) {
                        serverDepleted = true
                        Timber.tag("initLoad").d("Server depleted at page $pageNumber, returned ${pageTweets.size} tweets (${validTweets.size} valid)")
                    }
                    
                    // Also stop if we got no valid tweets from server
                    if (validTweets.isEmpty() && serverDepleted) {
                        Timber.tag("initLoad").d("Page $pageNumber returned no valid tweets, stopping initial load")
                        break
                    }
                    
                    pageNumber++
                }
            }

            Timber.tag("initLoad")
                .d("Initial load completed. Pinned tweets: ${pinnedTweets.value.size}, Regular tweets: ${tweets.value.size}")
        } catch (e: Exception) {
            Timber.tag("initLoad").e(e, "Error during initial load for user: ${user.value.mid}")
        } finally {
            initState.value = false
        }
    }

    /**
     * Refresh user data to ensure it's up to date (e.g., after profile editing)
     * Includes retry logic.
     */
    suspend fun refreshUserData(maxRetries: Int = 3) {
        try {
            refreshUserWithRetry(maxRetries)

            // If this is the current user's profile, update appUser from refreshed data
            if (userId == appUser.mid) {
                // Update appUser singleton with server data
                User.updateUserInstance(user.value)

                // Ensure appUser reference points to the updated singleton
                appUser = User.getInstance(appUser.mid)
                TweetCacheManager.saveUser(appUser)
            }

            // Update count variables from the refreshed user data (which now includes appUser if same user)
            _bookmarksCount.value = user.value.bookmarksCount
            _favoritesCount.value = user.value.favoritesCount
            _followersCount.value = user.value.followersCount
            _followingsCount.value = user.value.followingCount
            _tweetCount.value = user.value.tweetCount

            Timber.tag("refreshUserData").d("Refreshed user data for user: ${user.value.name}")
        } catch (e: Exception) {
            Timber.tag("refreshUserData").e(e, "Error refreshing user data for user: $userId")
        }
    }

    /**
     * Refresh user data with retry logic
     * Matches iOS ProfileView.refreshProfileData() behavior:
     * - Passes empty baseUrl to force fresh IP resolution and skip cache
     * - Does NOT use forceRefresh=true (relies on empty baseUrl logic)
     * - Does NOT remove cached user before fetching
     */
    private suspend fun refreshUserWithRetry(maxRetries: Int = 3) {
        repeat(maxRetries) { attempt ->
            try {
                // Pass empty baseUrl to force IP re-resolution and skip cache (matching iOS)
                // fetchUser will skip cache when baseUrl is empty and fetch from server
                val refreshedUser = fetchUser(userId, baseUrl = "", maxRetries = 1, forceRefresh = false)
                if (refreshedUser != null && !refreshedUser.isGuest()) {
                    _user.value = refreshedUser
                    return // Success, exit retry loop
                } else {
                    Timber.tag("refreshUserWithRetry").w("Failed to fetch valid user data for $userId (attempt ${attempt + 1})")
                }
            } catch (e: Exception) {
                Timber.tag("refreshUserWithRetry").e(e, "Error refreshing user $userId (attempt ${attempt + 1})")
            }
            
            // If this isn't the last attempt, wait before retrying
            if (attempt < maxRetries - 1) {
                val delayMs = minOf(3000L, 1000L * (1 shl attempt)) // Exponential backoff: 1s, 2s
                Timber.tag("refreshUserWithRetry").d("Retrying user refresh in ${delayMs}ms (attempt ${attempt + 2}/$maxRetries)")
                delay(delayMs)
            }
        }
    }

    /**
     * Simple function to fetch tweets for a specific page number.
     * TweetListView manages pagination logic internally.
     * Returns List<Tweet?> including null elements from the backend.
     */
    suspend fun fetchTweets(pageNumber: Int): List<Tweet?> {
        return getTweets(pageNumber)
    }

    /**
     * Load a page of cached tweets and merge them with the existing tweets StateFlow.
     * This function loads tweets from the cache without making network calls.
     * 
     * @param pageNumber The page number to load (0-indexed)
     * @return List of cached tweets loaded (as Tweet? to match fetchTweets signature)
     */
    suspend fun loadCachedTweetsPage(pageNumber: Int): List<Tweet?> {
        return try {
            Timber.tag("loadCachedTweetsPage").d("Loading cached tweets page $pageNumber for user: ${user.value.mid}")
            
            // Load cached tweets for this page
            val cachedTweets = loadCachedTweetsByAuthor(
                user.value.mid, 
                pageNumber * TW_CONST.PAGE_SIZE, 
                TW_CONST.PAGE_SIZE
            )
            
            // Update _tweets with cached tweets
            _tweets.update { currentTweets ->
                val currentTweetIds = currentTweets.map { it.mid }.toSet()
                val newCachedTweets = cachedTweets.filter { it.mid !in currentTweetIds }
                
                // Get pinned tweet IDs to exclude them from regular tweets list
                val pinnedTweetIds = pinnedTweets.value.map { it.mid }.toSet()
                val tweetsWithoutPinned = newCachedTweets.filterNot { 
                    pinnedTweetIds.contains(it.mid) 
                }
                
                if (tweetsWithoutPinned.isNotEmpty()) {
                    val mergedTweets = (currentTweets + tweetsWithoutPinned)
                        .distinctBy { tweet: Tweet -> tweet.mid }
                        .sortedByDescending { tweet: Tweet -> tweet.timestamp }
                    Timber.tag("loadCachedTweetsPage").d("Merged ${tweetsWithoutPinned.size} new cached tweets, total: ${mergedTweets.size}")
                    mergedTweets
                } else {
                    Timber.tag("loadCachedTweetsPage").d("No new cached tweets to merge")
                    currentTweets
                }
            }
            
            Timber.tag("loadCachedTweetsPage").d("Loaded ${cachedTweets.size} cached tweets for user: ${user.value.mid}, page: $pageNumber")
            
            // Return cached tweets as nullable list to match fetchTweets signature
            cachedTweets.map { it as Tweet? }
        } catch (e: Exception) {
            Timber.tag("loadCachedTweetsPage").e(e, "Error loading cached tweets page $pageNumber for user: ${user.value.mid}")
            emptyList()
        }
    }

    /**
     * Whether the tweet is pinned to top list.
     * */
    fun hasPinned(tweet: Tweet): Boolean {
        return pinnedTweets.value.any { it.mid == tweet.mid }
    }


    suspend fun updateAvatar(context: Context, uri: Uri) {
        isLoading.value = true
        try {
            // Store the old avatar ID to clear cache later
            val oldAvatarId = appUser.avatar
            
            // For now, user avatar can only be image.
            HproseInstance.uploadToIPFS(
                uri,
                referenceId = null  // appUser.mid, avoid a backend bug for now.
            )?.let {
                HproseInstance.setUserAvatar(appUser, it.mid)?.let { avatar ->  // Update appUser's avatar
                    // Clear the old avatar from cache if it exists
                    oldAvatarId?.let { oldId ->
                        us.fireshare.tweet.widget.ImageCacheManager.clearCachedImage(context, oldId)
                    }
                    
                    // Update the user objects with new avatar
                    appUser = appUser.copy(avatar = avatar)
                    _user.value = user.value.copy(avatar = avatar)
                    User.updateUserInstance(appUser)

                    // Save the updated user to cache
                    TweetCacheManager.saveUser(appUser)
                }
            }
        } finally {
            isLoading.value = false
        }
    }



    /**
     * Optimistically update followingList and enqueue background worker.
     * Immediately updates the followingList, then handles the actual operation in background.
     * If the operation fails, the worker will send a notification.
     * 
     * @param subjectUserId to add/remove it to/from the following list
     * @param userId the user performing the follow action (defaults to appUser.mid)
     * @param context context for WorkManager
     * @param updateTweetFeed callback to update tweet feed
     */
    fun toggleFollowingOptimistic(
        subjectUserId: MimeiId,
        userId: MimeiId = appUser.mid,
        context: Context,
        updateTweetFeed: (Boolean) -> Unit,
        rollbackTweetFeed: (Boolean) -> Unit
    ) {
        // Prevent concurrent operations on the same user
        if (pendingOperations.contains(subjectUserId)) {
            Timber.tag("UserViewModel").w("Follow operation already in progress for user: $subjectUserId, ignoring duplicate request")
            return
        }
        
        val currentlyFollowing = _followings.value.contains(subjectUserId)
        val newFollowingState = !currentlyFollowing
        
        // Store previous state for rollback
        val previousCount = _followingsCount.value
        
        // Mark operation as pending
        pendingOperations.add(subjectUserId)
        
        // Optimistically update the following list immediately
        _followings.update { list ->
            if (newFollowingState)
                (listOf(subjectUserId) + list).toSet().toList()
            else
                list.filterNot { it == subjectUserId }
        }
        
        // Update the count manually - increment/decrement based on the action
        val newCount = if (newFollowingState) {
            _followingsCount.value + 1
        } else {
            _followingsCount.value - 1
        }
        _followingsCount.value = newCount
        
        // Update the user object with the correct count
        _user.value = user.value.copy(followingCount = newCount)
        
        // Also update appUser.followingCount so refreshUserData uses the correct value
        if (userId == appUser.mid) {
            appUser.followingCount = newCount
        }
        
        // Update tweet feed optimistically
        viewModelScope.launch(IO) {
            updateTweetFeed(newFollowingState)
        }
        
        // Enqueue background worker to handle the actual operation
        val data = workDataOf(
            "followedId" to subjectUserId,
            "followingId" to userId,
            "isFollowing" to newFollowingState
        )
        val followRequest = OneTimeWorkRequest.Builder(FollowUserWorker::class.java)
            .setInputData(data)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                10_000L, // 10 seconds
                java.util.concurrent.TimeUnit.MILLISECONDS
            )
            .build()
        
        WorkManager.getInstance(context).enqueue(followRequest)
        
        // Observe work status to rollback on failure
        viewModelScope.launch {
            val observer = androidx.lifecycle.Observer<WorkInfo?> { workInfo ->
                when (workInfo?.state) {
                    WorkInfo.State.FAILED -> {
                        // Rollback the optimistic update
                        Timber.tag("UserViewModel").d("Follow operation failed, rolling back for: $subjectUserId")
                        
                        // Rollback following list
                        _followings.update { list ->
                            if (newFollowingState)
                                list.filterNot { it == subjectUserId }
                            else
                                (listOf(subjectUserId) + list).toSet().toList()
                        }
                        
                        // Rollback count
                        _followingsCount.value = previousCount
                        
                        // Rollback user object count
                        _user.value = user.value.copy(followingCount = previousCount)
                        
                        // Rollback appUser.followingCount
                        if (userId == appUser.mid) {
                            appUser.followingCount = previousCount
                        }
                        
                        // Rollback tweet feed
                        viewModelScope.launch(IO) {
                            rollbackTweetFeed(newFollowingState)
                        }
                        
                        // Signal failure to UI for toast notification
                        _followOperationFailed.value = subjectUserId
                        
                        // Clean up
                        cleanupObserver(context, followRequest.id, subjectUserId)
                    }
                    WorkInfo.State.SUCCEEDED -> {
                        Timber.tag("UserViewModel").d("Follow operation succeeded for: $subjectUserId")
                        // Clean up
                        cleanupObserver(context, followRequest.id, subjectUserId)
                    }
                    else -> {
                        // Still in progress or other state
                    }
                }
            }
            
            // Store observer for cleanup
            activeWorkObservers[followRequest.id] = observer
            
            WorkManager.getInstance(context)
                .getWorkInfoByIdLiveData(followRequest.id)
                .observeForever(observer)
        }
    }
    
    /**
     * Clean up observer and remove from pending operations
     */
    private fun cleanupObserver(context: Context, workId: java.util.UUID, userId: MimeiId) {
        activeWorkObservers[workId]?.let { observer ->
            WorkManager.getInstance(context)
                .getWorkInfoByIdLiveData(workId)
                .removeObserver(observer)
            activeWorkObservers.remove(workId)
        }
        pendingOperations.remove(userId)
    }
    
    /**
     * Clear the follow operation failed signal (called by UI after showing toast)
     */
    fun clearFollowOperationFailed() {
        _followOperationFailed.value = null
    }
    
    override fun onCleared() {
        super.onCleared()
        // Clean up all active observers when ViewModel is destroyed
        // Note: We can't access context here, so we'll rely on WorkManager's lifecycle
        // The observers will be automatically cleaned up when the app context is destroyed
        activeWorkObservers.clear()
        pendingOperations.clear()
        Timber.tag("UserViewModel").d("ViewModel cleared, cleaned up ${activeWorkObservers.size} observers and ${pendingOperations.size} pending operations")
    }

    suspend fun refreshFollowingsAndFans() {
        val fans = HproseInstance.getFans(user.value) ?: emptyList()
        val followings = HproseInstance.getFollowings(user.value)

        _followers.value = fans
        _followings.value = followings

        // Update the public count variables for UI
        _followersCount.value = fans.size
        _followingsCount.value = followings.size
    }

    companion object {
        // Use the constant from TW_CONST
    }

    /**
     * Fetch followers with pagination support
     * Returns List<MimeiId> (null values are filtered out)
     */
    suspend fun fetchFollowers(pageNumber: Int): List<MimeiId> {
        @Suppress("SENSELESS_COMPARISON")
        if (userId == null) return emptyList()
        Timber.tag("fetchFollowers").d("fetchFollowers called with pageNumber: $pageNumber")
        return try {
            if (pageNumber == 0) {
                // For page 0, refresh the entire list
                Timber.tag("fetchFollowers").d("Loading all followers for user: ${user.value.mid}")
                val allFollowers = HproseInstance.getFans(user.value) ?: emptyList()
                Timber.tag("fetchFollowers").d("getFans returned: ${allFollowers.size} followers")

                // Check for duplicates in the raw data
                val duplicates = allFollowers.groupingBy { it }.eachCount().filter { it.value > 1 }
                if (duplicates.isNotEmpty()) {
                    Timber.tag("fetchFollowers")
                        .w("Found duplicate user IDs in raw data: $duplicates")
                }

                _followers.value = allFollowers

                // Return the first batch of users, filtering out nulls
                val firstBatch = allFollowers.take(TW_CONST.USER_BATCH_SIZE)
                Timber.tag("fetchFollowers").d("Returning first batch: ${firstBatch.size} user IDs")
                firstBatch
            } else {
                // For subsequent pages, return the appropriate slice of already-loaded followers
                val startIndex = pageNumber * TW_CONST.USER_BATCH_SIZE
                val endIndex = startIndex + TW_CONST.USER_BATCH_SIZE
                val slice =
                    _followers.value.slice(startIndex until minOf(endIndex, _followers.value.size))

                if (slice.isEmpty()) {
                    // No more followers to return
                    Timber.tag("fetchFollowers")
                        .d("No more followers to return for page: $pageNumber")
                    emptyList()
                } else {
                    Timber.tag("fetchFollowers")
                        .d("Returning slice for page $pageNumber: ${slice.size} user IDs")
                    slice
                }
            }
        } catch (e: Exception) {
            Timber.tag("fetchFollowers")
                .e(e, "Error fetching followers for user: ${user.value.mid}")
            emptyList()
        }
    }

    /**
     * Fetch followings with pagination support
     * Returns List<MimeiId> (null values are filtered out)
     */
    suspend fun fetchFollowings(pageNumber: Int): List<MimeiId> {
        return try {
            if (pageNumber == 0) {
                // For page 0, refresh the entire list
                val allFollowings = HproseInstance.getFollowings(user.value)
                _followings.value = allFollowings

                // Return the first batch of users, filtering out nulls
                allFollowings.take(TW_CONST.USER_BATCH_SIZE)
            } else {
                // For subsequent pages, return the appropriate slice of already-loaded followings
                val startIndex = pageNumber * TW_CONST.USER_BATCH_SIZE
                val endIndex = startIndex + TW_CONST.USER_BATCH_SIZE
                val slice = _followings.value.slice(
                    startIndex until minOf(
                        endIndex,
                        _followings.value.size
                    )
                )

                slice.ifEmpty {
                    // No more followings to return
                    emptyList()
                }
            }
        } catch (e: Exception) {
            Timber.tag("fetchFollowings")
                .e(e, "Error fetching followings for user: ${user.value.mid}")
            emptyList()
        }
    }

    /**
     * Get bookmarks of the user
     * */
    suspend fun getBookmarks(pageNumber: Int) {
        // Ensure we have the latest user data before loading bookmarks
        if (userId == appUser.mid) {
            refreshFromAppUser()
        }
        
        // Use the most up-to-date user data (appUser) instead of potentially stale user.value
        val currentUser = if (userId == appUser.mid) appUser else user.value
        
        val tweetsWithNulls = getUserTweetsByType(
            currentUser,
            UserContentType.BOOKMARKS,
            pageNumber,
            TW_CONST.PAGE_SIZE
        )

        // Filter out null elements and get valid tweets
        val validTweets = tweetsWithNulls.filterNotNull()

        Timber.tag("getBookmarks")
            .d("Received ${tweetsWithNulls.size} tweets (${validTweets.size} valid) for user: ${user.value.mid}, page: $pageNumber")

        if (pageNumber == 0) {
            // For refresh (page 0), replace the list and sort it
            _bookmarks.value = validTweets.sortedByDescending { it.timestamp }
            // Don't override the count - it should come from server data, not local list size
        } else {
            // For load more (page > 0), append to the list
            _bookmarks.update { currentBookmarks ->
                val newTweetsMap = validTweets.associateBy { it.mid }
                val updatedBookmarks = currentBookmarks.map { bookmark ->
                    newTweetsMap[bookmark.mid] ?: bookmark
                }
                val finalBookmarks = (updatedBookmarks + validTweets)
                    .distinctBy { it.mid }
                    .sortedByDescending { it.timestamp }

                // Don't override the count - it should come from server data, not local list size
                finalBookmarks
            }
        }
    }

    /**
     * Update in-memory bookmark data for display.
     * This method now uses server data instead of optimistic updates.
     *
     * @param tweet The tweet to bookmark or unbookmark
     * @param isBookmarked True to add bookmark, false to remove bookmark
     */
    fun updateBookmark(tweet: Tweet, isBookmarked: Boolean) {
        // Get current user state for logging
        val currentUser = user.value

        // Log the operation details for debugging
        Timber.tag("UserViewModel")
            .d("updateBookmark: Current bookmarks count: ${currentUser.bookmarksCount}")
        Timber.tag("UserViewModel").d("updateBookmark: Current user ID: ${currentUser.mid}")
        Timber.tag("UserViewModel").d("updateBookmark: Tweet author ID: ${tweet.authorId}")
        Timber.tag("UserViewModel").d("updateBookmark: Is bookmark action: $isBookmarked")

        // Use the server's updated user data (appUser) instead of calculating locally
        val serverBookmarksCount = appUser.bookmarksCount
        
        // Update the user state with server data
        _user.value = appUser

        // Update the public bookmarks count for UI with server count
        _bookmarksCount.value = serverBookmarksCount

        // Update other count variables to match appUser (but don't override bookmarks count)
        _favoritesCount.value = appUser.favoritesCount
        _followersCount.value = appUser.followersCount
        _followingsCount.value = appUser.followingCount
        _tweetCount.value = appUser.tweetCount

        // Update the bookmarks list based on server response
        if (isBookmarked) {
            // Add tweet to the beginning of bookmarks list if not already present
            _bookmarks.update { bs -> 
                if (bs.any { it.mid == tweet.mid }) bs else listOf(tweet) + bs 
            }
            Timber.tag("UserViewModel")
                .d("Server bookmark: User bookmarked tweet ${tweet.mid}, server bookmarks count: $serverBookmarksCount")
        } else {
            // Remove tweet from bookmarks list
            _bookmarks.update { bs -> bs.filterNot { it.mid == tweet.mid } }
            Timber.tag("UserViewModel")
                .d("Server bookmark: User unbookmarked tweet ${tweet.mid}, server bookmarks count: $serverBookmarksCount")
        }

        // Log the final updated bookmark count
        Timber.tag("UserViewModel")
            .d("Server bookmark: Updated user bookmarksCount: ${appUser.bookmarksCount}")

        // Persist changes to cache immediately in background
        viewModelScope.launch(IO) {
            Timber.tag("UserViewModel")
                .d("updateBookmark: Saving user to cache with bookmarksCount: ${appUser.bookmarksCount}")
            TweetCacheManager.saveUser(appUser)
            Timber.tag("UserViewModel").d("updateBookmark: User saved to cache successfully")
            
            // Notify other ViewModels that user data has been updated
            TweetNotificationCenter.post(TweetEvent.UserDataUpdated(appUser))
        }
    }

    /**
     * Get favorite Tweets of the user.
     * */
    suspend fun getFavorites(pageNumber: Int) {
        // Ensure we have the latest user data before loading favorites
        if (userId == appUser.mid) {
            refreshFromAppUser()
        }
        
        // Use the most up-to-date user data (appUser) instead of potentially stale user.value
        val currentUser = if (userId == appUser.mid) appUser else user.value
        
        val tweetsWithNulls = getUserTweetsByType(
            currentUser,
            UserContentType.FAVORITES,
            pageNumber,
            TW_CONST.PAGE_SIZE
        )

        // Filter out null elements and get valid tweets
        val validTweets = tweetsWithNulls.filterNotNull()

        Timber.tag("getFavorites")
            .d("Received ${tweetsWithNulls.size} tweets (${validTweets.size} valid) for user: ${user.value.mid}, page: $pageNumber")

        if (pageNumber == 0) {
            // For refresh (page 0), replace the list and sort it
            _favorites.value = validTweets.sortedByDescending { it.timestamp }
            // Don't override the count - it should come from server data, not local list size
        } else {
            // For load more (page > 0), append to the list
            _favorites.update { currentFavorites ->
                val newTweetsMap = validTweets.associateBy { it.mid }
                val updatedFavorites = currentFavorites.map { favorite ->
                    newTweetsMap[favorite.mid] ?: favorite
                }
                val finalFavorites = (updatedFavorites + validTweets)
                    .distinctBy { it.mid }
                    .sortedByDescending { it.timestamp }

                // Don't override the count - it should come from server data, not local list size
                finalFavorites
            }
        }
    }

    /**
     * Update in-memory favorite data for display.
     * This method now uses server data instead of optimistic updates.
     *
     * @param tweet The tweet to favorite or unfavorite
     * @param isFavorite True to add favorite, false to remove favorite
     */
    fun updateFavorite(tweet: Tweet, isFavorite: Boolean) {
        // Get current user state for logging
        val currentUser = user.value

        // Log the operation details for debugging
        Timber.tag("UserViewModel")
            .d("updateFavorite: Current favorites count: ${currentUser.favoritesCount}")
        Timber.tag("UserViewModel").d("updateFavorite: Current user ID: ${currentUser.mid}")
        Timber.tag("UserViewModel").d("updateFavorite: Tweet author ID: ${tweet.authorId}")
        Timber.tag("UserViewModel").d("updateFavorite: Is favorite action: $isFavorite")

        // Use the server's updated user data (appUser) instead of calculating locally
        val serverFavoritesCount = appUser.favoritesCount
        
        // Update the user state with server data
        _user.value = appUser

        // Update the public favorites count for UI with server count
        _favoritesCount.value = serverFavoritesCount

        // Update other count variables to match appUser (but don't override favorites count)
        _bookmarksCount.value = appUser.bookmarksCount
        _followersCount.value = appUser.followersCount
        _followingsCount.value = appUser.followingCount
        _tweetCount.value = appUser.tweetCount

        // Update the favorites list based on server response
        if (isFavorite) {
            // Add tweet to the beginning of favorites list if not already present
            _favorites.update { bs -> 
                if (bs.any { it.mid == tweet.mid }) bs else listOf(tweet) + bs 
            }
            Timber.tag("UserViewModel")
                .d("Server favorite: User favorited tweet ${tweet.mid}, server favorites count: $serverFavoritesCount")
        } else {
            // Remove tweet from favorites list
            _favorites.update { bs -> bs.filterNot { it.mid == tweet.mid } }
            Timber.tag("UserViewModel")
                .d("Server favorite: User unfavorited tweet ${tweet.mid}, server favorites count: $serverFavoritesCount")
        }

        // Log the final updated favorite count
        Timber.tag("UserViewModel")
            .d("Server favorite: Updated user favoritesCount: ${appUser.favoritesCount}")

        // Persist changes to cache immediately in background
        viewModelScope.launch(IO) {
            Timber.tag("UserViewModel")
                .d("updateFavorite: Saving user to cache with favoritesCount: ${appUser.favoritesCount}")
            TweetCacheManager.saveUser(appUser)
            Timber.tag("UserViewModel").d("updateFavorite: User saved to cache successfully")
            
            // Notify other ViewModels that user data has been updated
            TweetNotificationCenter.post(TweetEvent.UserDataUpdated(appUser))
        }
    }

    @AssistedFactory
    interface UserViewModelFactory {
        fun create(userId: MimeiId): UserViewModel
    }

    init {
        // Start listening to notifications immediately when ViewModel is created
        startListeningToNotifications()
        
        if (userId != TW_CONST.GUEST_ID) {
            viewModelScope.launch(IO) {
                val loadedUser =
                    fetchUser(userId, maxRetries = 2) ?: User(mid = TW_CONST.GUEST_ID, baseUrl = appUser.baseUrl)
                _user.value = loadedUser

                // Initialize count variables from user data
                _bookmarksCount.value = loadedUser.bookmarksCount
                _favoritesCount.value = loadedUser.favoritesCount
                _followersCount.value = loadedUser.followersCount
                _followingsCount.value = loadedUser.followingCount
                _tweetCount.value = loadedUser.tweetCount

                if (userId == appUser.mid) {
                    // By default NOT to load fans and followings list of an user object.
                    // Do it only when opening the user's profile page.
                    // Only get current user's fans list when opening the app.
                    refreshFollowingsAndFans()
                }
            }
        } else {
            _user.value = appUser

            // Initialize count variables from appUser data
            _bookmarksCount.value = appUser.bookmarksCount
            _favoritesCount.value = appUser.favoritesCount
            _followersCount.value = appUser.followersCount
            _followingsCount.value = appUser.followingCount
            _tweetCount.value = appUser.tweetCount
        }
    }

    suspend fun getUser() {
        fetchUser(userId, maxRetries = 2)?.let {
            _user.value = it
        }
    }

    /**
     * Refresh user data from appUser if this is the current user.
     * This ensures that when appUser is updated (e.g., after favorite toggle),
     * this ViewModel gets the updated data.
     */
    fun refreshFromAppUser() {
        if (userId == appUser.mid) {
            Timber.tag("UserViewModel").d("Refreshing user data from appUser for current user: ${appUser.mid}")
            _user.value = appUser
            
            // Update all count variables to match appUser
            _bookmarksCount.value = appUser.bookmarksCount
            _favoritesCount.value = appUser.favoritesCount
            _followersCount.value = appUser.followersCount
            _followingsCount.value = appUser.followingCount
            _tweetCount.value = appUser.tweetCount
        }
    }

    private suspend fun getTweets(pageNumber: Int): List<Tweet?> {
        return try {
            // When pageNumber is 0, load pinned tweets first and wait for completion
            if (pageNumber == 0) {
                loadPinnedTweets()
                // Ensure pinned tweets are fully loaded before proceeding
                Timber.tag("getTweets").d("Pinned tweets loaded: ${pinnedTweets.value.size} tweets")
            }

            // Always load cached tweets first (especially for appUser, whose cache is shared with mainfeed)
            Timber.tag("getTweets").d("Loading cached tweets for user: ${user.value.mid}")
            val cachedTweets = loadCachedTweetsByAuthor(user.value.mid, pageNumber * TW_CONST.PAGE_SIZE, TW_CONST.PAGE_SIZE)
            val cachedTweetsWithNulls = cachedTweets.map { it as Tweet? }
            
            // Update _tweets with cached tweets
            _tweets.update { currentTweets ->
                val currentTweetIds = currentTweets.map { it.mid }.toSet()
                val newCachedTweets = cachedTweets.filter { it.mid !in currentTweetIds }
                
                if (newCachedTweets.isNotEmpty()) {
                    val mergedTweets = (currentTweets + newCachedTweets)
                        .distinctBy { tweet: Tweet -> tweet.mid }
                        .sortedByDescending { tweet: Tweet -> tweet.timestamp }
                    mergedTweets
                } else {
                    currentTweets
                }
            }
            
            Timber.tag("getTweets").d("Loaded ${cachedTweets.size} cached tweets for user: ${user.value.mid}")
            
            // If network is available, fetch more tweets from server
            if (user.value.baseUrl != null && appUser.baseUrl != null) {
                Timber.tag("getTweets").d("Network available, fetching additional tweets from server")
                // Fetch tweets of the author and update _tweets
                val newTweetsWithNulls = HproseInstance.getTweetsByUser(user.value, pageNumber)

                // Filter out null elements and get valid tweets
                val newTweets = newTweetsWithNulls.filterNotNull()

                Timber.tag("getTweets")
                    .d("Received ${newTweetsWithNulls.size} tweets (${newTweets.size} valid) for user: ${user.value.mid}, page: $pageNumber")

                // Always merge new tweets with existing ones, never replace (like TweetFeedViewModel)
                _tweets.update { currentTweets ->
                    val filteredTweets = newTweets.filterNot { tweet: Tweet ->
                        tweet.isPrivate && tweet.authorId != appUser.mid
                    }

                    // Get current pinned tweet IDs after ensuring they're loaded
                    val pinnedTweetIds = pinnedTweets.value.map { it.mid }.toSet()
                    val tweetsWithoutPinned = filteredTweets.filterNot { tweet: Tweet ->
                        pinnedTweetIds.contains(tweet.mid)
                    }

                    val currentTweetIds = currentTweets.map { it.mid }.toSet()
                    val trulyNewTweets = tweetsWithoutPinned.filter { it.mid !in currentTweetIds }

                    if (trulyNewTweets.isNotEmpty()) {
                        val mergedTweets = (currentTweets + trulyNewTweets)
                            .distinctBy { tweet: Tweet -> tweet.mid }
                            .sortedByDescending { tweet: Tweet -> tweet.timestamp }
                        // Don't override tweet count - it should come from server data, not local list size
                        mergedTweets
                    } else {
                        currentTweets
                    }
                }

                return newTweetsWithNulls
            } else {
                // Network unavailable, return cached tweets only
                Timber.tag("getTweets").w("Network unavailable, returning cached tweets only")
                return cachedTweetsWithNulls
            }
        } catch (e: Exception) {
            Timber.tag("getTweets")
                .e(e, "Error fetching tweets for user: ${user.value.mid}, page: $pageNumber")
            emptyList()
        }
    }

    private suspend fun loadPinnedTweets() {
        try {
            Timber.tag("loadPinnedTweets").d("Loading pinned tweets for user: ${user.value.mid}")

            // Get pinned tweets from getPinnedList which returns List<Map<String, Any>>
            val pinnedTweetsResponse = HproseInstance.getPinnedTweetsWithTimestamp(user.value)

            Timber.tag("loadPinnedTweets")
                .d("Retrieved ${pinnedTweetsResponse?.size ?: 0} pinned tweets")
            
            Timber.tag("loadPinnedTweets")
                .d("Pinned tweets response: $pinnedTweetsResponse")

            if (!pinnedTweetsResponse.isNullOrEmpty()) {
                // Parse the response: List<{tweet: Tweet, timestamp: Long}>
                val pinnedTweetsWithPinnedTimestamp = pinnedTweetsResponse.mapNotNull { map ->
                    try {
                        val tweetRaw = map["tweet"]
                        val timestampRaw = map["timestamp"]

                        // Convert tweet data
                        val tweet = when (tweetRaw) {
                            is Tweet -> tweetRaw
                            is Map<*, *> -> {
                                try {
                                    Tweet.from(tweetRaw as Map<String, Any>)
                                } catch (e: Exception) {
                                    Timber.tag("loadPinnedTweets")
                                        .e(e, "Error converting Map to Tweet")
                                    null
                                }
                            }

                            else -> {
                                Timber.tag("loadPinnedTweets")
                                    .w("Unknown tweet type: ${tweetRaw?.javaClass}")
                                null
                            }
                        }

                        // Convert timestamp
                        val pinnedTimestamp = when (timestampRaw) {
                            is Long -> timestampRaw
                            is Int -> timestampRaw.toLong()
                            is String -> timestampRaw.toLongOrNull()
                            is Double -> timestampRaw.toLong()
                            else -> {
                                Timber.tag("loadPinnedTweets")
                                    .w("Unknown timestamp type: ${timestampRaw?.javaClass}")
                                null
                            }
                        }

                        if (tweet != null && pinnedTimestamp != null) {
                            // Ensure the author field is set correctly for pinned tweets
                            val tweetWithAuthor = if (tweet.author == null) {
                                // Check cache first before fetching from server
                                val cachedAuthor = TweetCacheManager.getCachedUser(tweet.authorId)
                                tweet.copy(author = cachedAuthor ?: fetchUser(tweet.authorId))
                            } else {
                                tweet
                            }
                            // Keep the original tweet, but associate it with its pinned timestamp for sorting
                            Pair(tweetWithAuthor, pinnedTimestamp)
                        } else {
                            Timber.tag("loadPinnedTweets")
                                .w("Invalid pinned tweet data: tweet=$tweet, timestamp=$pinnedTimestamp")
                            null
                        }
                    } catch (e: Exception) {
                        Timber.tag("loadPinnedTweets").e(e, "Error parsing pinned tweet data: $map")
                        null
                    }
                }

                // Sort by the pinned timestamp (most recent first) but keep original tweet timestamps
                val sortedPinnedTweets = pinnedTweetsWithPinnedTimestamp
                    .distinctBy { (tweet, _) -> tweet.mid }
                    .sortedByDescending { (_, pinnedTimestamp) -> pinnedTimestamp }
                    .map { (tweet, _) -> tweet } // Extract just the tweets, keeping their original timestamps

                // Load original tweets for quoted tweets and filter out those that fail to load
                val validPinnedTweets = mutableListOf<Tweet>()

                for (tweet in sortedPinnedTweets) {
                    if (tweet.originalTweetId != null && tweet.originalAuthorId != null) {
                        // This is a quoted tweet, try to load the original tweet
                        try {
                            Timber.tag("loadPinnedTweets")
                                .d("Loading original tweet for pinned quoted tweet: ${tweet.originalTweetId}")
                            val originalTweet = HproseInstance.fetchTweet(
                                tweet.originalTweetId!!,
                                tweet.originalAuthorId!!
                            )

                            if (originalTweet != null) {
                                Timber.tag("loadPinnedTweets")
                                    .d("Successfully loaded original tweet for pinned tweet: ${tweet.mid}")
                                validPinnedTweets.add(tweet)
                            } else {
                                Timber.tag("loadPinnedTweets")
                                    .w("Failed to load original tweet for pinned tweet: ${tweet.mid}, removing from list")
                                // Don't add to validPinnedTweets - this removes it from the list
                            }
                        } catch (e: Exception) {
                            Timber.tag("loadPinnedTweets").e(
                                e,
                                "Error loading original tweet for pinned tweet: ${tweet.mid}, removing from list"
                            )
                            // Don't add to validPinnedTweets - this removes it from the list
                        }
                    } else {
                        // This is not a quoted tweet, add it directly
                        validPinnedTweets.add(tweet)
                    }
                }

                _pinnedTweets.value = validPinnedTweets

                Timber.tag("loadPinnedTweets")
                    .d("Updated pinned tweets list with ${_pinnedTweets.value.size} valid tweets: ${validPinnedTweets.map { it.mid }}")
                
                Timber.tag("loadPinnedTweets")
                    .d("Pinned tweets state after update: ${_pinnedTweets.value}")
            } else {
                // Clear pinned tweets if none found
                _pinnedTweets.value = emptyList()
                Timber.tag("loadPinnedTweets").d("No pinned tweets found, cleared list")
            }

        } catch (e: Exception) {
            Timber.tag("loadPinnedTweets")
                .e(e, "Error loading pinned tweets for user: ${user.value.mid}")
            // Don't clear pinned tweets on error, keep existing state
            Timber.tag("loadPinnedTweets")
                .d("Exception occurred, keeping existing pinned tweets: ${_pinnedTweets.value.size}")
        }
    }

    /**
     * User can pin or unpin any tweet, including quoted or retweet by this user.
     * */
    suspend fun pinToTop(tweet: Tweet) {
        HproseInstance.togglePinnedTweet(tweet.mid)?.let { isPinned ->
            if (isPinned) {
                // Tweet is now pinned: remove from tweets and add to pinned tweets
                _tweets.update { currentTweets ->
                    currentTweets.filterNot { it.mid == tweet.mid }
                }

                // Add to pinned tweets with current timestamp as pinned timestamp
                val currentTime = System.currentTimeMillis()
                val tweetWithPinnedTimestamp = tweet.copy(timestamp = currentTime)
                _pinnedTweets.update { currentPinnedTweets ->
                    (listOf(tweetWithPinnedTimestamp) + currentPinnedTweets)
                        .distinctBy { it.mid }
                }

                Timber.tag("pinToTop").d("Tweet ${tweet.mid} pinned successfully at $currentTime")
            } else {
                // Tweet is now unpinned: remove from pinned tweets and add back to tweets
                _pinnedTweets.update { currentPinnedTweets ->
                    currentPinnedTweets.filterNot { it.mid == tweet.mid }
                }

                // Add back to regular tweets with original timestamp
                _tweets.update { currentTweets ->
                    (listOf(tweet) + currentTweets)
                        .distinctBy { it.mid }
                        .sortedByDescending { it.timestamp } // Sort by original tweet timestamp
                }

                Timber.tag("pinToTop").d("Tweet ${tweet.mid} unpinned successfully")
            }

            // Don't override tweet count - it should come from server data, not local list size
        }
    }


    suspend fun login(context: Context, callback: () -> Unit, maxRetries: Int = 3) {
        isLoading.value = true
        loginError.value = "" // Clear previous errors
        
        if (username.value?.isNotEmpty() == true
            && password.value.isNotEmpty()
        ) {
            val ret = HproseInstance.login(username.value!!, password.value, context, maxRetries)

            if (ret.second != null) {
                // something wrong
                loginError.value = ret.second.toString()
                isLoading.value = false
            } else {
                appUser = ret.first as User
                preferenceHelper.setUserId(appUser.mid)
                _user.value = appUser
                username.value = appUser.username
                name.value = appUser.name ?: ""
                profile.value = appUser.profile ?: ""
                hostId.value = appUser.hostIds?.firstOrNull() ?: ""
                cloudDrivePort.value = if (appUser.cloudDrivePort == 0) "" else appUser.cloudDrivePort.toString()
                refreshFollowingsAndFans()
                
                // Notify TweetFeedViewModel to refresh feed after successful login
                TweetNotificationCenter.postAsync(
                    TweetEvent.FeedResetRequested(FeedResetReason.LOGIN)
                )

                // Show success Toast message on main thread
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.login_success),
                        Toast.LENGTH_SHORT
                    ).show()
                }

                // Keep spinner visible during navigation
                callback()
                // Spinner will be hidden when screen navigates away
            }
        } else {
            loginError.value = context.getString(R.string.username_required)
            isLoading.value = false
        }
    }

    suspend fun logout(popBack: () -> Unit) {
        preferenceHelper.setUserId(null)
        val ip = HproseInstance.getProviderIP(HproseInstance.getAlphaIds().first())
        appUser = User.getInstance(TW_CONST.GUEST_ID)
        appUser.baseUrl = "http://$ip"
        appUser.followingList = HproseInstance.getAlphaIds()

        /**
         * Do NOT clear the UserViewModel object. It will be reused by other users.
         * Don't clear tweet cache on logout - cache persists per user and is cleared periodically or manually
         * */
        _tweets.value = emptyList()
        _pinnedTweets.value = emptyList()
        // Note: Tweet cache is NOT cleared on logout to match iOS behavior
        // Cache persists per user and is cleared periodically or manually by user
        
        // Notify TweetFeedViewModel to reset for guest timeline
        TweetNotificationCenter.postAsync(
            TweetEvent.FeedResetRequested(FeedResetReason.LOGOUT)
        )
        
        popBack()
    }

    /**
     * Handle both register and update of user profile. Username, password are required.
     * Do NOT update appUser, wait for the new user to login.
     * */
    suspend fun register(context: Context, popBack: () -> Unit) {
        // Prevent repeated submission
        if (isLoading.value) return

        isLoading.value = true

        // Clear previous validation errors
        usernameError.value = ""
        passwordError.value = ""
        confirmPasswordError.value = ""
        hostIdError.value = ""
        cloudDrivePortError.value = ""

        // Validate cloud drive port if provided
        if (cloudDrivePort.value.isNotEmpty()) {
            val port = cloudDrivePort.value.toIntOrNull()
            if (port == null || port < 8000 || port > 65535) {
                cloudDrivePortError.value = "Port must be between 8000 and 65535"
                isLoading.value = false
                return
            }
        }

        if (this.hostId.value.isNotEmpty() && appUser.mid == TW_CONST.GUEST_ID) {
            /**
             * Register a new user. Check username and password first.
             * */
            if (username.value.isNullOrEmpty()) {
                usernameError.value = context.getString(R.string.username_required)
                isLoading.value = false
                return
            }
            if (password.value.isEmpty()) {
                passwordError.value = context.getString(R.string.password_required)
                isLoading.value = false
                return
            }
            // Find IP of the desired node. User can change its value to appoint to
            // a different host node later.
            HproseInstance.getHostIP(hostId.value)?.let { ip ->
                appUser = appUser.copy(baseUrl = "http://$ip")
                _user.value = user.value.copy(baseUrl = "http://$ip")
                User.updateUserInstance(appUser, true)
            } ?: run {
                hostIdError.value = context.getString(R.string.node_not_found)
                isLoading.value = false
                return
            }
        }

        // Call the new separate functions based on user type
        val (success, errorMessage) = if (appUser.isGuest()) {
            HproseInstance.registerUser(
                username = username.value!!.lowercase().trim(),
                password = password.value,
                alias = name.value.trim(),
                profile = profile.value.trim(),
                hostId = hostId.value.trim().takeIf { it.isNotEmpty() },
                cloudDrivePort = if (cloudDrivePort.value.isBlank()) 0 else (cloudDrivePort.value.toIntOrNull() ?: 0),
                domainToShare = domainToShare.value.trim().takeIf { it.isNotEmpty() }
            )
        } else {
            HproseInstance.updateUserCore(
                password = password.value,
                alias = name.value.trim(),
                profile = profile.value.trim(),
                hostId = hostId.value.trim().takeIf { it.isNotEmpty() },
                cloudDrivePort = if (cloudDrivePort.value.isBlank()) 0 else (cloudDrivePort.value.toIntOrNull() ?: 0),
                domainToShare = domainToShare.value.trim().takeIf { it.isNotEmpty() }
            )
        }

        if (success) {
            if (appUser.isGuest()) {
                // new user registered, wait for its login
                popBack()
            } else {
                // Update existing user profile - the updateUserCore function handles all UI updates internally
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.profile_update_ok),
                        Toast.LENGTH_SHORT
                    ).show()
                    popBack()
                }
            }
        } else {
            withContext(Dispatchers.Main) {
                val userFriendlyMessage = us.fireshare.tweet.utils.ErrorMessageHelper.getUserFriendlyMessage(errorMessage, context)
                Toast.makeText(
                    context,
                    userFriendlyMessage,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        isLoading.value = false
    }

    /**
     * When user input @, show suggestions of fans and followings' username.
     * */
    suspend fun getSuggestions(query: String): List<String> {
        val suggestions = mutableListOf<String>()

        // Check fans
        followers.value.forEach { fanId ->
            fetchUser(fanId)?.let { fan ->
                if (fan.username?.startsWith(query, ignoreCase = true) == true) {
                    suggestions.add(fan.username!!)
                }
            }
        }

        // Check followings
        followings.value.forEach { followingId ->
            fetchUser(followingId)?.let { following ->
                if (following.username?.startsWith(query, ignoreCase = true) == true) {
                    suggestions.add(following.username!!)
                }
            }
        }
        return suggestions.distinct()
    }

    fun onUsernameChange(value: String) {
        username.value = value.trim()
        isLoading.value = false
        loginError.value = ""
    }

    fun onNameChange(value: String) {
        name.value = value
        isLoading.value = false
        loginError.value = ""
    }

    fun onProfileChange(value: String) {
        profile.value = value
        isLoading.value = false
        loginError.value = ""
    }

    fun onNodeIdChange(value: String) {
        hostId.value = value
        isLoading.value = false
        loginError.value = ""
    }
    
    fun onCloudDrivePortChange(value: String) {
        // Only allow digits and limit to 5 digits
        if (value.isEmpty() || (value.all { it.isDigit() } && value.length <= 5)) {
            cloudDrivePort.value = value
            isLoading.value = false
            loginError.value = ""

            // Validate port range if not empty
            if (value.isNotEmpty()) {
                val port = value.toIntOrNull()
                if (port != null && (port !in 8000..65535)) {
                    cloudDrivePortError.value = "Port must be between 8000 and 65535"
                } else {
                    cloudDrivePortError.value = ""
                }
            } else {
                cloudDrivePortError.value = ""
            }
        }
    }

    fun onDomainToShareChange(value: String) {
        domainToShare.value = value.trim()
        isLoading.value = false
        loginError.value = ""
    }

    fun onPasswordChange(pwd: String) {
        password.value = pwd.trim()
        isLoading.value = false
        loginError.value = ""
    }

    fun onPasswordVisibilityChange() {
        isPasswordVisible.value = !isPasswordVisible.value
    }

    /**
     * Listen to tweet notifications and update user's tweet lists accordingly
     */
    fun startListeningToNotifications() {
        viewModelScope.launch {
            TweetNotificationCenter.events.collect { event ->
                when (event) {
                    is TweetEvent.TweetUploaded -> {
                        // Only add if it's the current user's tweet
                        if (event.tweet.authorId == user.value.mid) {
                            // Ensure the author is set correctly
                            val tweetWithAuthor = event.tweet.copy(author = user.value)

                            // Batch update all related state to reduce recompositions
                            val updatedTweets = (listOf(tweetWithAuthor) + tweets.value)
                                .distinctBy { it.mid }
                                .sortedByDescending { it.timestamp }

                            _tweets.value = updatedTweets
                            
                            // Cache the new tweet by authorId
                            TweetCacheManager.saveTweet(tweetWithAuthor, tweetWithAuthor.authorId)
                            
                            // Don't update tweet count here - let UserDataUpdated event handle it
                            // to avoid race conditions with the server refresh
                        }
                    }

                    is TweetEvent.TweetDeleted -> {
                        // Only process if it's the current user's tweet
                        if (event.authorId == user.value.mid) {
                            // Batch update all related state to reduce recompositions
                            val updatedTweets = tweets.value.filterNot { it.mid == event.tweetId }
                            val updatedPinnedTweets =
                                pinnedTweets.value.filterNot { it.mid == event.tweetId }
                            val updatedFavorites = favorites.value.filterNot { it.mid == event.tweetId }
                            val updatedBookmarks = bookmarks.value.filterNot { it.mid == event.tweetId }

                            _tweets.value = updatedTweets
                            _pinnedTweets.value = updatedPinnedTweets
                            _favorites.value = updatedFavorites
                            _bookmarks.value = updatedBookmarks

                            // Don't update tweet count here - let UserDataUpdated event handle it
                            // to avoid race conditions with the server refresh
                        }
                    }
                    
                    is TweetEvent.TweetRestored -> {
                        // Restore tweet if it belongs to current user
                        if (event.tweet.authorId == user.value.mid) {
                            Timber.tag("UserViewModel").d("Restoring tweet ${event.tweet.mid} after failed deletion")
                            
                            // Add back to tweets list if not already present
                            if (!tweets.value.any { it.mid == event.tweet.mid }) {
                                _tweets.value = (listOf(event.tweet) + tweets.value).distinctBy { it.mid }.sortedByDescending { it.timestamp }
                            }
                            
                            // Note: Don't restore to pinned/favorites/bookmarks automatically
                            // as we don't know which lists it was in before deletion attempt
                        }
                    }
                    
                    is TweetEvent.TweetRetweeted -> {
                        // Add the retweet to the user's tweet list if it's the current user's retweet
                        if (event.retweet.authorId == user.value.mid) {
                            // Add the retweet to the beginning of the tweets list
                            _tweets.update { currentTweets ->
                                (listOf(event.retweet) + currentTweets)
                                    .distinctBy { it.mid }
                                    .sortedByDescending { it.timestamp }
                            }
                            
                            // Don't update tweet count here - let UserDataUpdated event handle it
                            // to avoid race conditions with the server refresh
                        }
                    }

                    is TweetEvent.UserDataUpdated -> {
                        // Update user data if this is the current user
                        if (event.user.mid == userId) {
                            val updatedUser = event.user.copy()
                            _user.value = updatedUser
                            _bookmarksCount.value = updatedUser.bookmarksCount
                            _favoritesCount.value = updatedUser.favoritesCount
                            _followersCount.value = updatedUser.followersCount
                            _followingsCount.value = updatedUser.followingCount
                            _tweetCount.value = updatedUser.tweetCount
                        }
                    }

                    else -> {
                        // Handle other events if needed
                    }
                }
            }
        }
    }

    /**
     * Remove a tweet from all user lists (tweets, topTweets, favorites, bookmarks)
     * This is used for optimistic updates when tweets are deleted.
     */
    fun removeTweetFromAllLists(tweetId: MimeiId) {
        // Batch update all related state to reduce recompositions
        val updatedTweets = tweets.value.filterNot { it.mid == tweetId }
        val updatedPinnedTweets = pinnedTweets.value.filterNot { it.mid == tweetId }
        val updatedFavorites = favorites.value.filterNot { it.mid == tweetId }
        val updatedBookmarks = bookmarks.value.filterNot { it.mid == tweetId }

        _tweets.value = updatedTweets
        _pinnedTweets.value = updatedPinnedTweets
        _favorites.value = updatedFavorites
        _bookmarks.value = updatedBookmarks
    }
    
    fun restoreTweetToLists(tweet: Tweet, inTweets: Boolean, inPinned: Boolean, inFavorites: Boolean, inBookmarks: Boolean) {
        if (inTweets && !tweets.value.any { it.mid == tweet.mid }) {
            _tweets.value = (listOf(tweet) + tweets.value).distinctBy { it.mid }.sortedByDescending { it.timestamp }
        }
        if (inPinned && !pinnedTweets.value.any { it.mid == tweet.mid }) {
            _pinnedTweets.value = (listOf(tweet) + pinnedTweets.value).distinctBy { it.mid }.sortedByDescending { it.timestamp }
        }
        if (inFavorites && !favorites.value.any { it.mid == tweet.mid }) {
            _favorites.value = (listOf(tweet) + favorites.value).distinctBy { it.mid }.sortedByDescending { it.timestamp }
        }
        if (inBookmarks && !bookmarks.value.any { it.mid == tweet.mid }) {
            _bookmarks.value = (listOf(tweet) + bookmarks.value).distinctBy { it.mid }.sortedByDescending { it.timestamp }
        }
    }
}
