package com.fireshare.tweet.viewmodel

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fireshare.tweet.HproseInstance
import com.fireshare.tweet.HproseInstance.appUser
import com.fireshare.tweet.HproseInstance.getUser
import com.fireshare.tweet.HproseInstance.tweetCache
import com.fireshare.tweet.R
import com.fireshare.tweet.TweetApplication
import com.fireshare.tweet.TweetApplication.Companion.preferenceHelper
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

@HiltViewModel(assistedFactory = UserViewModel.UserViewModelFactory::class)
class UserViewModel @AssistedInject constructor(
    @Assisted private val userId: MimeiId,
    private val savedStateHandle: SavedStateHandle
): ViewModel(), TweetActionListener {
    var user = savedStateHandle.getStateFlow("user", appUser)

    // unpinned tweets
    private val _tweets = MutableStateFlow<List<Tweet>>(emptyList())
    val tweets: StateFlow<List<Tweet>> get() = _tweets.asStateFlow()

    // pinned tweets
    private val _topTweets = MutableStateFlow<List<Tweet>>(emptyList())
    val topTweets: StateFlow<List<Tweet>> get() = _topTweets.asStateFlow()

    private var _fans = MutableStateFlow(emptyList<MimeiId>())
    val fans: StateFlow<List<MimeiId>> get() = _fans.asStateFlow()
    private var _followings = MutableStateFlow(emptyList<MimeiId>())
    val followings: StateFlow<List<MimeiId>> get() = _followings.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshingAtTop: StateFlow<Boolean> get() = _isRefreshing.asStateFlow()
    private val _isRefreshingAtBottom = MutableStateFlow(false)
    val isRefreshingAtBottom: StateFlow<Boolean> get() = _isRefreshingAtBottom.asStateFlow()
    private var initState = MutableStateFlow(true)      // initial load state

    companion object {
        private const val THIRTY_DAYS_IN_MILLIS = 2_592_000_000L
        private const val SEVEN_DAYS_IN_MILLIS = 648_000_000L
    }
    private var startTimestamp = System.currentTimeMillis()    // current time
    private var endTimestamp = startTimestamp - THIRTY_DAYS_IN_MILLIS   // previous time

    // variable for login management
    private val preferencesHelper = TweetApplication.preferenceHelper
    var username = mutableStateOf(user.value.username)
    var password = mutableStateOf("")
    var name = mutableStateOf(user.value.name)
    var profile = mutableStateOf(user.value.profile)
    var hostId = mutableStateOf("")
    var isPasswordVisible = mutableStateOf(false)
    var isLoading = mutableStateOf(false)
    var loginError = mutableStateOf("")

    suspend fun loadNewerTweets() {
        if (initState.value) return
        _isRefreshing.value = true
        startTimestamp = System.currentTimeMillis()
        val endTimestamp = startTimestamp - SEVEN_DAYS_IN_MILLIS
        Timber.tag("UserVM.loadNewerTweets")
            .d("startTimestamp=$startTimestamp, endTimestamp=$endTimestamp")
        getTweets()
        _isRefreshing.value = false
    }
    suspend fun loadOlderTweets() {
        if (initState.value) return
        _isRefreshingAtBottom.value = true
        val startTimestamp = endTimestamp
        endTimestamp = startTimestamp - SEVEN_DAYS_IN_MILLIS
        Timber.tag("UserVM.loadOlderTweets")
            .d("startTimestamp=$startTimestamp, endTimestamp=$endTimestamp")
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
        } else user.value.hostIds!![0]
    }

    suspend fun updateAvatar(context: Context, uri: Uri) {
        isLoading.value = true
        // For now, user avatar can only be image.
        HproseInstance.uploadToIPFS(
            context,
            uri,
            referenceId = appUser.mid
        )?.let {
            HproseInstance.setUserAvatar(userId, it.mid)   // Update database value
            appUser = user.value.copy(avatar = it.mid)
            savedStateHandle["user"] = appUser
        }
        isLoading.value = false
    }

    suspend fun toggleFollow(subjectUserId: MimeiId,
                             appUserId: MimeiId = appUser.mid,
                             updateTweetFeed: (Boolean) -> Unit) {
        // update the interface without waiting for the server to respond.
        _followings.update { list ->
            if (list.contains(subjectUserId)) {
                list.filter { id -> id != subjectUserId }
            } else {
                list + subjectUserId
            }
        }

        // toggle the Following status on the given UserId
        HproseInstance.toggleFollowing(subjectUserId, appUserId)?.let { isFollowing ->
            // Succeed. Now it is the other party's turn to update its followers.
            HproseInstance.toggleFollower(subjectUserId, isFollowing, appUserId)
            refreshFollowingsAndFans()
            updateTweetFeed(isFollowing)
        }
    }

    suspend fun refreshFollowingsAndFans() {
        _fans.value = HproseInstance.getFans(user.value) ?: emptyList()
        _followings.value = HproseInstance.getFollowings(user.value) ?: emptyList()
        //
        val userData = UserData(userId = appUser.mid, followings = followings.value)
        HproseInstance.tweetCache.tweetDao().insertOrUpdateUserData(userData)
    }

    @AssistedFactory
    interface UserViewModelFactory {
        fun create(userId: MimeiId): UserViewModel
    }

    init {
        if (userId != TW_CONST.GUEST_ID) {
            viewModelScope.launch(Dispatchers.IO) {
                savedStateHandle["user"] = getUser(userId)
                if (userId == appUser.mid) {
                    // By default NOT to update fans and followings list of an user object.
                    // Do it only when opening the user's profile page.
                    // Only get current user's fans list when opening the app.
                    refreshFollowingsAndFans()
                }
            }
        } else {
            savedStateHandle["user"] = appUser
        }
    }

    suspend fun getTweets() {
        // 1. Fetch all tweets of the author and update _tweets
        val pinnedTweets = mutableSetOf<Tweet>()
        HproseInstance.getTweetList(user.value, _tweets.value, startTimestamp, endTimestamp)
            .collect { tweets ->
                _tweets.update { list -> (list + tweets)
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
            pinnedTweets.toList().distinctBy { it.mid }
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
            pinnedTweets.toList().distinctBy { it.mid }
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
            val ret = HproseInstance.login(username.value!!, password.value)
            isLoading.value = false

            if (ret.second != null) {
                // something wrong
                loginError.value = ret.second.toString()
            } else {
                appUser = ret.first as User
                preferencesHelper.setUserId(appUser.mid)
                savedStateHandle["user"] = appUser
                username.value = user.value.username
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
        preferencesHelper.setUserId(null)
        appUser = User(mid = TW_CONST.GUEST_ID, baseUrl = appUser.baseUrl)
        /**
         * Do NOT clear the UserViewModel object. It will be reused by other users.
         * */
        tweets.value.forEach {
            tweetCache.tweetDao().deleteCachedTweetAndRemoveFromMidList(it.mid)
        }
        _tweets.value = emptyList()
        _topTweets.value = emptyList()
//        savedStateHandle["user"] = appUser
//        _fans.value = emptyList()
//        _followings.value = emptyList()
//        username.value = ""
//        password.value = ""
//        profile.value = ""
//        name.value = ""
//        hostId.value = ""
        popBack()
    }

    /**
     * Handle both register and update of user profile. Username, password are required.
     * Do NOT update appUser, wait for the new user to login.
     * */
    suspend fun register(context: Context, popBack: () -> Unit) {
        if (username.value?.isEmpty() == true
            || password.value.isEmpty()
        ) {
            var message = ""
            if (username.value?.isEmpty() == true) {
                message = context.getString(R.string.username_required)
            } else if (password.value.isEmpty()) {
                message = context.getString(R.string.password_required)
            }
            val event = SnackbarEvent(
                message = message
            )
            showSnackbar(event)
            return
        }

        isLoading.value = true
        if (this.hostId.value.isNotEmpty() && appUser.mid == TW_CONST.GUEST_ID) {
            // Find IP of desired node. User can change its value to appoint
            // a different host node later.
            val ip = HproseInstance.getHostIP(hostId.value)
            if (ip == null) {
                showSnackbar(SnackbarEvent(message = context.getString(R.string.node_not_found)))
                isLoading.value = false
                return
            } else {
                // register user on desired node, and use it henceforth.
                appUser = appUser.copy(baseUrl = "http://$ip")
            }
        }
        val user = appUser.copy(
            name = name.value, hostIds = listOf(hostId.value),
            username = username.value, password = password.value,
            profile = profile.value, avatar = appUser.avatar
        )
        val ret = HproseInstance.setUserData(user)
        if (ret != null) {
            if (ret["status"] == "success") {
                val gson = Gson()
                val type = object : TypeToken<User>() {}.type
                if (appUser.mid == TW_CONST.GUEST_ID) {
                    val newUser: User = gson.fromJson(ret["user"].toString(), type)
                    /**
                     * Set the newly created user
                     * */
                    user.followingList?.forEach {
                        this.toggleFollow(it, newUser.mid) {}
                    }
                    password.value = ""
                    popBack()
                } else {
                    // update user profile
                    val updatedUser: User = gson.fromJson(ret["user"].toString(), type)
                    appUser = appUser.copy(name = updatedUser.name, profile = updatedUser.profile,
                        username = updatedUser.username, hostIds = updatedUser.hostIds,
                    )
                    savedStateHandle["user"] = appUser
                    appUser.name?.let { preferenceHelper.saveName(it) }
                    appUser.profile?.let { preferenceHelper.saveProfile(it) }

                    val event = SnackbarEvent(
                        message = context.getString(R.string.profile_update_ok)
                    )
                    showSnackbar(event)
                }
            } else {
                showSnackbar(SnackbarEvent(message = ret["reason"].toString()))
            }
        } else {
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
        fans.value.forEach { fanId ->
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
