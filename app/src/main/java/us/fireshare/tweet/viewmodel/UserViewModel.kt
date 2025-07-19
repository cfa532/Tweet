package us.fireshare.tweet.viewmodel

import android.content.Context
import android.net.Uri
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
import us.fireshare.tweet.datamodel.TweetEvent
import us.fireshare.tweet.datamodel.TweetNotificationCenter

import us.fireshare.tweet.datamodel.User
import us.fireshare.tweet.datamodel.UserContentType
import us.fireshare.tweet.service.SnackbarController
import us.fireshare.tweet.service.SnackbarEvent
import kotlin.math.max
import us.fireshare.tweet.datamodel.TweetCacheManager

@HiltViewModel(assistedFactory = UserViewModel.UserViewModelFactory::class)
class UserViewModel @AssistedInject constructor(
    @Assisted private val userId: MimeiId,
//    private val savedStateHandle: SavedStateHandle
): ViewModel() {
    private val _user = MutableStateFlow(User(mid = TW_CONST.GUEST_ID, baseUrl = appUser.baseUrl))
    val user: StateFlow<User> get() = _user.asStateFlow()

    // unpinned tweets
    private val _tweets = MutableStateFlow<List<Tweet>>(emptyList())
    val tweets: StateFlow<List<Tweet>> get() = _tweets.asStateFlow()

    // pinned tweets
    private val _topTweets = MutableStateFlow<List<Tweet>>(emptyList())
    val topTweets: StateFlow<List<Tweet>> get() = _topTweets.asStateFlow()

    private var _followers = MutableStateFlow(emptyList<MimeiId>())
    val followers: StateFlow<List<MimeiId>> get() = _followers.asStateFlow()
    private var _followings = MutableStateFlow(emptyList<MimeiId>())
    val followings: StateFlow<List<MimeiId>> get() = _followings.asStateFlow()

    var isLoading = MutableStateFlow(false)

    private val _bookmarks = MutableStateFlow<List<Tweet>>(emptyList())
    val bookmarks: StateFlow<List<Tweet>> get() = _bookmarks.asStateFlow()
    private val _favorites = MutableStateFlow<List<Tweet>>(emptyList())
    val favorites: StateFlow<List<Tweet>> get() = _favorites.asStateFlow()

    // variable for login management
    var username = mutableStateOf(appUser.username)
    var password = mutableStateOf("")
    var name = mutableStateOf(appUser.name)
    var profile = mutableStateOf(appUser.profile)
    var hostId = mutableStateOf("")
    var isPasswordVisible = mutableStateOf(false)
    var loginError = mutableStateOf("")

    private var initState = MutableStateFlow(true)      // initial load state

    /**
     * Initial load of tweets of an user. Execute only once.
     * */
    suspend fun initLoad() {
        try {
            // Load first page (page 0) which includes pinned tweets
            getTweets(0)

            // Load additional pages if needed to get at least 5 viewable tweets
            var pageNumber = 1
            while (tweets.value.count { !it.isPrivate || it.authorId == appUser.mid } < 5 && pageNumber < 3) {
                getTweets(pageNumber)
                pageNumber++
            }
        } catch (e: Exception) {
            Timber.tag("initLoad").e(e, "Error during initial load for user: ${user.value.mid}")
        } finally {
            initState.value = false
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
        return topTweets.value.any { it.mid == tweet.mid }
    }

    fun getHostId() {
        hostId.value = if (user.value.hostIds.isNullOrEmpty()) {
            HproseInstance.getHostId() ?: ""
        } else user.value.hostIds!!.first()
    }

    suspend fun updateAvatar(context: Context, uri: Uri) {
        isLoading.value = true
        // For now, user avatar can only be image.
        HproseInstance.uploadToIPFS(
            context,
            uri,
            referenceId = appUser.mid
        )?.let {
            HproseInstance.setUserAvatar(appUser, it.mid)   // Update appUser's avatar
            appUser = appUser.copy(avatar = it.mid)
            _user.value = appUser
        }
        isLoading.value = false
    }

    /**
     * @param userId calls this function to update its follower list
     * @param isFollower indicates if followerId is a follower or not.
     * */
    suspend fun toggleFollower(
        userId: MimeiId,
        isFollower: Boolean,
        followerId: MimeiId
    ) {
        HproseInstance.toggleFollower(userId, isFollower, followerId)
        _followers.update { list ->
            if (isFollower)
                (listOf(followerId) + list).toSet().toList()
            else
                list.filterNot { it == followerId }
        }
        _user.value = user.value.copy(followersCount = followers.value.size)
    }

    /**
     * @param subjectUserId to add/remove it to/from the following list
     * */
    suspend fun toggleFollowing(
        subjectUserId: MimeiId,
        userId: MimeiId = appUser.mid,
        updateTweetFeed: (Boolean) -> Unit
    ) {
        // toggle the Following status on the given UserId
        HproseInstance.toggleFollowing(subjectUserId, userId)?.let { isFollowing ->
            _followings.update { list ->
                if (isFollowing)
                    (listOf(subjectUserId) + list).toSet().toList()
                else
                    list.filterNot { it == subjectUserId }
            }
            _user.value = user.value.copy(followingCount = followings.value.size)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                TweetCacheManager.saveUser(appUser)
            }
            // callback to update tweet feed. Load or remove tweets of the others.
            updateTweetFeed(isFollowing)
        }
    }

    fun refreshFollowingsAndFans() {
        _followers.value = HproseInstance.getFans(user.value) ?: emptyList()
        _followings.value = HproseInstance.getFollowings(user.value)
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
                    Timber.tag("fetchFollowers").w("Found duplicate user IDs in raw data: $duplicates")
                }
                
                _followers.value = allFollowers
                
                // Return the first batch of users, filtering out nulls
                val firstBatch = allFollowers.take(TW_CONST.USER_BATCH_SIZE).filterNotNull()
                Timber.tag("fetchFollowers").d("Returning first batch: ${firstBatch.size} user IDs")
                firstBatch
            } else {
                // For subsequent pages, return the appropriate slice of already-loaded followers
                val startIndex = pageNumber * TW_CONST.USER_BATCH_SIZE
                val endIndex = startIndex + TW_CONST.USER_BATCH_SIZE
                val slice = _followers.value.slice(startIndex until minOf(endIndex, _followers.value.size))
                
                if (slice.isEmpty()) {
                    // No more followers to return
                    Timber.tag("fetchFollowers").d("No more followers to return for page: $pageNumber")
                    emptyList()
                } else {
                    val filteredSlice = slice.filterNotNull()
                    Timber.tag("fetchFollowers").d("Returning slice for page $pageNumber: ${filteredSlice.size} user IDs")
                    filteredSlice
                }
            }
        } catch (e: Exception) {
            Timber.tag("fetchFollowers").e(e, "Error fetching followers for user: ${user.value.mid}")
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
                allFollowings.take(TW_CONST.USER_BATCH_SIZE).filterNotNull()
            } else {
                // For subsequent pages, return the appropriate slice of already-loaded followings
                val startIndex = pageNumber * TW_CONST.USER_BATCH_SIZE
                val endIndex = startIndex + TW_CONST.USER_BATCH_SIZE
                val slice = _followings.value.slice(startIndex until minOf(endIndex, _followings.value.size))
                
                if (slice.isEmpty()) {
                    // No more followings to return
                    emptyList()
                } else {
                    slice.filterNotNull()
                }
            }
        } catch (e: Exception) {
            Timber.tag("fetchFollowings").e(e, "Error fetching followings for user: ${user.value.mid}")
            emptyList()
        }
    }

    /**
     * Fetch user data in batches from appUser's node
     * First tries to fetch in batches of USER_BATCH_SIZE, then falls back to individual calls for unavailable users
     */
    private suspend fun fetchUserDataInBatches(userIds: List<MimeiId>) {
        if (userIds.isEmpty()) return
        
        Timber.tag("fetchUserDataInBatches").d("Fetching user data for ${userIds.size} users in batches")
        
        // Process users in batches
        val batches = userIds.chunked(TW_CONST.USER_BATCH_SIZE)
        
        for ((batchIndex, batch) in batches.withIndex()) {
            Timber.tag("fetchUserDataInBatches").d("Processing batch $batchIndex with ${batch.size} users")
            
            // Try to fetch batch from appUser's node first
            val batchResults = fetchUserBatchFromAppUserNode(batch)
            
            // For users not available in batch, fetch individually
            val unavailableUsers = batch.filterIndexed { index, _ -> batchResults[index] == null }
            
            if (unavailableUsers.isNotEmpty()) {
                Timber.tag("fetchUserDataInBatches").d("Fetching ${unavailableUsers.size} users individually")
                unavailableUsers.forEach { userId ->
                    try {
                        HproseInstance.getUser(userId)
                    } catch (e: Exception) {
                        Timber.tag("fetchUserDataInBatches").e(e, "Error fetching individual user: $userId")
                    }
                }
            }
        }
    }

    /**
     * Try to fetch a batch of users from appUser's node
     * Returns a list where each element is either a User object or null if not available
     */
    private suspend fun fetchUserBatchFromAppUserNode(userIds: List<MimeiId>): List<User?> {
        return try {
            // Try to fetch users from appUser's node using a batch endpoint
            // If the endpoint doesn't exist, this will return nulls and we'll fall back to individual calls
            val entry = "get_users_batch"
            val params = mapOf(
                "aid" to HproseInstance.appId,
                "ver" to "last",
                "userids" to userIds
            )
            
            // Add timeout to prevent hanging
            val response = withTimeoutOrNull(5000L) { // 5 second timeout
                HproseInstance.appUser.hproseService?.runMApp<List<Map<String, Any>?>>(entry, params)
            }
            
            response?.map { userData ->
                if (userData == null) {
                    null
                } else {
                    try {
                        val user = User.from(userData)
                        // Cache the user
                        TweetCacheManager.saveUser(user)
                        user
                    } catch (e: Exception) {
                        Timber.tag("fetchUserBatchFromAppUserNode").e(e, "Error parsing user data")
                        null
                    }
                }
            } ?: List(userIds.size) { null }
            
        } catch (e: Exception) {
            Timber.tag("fetchUserBatchFromAppUserNode").d("Batch endpoint not available or failed, will use individual calls: $e")
            // Return nulls to indicate batch fetch failed, will fall back to individual calls
            List(userIds.size) { null }
        }
    }

    /**
     * Get bookmarks of the user
     * */
    suspend fun getBookmarks(pageNumber: Int) {
        val tweetsWithNulls = getUserTweetsByType(user.value, UserContentType.BOOKMARKS, pageNumber, TW_CONST.PAGE_SIZE)
        
        // Filter out null elements and get valid tweets
        val validTweets = tweetsWithNulls.filterNotNull()
        
        Timber.tag("getBookmarks")
            .d("Received ${tweetsWithNulls.size} tweets (${validTweets.size} valid) for user: ${user.value.mid}, page: $pageNumber")
        
        if (pageNumber == 0) {
            // For refresh (page 0), replace the list
            _bookmarks.value = validTweets
        } else {
            // For load more (page > 0), append to the list
            _bookmarks.update { currentBookmarks ->
                val newTweetsMap = validTweets.associateBy { it.mid }
                val updatedBookmarks = currentBookmarks.map { bookmark ->
                    newTweetsMap[bookmark.mid] ?: bookmark
                }
                (updatedBookmarks + validTweets)
                    .distinctBy { it.mid }
                    .sortedByDescending { it.timestamp }
            }
        }
    }

    /**
     * Update in-memory bookmark data for display.
     * if bookmarks screen of an user never opened, the bookmarks are empty.
     * */
    fun updateBookmark(tweet: Tweet, isBookmarked: Boolean) {
        if (isBookmarked) {
            _user.value = user.value.copy(bookmarksCount = user.value.bookmarksCount + 1)
            _bookmarks.update { bs -> listOf(tweet) + bs }
        } else {
            _user.value = user.value.copy(
                bookmarksCount = max(user.value.bookmarksCount - 1, 0)
            )
            _bookmarks.update { bs -> bs.filterNot { it.mid == tweet.mid } }
        }
    }

    /**
     * Get favorite Tweets of the user.
     * */
    suspend fun getFavorites(pageNumber: Int) {
        val tweetsWithNulls = getUserTweetsByType(user.value, UserContentType.FAVORITES, pageNumber, TW_CONST.PAGE_SIZE)
        
        // Filter out null elements and get valid tweets
        val validTweets = tweetsWithNulls.filterNotNull()
        
        Timber.tag("getFavorites")
            .d("Received ${tweetsWithNulls.size} tweets (${validTweets.size} valid) for user: ${user.value.mid}, page: $pageNumber")
        
        if (pageNumber == 0) {
            // For refresh (page 0), replace the list
            _favorites.value = validTweets
        } else {
            // For load more (page > 0), append to the list
            _favorites.update { currentFavorites ->
                val newTweetsMap = validTweets.associateBy { it.mid }
                val updatedFavorites = currentFavorites.map { favorite ->
                    newTweetsMap[favorite.mid] ?: favorite
                }
                (updatedFavorites + validTweets)
                    .distinctBy { it.mid }
                    .sortedByDescending { it.timestamp }
            }
        }
    }

    fun updateFavorite(tweet: Tweet, isFavorite: Boolean) {
        if (isFavorite) {
            _user.value = user.value.copy(favoritesCount = user.value.favoritesCount + 1)
            _favorites.update { bs -> listOf(tweet) + bs }
        } else {
            _user.value = user.value.copy(
                favoritesCount = max(user.value.favoritesCount - 1, 0)
            )
            _favorites.update { bs -> bs.filterNot { it.mid == tweet.mid } }
        }
    }

    @AssistedFactory
    interface UserViewModelFactory {
        fun create(userId: MimeiId): UserViewModel
    }

    init {
        if (userId != TW_CONST.GUEST_ID) {
            viewModelScope.launch(Dispatchers.IO) {
                _user.value =
                    getUser(userId) ?: User(mid = TW_CONST.GUEST_ID, baseUrl = appUser.baseUrl)
                if (userId == appUser.mid) {
                    // By default NOT to load fans and followings list of an user object.
                    // Do it only when opening the user's profile page.
                    // Only get current user's fans list when opening the app.
                    refreshFollowingsAndFans()
                }
            }
        } else {
            _user.value = appUser
        }
    }

    suspend fun refreshUser() {
        withContext(Dispatchers.IO) {
            TweetCacheManager.removeCachedUser(userId)
            _user.value = getUser(userId) ?: User(mid = TW_CONST.GUEST_ID, baseUrl = appUser.baseUrl)
        }
    }

    private suspend fun getTweets(pageNumber: Int): List<Tweet?> {
        return try {
            // When pageNumber is 0, load pinned tweets first
            if (pageNumber == 0) {
                loadPinnedTweets()
            }

            // Fetch tweets of the author and update _tweets
            val newTweetsWithNulls = HproseInstance.getTweetsByUser(user.value, pageNumber)

            // Filter out null elements and get valid tweets
            val newTweets = newTweetsWithNulls.filterNotNull()

            Timber.tag("getTweets")
                .d("Received ${newTweetsWithNulls.size} tweets (${newTweets.size} valid) for user: ${user.value.mid}, page: $pageNumber")

            if (pageNumber == 0) {
                // For refresh (page 0), replace the list
                _tweets.value =
                    newTweets.filterNot { tweet: Tweet -> tweet.isPrivate && tweet.authorId != appUser.mid }
            } else {
                // For load more (page > 0), append to the list
                _tweets.update { currentTweets ->
                    val newTweetsMap = newTweets.associateBy { tweet: Tweet -> tweet.mid }
                    val updatedTweets = currentTweets.map { tweet ->
                        newTweetsMap[tweet.mid] ?: tweet
                    }
                    (updatedTweets + newTweets)
                        .filterNot { tweet: Tweet -> tweet.isPrivate && tweet.authorId != appUser.mid }
                        .distinctBy { tweet: Tweet -> tweet.mid }
                        .sortedByDescending { tweet: Tweet -> tweet.timestamp }
                }
            }

            newTweetsWithNulls
        } catch (e: Exception) {
            Timber.tag("getTweets").e(e, "Error fetching tweets for user: ${user.value.mid}, page: $pageNumber")
            emptyList()
        }
    }

    private suspend fun loadPinnedTweets() {
        try {
            // 2. Get pinned tweets and update _topTweets, while avoiding duplication
            val pinnedTweets = mutableSetOf<Tweet>()
            HproseInstance.getPinnedList(user.value)?.forEach { map ->
                val tweet = tweets.value.find { it.mid == map["tweetId"] }
                if (tweet != null) {
                    // add tweet to topTweets, update its timestamp to when it is pinned.
                    pinnedTweets.add(tweet.copy(timestamp = map["timestamp"].toString().toLong()))
                } else {
                    HproseInstance.fetchTweet(map["tweetId"].toString(), user.value.mid, shouldCache = false)?.let { tweet1 ->
                        // Note: originalTweet is no longer loaded here, it will be loaded on-demand in the UI
                        pinnedTweets.add(tweet1.copy(timestamp = map["timestamp"].toString().toLong()))
                    }
                }
            }
            // 3. overwrite any tweet in _topTweets with one from pinnedTweets
            _topTweets.update { currentTopTweets ->
                val pinnedTweetsFiltered = pinnedTweets.toList()
                    .distinctBy { tweet: Tweet -> tweet.mid }
                    .sortedByDescending { tweet: Tweet -> tweet.timestamp }

                val currentTopTweetsMap = currentTopTweets.associateBy { it.mid }.toMutableMap()

                pinnedTweetsFiltered.forEach { pinnedTweet ->
                    currentTopTweetsMap[pinnedTweet.mid] = pinnedTweet // Overwrite or add
                }

                currentTopTweetsMap.values.toList() // Convert back to a list
            }
        } catch (e: Exception) {
            Timber.tag("loadPinnedTweets").e(e, "Error loading pinned tweets for user: ${user.value.mid}")
        }
    }

    /**
     * User can pin or unpin any tweet, including quoted or retweet by this user.
     * */
    suspend fun pinToTop(tweetId: MimeiId) {
        val pinnedTweets = mutableSetOf<Tweet>()
        HproseInstance.togglePinnedTweet(tweetId)?.forEach { map ->
            val tweet = tweets.value.find { it.mid == map["tweetId"] }
            if (tweet != null) {
                /**
                 * add tweet to topTweets, update its timestamp to when it is pinned,
                 * instead of when it was created.
                 * */
                pinnedTweets.add(tweet.copy(timestamp = map["timestamp"].toString().toLong()))
            } else {
                HproseInstance.fetchTweet(map["tweetId"].toString(), user.value.mid, shouldCache = false)?.let { tweet1 ->
                    // Note: originalTweet is no longer loaded here, it will be loaded on-demand in the UI
                    pinnedTweets.add(tweet1.copy(timestamp = map["timestamp"].toString().toLong()))
                }
            }
        }
        _topTweets.update {
            pinnedTweets.toList()
                .filterNot { tweet: Tweet -> tweet.isPrivate && tweet.authorId != appUser.mid }
                .distinctBy { tweet: Tweet -> tweet.mid }
                .sortedByDescending { tweet: Tweet -> tweet.timestamp }
        }
    }

    suspend fun showSnackbar(event: SnackbarEvent) {
        SnackbarController.sendEvent(event)
    }

    suspend fun login(context: Context, callback: () -> Unit) {
        isLoading.value = true
        if (username.value?.isNotEmpty() == true
            && password.value.isNotEmpty()
        ) {
            val ret = HproseInstance.login(username.value!!, password.value, context)
            isLoading.value = false

            if (ret.second != null) {
                // something wrong
                loginError.value = ret.second.toString()
            } else {
                appUser = ret.first as User
                preferenceHelper.setUserId(appUser.mid)
                _user.value = appUser
                username.value = appUser.username
                name.value = appUser.name ?: ""
                profile.value = appUser.profile ?: ""
                hostId.value = appUser.hostIds?.firstOrNull() ?: ""
                refreshFollowingsAndFans()
                callback()
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
        _topTweets.value = emptyList()
        dao.clearAllCachedTweets()
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
                showSnackbar(SnackbarEvent(message = context.getString(R.string.username_required)))
                isLoading.value = false
                return
            }
            if (password.value.isEmpty()) {
                showSnackbar(SnackbarEvent(message = context.getString(R.string.password_required)))
                isLoading.value = false
                return
            }
            // Find IP of the desired node. User can change its value to appoint to
            // a different host node later.
            HproseInstance.getHostIP(hostId.value)?.let { ip ->
                appUser = appUser.copy(baseUrl = "http://$ip")
            } ?: run {
                showSnackbar(SnackbarEvent(message = context.getString(R.string.node_not_found)))
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
                val gson = Gson()
                val userType = object : TypeToken<User>() {}.type
                if (appUser.isGuest()) {
                    val newUser: User = gson.fromJson(ret["user"].toString(), userType)
                    /**
                     * Set the newly created user as followers of admin users.
                     * */
                    HproseInstance.getAlphaIds().forEach {
                        HproseInstance.toggleFollower(it, true, newUser.mid)
                    }
                    password.value = ""     // clear the password
                    popBack()
                } else {
                    // update user profile
                    updatedUser = gson.fromJson(ret["user"].toString(), userType)
                    appUser = appUser.copy(
                        name = updatedUser.name, profile = updatedUser.profile,
                        username = updatedUser.username, hostIds = updatedUser.hostIds,
                    )
                    _user.value = appUser

                    val event = SnackbarEvent(
                        message = context.getString(R.string.profile_update_ok)
                    )
                    showSnackbar(event)
                }
            } else {
                showSnackbar(SnackbarEvent(message = ret["reason"].toString()))
            }
        } ?: run {
            showSnackbar(SnackbarEvent(message = context.getString(R.string.registration_failed)))
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
                            _tweets.update { currentTweets ->
                                (listOf(tweetWithAuthor) + currentTweets)
                                    .distinctBy { it.mid }
                                    .sortedByDescending { it.timestamp }
                            }
                            _user.value = user.value.copy(tweetCount = tweets.value.size)
                        }
                    }

                    is TweetEvent.TweetDeleted -> {
                        // Remove from all lists
                        _tweets.update { currentTweets -> currentTweets.filterNot { it.mid == event.tweetId } }
                        _topTweets.update { topTweets -> topTweets.filterNot { it.mid == event.tweetId } }
                        _favorites.update { currentTweets -> currentTweets.filterNot { it.mid == event.tweetId } }
                        _bookmarks.update { currentTweets -> currentTweets.filterNot { it.mid == event.tweetId } }

                        // Update user's tweet count
                        _user.value = user.value.copy(tweetCount = tweets.value.size)
                    }

                    is TweetEvent.TweetLiked -> {
                        // Update like status in favorites list
                        _favorites.update { currentTweets ->
                            currentTweets.map { tweet ->
                                if (tweet.mid == event.tweet.mid) event.tweet else tweet
                            }
                        }
                    }

                    is TweetEvent.TweetBookmarked -> {
                        // Update bookmark status in bookmarks list
                        _bookmarks.update { currentTweets ->
                            currentTweets.map { tweet ->
                                if (tweet.mid == event.tweet.mid) event.tweet else tweet
                            }
                        }
                    }

                    is TweetEvent.CommentUploaded -> {
                        // Update comment count for parent tweet in all lists
                        _tweets.update { currentTweets ->
                            currentTweets.map { tweet ->
                                if (tweet.mid == event.parentTweet.mid) {
                                    tweet.copy(commentCount = event.parentTweet.commentCount)
                                } else {
                                    tweet
                                }
                            }
                        }
                        _topTweets.update { topTweets ->
                            topTweets.map { tweet ->
                                if (tweet.mid == event.parentTweet.mid) {
                                    tweet.copy(commentCount = event.parentTweet.commentCount)
                                } else {
                                    tweet
                                }
                            }
                        }
                        _favorites.update { currentTweets ->
                            currentTweets.map { tweet ->
                                if (tweet.mid == event.parentTweet.mid) {
                                    tweet.copy(commentCount = event.parentTweet.commentCount)
                                } else {
                                    tweet
                                }
                            }
                        }
                        _bookmarks.update { currentTweets ->
                            currentTweets.map { tweet ->
                                if (tweet.mid == event.parentTweet.mid) {
                                    tweet.copy(commentCount = event.parentTweet.commentCount)
                                } else {
                                    tweet
                                }
                            }
                        }
                    }

                    is TweetEvent.CommentDeleted -> {
                        // Decrease comment count for parent tweet in all lists
                        _tweets.update { currentTweets ->
                            currentTweets.map { tweet ->
                                if (tweet.mid == event.parentTweetId) {
                                    tweet.copy(commentCount = max(0, tweet.commentCount - 1))
                                } else {
                                    tweet
                                }
                            }
                        }
                        _topTweets.update { topTweets ->
                            topTweets.map { tweet ->
                                if (tweet.mid == event.parentTweetId) {
                                    tweet.copy(commentCount = max(0, tweet.commentCount - 1))
                                } else {
                                    tweet
                                }
                            }
                        }
                        _favorites.update { currentTweets ->
                            currentTweets.map { tweet ->
                                if (tweet.mid == event.parentTweetId) {
                                    tweet.copy(commentCount = max(0, tweet.commentCount - 1))
                                } else {
                                    tweet
                                }
                            }
                        }
                        _bookmarks.update { currentTweets ->
                            currentTweets.map { tweet ->
                                if (tweet.mid == event.parentTweetId) {
                                    tweet.copy(commentCount = max(0, tweet.commentCount - 1))
                                } else {
                                    tweet
                                }
                            }
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
        Timber.tag("UserViewModel").d("Optimistic deletion: Removing tweet $tweetId from all user lists")
        
        // Remove from all lists
        _tweets.update { currentTweets -> 
            val filtered = currentTweets.filterNot { it.mid == tweetId }
            Timber.tag("UserViewModel").d("Removed from tweets: ${currentTweets.size} -> ${filtered.size}")
            filtered
        }
        _topTweets.update { topTweets -> 
            val filtered = topTweets.filterNot { it.mid == tweetId }
            Timber.tag("UserViewModel").d("Removed from topTweets: ${topTweets.size} -> ${filtered.size}")
            filtered
        }
        _favorites.update { currentTweets -> 
            val filtered = currentTweets.filterNot { it.mid == tweetId }
            Timber.tag("UserViewModel").d("Removed from favorites: ${currentTweets.size} -> ${filtered.size}")
            filtered
        }
        _bookmarks.update { currentTweets -> 
            val filtered = currentTweets.filterNot { it.mid == tweetId }
            Timber.tag("UserViewModel").d("Removed from bookmarks: ${currentTweets.size} -> ${filtered.size}")
            filtered
        }

        // Update user's tweet count
        _user.value = user.value.copy(tweetCount = tweets.value.size)
        Timber.tag("UserViewModel").d("Updated user tweet count to: ${tweets.value.size}")
    }

    fun someFunctionThatCallsSaveUser() {
        viewModelScope.launch(Dispatchers.IO) {
            TweetCacheManager.saveUser(appUser)
        }
    }
}
