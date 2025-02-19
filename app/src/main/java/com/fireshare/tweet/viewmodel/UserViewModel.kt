package com.fireshare.tweet.viewmodel

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fireshare.tweet.HproseInstance
import com.fireshare.tweet.HproseInstance.appUser
import com.fireshare.tweet.HproseInstance.getSortedMetaByUser
import com.fireshare.tweet.HproseInstance.getUser
import com.fireshare.tweet.HproseInstance.getUserId
import com.fireshare.tweet.HproseInstance.preferenceHelper
import com.fireshare.tweet.HproseInstance.tweetCache
import com.fireshare.tweet.R
import com.fireshare.tweet.datamodel.MimeiId
import com.fireshare.tweet.datamodel.TW_CONST
import com.fireshare.tweet.datamodel.Tweet
import com.fireshare.tweet.datamodel.TweetActionListener
import com.fireshare.tweet.datamodel.User
import com.fireshare.tweet.datamodel.UserData
import com.fireshare.tweet.service.SnackbarController
import com.fireshare.tweet.service.SnackbarEvent
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
import kotlin.math.max

@HiltViewModel(assistedFactory = UserViewModel.UserViewModelFactory::class)
class UserViewModel @AssistedInject constructor(
    @Assisted private val userId: MimeiId,
//    private val savedStateHandle: SavedStateHandle
): ViewModel(), TweetActionListener {
    private val _user = MutableStateFlow(appUser)
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

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshingAtTop: StateFlow<Boolean> get() = _isRefreshing.asStateFlow()
    private val _isRefreshingAtBottom = MutableStateFlow(false)
    val isRefreshingAtBottom: StateFlow<Boolean> get() = _isRefreshingAtBottom.asStateFlow()
    private var initState = MutableStateFlow(true)      // initial load state

    // current rank of tweet in DB. Retrieve 10 tweets each time start from it.
    private var startRank = MutableStateFlow(0)

    // variable for login management
    var username = mutableStateOf(appUser.username)
    var password = mutableStateOf("")
    var name = mutableStateOf(appUser.name)
    var profile = mutableStateOf(appUser.profile)
    var hostId = mutableStateOf("")
    var isPasswordVisible = mutableStateOf(false)
    var isLoading = mutableStateOf(false)
    var loginError = mutableStateOf("")

    suspend fun loadNewerTweets() {
        if (initState.value) return
        _isRefreshing.value = true
        startRank.value = 0
        Timber.tag("UserVM.loadNewerTweets")
            .d("start rank=${startRank.value}")
        getTweets()
        _isRefreshing.value = false
    }
    suspend fun loadOlderTweets() {
        if (initState.value) return
        _isRefreshingAtBottom.value = true
        startRank.value = 0
        Timber.tag("UserVM.loadOlderTweets")
            .d("start rank=${startRank.value}")
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
     * @param isFollower indicates if appUser is a follower of userId or not.
     * */
    suspend fun toggleFollower(
        userId: MimeiId,
        isFollower: Boolean,
        followerId: MimeiId
    ) {
        HproseInstance.toggleFollower(userId, isFollower, followerId)
        _followers.update { list ->
            if (isFollower)
                listOf(followerId) + list
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
                    listOf(subjectUserId) + list
                else
                    list.filterNot { it == subjectUserId }
            }
            _user.value = user.value.copy(followingCount = followings.value.size)

            // update cached followings of appUser
            val userData = UserData(userId = userId, followings = followings.value)
            tweetCache.tweetDao().insertOrUpdateUserData(userData)

            // callback to update tweet feed. Load or remove tweets of the others.
            updateTweetFeed(isFollowing)
        }
    }

    suspend fun refreshFollowingsAndFans() {
        _followers.value = HproseInstance.getFans(user.value) ?: emptyList()
        _followings.value = HproseInstance.getFollowings(user.value) ?: emptyList()
    }

    private val _bookmarks = MutableStateFlow<List<Tweet>>(emptyList())
    val bookmarks: StateFlow<List<Tweet>> get() = _bookmarks.asStateFlow()
    private val _favorites = MutableStateFlow<List<Tweet>>(emptyList())
    val favorites: StateFlow<List<Tweet>> get() = _favorites.asStateFlow()

    suspend fun getBookmarks(start: Int) {
        getSortedMetaByUser(user.value, "bookmark")?.let { list ->
            val end = (start + 10).coerceAtMost(list.size)
            for (index in start until end) {
                HproseInstance.getTweet(list[index], user.value.mid)?.let { newTweet ->
                    _bookmarks.update { bs ->
                        if (bs.none { existingTweet -> existingTweet.mid == newTweet.mid }) {
                            listOf(newTweet) + bs
                        } else {
                            bs
                        }
                    }
                }
            }
        }
    }
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

    suspend fun getFavorites(start: Int) {
        getSortedMetaByUser(user.value, "favorite")?.let { list ->
            val end = (start + 10).coerceAtMost(list.size)
            for (index in start until end) {
                HproseInstance.getTweet(list[index], user.value.mid)?.let { newTweet ->
                    _favorites.update { bs ->
                        if (bs.none { existingTweet -> existingTweet.mid == newTweet.mid }) {
                            listOf(newTweet) + bs
                        } else {
                            bs
                        }
                    }
                }
            }
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
                _user.value = getUser(userId) ?: appUser
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

    suspend fun getTweets() {
        // 1. Fetch all tweets of the author and update _tweets
        val pinnedTweets = mutableSetOf<Tweet>()
        HproseInstance.getTweetListByRank(user.value, _tweets.value, startRank.value)
            .collect { tweets ->
                startRank.value += tweets.size  // for loading older tweets
                _tweets.update { list -> (list + tweets)
                    .filterNot { it.isPrivate && it.authorId != appUser.mid }
                    .distinctBy { it.mid }
                    .sortedByDescending { it.timestamp }
                }
        }

        // 2. Get pinned tweets and update _topTweets, while avoiding duplication
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
        // 3. Filter tweetsList to exclude those in topTweets and _tweets, and update _tweets
        _topTweets.update {
            pinnedTweets.toList()
                .filterNot { it.isPrivate && it.authorId != appUser.mid }
                .distinctBy { it.mid }
                .sortedByDescending { it.timestamp }
        }
        initState.value = false
    }

    /**
     * User can pin or unpin any tweet, including quoted or retweet by this user.
     * */
    suspend fun pinToTop(tweetId: MimeiId) {
        val pinnedTweets = mutableSetOf<Tweet>()
        HproseInstance.toggleTopList(tweetId)?.forEach { map ->
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

     suspend fun logout(popBack: () -> Unit) {
         preferenceHelper.setUserId(null)
         appUser = User(mid = TW_CONST.GUEST_ID, baseUrl = appUser.baseUrl)
         /**
          * Do NOT clear the UserViewModel object. It will be reused by other users.
          * */
         _tweets.value = emptyList()
         _topTweets.value = emptyList()
         tweets.value.forEach {
             tweetCache.tweetDao().deleteCachedTweetAndRemoveFromMidList(it.mid, it.authorId)
         }
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
            // check if the name has been taken.
            // !!! Potential username clash may happen!!!
            val userId = getUserId(username.value!!) ?: return
            getUser(userId)?.let {
                showSnackbar(SnackbarEvent(message = context.getString(R.string.username_taken)))
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
            name = name.value?.trim(), hostIds = listOf(hostId.value.trim()),
            username = username.value!!.lowercase().trim(), password = password.value,
            profile = profile.value?.trim(), avatar = appUser.avatar, mid = appUser.mid
        )
        HproseInstance.setUserData(updatedUser)?.let { ret ->
            if (ret["status"] == "success") {
                val gson = Gson()
                val type = object : TypeToken<User>() {}.type
                if (appUser.mid == TW_CONST.GUEST_ID) {
                    val newUser: User = gson.fromJson(ret["user"].toString(), type)
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
                    updatedUser = gson.fromJson(ret["user"].toString(), type)
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
    }

    override fun onTweetDeleted(tweetId: MimeiId) {
        _topTweets.update { topTweets -> topTweets.filterNot { it.mid == tweetId } }
        _tweets.update { currentTweets -> currentTweets.filterNot { it.mid == tweetId } }
    }
}
