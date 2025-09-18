package us.fireshare.tweet.viewmodel

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
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
import us.fireshare.tweet.HproseInstance.dao
import us.fireshare.tweet.HproseInstance.getUser
import us.fireshare.tweet.HproseInstance.getUserTweetsByType
import us.fireshare.tweet.HproseInstance.preferenceHelper
import us.fireshare.tweet.R
import us.fireshare.tweet.datamodel.MimeiId
import us.fireshare.tweet.datamodel.TW_CONST
import us.fireshare.tweet.datamodel.Tweet
import us.fireshare.tweet.datamodel.TweetCacheManager
import us.fireshare.tweet.datamodel.TweetEvent
import us.fireshare.tweet.datamodel.TweetNotificationCenter
import us.fireshare.tweet.datamodel.User
import us.fireshare.tweet.datamodel.UserContentType

@HiltViewModel(assistedFactory = UserViewModel.UserViewModelFactory::class)
class UserViewModel @AssistedInject constructor(
    @Assisted val userId: MimeiId,
    private val tweetFeedViewModel: TweetFeedViewModel
): ViewModel() {
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
    var name = mutableStateOf(appUser.name)
    var profile = mutableStateOf(appUser.profile)
    var hostId = mutableStateOf("")
    var isPasswordVisible = mutableStateOf(false)
    var loginError = mutableStateOf("")

    var initState = MutableStateFlow(true)      // initial load state

    /**
     * Initial load of tweets of an user. Execute only once.
     * */
    suspend fun initLoad() {
        try {
            Timber.tag("initLoad").d("Starting initial load for user: ${user.value.mid}")

            // Load first page (page 0) which includes pinned tweets
            getTweets(0)

            // Load additional pages if needed to get at least 5 viewable tweets
            var pageNumber = 1
            while (tweets.value.size < 5 && pageNumber < 10) {
                getTweets(pageNumber)
                pageNumber++
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
     * Includes retry logic with exponential backoff for network-related failures.
     */
    suspend fun refreshUserData(maxRetries: Int = 3) {
        try {
            // If this is the current user's profile, update from appUser
            if (userId == appUser.mid) {
                _user.value = appUser
                
                // Also update the count variables to match the updated appUser
                _bookmarksCount.value = appUser.bookmarksCount
                _favoritesCount.value = appUser.favoritesCount
                _followersCount.value = appUser.followersCount
                _followingsCount.value = appUser.followingCount
                _tweetCount.value = appUser.tweetCount
                
                Timber.tag("refreshUserData").d("Refreshed user data for current user: ${appUser.name}")
            } else {
                // For other users, fetch fresh data from the server with retry logic
                refreshUserWithRetry(maxRetries)
                
                // Update count variables from the refreshed user data
                _bookmarksCount.value = user.value.bookmarksCount
                _favoritesCount.value = user.value.favoritesCount
                _followersCount.value = user.value.followersCount
                _followingsCount.value = user.value.followingCount
                _tweetCount.value = user.value.tweetCount
                
                Timber.tag("refreshUserData").d("Refreshed user data for user: ${user.value.name}")
            }
            
            // Reset the profile updated flag after refreshing
    
        } catch (e: Exception) {
            Timber.tag("refreshUserData").e(e, "Error refreshing user data for user: $userId")
        }
    }

    /**
     * Refresh user data with retry logic
     */
    private suspend fun refreshUserWithRetry(maxRetries: Int = 3) {
        repeat(maxRetries) { attempt ->
            try {
                val refreshedUser = getUser(userId, maxRetries = 1) // Single attempt per retry
                if (refreshedUser != null && !refreshedUser.isGuest()) {
                    _user.value = refreshedUser
                    return // Success, exit retry loop
                } else {
                    Timber.tag("refreshUserWithRetry").w("Failed to fetch valid user data for $userId (attempt ${attempt + 1})")
                }
            } catch (e: Exception) {
                Timber.tag("refreshUserWithRetry").e(e, "Error refreshing user $userId (attempt ${attempt + 1})")
                
                // Check if it's a network-related error that should be retried
                val isNetworkError = e.message?.contains("network", ignoreCase = true) == true ||
                        e.message?.contains("timeout", ignoreCase = true) == true ||
                        e.message?.contains("connection", ignoreCase = true) == true ||
                        e.message?.contains("unreachable", ignoreCase = true) == true
                
                if (!isNetworkError) {
                    // Don't retry for non-network errors
                    return
                }
            }
            
            // If this isn't the last attempt, wait before retrying
            if (attempt < maxRetries - 1) {
                val delayMs = minOf(3000L, 1000L * (1 shl attempt)) // Exponential backoff: 1s, 2s
                Timber.tag("refreshUserWithRetry").d("Retrying user refresh in ${delayMs}ms (attempt ${attempt + 2}/$maxRetries)")
                kotlinx.coroutines.delay(delayMs)
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
     * Whether the tweet is pinned to top list.
     * */
    fun hasPinned(tweet: Tweet): Boolean {
        return pinnedTweets.value.any { it.mid == tweet.mid }
    }

    suspend fun getHostId() {
        hostId.value = if (user.value.hostIds.isNullOrEmpty()) {
            HproseInstance.getHostId() ?: ""
        } else user.value.hostIds!!.first()
    }

    suspend fun updateAvatar(context: Context, uri: Uri) {
        isLoading.value = true
        try {
            // Store the old avatar ID to clear cache later
            val oldAvatarId = appUser.avatar
            
            // For now, user avatar can only be image.
            HproseInstance.uploadToIPFS(
                context,
                uri,
                referenceId = appUser.mid
            )?.let {
                HproseInstance.setUserAvatar(appUser, it.mid)?.let { avatar ->  // Update appUser's avatar
                    // Clear the old avatar from cache if it exists
                    oldAvatarId?.let { oldId ->
                        us.fireshare.tweet.widget.ImageCacheManager.clearCachedImage(context, oldId)
                    }
                    
                    // Update the user objects with new avatar
                    appUser = appUser.copy(avatar = avatar)
                    _user.value = user.value.copy(avatar = avatar)
                    

                    
                    // Save the updated user to cache
                    TweetCacheManager.saveUser(appUser)
                }
            }
        } finally {
            isLoading.value = false
        }
    }



    /**
     * @param subjectUserId to add/remove it to/from the following list
     * @return Boolean? - true if now following, false if now unfollowing, null if operation failed
     * */
    suspend fun toggleFollowingWithResult(
        subjectUserId: MimeiId,
        userId: MimeiId = appUser.mid,
        updateTweetFeed: (Boolean) -> Unit
    ): Boolean? {
        // toggle the Following status on the given UserId
        return HproseInstance.toggleFollowing(subjectUserId, userId)?.let { isFollowing ->
            _followings.update { list ->
                if (isFollowing)
                    (listOf(subjectUserId) + list).toSet().toList()
                else
                    list.filterNot { it == subjectUserId }
            }
            // Update the count manually - increment/decrement based on the action
            val newCount = if (isFollowing) {
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
            
            withContext(IO) {
                // Save the updated user object to cache
                TweetCacheManager.saveUser(_user.value)
            }
            // callback to update tweet feed. Load or remove tweets of the others.
            updateTweetFeed(isFollowing)
            isFollowing
        }
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
                    val filteredSlice = slice
                    Timber.tag("fetchFollowers")
                        .d("Returning slice for page $pageNumber: ${filteredSlice.size} user IDs")
                    filteredSlice
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
        val tweetsWithNulls = getUserTweetsByType(
            user.value,
            UserContentType.BOOKMARKS,
            pageNumber,
            TW_CONST.PAGE_SIZE
        )

        // Filter out null elements and get valid tweets
        val validTweets = tweetsWithNulls.filterNotNull()

        Timber.tag("getBookmarks")
            .d("Received ${tweetsWithNulls.size} tweets (${validTweets.size} valid) for user: ${user.value.mid}, page: $pageNumber")

        if (pageNumber == 0) {
            // For refresh (page 0), replace the list
            _bookmarks.value = validTweets
            _bookmarksCount.value = validTweets.size
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

                _bookmarksCount.value = finalBookmarks.size
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
        }
    }

    /**
     * Get favorite Tweets of the user.
     * */
    suspend fun getFavorites(pageNumber: Int) {
        val tweetsWithNulls = getUserTweetsByType(
            user.value,
            UserContentType.FAVORITES,
            pageNumber,
            TW_CONST.PAGE_SIZE
        )

        // Filter out null elements and get valid tweets
        val validTweets = tweetsWithNulls.filterNotNull()

        Timber.tag("getFavorites")
            .d("Received ${tweetsWithNulls.size} tweets (${validTweets.size} valid) for user: ${user.value.mid}, page: $pageNumber")

        if (pageNumber == 0) {
            // For refresh (page 0), replace the list
            _favorites.value = validTweets
            _favoritesCount.value = validTweets.size
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

                _favoritesCount.value = finalFavorites.size
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
        }
    }

    @AssistedFactory
    interface UserViewModelFactory {
        fun create(userId: MimeiId): UserViewModel
    }

    init {
        if (userId != TW_CONST.GUEST_ID) {
            viewModelScope.launch(Dispatchers.IO) {
                val loadedUser =
                    getUser(userId, maxRetries = 3) ?: User(mid = TW_CONST.GUEST_ID, baseUrl = appUser.baseUrl)
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

    suspend fun refreshUser() {
        getUser(userId, maxRetries = 3)?.let {
            _user.value = it
        }
    }

    private suspend fun getTweets(pageNumber: Int): List<Tweet?> {
        return try {
            // When pageNumber is 0, load pinned tweets first and wait for completion
            if (pageNumber == 0) {
                loadPinnedTweets()
                // Ensure pinned tweets are loaded before proceeding
                Timber.tag("getTweets").d("Pinned tweets loaded: ${pinnedTweets.value.size} tweets")
            }

            // Fetch tweets of the author and update _tweets
            val newTweetsWithNulls = HproseInstance.getTweetsByUser(user.value, pageNumber)

            // Filter out null elements and get valid tweets
            val newTweets = newTweetsWithNulls.filterNotNull()

            Timber.tag("getTweets")
                .d("Received ${newTweetsWithNulls.size} tweets (${newTweets.size} valid) for user: ${user.value.mid}, page: $pageNumber")

            if (pageNumber == 0) {
                // For refresh (page 0), replace the list only if we have no existing tweets
                // This prevents replacing tweets during TweetListView recreation
                if (_tweets.value.isEmpty()) {
                    val filteredTweets = newTweets.filterNot { tweet: Tweet ->
                        tweet.isPrivate && tweet.authorId != appUser.mid
                    }

                    // Get current pinned tweet IDs after ensuring they're loaded
                    val pinnedTweetIds = pinnedTweets.value.map { it.mid }.toSet()
                    val tweetsWithoutPinned = filteredTweets.filterNot { tweet: Tweet ->
                        pinnedTweetIds.contains(tweet.mid)
                    }

                    _tweets.value = tweetsWithoutPinned
                    _tweetCount.value = tweetsWithoutPinned.size

                    Timber.tag("getTweets")
                        .d("Initial load: Filtered out ${filteredTweets.size - tweetsWithoutPinned.size} pinned tweets from regular tweets list. Pinned IDs: $pinnedTweetIds")
                } else {
                    // We already have tweets, just ensure page 0 tweets are included
                    Timber.tag("getTweets")
                        .d("Page 0 called but tweets already exist (${_tweets.value.size} tweets), preserving existing list")
                }
            } else {
                // For load more (page > 0), append to the list
                _tweets.update { currentTweets ->
                    val newTweetsMap = newTweets.associateBy { tweet: Tweet -> tweet.mid }
                    val updatedTweets = currentTweets.map { tweet ->
                        newTweetsMap[tweet.mid] ?: tweet
                    }
                    val combinedTweets = (updatedTweets + newTweets)
                        .filterNot { tweet: Tweet -> tweet.isPrivate && tweet.authorId != appUser.mid }
                        .distinctBy { tweet: Tweet -> tweet.mid }

                    // Filter out pinned tweets from the combined list
                    val pinnedTweetIds = pinnedTweets.value.map { it.mid }.toSet()
                    val finalTweets = combinedTweets.filterNot { tweet: Tweet ->
                        pinnedTweetIds.contains(tweet.mid)
                    }.sortedByDescending { tweet: Tweet -> tweet.timestamp }

                    _tweetCount.value = finalTweets.size
                    finalTweets
                }
            }

            newTweetsWithNulls
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
                            // Keep the original tweet, but associate it with its pinned timestamp for sorting
                            Pair(tweet, pinnedTimestamp)
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
                                tweet.originalAuthorId!!,
                                shouldCache = false  // Memory cache only for profile screens
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
            } else {
                // Clear pinned tweets if none found
                _pinnedTweets.value = emptyList()
                Timber.tag("loadPinnedTweets").d("No pinned tweets found, cleared list")
            }

        } catch (e: Exception) {
            Timber.tag("loadPinnedTweets")
                .e(e, "Error loading pinned tweets for user: ${user.value.mid}")
            // Don't clear pinned tweets on error, keep existing state
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

            // Update tweet count
            _tweetCount.value = tweets.value.size
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
                refreshFollowingsAndFans()
                
                // Reset and refresh tweet feed after successful login
                tweetFeedViewModel.reset()

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

    fun logout(popBack: () -> Unit) {
        preferenceHelper.setUserId(null)
        appUser = User(
            mid = TW_CONST.GUEST_ID, baseUrl = appUser.baseUrl,
            followingList = HproseInstance.getAlphaIds()
        )
        /**
         * Do NOT clear the UserViewModel object. It will be reused by other users.
         * */
        _tweets.value = emptyList()
        _pinnedTweets.value = emptyList()
        dao.clearAllCachedTweets()
        
        // Reset TweetFeedViewModel to prevent stale data and race conditions
        tweetFeedViewModel.reset()
        
        popBack()
    }

    /**
     * Handle both register and update of user profile. Username, password are required.
     * Do NOT update appUser, wait for the new user to login.
     * */
    suspend fun register(context: Context, popBack: () -> Unit) {
        isLoading.value = true
        if (this.hostId.value.isNotEmpty() && appUser.mid == TW_CONST.GUEST_ID) {
            /**
             * Register a new user. Check username and password first.
             * */
            if (username.value.isNullOrEmpty()) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.username_required),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                isLoading.value = false
                return
            }
            if (password.value.isEmpty()) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.password_required),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                isLoading.value = false
                return
            }
            // Find IP of the desired node. User can change its value to appoint to
            // a different host node later.
            HproseInstance.getHostIP(hostId.value)?.let { ip ->
                appUser = appUser.copy(baseUrl = "http://$ip")
            } ?: run {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.node_not_found),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                isLoading.value = false
                return
            }
        }
        var updatedUser = User(
            baseUrl = appUser.baseUrl, avatar = appUser.avatar, mid = appUser.mid,
            name = name.value?.trim(), hostIds = listOf(hostId.value.trim()),
            username = username.value!!.lowercase().trim(), password = password.value,
            profile = profile.value?.trim()
        )
        HproseInstance.setUserData(updatedUser)?.let { ret ->
            if (ret["status"] == "success") {
                try {
                    // Pre-process the user data to handle timestamp conversion
                    val userData = ret["user"] as? Map<String, Any>
                    if (userData != null) {
                        val processedUserData = userData.toMutableMap()
                        
                        // Handle timestamp conversion from decimal to long
                        processedUserData["timestamp"]?.let { value ->
                            when (value) {
                                is Number -> processedUserData["timestamp"] = value.toLong()
                                is String -> {
                                    try {
                                        processedUserData["timestamp"] = value.toDouble().toLong()
                                    } catch (e: NumberFormatException) {
                                        Timber.w("Failed to parse timestamp: $value")
                                        processedUserData["timestamp"] = System.currentTimeMillis()
                                    }
                                }
                            }
                        }
                        
                        // Handle lastLogin conversion from decimal to long
                        processedUserData["lastLogin"]?.let { value ->
                            when (value) {
                                is Number -> processedUserData["lastLogin"] = value.toLong()
                                is String -> {
                                    try {
                                        processedUserData["lastLogin"] = value.toDouble().toLong()
                                    } catch (e: NumberFormatException) {
                                        Timber.w("Failed to parse lastLogin: $value")
                                        processedUserData["lastLogin"] = System.currentTimeMillis()
                                    }
                                }
                            }
                        }
                        
                        if (appUser.isGuest()) {
                            // Create new user from processed data
                            val newUser = User.from(processedUserData)

                            password.value = ""     // clear the password
                            popBack()
                        } else {
                            // Update existing user profile
                            appUser.from(processedUserData)
                            _user.value = appUser
                            
                            // Update the shared appUserViewModel if this is the current user's profile
                            if (userId == appUser.mid) {
                                // Update all the count variables in this ViewModel to match the updated appUser
                                _bookmarksCount.value = appUser.bookmarksCount
                                _favoritesCount.value = appUser.favoritesCount
                                _followersCount.value = appUser.followersCount
                                _followingsCount.value = appUser.followingCount
                                _tweetCount.value = appUser.tweetCount
                                
                                // Also update the user state to reflect the new profile data
                                _user.value = appUser
                                
                                // Save the updated user to cache
                                TweetCacheManager.saveUser(appUser)
                                

                                
                                // Force refresh of the shared appUserViewModel by updating its user state
                                // This ensures that all components using the shared ViewModel get updated
                                viewModelScope.launch {
                                    // Small delay to ensure the update is processed
                                    delay(100)
                                    _user.value = appUser
                                }
                            }
                            
                            withContext(Dispatchers.Main) {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.profile_update_ok),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    } else {
                        // Fallback to custom Gson parsing with timestamp handling
                        try {
                            val jsonString = ret["user"].toString()
                            
                            // Pre-process JSON string to fix timestamp issues
                            val processedJson = jsonString.replace(
                            Regex("\"timestamp\":\\s*([0-9]+\\.[0-9]+)"),
                            "\"timestamp\": $1"
                        ).replace(
                            Regex("\"lastLogin\":\\s*([0-9]+\\.[0-9]+)"),
                            "\"lastLogin\": $1"
                        ).replace(
                                Regex("\"timestamp\":\\s*([0-9]+)\\.([0-9]+)"),
                                "\"timestamp\": $1"
                            ).replace(
                                Regex("\"lastLogin\":\\s*([0-9]+)\\.([0-9]+)"),
                                "\"lastLogin\": $1"
                            )
                            
                            val gson = Gson()
                            val userType = object : TypeToken<User>() {}.type
                            
                            if (appUser.isGuest()) {
                                val newUser: User = gson.fromJson(processedJson, userType)
                                password.value = ""
                                popBack()
                            } else {
                                updatedUser = gson.fromJson(processedJson, userType)
                                appUser = appUser.copy(
                                    name = updatedUser.name, profile = updatedUser.profile,
                                    username = updatedUser.username, hostIds = updatedUser.hostIds,
                                )
                                _user.value = appUser
                                
                                // Update the shared appUserViewModel if this is the current user's profile
                                if (userId == appUser.mid) {
                                    // Update all the count variables in this ViewModel to match the updated appUser
                                    _bookmarksCount.value = appUser.bookmarksCount
                                    _favoritesCount.value = appUser.favoritesCount
                                    _followersCount.value = appUser.followersCount
                                    _followingsCount.value = appUser.followingCount
                                    _tweetCount.value = appUser.tweetCount
                                    
                                    // Also update the user state to reflect the new profile data
                                    _user.value = appUser
                                    
                                    // Save the updated user to cache
                                    TweetCacheManager.saveUser(appUser)
                                    

                                    
                                    // Force refresh of the shared appUserViewModel by updating its user state
                                    // This ensures that all components using the shared ViewModel get updated
                                    viewModelScope.launch {
                                        // Small delay to ensure the update is processed
                                        delay(100)
                                        _user.value = appUser
                                    }
                                }
                                
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.profile_update_ok),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        } catch (e: Exception) {
                            Timber.e(e, "Error in fallback Gson parsing")
                            throw e // Re-throw to be caught by outer catch block
                        }
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Error processing user data during ${if (appUser.isGuest()) "registration" else "profile update"}")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            context,
                            if (appUser.isGuest()) context.getString(R.string.registration_failed)
                            else context.getString(R.string.profile_update_failed),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            } else {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, ret["reason"].toString(), Toast.LENGTH_SHORT).show()
                }
            }
        } ?: run {
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    context,
                    context.getString(R.string.registration_failed),
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
            getUser(fanId)?.let { fan ->
                if (fan.username?.startsWith(query, ignoreCase = true) == true) {
                    suggestions.add(fan.username!!)
                }
            }
        }

        // Check followings
        followings.value.forEach { followingId ->
            getUser(followingId)?.let { following ->
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
                            _user.value = user.value.copy(tweetCount = updatedTweets.size)
                            _tweetCount.value = updatedTweets.size
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

                            // Update user's tweet count while preserving other fields
                            _user.value = _user.value.copy(tweetCount = updatedTweets.size)
                            _tweetCount.value = updatedTweets.size
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

        // Update user's tweet count while preserving other fields
        _user.value = _user.value.copy(tweetCount = updatedTweets.size)
    }
}
