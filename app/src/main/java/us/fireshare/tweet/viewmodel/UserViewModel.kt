package us.fireshare.tweet.viewmodel

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.mutableIntStateOf
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
import timber.log.Timber
import us.fireshare.tweet.HproseInstance
import us.fireshare.tweet.HproseInstance.appUser
import us.fireshare.tweet.HproseInstance.dao
import us.fireshare.tweet.HproseInstance.getSortedMetaByUser
import us.fireshare.tweet.HproseInstance.getUser
import us.fireshare.tweet.HproseInstance.preferenceHelper
import us.fireshare.tweet.R
import us.fireshare.tweet.datamodel.CachedUser
import us.fireshare.tweet.datamodel.MimeiId
import us.fireshare.tweet.datamodel.TW_CONST
import us.fireshare.tweet.datamodel.Tweet
import us.fireshare.tweet.datamodel.TweetActionListener
import us.fireshare.tweet.datamodel.User
import us.fireshare.tweet.datamodel.isGuest
import us.fireshare.tweet.service.SnackbarController
import us.fireshare.tweet.service.SnackbarEvent
import kotlin.math.max

@HiltViewModel(assistedFactory = UserViewModel.UserViewModelFactory::class)
class UserViewModel @AssistedInject constructor(
    @Assisted private val userId: MimeiId,
//    private val savedStateHandle: SavedStateHandle
): ViewModel(), TweetActionListener {
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

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshingAtTop: StateFlow<Boolean> get() = _isRefreshing.asStateFlow()
    private val _isRefreshingAtBottom = MutableStateFlow(false)
    val isRefreshingAtBottom: StateFlow<Boolean> get() = _isRefreshingAtBottom.asStateFlow()

    private val _bookmarks = MutableStateFlow<List<Tweet>>(emptyList())
    val bookmarks: StateFlow<List<Tweet>> get() = _bookmarks.asStateFlow()
    private val _favorites = MutableStateFlow<List<Tweet>>(emptyList())
    val favorites: StateFlow<List<Tweet>> get() = _favorites.asStateFlow()

    // current rank of tweet in DB. Retrieve 10 tweets each time start from it.
    private var startRank = mutableIntStateOf(0)

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
     * Initial load of tweets of an user.
     * */
    suspend fun initLoad() {
        while (startRank.intValue < user.value.tweetCount) {
            HproseInstance.getTweetListByRank(user.value, startRank.intValue)
                .collect { newTweets ->
                    Timber.tag("newTweets").d("$newTweets")
                    if (newTweets.isEmpty()) {
                        initState.value = false
                        return@collect
                    }
                    _tweets.update { currentTweets ->
                        val newTweetsMap = newTweets.associateBy { it.mid }
                        (currentTweets + newTweets)
                            .map { newTweetsMap[it.mid] ?: it } // Update existing tweets
                            .filter { !it.isPrivate || it.authorId == appUser.mid } // Filter private tweets
                            .distinctBy { it.mid }
                            .sortedByDescending { it.timestamp }
                    }
                }
            // private tweets not viewable to other users.
            val viewableTweetsCount =
                tweets.value.count { !it.isPrivate || it.authorId == appUser.mid }
            if (viewableTweetsCount < 5) {
                startRank.intValue += 10
                Timber.tag("initLoad").d("Incrementing startRank to ${startRank.intValue} and retrying.")
            } else {
                startRank.intValue = tweets.value.size   // for loading older tweets next time
                break
            }
        }
        loadPinnedTweets()
    }

    suspend fun loadNewerTweets() {
        if (initState.value) return
        _isRefreshing.value = true
        startRank.intValue = 0
        Timber.tag("UserVM.loadNewerTweets")
            .d("start rank=${startRank.intValue}")
        getTweets()
        _isRefreshing.value = false
    }
    suspend fun loadOlderTweets() {
        if (initState.value) return
        _isRefreshingAtBottom.value = true
        startRank.intValue = tweets.value.size
        Timber.tag("UserVM.loadOlderTweets")
            .d("start rank=${startRank.intValue}")
        getTweets()
        _isRefreshingAtBottom.value = false
    }

    /**
     * Whether the tweet is pinned to top list.
     * */
    fun hasPinned(tweet: Tweet): Boolean {
        return topTweets.value.any { it.mid == tweet.mid }
    }

    suspend fun getHostId() {
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
            dao.insertOrUpdateCachedUser(CachedUser(userId, appUser))

            // callback to update tweet feed. Load or remove tweets of the others.
            updateTweetFeed(isFollowing)
        }
    }

    suspend fun refreshFollowingsAndFans() {
        _followers.value = HproseInstance.getFans(user.value) ?: emptyList()
        _followings.value = HproseInstance.getFollowings(user.value)
    }

    /**
     * Get bookmarks of the user
     * */
    suspend fun getBookmarks(start: Int) {
        getSortedMetaByUser(user.value, "bookmark")?.let {
            _bookmarks.value = it
        }
    }

    /**
     * Update in-memory bookmark data for display.
     * if bookmarks screen of an user never opened, the bookmarks are empty.
     * */
    fun updateBookmark(tweet: Tweet, isBookmarked: Boolean) {
        if (isBookmarked) {
            _user.value = user.value.copy(bookmarksCount = user.value.bookmarksCount?.plus(1))
            _bookmarks.update { bs -> listOf(tweet) + bs }
        } else {
            _user.value = user.value.copy(
                bookmarksCount = user.value.bookmarksCount?.minus(1)?.let { max(it, 0) })
            _bookmarks.update { bs -> bs.filterNot { it.mid == tweet.mid } }
        }
    }

    /**
     * Get favorite Tweets of the user.
     * */
    suspend fun getFavorites(start: Int) {
        getSortedMetaByUser(user.value, "favorite")?.let {
            _favorites.value = it
        }
    }

    fun updateFavorite(tweet: Tweet, isFavorite: Boolean) {
        if (isFavorite) {
            _user.value = user.value.copy(favoritesCount = user.value.favoritesCount?.plus(1))
            _favorites.update { bs -> listOf(tweet) + bs }
        } else {
            _user.value = user.value.copy(
                favoritesCount = user.value.favoritesCount?.minus(1)?.let { max(it, 0) })
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
                _user.value = getUser(userId) ?: User(mid = TW_CONST.GUEST_ID, baseUrl = appUser.baseUrl)
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
        HproseInstance.removeCachedUser(userId)
        _user.value = getUser(userId) ?: User(mid = TW_CONST.GUEST_ID, baseUrl = appUser.baseUrl)
    }

    private suspend fun getTweets() {
        // 1. Fetch tweets of the author and update _tweets
        HproseInstance.getTweetListByRank(user.value, startRank.intValue)
            .collect { newTweets ->
                _tweets.update { currentTweets ->
                    val newTweetsMap = newTweets.associateBy { it.mid }
                    val updatedTweets = currentTweets.map { tweet ->
                        newTweetsMap[tweet.mid] ?: tweet
                    }
                    (updatedTweets + newTweets)
                        .filterNot { it.isPrivate && it.authorId != appUser.mid }
                        .distinctBy { it.mid }
                        .sortedByDescending { it.timestamp }
                }
            }
        startRank.intValue = tweets.value.size   // for loading older tweets next time

        loadPinnedTweets()
        initState.value = false
    }

    private suspend fun loadPinnedTweets() {
        // 2. Get pinned tweets and update _topTweets, while avoiding duplication
        val pinnedTweets = mutableSetOf<Tweet>()
        HproseInstance.getTopList(user.value)?.forEach { map ->
            val tweet = tweets.value.find { it.mid == map["tweetId"] }
            if (tweet != null) {
                // add tweet to topTweets, update its timestamp to when it is pinned.
                pinnedTweets.add(tweet.copy(timestamp = map["timestamp"].toString().toLong()))
            } else {
                HproseInstance.getTweet(map["tweetId"].toString(), user.value.mid)?.let { tweet1 ->
                    tweet1.originalTweetId?.let {
                        tweet1.originalAuthorId?.let { it1 ->
                            tweet1.originalTweet = HproseInstance.getTweet(it, it1)
                        }
                    }
                    pinnedTweets.add(tweet1.copy(timestamp = map["timestamp"].toString().toLong()))
                }
            }
        }
        // 3. overwrite any tweet in _topTweets with one from pinnedTweets
        _topTweets.update { currentTopTweets ->
            val pinnedTweetsFiltered = pinnedTweets.toList()
                .filterNot { it.isPrivate && it.authorId != appUser.mid }
                .distinctBy { it.mid }
                .sortedByDescending { it.timestamp }

            val currentTopTweetsMap = currentTopTweets.associateBy { it.mid }.toMutableMap()

            pinnedTweetsFiltered.forEach { pinnedTweet ->
                currentTopTweetsMap[pinnedTweet.mid] = pinnedTweet // Overwrite or add
            }

            currentTopTweetsMap.values.toList() // Convert back to a list
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
                // add tweet to topTweets, update its timestamp to when it is pinned.
                pinnedTweets.add(tweet.copy(timestamp = map["timestamp"].toString().toLong()))
            } else {
                HproseInstance.getTweet(map["tweetId"].toString(), user.value.mid)?.let { tweet1 ->
                    tweet1.originalTweetId?.let {
                        tweet1.originalAuthorId?.let { it1 ->
                            tweet1.originalTweet = HproseInstance.getTweet(it, it1)
                        }
                    }
                    pinnedTweets.add(tweet1.copy(timestamp = map["timestamp"].toString().toLong()))
                }
            }
        }
        _topTweets.update {
            pinnedTweets.toList()
                .filterNot { it.isPrivate && it.authorId != appUser.mid }
                .distinctBy { it.mid }
                .sortedByDescending { it.timestamp }
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
        appUser = User(mid = TW_CONST.GUEST_ID, baseUrl = appUser.baseUrl,
            followingList = HproseInstance.getAlphaIds())
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
            }?: run {
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
                    appUser.followingList?.forEach {
                        HproseInstance.toggleFollower(it, true, newUser.mid)
                    }
                    password.value = ""     // clear the password
                    popBack()
                } else {
                    // update user profile
                    updatedUser = gson.fromJson(ret["user"].toString(), userType)
                    appUser = appUser.copy(name = updatedUser.name, profile = updatedUser.profile,
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
        }?: run {
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
        isPasswordVisible.value = ! isPasswordVisible.value
    }

    override fun onTweetAdded(tweet: Tweet) {
        _tweets.update { currentTweets -> (listOf(tweet) + currentTweets)
            .distinctBy { it.mid }
            .sortedByDescending { it.timestamp }
        }
        _user.value = user.value.copy(tweetCount = tweets.value.size)
    }

    /**
     * A tweet is deleted by appUser, remove it from all tweet lists that has the tweet.
     * */
    override fun onTweetDeleted(tweetId: MimeiId) {
        _tweets.update { currentTweets -> currentTweets.filterNot { it.mid == tweetId } }
        _topTweets.update { topTweets -> topTweets.filterNot { it.mid == tweetId } }

        // remove deleted tweet from favorite list, if it is there. May not be loaded yet.
        _favorites.update { currentTweets -> currentTweets.filterNot { it.mid == tweetId } }
        _bookmarks.update { currentTweets -> currentTweets.filterNot { it.mid == tweetId } }

        /**
         * Remove bookmark and favorite from User mimei, if there are any.
         * */
//        HproseInstance.updateFavoriteOfUser(tweetId, false)
//        val updatedUser = HproseInstance.updateBookmarkOfUser(tweetId, false)
//        _user.value = user.value.copy(
//            favoritesCount = updatedUser.favoritesCount,
//            bookmarksCount = updatedUser.bookmarksCount,
//            tweetCount = tweets.value.size
//        )
    }
}
